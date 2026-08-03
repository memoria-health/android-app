@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.UpdateEventRequest
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.nav.DeepLinkRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Produces [EventsListState] for [EventsListScreen], and owns everything
 * `PatientEventsView` does around it that touches the network. Port of that
 * view's `@State` plus its two `.task` / `.onChange` blocks.
 *
 * iOS has no object here either: the view reads `env.api.eventsByPatient[pid]`
 * and `env.api.lastReviewError` straight off `@Observable`. [state] is that same
 * read, so a Realtime INSERT that lands in the client's cache appears in the
 * list with nothing else plumbed in between.
 *
 * **The three filter chips filter in memory and never reach the server.** The
 * feed is one page — the newest 30 events — and `loadEventsFirstPage` is always
 * called with the default `all` filter. `EventsUiState.kt` says why the chip
 * enum is deliberately not `data/model/EventFilter`; this is the other half of
 * that: nothing here passes the chip to a request, and there is no paging.
 *
 * One thing that is NOT [EventsListState]: [openEvent]. The state type describes
 * what the list draws, and "which event's detail is presented" is not drawn by
 * the list — it is a navigation target. iOS holds it as a second `@State`
 * (`openedEvent`) for the same reason, and both the row tap and the APNs deep
 * link write that one variable. So do [onEventTap] and the focus handler below.
 */
