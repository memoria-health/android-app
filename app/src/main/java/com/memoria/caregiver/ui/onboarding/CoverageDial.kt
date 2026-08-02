package com.memoria.caregiver.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Terra
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The coverage ring shared by both enrollment dials. Spec: PARITY.md §27 and
 * `Features/Onboarding/Enrollment/CoverageDial.swift`.
 *
 * One idea, one state per arc: an arc is faint until captured and green once
 * captured. Over the arcs, a pose needle points from the centre in the direction
 * the (smoothed) nose faces, its length showing how extreme the angle is. Sweep
 * the needle into a faint arc and it charges terra, then snaps green the instant
 * it captures. The needle greys out when the face is not well framed — the cue
 * that you are not lined up even at a good angle. No counts, no text.
 *
 * The dial renders [CoverageDialState] and nothing else. The coverage model that
 * produces it owns capture, and lives outside this unit.
 */
@Composable
fun CoverageDial(
    state: CoverageDialState,
    modifier: Modifier = Modifier,
    lineWidth: Dp = CoverageDialFrontLineWidth,
) {
    Canvas(modifier.fillMaxSize()) {
        val stroke = lineWidth.toPx()
        val radius = min(size.width, size.height) / 2f - stroke
        val centre = Offset(size.width / 2f, size.height / 2f)
        val half = coverageArcHalfSpanDeg(state.segmentCount)

        for (index in 0 until state.segmentCount) {
            // The model is 0 deg = right, 90 deg = up, counter-clockwise. The
            // screen is y-down, so every angle is negated at draw time; get this
            // wrong and the whole dial renders vertically mirrored.
            val startDeg = -(coverageSegmentCentreDeg(index, state.segmentCount) + half)
            val sweepDeg = half * 2.0

            when (coverageArcState(index, state)) {
                CoverageArcState.Covered ->
                    strokeArc(centre, radius, startDeg, sweepDeg, Success, stroke)

                CoverageArcState.Charging -> {
                    strokeArc(
                        centre, radius, startDeg, sweepDeg, Line,
                        stroke * CoverageArcState.Charging.widthMultiplier,
                    )
                    val frac = state.needleFrac
                    if (frac > CoverageChargeMinFrac) {
                        strokeArc(centre, radius, startDeg, sweepDeg * frac, Terra, stroke)
                    }
                }

                CoverageArcState.Idle ->
                    strokeArc(
                        centre, radius, startDeg, sweepDeg, Line,
                        stroke * CoverageArcState.Idle.widthMultiplier,
                    )
            }
        }

        drawNeedle(state, centre, radius)
    }
}

// ---------------------------------------------------------------------------
// Rendering rules — pure, so they can be tested without a Canvas
// ---------------------------------------------------------------------------

/** Twelve arcs, matching `FaceCoverageModel`'s `segmentCount`. */
const val COVERAGE_SEGMENT_COUNT: Int = 12

/** Gap between neighbouring arcs, in degrees. */
const val COVERAGE_ARC_GAP_DEG: Double = 5.0

/** Below this the charging fill is not drawn at all — it would be a speck. */
const val CoverageChargeMinFrac: Double = 0.02

/** Below this the needle itself is not drawn: the hub alone marks the centre. */
const val CoverageNeedleMinFrac: Double = 0.05

/** iOS: `CoverageDial(coverage:lineWidth: 14)` on the front dial. */
val CoverageDialFrontLineWidth: Dp = 14.dp

/** iOS: `lineWidth: 12` on the rear dial, which sits over live video. */
val CoverageDialRearLineWidth: Dp = 12.dp

/** Hub radius. The pointer's pivot, and the frontal-capture target. */
private val HubRadius = 12.dp

/**
 * What the dial draws for a given arc, and how wide.
 *
 * The two uncovered widths differ on purpose: the arc the nose currently points
 * at sits at 0.65 so the terra fill growing over it reads as charging, and every
 * other arc sits at 0.55 so it recedes.
 */
enum class CoverageArcState(val widthMultiplier: Float) {
    Covered(1f),
    Charging(0.65f),
    Idle(0.55f),
}

/** Which of the three states arc [index] is in. */
fun coverageArcState(index: Int, state: CoverageDialState): CoverageArcState = when {
    index in state.covered -> CoverageArcState.Covered
    index == state.currentSegment -> CoverageArcState.Charging
    else -> CoverageArcState.Idle
}

/**
 * Needle colour, which is the whole of its guidance.
 *
 * Green appears **only** when the tip is on an arc that is already captured.
 * Travelling out from the hub, or charging an uncovered arc, is terra; off-frame
 * is grey. Greening before the needle reaches a finished edge would tell the
 * caregiver an angle is done when it is not.
 */
enum class CoverageNeedleTint { Unframed, Charging, OnCovered }

fun coverageNeedleTint(state: CoverageDialState): CoverageNeedleTint = when {
    !state.wellFramed -> CoverageNeedleTint.Unframed
    state.currentSegment != null && state.currentSegment in state.covered ->
        CoverageNeedleTint.OnCovered
    else -> CoverageNeedleTint.Charging
}

/** Centre direction of arc [index] in model degrees (0 = right, 90 = up). */
fun coverageSegmentCentreDeg(index: Int, segmentCount: Int = COVERAGE_SEGMENT_COUNT): Double =
    (index + 0.5) * (360.0 / segmentCount)

