package com.memoria.caregiver.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.cameras.plainClickable
import com.memoria.caregiver.ui.components.NemaraCamFeedPlaceholder
import com.memoria.caregiver.ui.theme.NemaraType

/**
 * The expanded single-camera view. Spec:
 * `PatientLiveView.fullScreenView(forCameraId:)`, PARITY.md §18.
 *
 * **Rotated manually, on purpose.** The app is portrait-locked
 * (`android:screenOrientation="portrait"`, matching iOS's
 * `UISupportedInterfaceOrientations`) but the wall phone publishes a landscape
 * 1280x720 track. The view therefore rotates 90 degrees with width and height
 * swapped, so the landscape video fills the portrait device sideways and the
 * caregiver physically turns their phone — the same gesture every mobile video
 * player uses.
 *
 * The chrome rotates **with** the video. Once the phone is turned, the close
 * control and the room label are both right-side-up over the feed; leaving them
 * unrotated would put them sideways exactly when they become usable.
 *
 * Two things the host owns, because this composable cannot reach them:
 *
 * - **It must be presented outside the root's `safeDrawingPadding()`.**
 *   `DECISIONS.md` D-010 consumes insets once at the root and says a screen that
 *   deliberately bleeds under a bar opts out locally; this is the case that
 *   anticipated. iOS: `.ignoresSafeArea()`.
 * - **The status bar must be hidden while it is up.** iOS: `.statusBarHidden()`.
 *
 * [onDismiss] fires both from the close control and from the camera going away
 * mid-presentation (unpaired, deleted): iOS bounces straight back to the
 * carousel rather than leaving a black screen with no exit.
 */
@Composable
fun LiveFullScreenView(
    state: LiveVideoState,
    cameraId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    videoTile: @Composable (LiveVideoTile, Modifier) -> Unit = { tile, tileModifier ->
        DefaultLiveVideoTile(tile, tileModifier)
    },
) {
    val camera = state.cameras.firstOrNull { it.id == cameraId }
    val identity = camera?.livekitIdentity

    // Camera went away mid-presentation. Black, and bounce back.
    if (camera == null || identity == null) {
        LaunchedEffect(cameraId) { onDismiss() }
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        RotatedLandscape(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                val track = state.videoTracksByIdentity[identity]
                when {
                    camera.privacyEnabled -> PrivacyPlacard(Modifier.fillMaxSize())
                    track != null -> videoTile(
                        LiveVideoTile(
                            room = state.room,
                            track = track,
                            identity = identity,
                            blurred = state.peopleBlurEnabled,
                        ),
                        Modifier.fillMaxSize(),
                    )
                    else -> NemaraCamFeedPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        hue = 30f,
                        loading = true,
                    )
                }

                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(
                            modifier = Modifier
                                .padding(top = 16.dp, end = 16.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .plainClickable(onClick = onDismiss)
                                .padding(12.dp)
                                .semantics { contentDescription = "Close full screen" },
                        ) {
                            LiveGlyph(name = "xmark", size = 16.dp, tint = Color.White)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = camera.room,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        // iOS: NemaraFont.bodyLarge.weight(.semibold) — bodyLarge
                        // is Inter 16 regular, so the weight is asked for here.
                        style = NemaraType.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Lays [content] out in the container's **swapped** dimensions, then rotates it a
 * quarter turn about the centre so it fills the portrait container sideways.
 *
 * iOS expresses this as `.frame(width: geo.size.height, height: geo.size.width)`
 * plus `.rotationEffect(.degrees(90))` plus `.position(centre)`. The three pieces
 * are the same here: a fixed swapped measurement, a rotation that does not affect
 * layout, and a placement that re-centres what the swap moved.
 *
 * Kept as its own composable because getting the order wrong renders sideways
 * video that is also cropped, which is hard to diagnose from a screenshot.
 */
@Composable
private fun RotatedLandscape(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        Box(
            Modifier
                // Outermost: measure the child in the SWAPPED box — as wide as
                // the container is tall, and as tall as it is wide — then report
                // the container's own size so nothing downstream sees the swap,
                // and place the child centre-on-centre.
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed(width = h, height = w))
                    layout(w, h) { placeable.place(x = (w - h) / 2, y = (h - w) / 2) }
                }
                // Inner: a quarter turn about the layer's own centre, which is
                // the same point the placement above centred. Rotating a h-by-w
                // box a quarter turn gives a w-by-h footprint, so it lands
                // exactly on the container.
                .graphicsLayer { rotationZ = 90f },
        ) {
            content()
        }
    }
}