class EventsListViewModel(
    private val api: NemaraApiClient,
    private val patientId: String,
    /**
     * Read for `focusEventId` and cleared once the deep link has been spent.
     * Taken as the router itself rather than as a flow: it is a plain object
     * with no Android or Supabase dependency, so there is nothing to seam out.
     */
    private val deepLink: DeepLinkRouter,
) : ViewModel() {

    /**
     * iOS `@State private var filter: Filter = .unreviewed`. Held here rather
     * than derived because it is a per-screen choice with no server side to it.
     */
    private val _filter = MutableStateFlow(EventsFilter.Unreviewed)

    /**
     * iOS `api.lastReviewError`, which lives on the shared client only because
     * the review step is a different view that needs to publish into this one.
     * The optimistic submit that writes it is [submitReview], directly below, so
     * on Android it can stay with the screen that renders it.
     */
    private val _reviewError = MutableStateFlow<String?>(null)

    val state: StateFlow<EventsListState> = combine(
        api.eventsByPatientFlow,
        _filter,
        _reviewError,
    ) { eventsByPatient, filter, reviewError ->
        EventsListState(
            // Server order, untouched. The page arrives newest-first and the
            // Realtime handler inserts at index 0 on that assumption; re-sorting
            // here would fight both.
            events = eventsByPatient[patientId].orEmpty(),
            filter = filter,
            reviewError = reviewError,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly, for the reason RosterViewModel gives: every upstream is an
        // in-memory StateFlow, and the default value is not a blank list but the
        // whole "All caught up" empty state.
        started = SharingStarted.Eagerly,
        initialValue = EventsListState(),
    )

    private val _openEvent = MutableStateFlow<DetectionEvent?>(null)

    /**
     * The event whose detail should be presented, or null for none. iOS:
     * `@State private var openedEvent`.
     *
     * The navigation layer collects this and calls [closeEvent] when the detail
     * goes away. That is not bookkeeping: [runFeedPoll] pauses while this is
     * non-null, and leaving it set would stop the feed refreshing for good.
     */
    val openEvent: StateFlow<DetectionEvent?> = _openEvent.asStateFlow()

    init {
        // iOS: `.onChange(of: focusEventId, initial: true)`. Collecting a
        // StateFlow delivers the current value first, which covers the same
        // cold-launch case — the tap that routed before this screen existed.
        //
        // distinctUntilChanged sits BEFORE filterNotNull so that the null we
        // write via clearFocusEvent() is what resets it. Behind the filter,
        // "e1, null, e1" would collapse to a single "e1" and a second
        // notification for the same event would never open.
        viewModelScope.launch {
            deepLink.state
                .map { it.focusEventId }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { openFocusedEvent(it) }
        }
    }

    /** The chips. Client-side only — see the class comment. */
    fun setFilter(filter: EventsFilter) {
        _filter.value = filter
    }

    /**
     * A row tap. `PATCH {opened: true}` first when the event has not been opened
     * before, then present — in that order, and without waiting for the patch.
     *
     * The patch is fire-and-forget on both platforms (`try?` in the Swift). It
     * only drives the card's dimmed/undimmed look; making the caregiver wait for
     * a round trip before the detail opens, or surfacing an error for it, would
     * both be worse than the card staying bright until the next load.
     */
    fun onEventTap(event: DetectionEvent) {
        if (!event.opened) {
            viewModelScope.launch {
                // api.updateEvent patches the cached row from the response, so
                // the card dims without anything else being told.
                runCatchingApi { api.updateEvent(event.id, UpdateEventRequest(opened = true)) }
            }
        }
        _openEvent.value = event
    }

    /** The detail was dismissed. Resumes [runFeedPoll]. */
    fun closeEvent() {
        _openEvent.value = null
    }

    /** The banner's close glyph. iOS: `env.api.lastReviewError = nil`. */
    fun dismissReviewError() {
        _reviewError.value = null
    }

    // -----------------------------------------------------------------------
    // The quiet poll
    // -----------------------------------------------------------------------

    /**
     * Refetch the first page every 20 seconds while the Events tab is on screen.
     * Port of `PatientEventsView`'s `.task(id: patientId)`.
     *
     * **A suspend function rather than something started in `init`**, because
     * the iOS behaviour being ported is scoped to the tab, not to the screen's
     * data: `.task` starts when the tab appears and cancels when it goes away.
     * Launch it from the Events tab's `LaunchedEffect` and cancellation lines up
     * exactly; started in `init` it would keep polling from behind the Live tab.
     *
     * Why it exists at all: Realtime has no replay, so an INSERT missed during a
     * token refresh, a dropped socket or a spell in the background would stay
     * invisible until the app was relaunched. It is quiet — no spinner — and
     * every failure is swallowed, because nobody asked for this refresh.
     *
     * **The pause while a detail is open is load-bearing.** A refetch replaces
     * the cached list, which re-renders the open detail, and that historically
     * killed clip playback mid-watch.
     */
    suspend fun runFeedPoll() {
        while (currentCoroutineContext().isActive) {
            if (_openEvent.value == null) {
                runCatchingApi { api.loadEventsFirstPage(patientId) }
            }
            delay(FeedPollInterval)
        }
    }

    // -----------------------------------------------------------------------
    // The deep link
    // -----------------------------------------------------------------------

    /**
     * Resolve the deep-linked event and present it, then spend the latch exactly
     * once. Port of `openFocusedEvent()`.
     *
     * Cache first, because the common case is a notification tapped while the
     * app is warm and the event is already in the loaded page. On a miss — a
     * cold launch, or an event older than the newest 30 — fall back to the
     * authoritative, caregiver-scoped `GET /v1/events/{id}`.
     *
     * The latch is cleared on every path, including failure. A caregiver who
     * taps a notification for a deleted event is left on the Events tab, which
     * is a dead tap; leaving `focusEventId` set would be worse, because it would
     * re-fire on the next recomposition and could never be cleared.
     */
    private suspend fun openFocusedEvent(eventId: String) {
        val cached = api.eventsByPatient[patientId]?.firstOrNull { it.id == eventId }
        if (cached != null) {
            _openEvent.value = cached
            deepLink.clearFocusEvent()
            return
        }
        // The patient guard is iOS's. The router sets both ids together, but a
        // fetched event that belongs to someone else must not be presented
        // inside this patient's shell.
        val fetched = runCatchingApi { api.eventsDirect.fetchEvent(eventId) }
        if (fetched != null && fetched.patientId == patientId) {
            _openEvent.value = fetched
        }
        deepLink.clearFocusEvent()
    }

    // -----------------------------------------------------------------------
    // The review submit
    // -----------------------------------------------------------------------

    /**
     * Step 2's submit. Port of `EventReviewView.submit()`, which lives here
     * rather than with that screen because everything it touches is this
     * screen's: the patient's cached list, and the banner above the chips.
     *
     * **Optimistic.** The reviewed state is written into the cache and the
     * detail is dismissed immediately, then the PATCH goes out. A failure rolls
     * the row back to the snapshot and publishes the banner, so the close stays
     * snappy without a failure being hidden.
     */
    fun submitReview(
        event: DetectionEvent,
        choice: DetectionEvent.ReviewChoice,
        notes: String?,
    ) {
        // iOS: `notes.isEmpty ? nil : notes`. It does not trim, despite the
        // local being called `trimmedNotes`, and a caregiver who typed only
        // spaces sends them. Matched rather than tidied: the two platforms would
        // otherwise store different values for the same input.
        val submittedNotes = notes?.takeIf { it.isNotEmpty() }
        val original = event

        patchCachedRow(original.id) {
            it.copy(
                opened = true,
                reviewed = true,
                reviewChoice = choice,
                reviewNotes = submittedNotes,
                reviewedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            )
        }

        // iOS's `onSubmitted` closure, which dismisses the whole sheet back to
        // the list rather than popping one level to the detail behind it.
        _openEvent.value = null

        viewModelScope.launch {
            try {
                api.updateEvent(
                    original.id,
                    UpdateEventRequest(
                        opened = true,
                        reviewed = true,
                        reviewChoice = choice,
                        reviewNotes = submittedNotes,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                // Read the map HERE, after the await — never before it. The
                // fetch is a suspension point, and a poll that replaced the list
                // while the PATCH was in flight would otherwise be erased by
                // this rollback (FINDINGS.md F-12).
                patchCachedRow(original.id) { original }
                _reviewError.value =
                    "Couldn't save your review. ${messageFor(e)} Tap the event to try again."
            }
        }
    }

    /**
     * Replace one cached row in place, leaving the list alone when the row is
     * gone. Read-modify-write with **no suspension in between**, which is what
     * makes it safe to call from either side of an await.
     */
    private inline fun patchCachedRow(
        eventId: String,
        transform: (DetectionEvent) -> DetectionEvent,
    ) {
        val arr = api.eventsByPatient[patientId] ?: return
        if (arr.none { it.id == eventId }) return
        api.eventsByPatient = api.eventsByPatient +
            (patientId to arr.map { if (it.id == eventId) transform(it) else it })
    }

    /**
     * Verbatim from [ApiError.errorDescription] — the same sentence Swift's
     * `error.localizedDescription` resolves to. Rewording it here would be a
     * silent product change. The fallback is for a throwable that is not an
     * [ApiError] at all, which nothing on these paths currently throws.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }

    /**
     * Run a call whose failure is not the caregiver's business, returning null
     * when it failed. Cancellation is rethrown rather than swallowed: it is not
     * a failure, and eating it would let a cancelled poll or a cancelled
     * ViewModel keep running.
     */
    private suspend inline fun <T> runCatchingApi(block: () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** iOS: `Task.sleep(nanoseconds: 20_000_000_000)`. */
        val FeedPollInterval = 20.seconds
    }
}
