package com.memoria.caregiver.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for "which patient shell is open" and "which event (if
 * any) to deep-link into". Port of `App/DeepLinkRouter.swift`.
 *
 * One field pair drives every way a patient screen can be reached: a roster tap,
 * a settings-row tap, and a notification tap. iOS made that a hard rule because
 * two independent presenters fought over one full-screen cover; the same rule
 * holds here, so the navigation host reads [state] and nothing else opens a
 * patient.
 *
 * **Why durable state rather than a one-shot event.** A notification tap happens
 * at a moment when nothing can act on it yet: the app may still be signed out,
 * or signed in but not bootstrapped, or the navigation graph may not be composed.
 * So the tap *writes* a target here and every navigation layer *moves toward*
 * that target once it is able to. The target survives the gaps; an event would
 * not. This is the reason a tap on a notification works from a cold start.
 *
 * **What is deliberately NOT ported (DIFFERENCES.md D1).** iOS's `route()` clears
 * `openPatientId`, waits one run-loop tick, then sets the new id, purely because
 * SwiftUI's `fullScreenCover(item:)` cannot swap to a different id in one tick.
 * Navigation Compose can, so this navigates straight across. Only that
 * workaround is dropped; the model above is ported whole.
 */
class DeepLinkRouter {

    /**
     * The whole routing target, as ONE value.
     *
     * The two ids are held together rather than in two flows on purpose. Dropping
     * the two-tick dance (D1) means a switch from patient A to patient B now lands
     * in a single write, and readers must never observe the halfway state
     * "patient A is open, focus event belongs to B". If they could, patient A's
     * events screen would try to open B's event, find the patient ids do not
     * match, and clear the focus id anyway — eating the deep link so B's event
     * never opens. One flow makes that state unrepresentable rather than
     * unlikely.
     *
     * SwiftUI's `@Observable` gets the same property for free by batching both
     * writes into one run-loop tick; two `StateFlow`s would not.
     */
    data class DeepLinkState(
        /** Which patient shell is open. `null` = none. */
        val openPatientId: String? = null,
        /**
         * Set together with [openPatientId] when the target is a specific event.
         * The patient shell jumps to its Events tab, the events screen opens this
         * event (fetching it by id when it is not already cached) and then calls
         * [clearFocusEvent]. Read once, then cleared.
         */
        val focusEventId: String? = null,
    )

    private val _state = MutableStateFlow(DeepLinkState())

    /** What the navigation layers move toward. */
    val state: StateFlow<DeepLinkState> = _state.asStateFlow()

    /**
     * A notification tap that has been recorded but not yet turned into a route.
     *
     * Held for the same reason iOS holds it on the app delegate: the code that
     * receives the tap must be able to record it at any time, from any thread,
     * without knowing whether anything is ready to navigate. [drainPendingTap]
     * takes it out again exactly once.
     */
    private val pendingEventTap = AtomicReference<PendingEventTap?>(null)

    /**
     * A recorded notification tap. Both ids come from the payload root that the
     * backend builds (`src/services/push.py:build_event_payload`).
     */
    data class PendingEventTap(val eventId: String, val patientId: String)

    /** Manual navigation (roster row / settings row tap). No event focus. */
    fun openPatient(id: String) = route(patientId = id, focusEventId = null)

    /**
     * The patient shell was closed. Clears only [DeepLinkState.openPatientId],
     * matching iOS, where dismissing the cover writes back through a binding that
     * touches that one field.
     */
    fun closePatient() {
        _state.update { it.copy(openPatientId = null) }
    }

    /**
     * The focused event has been dealt with: either its sheet is now open, or it
     * could not be resolved. Either way the deep link is spent, so it is cleared
     * and cannot re-open the sheet on the next recomposition.
     */
    fun clearFocusEvent() {
        _state.update { it.copy(focusEventId = null) }
    }

    /**
     * Record a notification tap, then route it immediately.
     *
     * Safe to call from any thread, which matters because the tap arrives on
     * whatever thread the push layer runs on.
     *
     * iOS does the same two steps through a callback (`pendingEventTap = tap`
     * followed by `onEventTap?()`, which is wired to [drainPendingTap]) only
     * because its app delegate cannot see the router. Here it is a direct call.
     * The recording step is still real: [drainPendingTap] is what consumes it,
     * and a second caller draining first simply finds nothing left.
     */
    fun bufferEventTap(eventId: String, patientId: String) {
        pendingEventTap.set(PendingEventTap(eventId = eventId, patientId = patientId))
        drainPendingTap()
    }

    /**
     * Turn a recorded notification tap into a route.
     *
     * Called again by the signed-in surface when it appears, which covers the tap
     * that arrived before that surface existed and the tap that arrived before the
     * caregiver signed in. Take-and-clear, so calling it twice cannot navigate
     * twice and calling it with nothing recorded does nothing.
     */
    fun drainPendingTap() {
        val tap = pendingEventTap.getAndSet(null) ?: return
        route(patientId = tap.patientId, focusEventId = tap.eventId)
    }

    /**
     * The one write. Both ids land together, including the `null` focus id of a
     * manual open, which is what stops a stale focused event from re-opening when
     * the caregiver later taps the same patient from the roster.
     */
    private fun route(patientId: String, focusEventId: String?) {
        _state.value = DeepLinkState(openPatientId = patientId, focusEventId = focusEventId)
    }
}
