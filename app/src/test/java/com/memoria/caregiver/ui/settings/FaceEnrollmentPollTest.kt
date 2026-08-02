package com.memoria.caregiver.ui.settings

import com.memoria.caregiver.data.model.FaceEnrollmentSummary
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The enrollment poll behind the identity card. Spec:
 * `PatientSettingsView.loadEnrollmentSummary()`.
 *
 * `runTest`'s virtual clock means the 80-second budget is asserted in
 * milliseconds of wall time, not skipped.
 */
class FaceEnrollmentPollTest {

    private fun summary(processing: Int) = FaceEnrollmentSummary(
        selfieCount = 1,
        harvestedCount = 0,
        processingCount = processing,
        failedCount = 0,
    )

    @Test
    fun `stops as soon as nothing is processing`() = runTest {
        var calls = 0
        val seen = mutableListOf<FaceEnrollmentSummary>()

        pollFaceEnrollmentSummary(
            load = { calls++; summary(processing = 0) },
            onSummary = { seen += it },
        )

        assertEquals(1, calls)
        assertEquals(1, seen.size)
        // No trailing sleep on the terminating attempt.
        assertEquals(0L, currentTime)
    }

    @Test
    fun `keeps polling while a batch is processing, then stops`() = runTest {
        var calls = 0

        pollFaceEnrollmentSummary(
            load = { calls++; summary(processing = if (calls < 3) 2 else 0) },
            onSummary = {},
        )

        assertEquals(3, calls)
        // Two 2s gaps between three attempts, and none after the last.
        assertEquals(2 * ENROLLMENT_POLL_INTERVAL_MS, currentTime)
    }

    /**
     * The budget. 40 attempts with a gap between each but not after the last,
     * which is the "~80s" the Swift comment quotes. Dropping the
     * `attempt < 39` guard would add a pointless two-second tail; raising the
     * count would leave a screen polling long after the caregiver has moved on.
     */
    @Test
    fun `gives up after the full budget`() = runTest {
        var calls = 0

        pollFaceEnrollmentSummary(
            load = { calls++; summary(processing = 1) },
            onSummary = {},
        )

        assertEquals(ENROLLMENT_POLL_ATTEMPTS, calls)
        assertEquals(
            (ENROLLMENT_POLL_ATTEMPTS - 1) * ENROLLMENT_POLL_INTERVAL_MS,
            currentTime,
        )
    }

    /**
     * Best effort: a transient failure must not end the poll and must not clear
     * the card. iOS logs and carries on.
     */
    @Test
    fun `a failed request is swallowed and the poll continues`() = runTest {
        var calls = 0
        val seen = mutableListOf<FaceEnrollmentSummary>()

        pollFaceEnrollmentSummary(
            load = {
                calls++
                if (calls == 1) throw IOException("offline")
                summary(processing = 0)
            },
            onSummary = { seen += it },
        )

        assertEquals(2, calls)
        // Only the successful attempt published, so the card keeps its previous
        // contents through the failure rather than blanking.
        assertEquals(1, seen.size)
    }
}
