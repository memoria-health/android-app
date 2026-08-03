package com.memoria.caregiver.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Produces [EventDetailState] for [EventDetailScreen], and runs the clip-URL
 * poll behind it. Port of `EventDetailView`'s `@State` and its
 * `.task(id: event.id)`.
 *
 * **One ViewModel per opened event.** iOS presents the detail as a sheet and
 * gets a fresh view identity — and so a fresh clip state and a fresh player —
 * for each event; the equivalent here is a ViewModel scoped to the detail
 * destination, which is also what makes [viewModelScope] the right lifetime for
 * the poll below.
 *
 * The event is passed in **and** looked up in the cache on every emission. Both
 * halves are needed:
 *  - the lookup is what lets a change reach the open detail without plumbing —
 *    an optimistic review write, a `PATCH {opened: true}` response, a Realtime
 *    UPDATE;
 *  - the passed-in copy is the fallback, because a deep-linked event fetched by
 *    id is legitimately not in the loaded page, and this screen has an event to
 *    draw either way. Snapshotting the passed-in copy alone would look right on
 *    first paint and then quietly stop updating.
 */
class EventDetailViewModel(
    private val api: NemaraApiClient,
    private val patientId: String,
    /** The row the list (or the deep link) opened. See the class comment. */
    private val event: DetectionEvent,
) : ViewModel() {

    private val _clip = MutableStateFlow<ClipState>(ClipState.Pending)

    val state: StateFlow<EventDetailState> = combine(
        api.eventsByPatientFlow,
        _clip,
    ) { eventsByPatient, clip ->
        EventDetailState(
            event = eventsByPatient[patientId]?.firstOrNull { it.id == event.id } ?: event,
            clip = clip,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly, as elsewhere: the upstreams are in-memory StateFlows, and a
        // detail screen that showed its initial value again after a
        // configuration change would restart the clip placeholder over a
        // playing video.
        started = SharingStarted.Eagerly,
        initialValue = EventDetailState(event = event, clip = ClipState.Pending),
    )

    init {
        pollClipUrl()
    }

    /**
     * Ask for the clip's signed URL until it resolves. Port of the `.task` on
     * `EventDetailView`.
     *
     * Archival runs roughly 25-45 seconds after the event fires — the 15-second
     * post-buffer, then per-frame annotation, then the upload — so the first ask
     * usually finds nothing. **A 409 is not an error**: the API group already
     * turns it into a null URL, meaning "not ready, ask again", and the
     * placeholder keeps saying "Generating clip…" in the meantime.
     *
     * Three ways to stop, and no fourth:
     *  1. a URL resolved,
     *  2. the request failed for a real reason (a 404 for a deleted event, an
     *     expired session) — one retry loop must not hammer a permanent failure,
     *  3. the screen went away, which cancels [viewModelScope].
     *
     * `clip_suppressed` never starts: there is no clip and there never will be,
     * so the screen shows its final placard rather than a spinner that outlives
     * everyone's patience.
     */
    private fun pollClipUrl() {
        if (event.clipSuppressed) return
        viewModelScope.launch {
            while (isActive) {
                // The event's own `has_clip` flag is the cheap signal that
                // archival has not finished, so the label moves off "Loading" on
                // the first pass rather than after the first round trip.
                if (!currentEvent().hasClip) {
                    _clip.value = ClipState.Generating
                }
                val url = try {
                    api.eventsDirect.clipUrl(event.id)
                } catch (cancellation: CancellationException) {
                    // Not a failure — the screen went away. Rethrowing keeps
                    // the placeholder as it was instead of stamping an error
                    // onto a view nobody is looking at.
                    throw cancellation
                } catch (e: Throwable) {
                    // Verbatim copy, rendered as the placeholder's label.
                    _clip.value = ClipState.Failed(messageFor(e))
                    return@launch
                }
                if (url != null) {
                    _clip.value = ClipState.Loaded(url)
                    return@launch
                }
                _clip.value = ClipState.Generating
                delay(ClipPollInterval)
            }
        }
    }

    /**
     * The freshest copy of this event, for the `has_clip` read above. Straight
     * off the cache rather than off [state], because [state] is a derived flow
     * and this is a plain question about the current row.
     *
     * iOS reads the copy the sheet was presented with, which goes stale the
     * moment archival finishes. Reading the cache instead only affects which
     * placeholder label is shown for a few seconds, and it keeps the label
     * agreeing with the event the screen is actually drawing — which is this
     * same lookup.
     */
    private fun currentEvent(): DetectionEvent =
        api.eventsByPatient[patientId]?.firstOrNull { it.id == event.id } ?: event

    /**
     * Verbatim from [ApiError.errorDescription] — what Swift's
     * `error.localizedDescription` resolves to in the same `catch`. Rewording it
     * would be a silent product change, and this string is shown to a caregiver
     * inside the clip well. The fallback covers a throwable that is not an
     * [ApiError]; iOS's catch is untyped, so it has one implicitly.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Couldn't load the clip."
    }

    private companion object {
        /** iOS: `Task.sleep(for: .seconds(3))`. */
        val ClipPollInterval = 3.seconds
    }
}
