package com.memoria.caregiver.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which milestone cue fires, and when. Spec: PARITY.md §27's "Haptics +
 * lifecycle".
 *
 * The rule worth pinning is the suppression: the capture that completes
 * enrollment must produce one cue, not an arc buzz immediately followed by the
 * completion buzz. On iOS that falls out of the ordering of SwiftUI's change
 * handlers, which is exactly the kind of accident a port loses.
 */
class EnrollmentHapticsTest {

    @Test
    fun `a newly covered arc buzzes`() {
        assertEquals(
            EnrollmentHaptic.ArcCaptured,
            enrollmentHaptic(
                previousCovered = 3, covered = 4,
                previousFrontal = 1, frontal = 1,
                wasComplete = false, isComplete = false,
            ),
        )
    }

    /** The frontal frame is a capture too, and there are two of them. */
    @Test
    fun `a newly kept frontal frame buzzes`() {
        assertEquals(
            EnrollmentHaptic.ArcCaptured,
            enrollmentHaptic(
                previousCovered = 3, covered = 3,
                previousFrontal = 1, frontal = 2,
                wasComplete = false, isComplete = false,
            ),
        )
    }

    @Test
    fun `nothing fires when nothing was captured`() {
        assertNull(
            enrollmentHaptic(
                previousCovered = 3, covered = 3,
                previousFrontal = 1, frontal = 1,
                wasComplete = false, isComplete = false,
            ),
        )
    }

    /** The whole point: one cue on the capture that finishes it. */
    @Test
    fun `the arc buzz is suppressed on the capture that completes`() {
        assertEquals(
            EnrollmentHaptic.Completed,
            enrollmentHaptic(
                previousCovered = 11, covered = 12,
                previousFrontal = 2, frontal = 2,
                wasComplete = false, isComplete = true,
            ),
        )
    }

    /** Completion is announced once; a later recomposition must stay silent. */
    @Test
    fun `completion does not re-fire`() {
        assertNull(
            enrollmentHaptic(
                previousCovered = 12, covered = 12,
                previousFrontal = 2, frontal = 2,
                wasComplete = true, isComplete = true,
            ),
        )
    }

    /**
     * Frames can still arrive after completion — the camera stops on completion,
     * but a frame already in flight must not buzz behind the completion cue.
     */
    @Test
    fun `a capture after completion is silent`() {
        assertNull(
            enrollmentHaptic(
                previousCovered = 12, covered = 12,
                previousFrontal = 2, frontal = 3,
                wasComplete = true, isComplete = true,
            ),
        )
    }
}
