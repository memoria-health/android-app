package com.memoria.caregiver.ui.settings

import com.memoria.caregiver.data.model.FaceEnrollmentSummary
import com.memoria.caregiver.data.model.Patient

/**
 * The state the patient "Settings" sub-tab renders from, and the pure logic that
 * sits behind it. Spec: `Features/Patient/Settings/PatientSettingsView.swift`
 * and its four cards, PARITY.md §24.
 *
 * **The screen is a pure `@Composable` over this type plus callbacks**, per the
 * pattern set in `AuthUiState.kt`: no ViewModel, no I/O, no API client. iOS
 * keeps every field below in `@State` on the view (or reads it off
 * `env.api.patients`); the shape here is the same state, hoisted.
 *
 * Each card carries its **own** `busy` / `error` pair because each card on iOS
 * has its own, and they are not interchangeable: PARITY.md §24 is explicit that
 * the three toggle cards must not be unified, because their copy and their
 * failure behavior differ. `AudioCaptureCard` renders its error *in place of*
 * the body copy; `DataCollectionCard` and `PatientEmailAlertsCard` render theirs
 * as a separate line below the master row.
 */
data class PatientSettingsState(
    /**
     * Null while the patient is not in the roster — iOS's
     * `env.api.patients.first(where:)` returning nil. Every card degrades to its
     * documented default rather than disappearing, and the remove button
     * disables.
     */
    val patient: Patient? = null,

    /**
     * iOS: `env.auth.currentCaregiver?.email ?? "your account"`. The literal is
     * the fallback shown in the read-only recipients row, so it is carried here
     * rather than in the card.
     */
    val accountEmail: String = "your account",

    /** Latest `GET /v1/patients/{id}/face-enrollment/summary`, or null before the first response. */
    val enrollmentSummary: FaceEnrollmentSummary? = null,

    /** Device-local "Blur people" preference for this patient (iOS: `PeopleBlurStore`). */
    val peopleBlurEnabled: Boolean = false,

    val isUploadingPhoto: Boolean = false,
    val photoUploadError: String? = null,
    val isDeleting: Boolean = false,

    val audioCapture: CardMutationState = CardMutationState(),
    val dataCollection: CardMutationState = CardMutationState(),
    val emailAlerts: EmailAlertsState = EmailAlertsState(),
)

/**
 * The `busy` + `error` pair every server-backed settings card keeps in `@State`.
 *
 * @param busy a PATCH is in flight. The card's toggle disables on it, matching
 *   `.disabled(busy)`, so a double tap cannot queue two conflicting writes.
 * @param errorText rendered verbatim. On iOS this is
 *   `error.localizedDescription` for `DataCollectionCard`, and the fixed
 *   sentence "Couldn't update audio setting. Try again." for `AudioCaptureCard`
 *   — the caller decides, the card just draws it.
 */
data class CardMutationState(
    val busy: Boolean = false,
    val errorText: String? = null,
)

/**
 * `PatientEmailAlertsCard`'s local state. One `busy` flag covers the toggle, the
 * add, the remove and the test send, exactly as the Swift does — which is why
 * the "Send test email" button shows a spinner while a *recipient* is being
 * added.
 *
 * @param draft the "add another email" field. Cleared by the host only after the
 *   PATCH succeeds, so a failed add does not lose what was typed.
 * @param testStatus the `status` string from `POST /v1/patients/{id}/test-email`,
 *   shown beside the button. Server copy, never reworded.
 */
data class EmailAlertsState(
    val draft: String = "",
    val busy: Boolean = false,
    val errorText: String? = null,
    val testStatus: String? = null,
)

// ---------------------------------------------------------------------------
// Identity (face-enrollment) card — pure derivation
// ---------------------------------------------------------------------------

/** iOS: `PatientSettingsView.IdentityCardState`. */
enum class IdentityCardState { Loading, Enrolled, Processing, NotEnrolled }

/**
 * iOS: `PatientSettingsView.identityState`.
 *
 * **Order matters and is not the obvious one**: processing is tested *before*
 * enrolled, so a patient who already has selfies but is mid-batch shows "in
 * progress" rather than "complete". Reversing the two would flip the card back
 * to "complete" the moment the first embedding lands and leave it there while
 * the rest of the batch is still running.
 */
fun identityCardState(summary: FaceEnrollmentSummary?): IdentityCardState = when {
    summary == null -> IdentityCardState.Loading
    summary.isProcessing -> IdentityCardState.Processing
    summary.isEnrolled -> IdentityCardState.Enrolled
    else -> IdentityCardState.NotEnrolled
}

