package com.memoria.caregiver.ui.settings

import com.memoria.caregiver.data.model.FaceEnrollmentSummary
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * Attempts, and the gap between them. iOS: `for attempt in 0..<40` with
 * `Task.sleep(for: .seconds(2))`, giving the "~80s" the Swift comment quotes.
 *
 * These are the screen's tuning constants and are the whole reason this function
 * exists here rather than being re-invented by whatever hosts the screen: a
 * 40 x 2s budget is what makes the identity card flip from "in progress" to
 * "complete" on its own, without the caregiver leaving and re-opening the tab.
 */
internal const val ENROLLMENT_POLL_ATTEMPTS = 40
internal const val ENROLLMENT_POLL_INTERVAL_MS = 2_000L

/**
 * iOS: `PatientSettingsView.loadEnrollmentSummary()`.
 *
 * Polls while the backend is still computing embeddings, publishing each summary
 * through [onSummary], and stops as soon as nothing is processing or the budget
 * is spent.
 *
 * Three behaviors carried verbatim:
 *
 * 1. **Best effort.** A failed request is swallowed (iOS logs and continues), so
 *    a transient failure leaves the card in its previous state instead of
 *    blanking it or surfacing an error the caregiver cannot act on.
 * 2. **No trailing sleep.** iOS guards the sleep with `if attempt < 39`, so the
 *    last attempt returns immediately rather than idling for two more seconds.
 * 3. **Cancellable throughout.** iOS re-checks `Task.isCancelled` after the sleep
 *    because `try?` swallows the `CancellationError`. The Kotlin equivalent is to
 *    let `delay` throw and to rethrow [CancellationException] from the catch, so a
 *    cancelled poll does not keep writing into a composition that is going away.
 *
 * Called from the host with the same lifecycle scoping as `.task(id: reloadToken)`:
 * cancelled when the screen leaves, restarted when the token bumps after
 * re-enrollment.
 */
suspend fun pollFaceEnrollmentSummary(
    load: suspend () -> FaceEnrollmentSummary,
    onSummary: (FaceEnrollmentSummary) -> Unit,
) {
    for (attempt in 0 until ENROLLMENT_POLL_ATTEMPTS) {
        try {
            val summary = load()
            onSummary(summary)
            if (!summary.isProcessing) return
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // iOS: NSLog + continue. A transient failure must not clear the card.
        }
        if (attempt < ENROLLMENT_POLL_ATTEMPTS - 1) {
            delay(ENROLLMENT_POLL_INTERVAL_MS)
        }
    }
}
