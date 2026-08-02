package com.memoria.caregiver.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.Ink
import kotlin.math.max

// ---------------------------------------------------------------------------
// Tuning constants, carried verbatim from NemaraCamFeedPlaceholder.swift.
// They are `internal` rather than private so NemaraCamFeedPlaceholderTest can
// pin them: every one of these is a number a port silently rounds or drops.
// ---------------------------------------------------------------------------

/**
 * One stop of the "lit room" gradient: HSB in DEGREES, not the 0...1 fraction
 * SwiftUI takes. Kept as data so the hue offsets and the asymmetric clamp below
 * are assertable without rendering anything.
 */
internal data class CamFeedGradientStop(
    val hueDegrees: Float,
    val saturation: Float,
    val brightness: Float,
)

/**
 * The three lit-gradient stops for a room hue, top-leading to bottom-trailing.
 *
 * Swift:
 * ```
 * Color(hue: hue / 360,                    saturation: 0.32, brightness: 0.72)
 * Color(hue: (hue + 6) / 360,              saturation: 0.36, brightness: 0.56)
 * Color(hue: max(0, (hue - 4) / 360),      saturation: 0.30, brightness: 0.34)
 * ```
 *
 * **The clamp is asymmetric on purpose and is ported as-is.** Only the third
 * stop is floored at 0; the first two are not. So a hue below 4 pulls the
 * shadow stop to red rather than wrapping it round to magenta, while the other
 * two wrap. That reads like an oversight in the Swift, but it is observable
 * behavior and "fixing" it would change the rendering.
 *
 * `max(0, (hue - 4) / 360)` is equivalent to `max(0, hue - 4)` in degrees,
 * because dividing by 360 is monotonic and positive.
 */
internal fun camFeedGradientStops(hue: Float): List<CamFeedGradientStop> = listOf(
    CamFeedGradientStop(wrapHueDegrees(hue), saturation = 0.32f, brightness = 0.72f),
    CamFeedGradientStop(wrapHueDegrees(hue + 6f), saturation = 0.36f, brightness = 0.56f),
    CamFeedGradientStop(wrapHueDegrees(max(0f, hue - 4f)), saturation = 0.30f, brightness = 0.34f),
)

/**
 * SwiftUI's `Color(hue:saturation:brightness:)` takes a 0...1 fraction and wraps
 * out-of-range hues (its HSB conversion does `h * 6` then takes the sector
 * modulo 6, so 1.02 renders as 0.02). Compose's `Color.hsv` takes DEGREES and
 * throws outside 0..360, so the wrap has to be explicit here rather than
 * implicit in the color constructor.
 *
 * In-app hues are all 30 today, so this only matters if a room hue near 354+ or
 * a negative hue ever appears — at which point iOS wraps and Compose would have
 * crashed.
 */
private fun wrapHueDegrees(degrees: Float): Float {
    val wrapped = degrees % 360f
    return if (wrapped < 0f) wrapped + 360f else wrapped
}

internal fun camFeedGradientColors(hue: Float): List<Color> =
    camFeedGradientStops(hue).map { Color.hsv(it.hueDegrees, it.saturation, it.brightness) }

/**
 * One furniture block, positioned as fractions of the feed's width and height.
 *
 * Swift places these with `.frame(width:height:)` + `.offset(x:y:)` inside a
 * `GeometryReader` and a `.topLeading` ZStack, which is exactly "top-left corner
 * at (x·w, y·h), size (width·w, height·h)".
 */
