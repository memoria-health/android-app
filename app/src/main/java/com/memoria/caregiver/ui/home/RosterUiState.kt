package com.memoria.caregiver.ui.home

import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.Patient

/**
 * The state the Home tab renders from, and the derivations it needs.
 *
 * **Screens are pure `@Composable` functions over these types**, the same
 * contract `ui/auth/AuthUiState.kt` establishes: no `ViewModel`, no I/O, no
 * direct service access. iOS keeps this in `@Environment(AppEnvironment.self)`
 * and reads `env.api.patients` / `env.api.eventsByPatient` / `env.auth
 * .currentCaregiver` straight off it; the shape below is the same state,
 * hoisted so the screen can be handed a value instead of an environment.
 *
 * Every derivation the roster performs lives here as a plain function rather
 * than inside a composable, because each one is a rule a caregiver would notice
 * breaking (the wrong greeting, a missing plural, a red card for a routine
 * event) and none of them needs a composition to be checked.
 */

/**
 * Roster (Home tab) state. iOS: `RosterView` + the fields `PatientRow` reads
 * off the environment.
 *
 * @param patients `env.api.patients`, in server order. Empty means the empty
 *   state — see [EmptyRosterScreen].
 * @param caregiverDisplayName `env.auth.currentCaregiver?.displayName`, null
 *   when the caregiver record has not loaded. [rosterFirstName] owns the
 *   fallback.
 * @param eventsByPatient `env.api.eventsByPatient`, keyed by patient id.
 *   **The row derives everything it shows from this**, not from
 *   `patient.state` / `statusLine` / `lastEventAt`, which are not
 *   server-maintained yet (PARITY.md §15).
 * @param isRefreshing a pull-to-refresh `api.refresh()` is in flight. iOS gets
 *   this for free from `.refreshable`'s suspending closure; Compose's
 *   `PullToRefreshBox` wants the flag, so it is hoisted with everything else.
 */
data class RosterState(
    val patients: List<Patient> = emptyList(),
    val caregiverDisplayName: String? = null,
    val eventsByPatient: Map<String, List<DetectionEvent>> = emptyMap(),
    val isRefreshing: Boolean = false,
)

/**
 * Shown when the caregiver record has no usable name.
 *
 * Carried verbatim from `RosterView.swift:87` and `EmptyRosterView.swift:55`,
 * where both greetings end in `?? "Jane"`. It is a placeholder, not a default
 * account name, and it is user-facing copy — so it is pinned by test.
 */
internal const val ROSTER_FALLBACK_FIRST_NAME = "Jane"

/**
 * First name for the greeting and the header avatar.
 *
 * Swift: `displayName.split(separator: " ").first.map(String.init) ?? "Jane"`.
 *
 * `split(separator:)` **omits empty subsequences by default**, so " Jane Doe"
 * yields "Jane" and "" yields nothing at all. Kotlin's `split(" ")` keeps the
 * empties, which would hand back "" for a leading space and render "Welcome, ".
 * Taking the first non-empty run reproduces the Swift for every input.
 */
fun rosterFirstName(displayName: String?): String {
    val first = displayName?.split(" ")?.firstOrNull { it.isNotEmpty() }
    return if (first.isNullOrEmpty()) ROSTER_FALLBACK_FIRST_NAME else first
}

/**
 * Time-of-day greeting. Swift: `Calendar.current.component(.hour, from: Date())`
 * switched over `5..<12`, `12..<17`, `default`.
 *
 * Note the shape of the ranges: **the small hours fall through to "Good
 * evening"**, so 2am greets the caregiver the same way 9pm does. That is
 * deliberate on iOS and is not a gap to fill with "Good night".
 */
fun rosterGreeting(hourOfDay: Int): String = when (hourOfDay) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * The unopened events for one patient. Swift:
 * `env.api.eventsByPatient[patient.id]?.filter { !$0.opened } ?? []`.
 */
internal fun unopenedEvents(events: List<DetectionEvent>?): List<DetectionEvent> =
    events?.filter { !it.opened } ?: emptyList()

/**
 * The prominent status line on a roster row.
 *
 * Swift: `"\(unopenedCount) New event" + (unopenedCount == 1 ? "" : "s")`.
 * Built by concatenation rather than by a plural rule so the copy stays exactly
 * what iOS renders — "1 New event", "2 New events", capital N and all.
 */
internal fun patientRowStatusText(unopenedCount: Int): String {
    if (unopenedCount == 0) return "All quiet"
    return "$unopenedCount New event" + (if (unopenedCount == 1) "" else "s")
}

/**
 * Whether the row escalates to the terra attention treatment.
 *
 * **Only an unopened SAFETY event does.** Routine (sky) events still show the
 * NEW count but never paint the card red — see `PatientRow.swift:30-32` and
 * PARITY.md §15. Widening this to "any unopened event" would turn every
 * medication reminder into an emergency.
 */
internal fun patientRowHasUnopenedSafety(
    unopened: List<DetectionEvent>,
    isPaused: Boolean,
): Boolean = !isPaused && unopened.any { it.category == Detector.Category.SAFETY }
