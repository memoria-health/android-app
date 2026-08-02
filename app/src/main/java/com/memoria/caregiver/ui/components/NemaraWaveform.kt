package com.memoria.caregiver.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.TerraInk
import kotlin.math.max
import kotlin.math.min

// ---- Tuning constants, carried from NemaraWaveform.swift ----

/**
 * How many bars to render. "A comfortable spoken-language width for ~3-4s of
 * audio at 20-50 fps."
 */
internal const val WaveformDefaultBarCount = 40

/**
 * Minimum bar height so an idle/silent input still shows a faint baseline, not
 * flat zero. Expressed as a fraction of the container height (0.05 = 5%).
 *
 * The Swift doc comment gives 5% as the illustration but the default is 6%.
 * Both are carried verbatim rather than reconciled — the number is what ships.
 */
internal const val WaveformDefaultMinHeightFraction = 0.06f

/** Gap between bars. `let spacing: CGFloat = 2` in the Swift. */
internal val WaveformBarSpacing: Dp = 2.dp

/**
 * Floor on the computed bar width, from the Swift's `max(1, …)`. Keeps the
 * waveform visible rather than vanishing when the container is too narrow for
 * [WaveformDefaultBarCount] bars plus their spacing.
 */
internal val WaveformMinBarWidth: Dp = 1.dp

/** `.easeOut(duration: 0.08)` in the Swift. */
internal const val WaveformAnimationDurationMillis = 80

// ---- Pure geometry, kept out of the composable so it is unit-testable ----

/** The visual takes the trailing [count] entries; fewer is fine while warming up. */
internal fun waveformTrailingSamples(samples: List<Float>, count: Int): List<Float> =
    if (samples.size <= count) samples else samples.takeLast(count)

/**
 * Index `0` is the oldest visible bar; index `barCount-1` is the newest.
 *
 * Right-align: idx -> sample at (len - barCount + idx). When we don't have
 * enough samples yet (rolling buffer warming up), missing slots resolve to 0 and
 * therefore to the floor height.
 *
 * [samples] must already be the trailing window, i.e. the output of
 * [waveformTrailingSamples].
 */
internal fun waveformLevel(index: Int, samples: List<Float>, barCount: Int): Float {
    val offset = samples.size - barCount + index
    return if (offset < 0 || offset >= samples.size) 0f else samples[offset]
}

/**
 * Bar height as a fraction of the container height.
 *
 * The Swift computes `max(containerHeight * minHeightFraction, clamp(level) *
 * containerHeight)`. Both terms scale linearly with the container, so factoring
 * the height out is exact and leaves a resolution-independent number the draw
 * pass can multiply by whatever it is given.
 */
internal fun waveformBarFraction(
    index: Int,
    samples: List<Float>,
    barCount: Int,
    minHeightFraction: Float,
): Float = max(minHeightFraction, min(max(waveformLevel(index, samples, barCount), 0f), 1f))

/** One fraction per bar, oldest first. Always [barCount] entries. */
internal fun waveformFractions(
    samples: List<Float>,
    barCount: Int,
    minHeightFraction: Float,
): FloatArray = FloatArray(barCount) { waveformBarFraction(it, samples, barCount, minHeightFraction) }

/**
 * `max(1, (width - (barCount - 1) * spacing) / barCount)`, in pixels.
 *
 * Unclamped this divides the container exactly; the clamp only bites when the
 * container is narrower than `barCount * minWidth + (barCount - 1) * spacing`.
 */
internal fun waveformBarWidth(
    containerWidth: Float,
    barCount: Int,
    spacing: Float,
    minWidth: Float,
): Float = max(minWidth, (containerWidth - (barCount - 1) * spacing) / barCount)

/**
 * Left edge of the bar row. The Swift frames the HStack to the full width with
 * `alignment: .center`, so once [waveformBarWidth] clamps, the overflow hangs
 * off both edges equally rather than off the right.
 */
internal fun waveformRowOrigin(
    containerWidth: Float,
    barWidth: Float,
    barCount: Int,
    spacing: Float,
): Float = (containerWidth - (barCount * barWidth + (barCount - 1) * spacing)) / 2f

