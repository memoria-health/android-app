package com.memoria.caregiver.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dial's rendering rules. Spec: PARITY.md §27 and `CoverageDial.swift`.
 *
 * These are pure because getting them wrong is invisible in a screenshot of a
 * still dial and obvious only in the hand: a needle that greens before it
 * reaches a captured arc tells the caregiver an angle is done when it is not,
 * and an arc geometry that drifts leaves gaps the eye reads as missing captures.
 */
class CoverageDialRulesTest {

    private val twelve = CoverageDialState(faceVisible = true, wellFramed = true)

    // ---- geometry ----

    @Test
    fun `twelve arcs with a five degree gap`() {
        assertEquals(12, COVERAGE_SEGMENT_COUNT)
        assertEquals(5.0, COVERAGE_ARC_GAP_DEG, 0.0)
    }

    /** Arc centres sit half a span in, so arc 0 straddles 0 degrees rather than starting on it. */
    @Test
    fun `arc centres are evenly spaced from half a span`() {
        assertEquals(15.0, coverageSegmentCentreDeg(0), 1e-9)
        assertEquals(45.0, coverageSegmentCentreDeg(1), 1e-9)
        assertEquals(345.0, coverageSegmentCentreDeg(11), 1e-9)
    }

    /** Each arc gives up half a gap at each end: 30 / 2 - 5 / 2. */
    @Test
    fun `an arc spans its share of the circle less the gap`() {
        assertEquals(12.5, coverageArcHalfSpanDeg(), 1e-9)
        assertEquals(25.0, coverageArcHalfSpanDeg() * 2, 1e-9)
    }

    // ---- arc states ----

    @Test
    fun `a captured arc is green even while the needle is on it`() {
        val state = twelve.copy(covered = setOf(4), currentSegment = 4)
        assertEquals(CoverageArcState.Covered, coverageArcState(4, state))
    }

    @Test
    fun `the arc under the needle charges and the rest recede`() {
        val state = twelve.copy(currentSegment = 4)
        assertEquals(CoverageArcState.Charging, coverageArcState(4, state))
        assertEquals(CoverageArcState.Idle, coverageArcState(5, state))
    }

    /**
     * The two uncovered widths differ so the charging arc reads as the live one.
     * Collapsing them to a single width loses the whole affordance.
     */
    @Test
    fun `the three arc widths are distinct`() {
        assertEquals(1f, CoverageArcState.Covered.widthMultiplier, 0f)
        assertEquals(0.65f, CoverageArcState.Charging.widthMultiplier, 0f)
        assertEquals(0.55f, CoverageArcState.Idle.widthMultiplier, 0f)
    }

    // ---- needle colour ----

    /** Off frame greys the needle: the cue that you are not lined up. */
    @Test
    fun `an unframed face greys the needle whatever it points at`() {
        val state = twelve.copy(wellFramed = false, covered = setOf(2), currentSegment = 2)
        assertEquals(CoverageNeedleTint.Unframed, coverageNeedleTint(state))
    }

    /** Green ONLY on an arc that is already captured. */
    @Test
    fun `green appears only on a finished arc`() {
        assertEquals(
            CoverageNeedleTint.OnCovered,
            coverageNeedleTint(twelve.copy(covered = setOf(2), currentSegment = 2)),
        )
        assertEquals(
            CoverageNeedleTint.Charging,
            coverageNeedleTint(twelve.copy(covered = setOf(3), currentSegment = 2)),
        )
    }

    /** Travelling out from the hub — no arc yet — is terra, not green. */
    @Test
    fun `a needle between arcs is charging, not done`() {
        assertEquals(
            CoverageNeedleTint.Charging,
            coverageNeedleTint(twelve.copy(covered = setOf(0, 1, 2), currentSegment = null)),
        )
    }

    // ---- thresholds ----

    @Test
    fun `the draw thresholds match iOS`() {
        assertEquals(0.02, CoverageChargeMinFrac, 0.0)
        assertEquals(0.05, CoverageNeedleMinFrac, 0.0)
    }

    // ---- the rear ring ----

    @Test
    fun `the rear ring is the longer side of the face box times 1_9`() {
        assertEquals(1.9f, REAR_RING_BOX_MULTIPLE, 0f)
        assertEquals(190f, rearRingDiameter(100f, 60f), 1e-4f)
        assertEquals(190f, rearRingDiameter(60f, 100f), 1e-4f)
    }
}