/**
 * iOS: `identityCardTitle(state:name:)`. The wording is matched byte-for-byte
 * with the web dashboard's `PatientIdentityStatus` card so a caregiver flipping
 * between devices sees the same language. Do not reword.
 */
fun identityCardTitle(state: IdentityCardState, name: String): String = when (state) {
    IdentityCardState.Loading -> "Patient identity"
    IdentityCardState.Enrolled -> "$name's face enrollment is complete"
    IdentityCardState.Processing -> "$name's face enrollment is in progress"
    IdentityCardState.NotEnrolled -> "$name's face enrollment is incomplete"
}

/**
 * The name the identity card and the data-collection card interpolate.
 *
 * iOS is `patient?.preferredName ?? patient?.fullName ?? "this patient"`.
 * `preferredName` is non-optional on the model, so the `fullName` arm is
 * unreachable whenever a patient exists — carried anyway, because it is what the
 * Swift says and dropping it would be a silent behavior decision.
 */
fun patientDisplayName(patient: Patient?): String =
    patient?.preferredName ?: patient?.fullName ?: "this patient"

// ---------------------------------------------------------------------------
// Email-alert recipients — pure validation
// ---------------------------------------------------------------------------

/** The outcome of tapping "Add" on the recipients block. */
sealed interface AddRecipientResult {
    /** Nothing to do — the draft was blank. iOS returns early without an error. */
    data object Ignored : AddRecipientResult

    /** Show [message] in the card's error slot; the draft is left alone. */
    data class Rejected(val message: String) : AddRecipientResult

    /**
     * PATCH [recipients] — the **whole** array, because the server replaces it
     * wholesale on every add or remove.
     */
    data class Accepted(val recipients: List<String>) : AddRecipientResult
}

/**
 * iOS: `PatientEmailAlertsCard.isValidEmail`.
 *
 * A deliberately loose test, and it must stay exactly this loose: tightening it
 * would reject addresses iOS accepts, so the two platforms would disagree about
 * the same typed string.
 *
 * **The empty-component behavior is the trap.** Swift's
 * `split(separator: "@")` omits empty subsequences by default, so `"a@@b.com"`
 * splits to two parts and passes, while Kotlin's `split("@")` yields three and
 * would fail. The empty parts are filtered out here to reproduce Swift exactly.
 * `parts[0].isNotEmpty()` is then unreachable — carried because the Swift has
 * it, and because it stops being unreachable if the filter is ever removed.
 */
fun isValidRecipientEmail(value: String): Boolean {
    val parts = value.split("@").filter { it.isNotEmpty() }
    if (parts.size != 2) return false
    if (parts[0].isEmpty()) return false
    if (!parts[1].contains(".")) return false
    return !value.contains(" ")
}

/**
 * iOS: the guards at the top of `PatientEmailAlertsCard.add()`.
 *
 * Normalizes (trim + lowercase), rejects a malformed address, rejects one
 * already on the list — comparing case-insensitively against both the account
 * email and the existing recipients — and otherwise returns the full next array.
 *
 * The two rejection sentences are product copy and are asserted by test.
 *
 * `trim()` is a hair wider than Swift's `.whitespaces`, which is the Unicode
 * space separators plus tab and does **not** include newlines; Kotlin's
 * `Char.isWhitespace()` does. The field is single-line on both platforms, so no
 * newline can reach here — noted rather than worked around, because narrowing it
 * would mean hand-rolling a character predicate for a case that cannot occur.
 */
fun addRecipient(
    draft: String,
    accountEmail: String,
    recipients: List<String>,
): AddRecipientResult {
    val email = draft.trim().lowercase()
    if (email.isEmpty()) return AddRecipientResult.Ignored
    if (!isValidRecipientEmail(email)) {
        return AddRecipientResult.Rejected("That doesn't look like a valid email address.")
    }
    val duplicate = email == accountEmail.lowercase() ||
        recipients.any { it.lowercase() == email }
    if (duplicate) {
        return AddRecipientResult.Rejected("That address is already on the list.")
    }
    return AddRecipientResult.Accepted(recipients + email)
}

/**
 * iOS: `remove(_:)`. Exact-match filter, not case-insensitive — the list is the
 * server's, and removing by anything looser could drop a second address that
 * differs only in case.
 */
fun removeRecipient(email: String, recipients: List<String>): List<String> =
    recipients.filter { it != email }
