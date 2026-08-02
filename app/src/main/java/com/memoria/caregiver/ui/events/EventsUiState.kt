package com.memoria.caregiver.ui.events

import com.memoria.caregiver.data.model.DetectionEvent

/**
 * State the three event screens render from, and the derivations that used to
 * live in the SwiftUI views as computed properties.
 *
 * **The screens are pure `@Composable` functions over these types**, exactly as
 * `ui/auth/AuthUiState.kt` establishes: no ViewModel, no I/O, no access to the
 * API client. Everything iOS does inside `PatientEventsView` that touches the
 * network — the `PATCH {opened: true}` before presenting, the 20-second quiet
 * poll, the deep-link `GET /v1/events/{id}` fallback, the clip-URL polling — is
 * the host's job and reaches these screens only as state or as a callback.
 *
 * Spec: `Features/Patient/Events/PatientEventsView.swift`,
 * `EventCard.swift`, `EventDetailView.swift`, and PARITY.md §15.
 */

/**
 * The three filter chips. iOS: `PatientEventsView.Filter`, a
 * `String`-raw-valued enum whose raw value is both the chip label and the
 * `Identifiable` id.
 *
 * Deliberately NOT `data/model/EventFilter`. That enum is the wire value for
 * `GET /v1/patients/{id}/events?filter=`; this one is a client-side view over
 * an already-loaded list, and iOS keeps them separate for the same reason —
 * `PatientEventsView` filters `eventsByPatient` in memory and never sends the
 * chip selection to the server.
 */
enum class EventsFilter(val title: String) {
    All("All"),
    Unreviewed("Unreviewed"),
    Reviewed("Reviewed"),
}

/**
 * Events tab. iOS: `PatientEventsView`.
 *
 * @param events every loaded event for the patient, newest first — the host's
 *   `eventsByPatient[patientId] ?? []`.
 * @param filter which chip is active. **Defaults to [EventsFilter.Unreviewed]**,
 *   matching `@State private var filter: Filter = .unreviewed`. The work-first
 *   default is the point of the screen; do not "fix" it to All.
 * @param reviewError the sentence from a failed optimistic review submit
 *   (`api.lastReviewError`). Rendered verbatim in a dismissible terra banner.
 */
data class EventsListState(
    val events: List<DetectionEvent> = emptyList(),
    val filter: EventsFilter = EventsFilter.Unreviewed,
    val reviewError: String? = null,
) {
    /**
     * Chip counts. iOS computes all three every render regardless of the active
     * filter, because all three are always on screen.
     */
    val counts: Map<EventsFilter, Int>
        get() = mapOf(
            EventsFilter.All to events.size,
            EventsFilter.Unreviewed to events.count { !it.reviewed },
            EventsFilter.Reviewed to events.count { it.reviewed },
        )

    /** The rows the active filter admits, in the order the host supplied. */
    val visible: List<DetectionEvent>
        get() = when (filter) {
            EventsFilter.All -> events
            EventsFilter.Unreviewed -> events.filter { !it.reviewed }
            EventsFilter.Reviewed -> events.filter { it.reviewed }
        }
}

/**
 * The centered "nothing here" copy. Split out of the composable because it is
 * pure product copy with three branches — the kind of thing a test can pin
 * without rendering anything.
 *
 * @param icon an SF Symbol name, resolved by [EventGlyph].
 */
internal data class EventsEmptyCopy(
    val icon: String,
    val title: String,
    val subtitle: String,
)

/**
 * iOS: `PatientEventsView.emptyCopy`. The three sentences are deliberately
 * different — "all caught up" is not the same statement as "nothing has
 * happened yet" — so they are carried verbatim.
 */
internal fun eventsEmptyCopy(filter: EventsFilter): EventsEmptyCopy = when (filter) {
    EventsFilter.All -> EventsEmptyCopy(
        icon = "tray",
        title = "Nothing to see here yet",
        subtitle = "When a detector notices something, events will show up here.",
    )
    EventsFilter.Unreviewed -> EventsEmptyCopy(
        icon = "checkmark.circle",
        title = "All caught up",
        subtitle = "No events are waiting for your review.",
    )
    EventsFilter.Reviewed -> EventsEmptyCopy(
        icon = "tray",
        title = "Nothing reviewed yet",
        subtitle = "Events you've reviewed will appear here.",
    )
}

/**
 * Where the archived clip has got to. iOS:
 * `EventDetailView.ClipResolutionState`.
 *
 * The host owns the polling loop that walks this forward — archival runs ~25-45s
 * after the event fires, so iOS re-asks `GET /v1/events/{id}/clip-url` every 3
 * seconds until it resolves, and stops on success, on a non-recoverable error,
 * or when the view goes away.
 *
 * [NotReady] is carried because it is one of the five states the spec names,
 * even though the shipped Swift never assigns it — a 409 from the clip-URL call
 * is control flow (PARITY.md §10) and surfaces as a nil URL, which becomes
 * [Generating]. Porting the state without the assignment keeps the label
 * available for whichever layer starts distinguishing the two.
 */
sealed interface ClipState {
    /** Haven't asked yet. */
    data object Pending : ClipState

    /** Asked; the backend has no clip for this event yet. */
    data object Generating : ClipState

    /** Explicitly not-ready. */
    data object NotReady : ClipState

    /** The request failed. [message] is shown verbatim in the placeholder. */
    data class Failed(val message: String) : ClipState

    /** A signed URL resolved. Short-lived — 10 minutes by default. */
    data class Loaded(val url: String) : ClipState
}

/**
 * Event detail, step 1 of 2. iOS: `EventDetailView`.
 *
 * `censoredRevealed` is deliberately **not** here. It is per-open state that
 * must reset to false every single time the sheet opens (dignity-first default),
 * so `EventDetailScreen` holds it in a plain `remember` where it cannot be
 * hoisted into something longer-lived, saved, or persisted. Same reasoning as
 * `confirmPassword` in `AuthUiState.kt`.
 */
data class EventDetailState(
    val event: DetectionEvent,
    val clip: ClipState = ClipState.Pending,
)

/**
 * Label for the clip skeleton, or null when the video itself should render.
 *
 * `clip_suppressed` is checked by the caller before this, because it is
 * terminal: there is no clip and there never will be, so it gets a final
 * statement rather than a label that reads like a spinner.
 */
internal fun clipPlaceholderLabel(clip: ClipState): String? = when (clip) {
    ClipState.Pending -> "Loading clip…"
    ClipState.Generating -> "Generating clip…"
    ClipState.NotReady -> "Clip processing…"
    is ClipState.Failed -> clip.message
    is ClipState.Loaded -> null
}
