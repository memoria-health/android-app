package com.memoria.caregiver.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pulse is nothing but tuning constants and a curve, so that is what is
 * tested. Spec: DesignSystem/Components/NemaraPulse.swift.
 *
 * The failure this suite exists to catch is a number quietly rounded or
 * inverted in transcription — a 2.4 that became 2.0, an alpha that no longer
 * reaches zero, or an ease-IN curve where iOS has ease-OUT. None of those break
 * the build and none are visible in a still screenshot.
 */
class NemaraPulseTest {

    private val tolerance = 1e-6f

    // ---- ring scale: .scaleEffect(animate ? 2.4 : 0.6) ----

    @Test fun `ring grows from six tenths to two and four tenths`() {
        assertEquals(0.6f, NemaraPulseSpec.ringScale(0f), tolerance)
        assertEquals(2.4f, NemaraPulseSpec.ringScale(1f), tolerance)
    }

    @Test fun `ring scale is linear in progress, the curve lives in the easing`() {
        assertEquals(1.5f, NemaraPulseSpec.ringScale(0.5f), tolerance)
        assertEquals(1.05f, NemaraPulseSpec.ringScale(0.25f), tolerance)
    }

    /**
     * The ring ends 2.4× the declared frame, so it paints outside the layout
     * box. iOS relies on SwiftUI not clipping a `.frame`; this pins the fact
     * that the overflow is intended, so nobody "fixes" the clipping.
     */
    @Test fun `ring ends larger than the layout box`() {
        assertTrue(NemaraPulseSpec.ringScale(1f) > 1f)
        assertTrue(NemaraPulseSpec.ringScale(0f) < 1f)
    }

    // ---- ring opacity: .opacity(animate ? 0 : 0.5) ----

    @Test fun `ring fades from half opacity to fully transparent`() {
        assertEquals(0.5f, NemaraPulseSpec.ringAlpha(0f), tolerance)
        assertEquals(0f, NemaraPulseSpec.ringAlpha(1f), tolerance)
    }

    @Test fun `ring never starts opaque and never goes negative`() {
        var previous = Float.POSITIVE_INFINITY
        for (step in 0..100) {
            val alpha = NemaraPulseSpec.ringAlpha(step / 100f)
            assertTrue("alpha $alpha out of range at step $step", alpha in 0f..1f)
            assertTrue("alpha rose at step $step", alpha < previous)
            previous = alpha
        }
    }

    @Test fun `ring expands as it fades, never the reverse`() {
        var previous = Float.NEGATIVE_INFINITY
        for (step in 0..100) {
            val scale = NemaraPulseSpec.ringScale(step / 100f)
            assertTrue("scale shrank at step $step", scale > previous)
            previous = scale
        }
    }

    // ---- the curve ----

    @Test fun `one cycle lasts one point six seconds`() {
        assertEquals(1600, NemaraPulseSpec.DURATION_MILLIS)
    }

    /**
     * Ease-out decelerates: it covers more than its share of the distance early,
     * so the curve sits ABOVE the diagonal everywhere between the endpoints. An
     * ease-in curve, or a linear one, fails this.
     */
    @Test fun `easing decelerates, matching SwiftUI easeOut`() {
        assertEquals(0f, NemaraPulseSpec.RingEasing.transform(0f), tolerance)
        assertEquals(1f, NemaraPulseSpec.RingEasing.transform(1f), tolerance)
        for (step in 1..99) {
            val t = step / 100f
            val eased = NemaraPulseSpec.RingEasing.transform(t)
            assertTrue("expected eased $eased > $t", eased > t)
        }
    }

    // ---- the solid centre dot ----

    // Compared as floats with a tolerance rather than as Dp values: 12.dp * 0.6f
    // lands exactly halfway between two representable floats, so Dp equality
    // there depends on the rounding mode rather than on the port being correct.
    @Test fun `centre dot is six tenths of the overall size`() {
        assertEquals(4.8f, NemaraPulseSpec.dotDiameter(8.dp).value, 1e-5f)
        assertEquals(7.2f, NemaraPulseSpec.dotDiameter(12.dp).value, 1e-5f)
    }
}
