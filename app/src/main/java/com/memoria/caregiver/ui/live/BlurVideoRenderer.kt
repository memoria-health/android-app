package com.memoria.caregiver.ui.live

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.VideoSinkVisibility
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/**
 * Attaches to a LiveKit video track and, for each decoded frame, blurs a box
 * over each detected person. Spec:
 * `Features/Patient/Live/BlurredVideoView.swift` (`BlurVideoRenderer`),
 * PARITY.md §12.
 *
 * **DISPLAY privacy only.** Frames are still decoded on this device; nothing
 * here changes what is recorded, what the backend sees, or what another device
 * shows. The Swift says the same thing in the same words.
 *
 * [onFrame] runs on the WebRTC decode thread, which is serial, so the mutable
 * state here needs no locking — the same reasoning the Swift gives for the SDK's
 * serial video thread. Only the finished [Bitmap] crosses to the main thread.
 *
 * Nothing in this class logs. A frame of video is exactly the thing that must
 * never reach logcat, and neither must the fact that a particular patient's
 * tile is being blurred.
 */
class BlurVideoRenderer(
    private val detector: PersonDetector,
    /** Monotonic milliseconds. Injected so the cadence is testable. */
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val hold: BlurBoxHold = BlurBoxHold(),
    private val pipeline: BlurPipeline = BlurPipeline(),
) : VideoSink {

    /**
     * Called on the **main** thread with each composited frame, mirroring the
     * Swift's `onImage` and its hop to main.
     *
     * The bitmap handed over belongs to this renderer and is reused; a caller
     * that needs to keep one beyond the next couple of frames must copy it.
     */
    var onImage: ((Bitmap) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // One buffer per frame SIZE, not per frame. A 1280x720 ARGB frame is 3.5 MB
    // and there are 30 of them a second.
    private var decoded: ArgbFrame? = null
    private var upright: ArgbFrame? = null

    /**
     * Two output bitmaps, used alternately. The main thread draws the bitmap it
     * was last handed while this thread fills the other one, so a frame is never
     * overwritten while it is on screen.
     */
    private val output = arrayOfNulls<Bitmap>(2)
    private var outputIndex = 0

    override fun onFrame(frame: VideoFrame) {
        val i420 = frame.buffer.toI420() ?: return
        try {
            val source = decodedBuffer(i420.width, i420.height) ?: return
            VideoFramePixels.i420ToArgb(
                dataY = i420.dataY,
                strideY = i420.strideY,
                dataU = i420.dataU,
                strideU = i420.strideU,
                dataV = i420.dataV,
                strideV = i420.strideV,
                dest = source,
            )

            val rotation = VideoFramePixels.normalizeRotation(frame.rotation)
            val picture = if (rotation == 0) {
                source
            } else {
                val target = uprightBuffer(
                    VideoFramePixels.rotatedWidth(source.width, source.height, rotation),
                    VideoFramePixels.rotatedHeight(source.width, source.height, rotation),
                ) ?: return
                VideoFramePixels.rotateClockwise(source, rotation, target)
                target
            }

            val boxes = hold.onFrame(nowMillis()) {
                PersonBoxMath.boxes(detector.detect(picture), picture.width, picture.height)
            }
            pipeline.blurBoxesInPlace(picture, boxes)

            publish(picture)
        } finally {
            i420.release()
        }
    }

    private fun publish(picture: ArgbFrame) {
        val cached = output[outputIndex]
        val bitmap = if (
            cached != null && cached.width == picture.width && cached.height == picture.height
        ) {
            cached
        } else {
            cached?.recycle()
            Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
                .also { output[outputIndex] = it }
        }
        bitmap.setPixels(picture.pixels, 0, picture.width, 0, 0, picture.width, picture.height)
        outputIndex = (outputIndex + 1) % output.size

        val handler = onImage ?: return
        mainHandler.post { handler(bitmap) }
    }

    /**
     * Releases the two output bitmaps. Called when the view goes away; the
     * renderer must already be detached from its track, because nothing here is
     * safe to run concurrently with [onFrame].
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        onImage = null
        for (i in output.indices) {
            output[i]?.recycle()
            output[i] = null
        }
        decoded = null
        upright = null
    }

    // Two near-identical accessors rather than one generic one taking a property
    // reference: a bound property reference allocates on every call, and this is
    // a callback that runs thirty times a second.

    private fun decodedBuffer(width: Int, height: Int): ArgbFrame? {
        if (width <= 0 || height <= 0) return null
        val existing = decoded
        if (existing != null && existing.hasSize(width, height)) return existing
        return ArgbFrame(width, height).also { decoded = it }
    }

    private fun uprightBuffer(width: Int, height: Int): ArgbFrame? {
        if (width <= 0 || height <= 0) return null
        val existing = upright
        if (existing != null && existing.hasSize(width, height)) return existing
        return ArgbFrame(width, height).also { upright = it }
    }
}

/**
 * Keeps the blurred tile out of adaptive-stream downscaling.
 *
 * iOS says the same thing with two properties — `isAdaptiveStreamEnabled` false
 * and `adaptiveStreamSize` `.zero` — with the comment *"we render every frame
 * ourselves, so opt out of adaptive-stream downscaling"*. Android expresses
 * visibility per sink instead, so the equivalent is a sink that is always
 * visible and asks for no particular size: it never causes the track to be
 * paused, and it contributes nothing to the resolution the SDK requests.
 */
class BlurSinkVisibility : VideoSinkVisibility() {
    override fun isVisible(): Boolean = true

    override fun size(): Track.Dimensions = ZERO

    private companion object {
        val ZERO = Track.Dimensions(0, 0)
    }
}