internal data class CamFeedFurniturePiece(
    val color: Color,
    val cornerRadius: Dp,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * The mock room: floor, couch, chest, window. Colors are RGB literals in the
 * Swift, not palette tokens, and stay literals here — they are scene paint for a
 * fake video frame, not interface surfaces, and pointing them at the palette
 * would couple a throwaway placeholder to the design system.
 *
 * Order is paint order: floor first, window last.
 */
internal val CamFeedFurniture: List<CamFeedFurniturePiece> = listOf(
    // Floor
    CamFeedFurniturePiece(
        color = Color(0xFF3A2A1C), // 58, 42, 28
        cornerRadius = 0.dp,
        x = 0f, y = 0.68f, width = 1f, height = 0.32f,
    ),
    // Couch
    CamFeedFurniturePiece(
        color = Color(0xFF5A3F2A), // 90, 63, 42
        cornerRadius = 3.dp,
        x = 0.12f, y = 0.40f, width = 0.31f, height = 0.30f,
    ),
    // Chest
    CamFeedFurniturePiece(
        color = Color(0xFF6B4A30), // 107, 74, 48
        cornerRadius = 3.dp,
        x = 0.56f, y = 0.30f, width = 0.28f, height = 0.40f,
    ),
    // Window
    CamFeedFurniturePiece(
        color = Color(0xFFCDB88E).copy(alpha = 0.6f), // 205, 184, 142 @ 60%
        cornerRadius = 0.dp,
        x = 0.75f, y = 0f, width = 0.25f, height = 0.42f,
    ),
)

/** `.blendMode(.multiply).opacity(0.4)` on the whole silhouette group. */
internal const val CamFeedFurnitureOpacity = 0.4f

/** Unlit rooms get a dim warm pool over `NemaraColor.ink`: rgb(60,50,40) @ 60%. */
internal val CamFeedUnlitGlow = Color(0xFF3C3228).copy(alpha = 0.6f)

/** `endRadius: 220` on the unlit radial gradient. Points on iOS, dp here. */
internal val CamFeedUnlitGlowRadius = 220.dp

/**
 * Darkens the mock room while loading, so the spinner reads as "loading" rather
 * than implying the silhouettes are the live feed.
 */
internal const val CamFeedLoadingScrimOpacity = 0.3f

/** `Circle().trim(from: 0, to: 0.72)`. */
internal const val CamFeedSpinnerTrimFraction = 0.72f
internal const val CamFeedSpinnerSweepDegrees = CamFeedSpinnerTrimFraction * 360f

/** `.linear(duration: 0.9).repeatForever(autoreverses: false)`. */
internal const val CamFeedSpinnerDurationMillis = 900

internal val CamFeedSpinnerDiameter = 26.dp
internal val CamFeedSpinnerStrokeWidth = 3.dp
internal const val CamFeedSpinnerStrokeOpacity = 0.92f
internal const val CamFeedSpinnerLabelOpacity = 0.9f

/**
 * Mock "wall-mounted phone camera" feed. Renders a warm gradient with simple
 * rectangular furniture silhouettes — enough to suggest a room without
 * pretending to be a real video frame. Replace with a LiveKit (or HLS) player
 * when real feeds land.
 *
 * [hue] shifts the lighting per room so different rooms feel distinct.
 *
 * [loading] overlays a scrim + spinner for the window where the camera is
 * expected-online but the track hasn't landed yet. The spinner is deliberately
 * hand-drawn (see [NemaraLoadingSpinner]), NOT a platform progress indicator:
 * on iOS a UIKit `UIActivityIndicatorView` animating next to the LiveKit
 * `VideoView` in the tile background risked the nil→track swap happening
 * mid-animation and leaving the new video view unpainted. The same hazard
 * exists here — the Android LiveKit renderer is a `SurfaceView`/`TextureView`
 * hosted by `AndroidView`, and swapping it under a running animation is the
 * same class of problem. A scoped rotation on a drawn arc keeps the animation
 * contained and adds no sibling view.
 *
 * The whole scene is one [Canvas] rather than a stack of composables, because
 * every layer is a greedy full-bleed fill and the silhouette group needs a real
 * offscreen layer to get group-alpha + multiply right (see
 * [drawFurnitureSilhouette]). SwiftUI's ZStack gives that for free; in Compose
 * one draw scope is both the faithful and the cheap way to express it.
 */
@Composable
fun NemaraCamFeedPlaceholder(
    modifier: Modifier = Modifier,
    hue: Float = 30f,
    lit: Boolean = true,
    loading: Boolean = false,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (lit) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = camFeedGradientColors(hue),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
                drawFurnitureSilhouette()
            } else {
                drawRect(color = Ink)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(CamFeedUnlitGlow, Color.Transparent),
                        center = center,
                        radius = CamFeedUnlitGlowRadius.toPx(),
                    ),
                )
            }
            if (loading) {
                drawRect(color = Color.Black.copy(alpha = CamFeedLoadingScrimOpacity))
            }
        }
        if (loading) {
            NemaraLoadingSpinner()
        }
    }
}