/** Half the drawn span of one arc: half its share of the circle, less half a gap. */
fun coverageArcHalfSpanDeg(
    segmentCount: Int = COVERAGE_SEGMENT_COUNT,
    gapDeg: Double = COVERAGE_ARC_GAP_DEG,
): Double = 360.0 / segmentCount / 2.0 - gapDeg / 2.0

/**
 * What the dial needs to draw itself, produced by the coverage model.
 *
 * Deliberately not the model: the model owns capture, quality floors and frame
 * retention, none of which the dial can see. Keeping the seam at plain values
 * also means the dial's rules are testable without a camera.
 */
@Immutable
data class CoverageDialState(
    val segmentCount: Int = COVERAGE_SEGMENT_COUNT,
    /** Indices of arcs with at least one kept frame. */
    val covered: Set<Int> = emptySet(),
    /** Arc the nose currently points at; null when frontal or with no face. */
    val currentSegment: Int? = null,
    /** Smoothed nose direction, model degrees (0 = right, 90 = up). */
    val needleAngleDeg: Double = 0.0,
    /** How far the needle reaches toward the rim, 0..1 (1 = capture angle). */
    val needleFrac: Double = 0.0,
    val faceVisible: Boolean = false,
    /** Fully in frame, so a capture is possible. False greys the needle. */
    val wellFramed: Boolean = false,
    /** The straight-ahead frame is captured. Greens the hub. */
    val hasFrontal: Boolean = false,
)

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

private fun DrawScope.strokeArc(
    centre: Offset,
    radius: Float,
    startDeg: Double,
    sweepDeg: Double,
    color: Color,
    width: Float,
) {
    drawArc(
        color = color,
        startAngle = startDeg.toFloat(),
        sweepAngle = sweepDeg.toFloat(),
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

/**
 * The pose pointer: a fixed centre hub marking where the nose should sit, and a
 * thin gradient shaft growing out of it toward a haloed tip bead.
 *
 * **The hub is the frontal target, not decoration.** It greens once the
 * straight-ahead frame is captured, which is what makes "everything green" the
 * real completion condition rather than hiding a gate the caregiver cannot see.
 */
private fun DrawScope.drawNeedle(state: CoverageDialState, centre: Offset, radius: Float) {
    val color = when (coverageNeedleTint(state)) {
        CoverageNeedleTint.Unframed -> Ink3.copy(alpha = 0.75f)
        CoverageNeedleTint.OnCovered -> Success
        CoverageNeedleTint.Charging -> Terra
    }

    val framed = state.wellFramed && state.faceVisible
    val hubColor = when {
        state.hasFrontal -> Success
        framed -> Color.White.copy(alpha = 0.92f)
        else -> Ink3.copy(alpha = 0.8f)
    }
    val hubR = HubRadius.toPx()
    drawCircle(
        color = hubColor,
        radius = hubR,
        center = centre,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(color = hubColor, radius = 2.5.dp.toPx(), center = centre)

    if (!state.faceVisible || state.needleFrac <= CoverageNeedleMinFrac) return

    // Model angle (0 = right, 90 = up) to screen (y-down): negate the y term.
    val a = Math.toRadians(state.needleAngleDeg)
    val len = radius * state.needleFrac.toFloat()
    // Start at the hub rim so shaft and hub read as one connected pointer.
    val start = Offset(
        centre.x + (cos(a) * hubR).toFloat(),
        centre.y - (sin(a) * hubR).toFloat(),
    )
    val tip = Offset(
        centre.x + (cos(a) * len).toFloat(),
        centre.y - (sin(a) * len).toFloat(),
    )

    // Thin gradient shaft — faint at the hub, vivid at the tip.
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(color.copy(alpha = color.alpha * 0.25f), color),
            start = start,
            end = tip,
        ),
        start = start,
        end = tip,
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )

    // Tip bead: soft halo, solid dot, crisp white core.
    val bead = 6.dp.toPx()
    drawCircle(color = color.copy(alpha = color.alpha * 0.18f), radius = bead + 4.dp.toPx(), center = tip)
    drawCircle(color = color, radius = bead, center = tip)
    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = 2.dp.toPx(), center = tip)
}

// ---------------------------------------------------------------------------
// Completion
// ---------------------------------------------------------------------------

/**
 * Big spring-in green tick shown when enrollment completes. Replaces the live
 * preview so the finished state reads as locked, not still running.
 */
@Composable
fun EnrollmentCompleteBadge(modifier: Modifier = Modifier, diameter: Dp = 300.dp) {
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }

    // iOS: .spring(response: 0.45, dampingFraction: 0.6). SwiftUI's response is
    // the undamped period, so stiffness = (2 pi / 0.45)^2 = 194.9 in the units
    // Compose's spring uses, and dampingFraction is dampingRatio unchanged.
    val progress by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 194.9f),
        label = "enrollment-complete-tick",
    )

    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Success.copy(alpha = 0.12f))
            .border(3.dp, Success, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CheckmarkGlyph(
            size = diameter * 0.32f,
            color = Success,
            // The tick springs from 0.4 to full size while fading in; the disc
            // behind it does not move.
            modifier = Modifier
                .scale(0.4f + 0.6f * progress)
                .alpha(progress),
        )
    }
}

/**
 * Locked completion state: green tick plus confirmation. Capturing has stopped,
 * the camera picker is disabled, and only Done remains.
 */
@Composable
fun EnrollmentCompleteView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EnrollmentCompleteBadge(diameter = 240.dp)
        Text(
            text = "Enrollment complete",
            style = NemaraType.title3,
            color = Success,
        )
    }
}
