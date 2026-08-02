package com.memoria.caregiver.ui.live

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.VideoTrack

/**
 * Live camera tile with people blurred on-device ("Blur people"). Spec:
 * `Features/Patient/Live/BlurredVideoView.swift`, PARITY.md §12.
 *
 * **A drop-in alternative to the normal video view, used only when the
 * per-patient blur preference is on.** The Swift is explicit that the path is
 * fully additive: turning blur off restores the untouched original video view,
 * so the feature is reversible by construction. Callers pick between the two;
 * this composable never decides.
 *
 * [detector] has no implementation in the repository yet — see [PersonDetector].
 * It is a parameter rather than something constructed here for the same reason
 * DIFFERENCES.md D8 injects the avatar loader: the seam belongs to the app root,
 * not to the screen.
 *
 * There is no render watchdog here, and that is parity. PARITY.md §12 attaches
 * the watchdog to the plain `LiveKitVideoView`; `BlurredVideoView` on iOS has
 * none, because this path never hands frames to a platform video renderer that
 * can be reclaimed while backgrounded — it draws bitmaps it composed itself.
 */
@Composable
fun BlurredVideoView(
    track: VideoTrack?,
    detector: PersonDetector,
    modifier: Modifier = Modifier,
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    val renderer = remember(detector) { BlurVideoRenderer(detector) }

    // Keyed on the track identity, which is what `guard track !== attachedTrack`
    // does on iOS: the same track object re-supplied re-runs nothing, and a
    // different one detaches before attaching.
    DisposableEffect(renderer, track) {
        renderer.onImage = { bitmap: Bitmap -> image = bitmap.asImageBitmap() }

        // Only a remote track participates in adaptive stream, and only it
        // accepts a visibility. See BlurSinkVisibility for why one is needed.
        var visibility: BlurSinkVisibility? = null
        if (track is RemoteVideoTrack) {
            val sinkVisibility = BlurSinkVisibility()
            visibility = sinkVisibility
            track.addRenderer(renderer, sinkVisibility)
        } else {
            track?.addRenderer(renderer)
        }

        onDispose {
            // Detach first. Everything after this frees buffers the decode
            // thread would otherwise still be writing into.
            track?.removeRenderer(renderer)
            visibility?.close()
            // The tile must not keep painting the last frame of a track it is no
            // longer subscribed to, and the bitmap behind it is about to go.
            image = null
            renderer.release()
        }
    }

    BlurOverlaySurface(image = image, modifier = modifier)
}

/**
 * Draws the composited frame, or black before the first one arrives.
 *
 * **Fill, not fit** — iOS uses `contentsGravity = .resizeAspectFill` here to
 * match the plain video view, and PARITY.md §12 records why fitting is wrong:
 * the wall phone publishes portrait while the caregiver app renders landscape,
 * so letterboxing would throw away half the tile.
 *
 * No content description, matching iOS, and required: a description of a live
 * video frame is a description of someone's home.
 */
@Composable
fun BlurOverlaySurface(
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
            .testTag(BLUR_OVERLAY_TAG),
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag(BLUR_OVERLAY_IMAGE_TAG),
            )
        }
    }
}

internal const val BLUR_OVERLAY_TAG = "blur_overlay"
internal const val BLUR_OVERLAY_IMAGE_TAG = "blur_overlay_image"