/**
 * The silhouette, multiplied into the gradient at 40% **per shape**.
 *
 * The pieces overlap — the couch and chest sit on the floor, the window overlaps
 * the chest — so whether the 40% is applied per-leaf or to the flattened group
 * is visible: per-leaf double-darkens the overlaps, group does not.
 *
 * iOS applies it **per leaf**. `.blendMode(.multiply).opacity(0.4)` on a
 * container propagates to each leaf unless `.compositingGroup()` intervenes, and
 * `compositingGroup` appears nowhere in the iOS app (verified by grep over the
 * whole target). So the overlaps really do double-darken there.
 *
 * An earlier draft used `saveLayer` to composite the group once, on the
 * reasonable-sounding assumption that a modifier on a container applies to the
 * container. That flattens exactly the overlaps this drawing is made of.
 */
private fun DrawScope.drawFurnitureSilhouette() {
    CamFeedFurniture.forEach { piece ->
        // A zero corner radius renders as a plain rectangle, so the two
        // `Rectangle`s and the two `RoundedRectangle`s share one call.
        drawRoundRect(
            color = piece.color,
            topLeft = Offset(size.width * piece.x, size.height * piece.y),
            size = Size(size.width * piece.width, size.height * piece.height),
            cornerRadius = CornerRadius(piece.cornerRadius.toPx()),
            // Per shape, not per group. `piece.color` already carries the
            // window's own translucency, and this composes with it the same way
            // SwiftUI composes a leaf's fill alpha with an inherited .opacity.
            alpha = CamFeedFurnitureOpacity,
            blendMode = BlendMode.Multiply,
        )
    }
}

/**
 * Hand-drawn loading spinner — a trimmed circle rotating forever, with a label.
 *
 * Used instead of a platform progress indicator on purpose, for the reason
 * recorded on [NemaraCamFeedPlaceholder]: an always-animating platform view
 * sitting beside the LiveKit renderer in the tile background risks the
 * nil→track swap committing mid-animation and the new video view never
 * painting. This keeps the animation scoped to a rotation on a drawn arc so it
 * cannot wrap that swap.
 */
@Composable
private fun NemaraLoadingSpinner() {
    val transition = rememberInfiniteTransition(label = "camFeedSpinner")
    // SwiftUI: .linear(duration: 0.9).repeatForever(autoreverses: false) driving
    // rotationEffect 0 -> 360. RepeatMode.Restart is the autoreverses:false half.
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CamFeedSpinnerDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "camFeedSpinnerAngle",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(
            Modifier
                .size(CamFeedSpinnerDiameter)
                // `angle.value` is read inside the graphicsLayer block, not in
                // composition, so a 900ms loop that never ends costs zero
                // recompositions for the lifetime of the tile.
                .graphicsLayer { rotationZ = angle.value },
        ) {
            // SwiftUI's Circle path starts at 3 o'clock and runs clockwise, so
            // trim(0, 0.72) is startAngle 0 with a clockwise 259.2 degree sweep.
            // Compose's drawArc uses the same origin and direction.
            drawArc(
                color = Color.White.copy(alpha = CamFeedSpinnerStrokeOpacity),
                startAngle = 0f,
                sweepAngle = CamFeedSpinnerSweepDegrees,
                useCenter = false,
                style = Stroke(
                    width = CamFeedSpinnerStrokeWidth.toPx(),
                    cap = StrokeCap.Round,
                ),
            )
        }
        Text(
            text = "Loading",
            // Swift uses .system(size: 12, weight: .medium) here, NOT a Nemara
            // type token, so the platform default face is the faithful port.
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = CamFeedSpinnerLabelOpacity),
        )
    }
}
