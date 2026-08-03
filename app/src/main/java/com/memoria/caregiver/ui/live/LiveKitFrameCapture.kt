package com.memoria.caregiver.ui.live

import android.graphics.Bitmap
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One still frame off a live track, as a JPEG.
 *
 * Port of iOS's `LiveKitFrameCapture.jpeg(from:)`. The exit-door picker draws
 * boxes over a frozen frame, and the VLM that finds the doorways is sent the
 * same picture — so this is the input to both halves of that screen
 * (FINDINGS.md F-41).
 *
 * ## How it takes exactly one
 *
 * A `VideoSink` attached to a track receives every decoded frame, at whatever
 * rate the publisher is sending. This attaches one, keeps the first frame that
 * arrives, and detaches. [taken] guards that: `onFrame` runs on the WebRTC
 * decode thread and detaching is not instantaneous, so a second frame can and
 * does arrive after the first has been handled. Without the guard the
 * continuation would be resumed twice, which throws.
 *
 * The conversion is not written here. `VideoFramePixels` already does I420 to
 * ARGB with the strides and the limited-range expansion handled, because the
 * blur renderer needed exactly the same thing — see `BlurVideoRenderer.onFrame`,
 * which this deliberately mirrors so the two cannot drift.
 */
object LiveKitFrameCapture {

    /** iOS waits 30 x 200ms for a track; this is the same budget for a frame. */
    const val DEFAULT_TIMEOUT_MILLIS: Long = 6_000L

    /**
     * High enough that a doorframe is still a doorframe after the round trip.
     * The frame goes to a VLM, and compression artefacts on an edge are exactly
     * what a door detector would mistake for one.
     */
    const val JPEG_QUALITY: Int = 90

    /**
     * Grab one frame from [track], or null if none arrives within
     * [timeoutMillis].
     *
     * Null is a normal outcome, not an error: a sensor whose app has been
     * backgrounded is subscribed but silent, and the caller turns that into
     * "this sensor isn't streaming right now" rather than a failure.
     *
     * @param nowUpright rotates to the orientation the picture should be viewed
     *   at, so a box the caregiver taps lands where they tapped. A publisher can
     *   send 90/180/270 in the frame metadata and the raw buffer is untouched by
     *   it.
     */
    suspend fun jpeg(
        track: VideoTrack,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        quality: Int = JPEG_QUALITY,
        nowUpright: Boolean = true,
    ): ByteArray? = try {
        withTimeout(timeoutMillis) {
            val frame = nextFrame(track, nowUpright)
            frame?.let { encode(it, quality) }
        }
    } catch (timeout: TimeoutCancellationException) {
        // The sensor is subscribed but not publishing. Distinguishing that from
        // a real failure is the caller's job and it needs a value, not an
        // exception, to do it.
        null
    }

    private suspend fun nextFrame(track: VideoTrack, nowUpright: Boolean): ArgbFrame? =
        suspendCancellableCoroutine { continuation ->
            val taken = AtomicBoolean(false)
            // Declared before the sink so the sink body can detach itself.
            var sink: VideoSink? = null
            sink = VideoSink { videoFrame ->
                if (!taken.compareAndSet(false, true)) return@VideoSink
                val picture = convert(videoFrame, nowUpright)
                sink?.let { track.removeRenderer(it) }
                if (continuation.isActive) continuation.resume(picture)
            }
            track.addRenderer(sink)
            continuation.invokeOnCancellation {
                // Covers the timeout path, where no frame ever arrived. A sink
                // left attached to a track keeps decoding into a dead
                // continuation for as long as the room is up.
                sink?.let { track.removeRenderer(it) }
            }
        }

    /**
     * I420 to upright ARGB. Returns null if the buffer cannot be converted,
     * which the caller reports as a failed capture.
     */
    private fun convert(frame: VideoFrame, nowUpright: Boolean): ArgbFrame? {
        val i420 = frame.buffer.toI420() ?: return null
        return try {
            val source = ArgbFrame(i420.width, i420.height)
            VideoFramePixels.i420ToArgb(
                dataY = i420.dataY,
                strideY = i420.strideY,
                dataU = i420.dataU,
                strideU = i420.strideU,
                dataV = i420.dataV,
                strideV = i420.strideV,
                dest = source,
            )
            if (!nowUpright) return source
            val rotation = VideoFramePixels.normalizeRotation(frame.rotation)
            if (rotation == 0) {
                source
            } else {
                val target = ArgbFrame(
                    VideoFramePixels.rotatedWidth(source.width, source.height, rotation),
                    VideoFramePixels.rotatedHeight(source.width, source.height, rotation),
                )
                VideoFramePixels.rotateClockwise(source, rotation, target)
                target
            }
        } finally {
            // Always, including the early return above: an unreleased I420
            // buffer is a native leak, and this runs on the decode thread that
            // owns the pool it came from.
            i420.release()
        }
    }

    private fun encode(frame: ArgbFrame, quality: Int): ByteArray? {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
        } finally {
            // The bitmap exists only to reach the encoder. Holding one 1280x720
            // ARGB_8888 frame is 3.5 MB, and this screen can be reopened per
            // camera.
            bitmap.recycle()
        }
    }
}
