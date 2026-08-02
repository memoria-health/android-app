package com.memoria.caregiver.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Terra

/**
 * The tuning values of the pulse, pulled out of the composable so they can be
 * asserted directly. Every number here is a literal transcribed from
 * DesignSystem/Components/NemaraPulse.swift; the Swift in turn matches the
 * `@keyframes nemaraPulse` of the design canvas.
 *
 * Both the ring's scale and its opacity are driven by ONE progress value on iOS,
 * because a single `.animation(...)` modifier covers both `.scaleEffect` and
 * `.opacity`. Deriving both from one animated float here keeps that guarantee —
 * two independent animations could drift apart by a frame and leave a visible
 * ring at full expansion.
 */
internal object NemaraPulseSpec {
    /** `.easeOut(duration: 1.6)`, in milliseconds. */
    const val DURATION_MILLIS = 1600

    /** `.scaleEffect(animate ? 2.4 : 0.6)` — the ring starts inside the dot. */
    const val RING_SCALE_START = 0.6f
    const val RING_SCALE_END = 2.4f

    /** `.opacity(animate ? 0 : 0.5)` — the ring fades out completely as it grows. */
    const val RING_ALPHA_START = 0.5f
    const val RING_ALPHA_END = 0f

    /** The solid centre dot is `size * 0.6` across, unaffected by the animation. */
    const val DOT_FRACTION = 0.6f

    /**
     * `.repeatForever(autoreverses: false)` — every cycle starts over from the
     * inside. Reversing instead would make the ring shrink back into the dot,
     * a visible defect on every LIVE chip in the app, so it is stated here as a
     * value rather than left implicit in the animation spec.
     */
    const val REPEAT_REVERSES = false

    /** iOS `init(color: NemaraColor.terra, size: 8)`. */
    val DEFAULT_SIZE_DP = 8f

    /**
     * SwiftUI's `.easeOut` is CSS ease-out, i.e. cubic-bezier(0, 0, 0.58, 1).
     * Spelled out rather than using the stdlib `EaseOut` alias so the mapping to
     * the Swift is checkable without opening the Compose source.
     */
    val RingEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** Ring diameter as a multiple of [NemaraPulse]'s `size`, at animation progress 0..1. */
    fun ringScale(progress: Float): Float =
        RING_SCALE_START + (RING_SCALE_END - RING_SCALE_START) * progress

    /** Ring opacity at animation progress 0..1. */
    fun ringAlpha(progress: Float): Float =
        RING_ALPHA_START + (RING_ALPHA_END - RING_ALPHA_START) * progress

    /** Diameter of the solid centre dot for a pulse of the given overall [size]. */
    fun dotDiameter(size: Dp): Dp = size * DOT_FRACTION
}

/**
 * Pulsing dot. Used for the LIVE chip on the camera feed and for the
 * critical-event indicator on patient rows.
 *
 * Implementation: a fixed-size colored circle in the center plus an
 * expanding outer ring that scales and fades. Matches the
 * `@keyframes nemaraPulse` from the design canvas.
 *
 * Two Compose-specific notes:
 *
 * **The ring deliberately overflows the layout box.** iOS declares
 * `.frame(width: size, height: size)` and then scales the ring to 2.4×, and
 * SwiftUI does not clip to a frame — so the ring paints outside the 8dp box and
 * over whatever sits beside it. Drawing here is likewise unclipped, which is why
 * the ring is drawn in a `Canvas` rather than as a scaled child Box: a `Box` with
 * `Modifier.clip` (or any ancestor clip added later) would crop it. Nothing may
 * add a clip to this component.
 *
 * **The animated value is read inside the draw lambda, not at composition.**
 * `progress.value` is deliberately not delegated with `by`; reading it in the
 * composable body would recompose this node on every frame, forever, on every
 * screen that shows a LIVE chip. Read from the draw lambda it invalidates the
 * draw phase only.
 */
@Composable
fun NemaraPulse(
    modifier: Modifier = Modifier,
    color: Color = Terra,
    size: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    // iOS: .easeOut(duration: 1.6).repeatForever(autoreverses: false), started by
    // flipping one @State flag in .onAppear. RepeatMode.Restart is the
    // autoreverses:false half of that; an infinite transition is the equivalent
    // of the flag, since it starts with the composition and stops with it.
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = NemaraPulseSpec.DURATION_MILLIS,
                easing = NemaraPulseSpec.RingEasing,
            ),
            repeatMode = if (NemaraPulseSpec.REPEAT_REVERSES) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "pulseProgress",
    )

    // Aliased because inside a DrawScope lambda the name `size` resolves to the
    // receiver's Size (the layout bounds in pixels), not to this parameter.
    val diameter = size

    Canvas(modifier = modifier.size(size)) {
        val p = progress.value

        drawCircle(
            color = color,
            radius = diameter.toPx() / 2f * NemaraPulseSpec.ringScale(p),
            alpha = NemaraPulseSpec.ringAlpha(p),
        )
        drawCircle(
            color = color,
            radius = NemaraPulseSpec.dotDiameter(diameter).toPx() / 2f,
        )
    }
}