/**
 * Generic audio-level waveform — a horizontal row of vertical bars whose heights
 * track the most-recent N samples of a `List<Float>` level stream
 * (range 0.0-1.0).
 *
 * **Decoupled from any specific audio source.** Callers pass in an
 * already-computed levels list; producers like the LiveKit client keep that list
 * fresh. This keeps the component reusable for future surfaces (mic-test
 * screens, dictation affordances, etc.) without dragging a LiveKit dep into the
 * design system.
 *
 * The bars render right-aligned (newest sample on the right) so the shape
 * "slides leftward" as new samples arrive — the standard scrolling-mic-waveform
 * feel.
 *
 * **Drawn with one Canvas rather than a Row of 40 shapes.** The Swift uses an
 * HStack of Capsules because SwiftUI collapses that to nothing; here it would be
 * 40 layout nodes re-measured on every audio frame (20-50 fps). The geometry is
 * identical: a Capsule is a rounded rectangle whose corner radius is half its
 * shorter side, which is what [CornerRadius] gets below.
 *
 * **[levels] must arrive as a new list (or a snapshot-backed one).** SwiftUI
 * arrays are values, so mutating the producer's buffer re-renders for free;
 * Kotlin lists are references, so a plain `MutableList` mutated in place will
 * not invalidate anything.
 *
 * Sizes itself to the space it is given, like the Swift's `GeometryReader`; the
 * call site supplies the frame (`Modifier.size(100.dp, 22.dp)` on the live
 * screen).
 *
 * @param levels Recent audio peak levels (0.0-1.0). The visual takes the
 *   trailing [barCount] entries.
 * @param barCount How many bars to render.
 * @param tint Bar fill tint. Defaults to the brand accent so the waveform reads
 *   "live"; pass a muted color for idle/inactive states.
 * @param minHeightFraction Minimum bar height, as a fraction of the container
 *   height, so silence still shows a baseline.
 */
@Composable
fun NemaraWaveform(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    barCount: Int = WaveformDefaultBarCount,
    tint: Color = TerraInk,
    minHeightFraction: Float = WaveformDefaultMinHeightFraction,
) {
    val displayed = waveformTrailingSamples(levels, barCount)
    val target = waveformFractions(displayed, barCount, minHeightFraction)

    // The Swift attaches `.animation(.easeOut(duration: 0.08), value: displayed.count)`,
    // which animates height changes ONLY on the frames where the number of
    // visible samples changes — i.e. while the rolling buffer warms up. Once it
    // is full the count is constant and every later height change is applied
    // instantly. That is not "animate the waveform"; reproducing it faithfully
    // means an animation with an explicit trigger, not a per-bar
    // animate*AsState (which would smooth every frame forever).
    //
    // [from] is where the bars were when the current animation began, [to] is
    // where they are heading, [blend] walks 0 -> 1 between them. All three are
    // keyed on barCount so the three arrays can never disagree on length.
    val from = remember(barCount) { mutableStateOf(target) }
    val to = remember(barCount) { mutableStateOf(target) }
    val blend = remember(barCount) { Animatable(1f) }
    val lastCount = remember(barCount) { mutableStateOf(displayed.size) }

    // `target` is a fresh array each composition, so this restarts whenever new
    // levels arrive; the restart cancels an in-flight animateTo, which is the
    // interruption behavior SwiftUI has anyway. The effect body runs in the same
    // frame as the composition that triggered it and before the draw pass, so
    // the bars never render a frame behind the levels they were given.
    // Keyed on the CONTENT, not the array instance. `target` is a FloatArray
    // returned fresh from waveformFractions on every composition, and Compose
    // compares keys with ==, which for arrays is referential identity — so
    // keying on it restarts the effect on EVERY recomposition, including ones
    // where the levels did not change, cutting an in-flight animation short.
    // SwiftUI supersedes an animation only when the animated value changes.
    // toList() gives structural equality, so the effect restarts exactly when
    // the bar heights actually differ.
    LaunchedEffect(target.toList()) {
        val countChanged = displayed.size != lastCount.value
        lastCount.value = displayed.size
        if (countChanged) {
            // Freeze the bars where they currently are, then ease to the new
            // shape. Compose's EaseOut is CubicBezier(0, 0, 0.58, 1), the same
            // curve SwiftUI's .easeOut uses.
            val t = blend.value
            val start = from.value
            val end = to.value
            from.value = FloatArray(barCount) { start[it] + (end[it] - start[it]) * t }
            to.value = target
            blend.snapTo(0f)
            blend.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = WaveformAnimationDurationMillis,
                    easing = EaseOut,
                ),
            )
        } else {
            from.value = target
            to.value = target
            blend.snapTo(1f)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val spacing = WaveformBarSpacing.toPx()
        val barWidth = waveformBarWidth(size.width, barCount, spacing, WaveformMinBarWidth.toPx())
        val t = blend.value
        val start = from.value
        val end = to.value
        var x = waveformRowOrigin(size.width, barWidth, barCount, spacing)
        for (i in 0 until barCount) {
            val barHeight = (start[i] + (end[i] - start[i]) * t) * size.height
            drawRoundRect(
                color = tint,
                // HStack(alignment: .center): each bar is centred vertically.
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(min(barWidth, barHeight) / 2f),
            )
            x += barWidth + spacing
        }
    }
}
