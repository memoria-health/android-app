package com.memoria.caregiver.ui.nav

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests: the router touches no Android API and no Compose, so there is
 * nothing here that needs Robolectric.
 */
class DeepLinkRouterTest {

    private val patientA = "11111111-1111-1111-1111-111111111111"
    private val patientB = "22222222-2222-2222-2222-222222222222"
    private val eventB = "33333333-3333-3333-3333-333333333333"

    @Test
    fun `opening a patient manually carries no focused event`() {
        val router = DeepLinkRouter()

        router.openPatient(patientA)

        assertEquals(patientA, router.state.value.openPatientId)
        assertNull(router.state.value.focusEventId)
    }

    @Test
    fun `opening a patient manually drops a focused event that was never consumed`() {
        val router = DeepLinkRouter()
        router.bufferEventTap(eventId = eventB, patientId = patientB)

        router.openPatient(patientB)

        // Otherwise the next roster tap on this patient would re-open the event
        // sheet of a notification the caregiver already walked away from.
        assertNull(router.state.value.focusEventId)
    }

    @Test
    fun `a notification tap targets the patient and the event together`() {
        val router = DeepLinkRouter()

        router.bufferEventTap(eventId = eventB, patientId = patientB)

        assertEquals(
            DeepLinkRouter.DeepLinkState(openPatientId = patientB, focusEventId = eventB),
            router.state.value,
        )
    }

    @Test
    fun `a notification tap is consumed exactly once`() {
        val router = DeepLinkRouter()
        router.bufferEventTap(eventId = eventB, patientId = patientB)
        router.closePatient()

        // The signed-in surface drains again when it appears. The tap is spent,
        // so this must not re-open the patient the caregiver just backed out of.
        router.drainPendingTap()

        assertNull(router.state.value.openPatientId)
    }

    @Test
    fun `draining with no recorded tap leaves the current target alone`() {
        val router = DeepLinkRouter()
        router.openPatient(patientA)

        router.drainPendingTap()

        assertEquals(patientA, router.state.value.openPatientId)
    }

    @Test
    fun `closing the patient shell does not clear the focused event`() {
        val router = DeepLinkRouter()
        router.bufferEventTap(eventId = eventB, patientId = patientB)

        router.closePatient()

        // Matches iOS, where dismissing the cover writes back through a binding
        // that touches openPatientId only.
        assertNull(router.state.value.openPatientId)
        assertEquals(eventB, router.state.value.focusEventId)
    }

    @Test
    fun `clearing the focused event keeps the patient shell open`() {
        val router = DeepLinkRouter()
        router.bufferEventTap(eventId = eventB, patientId = patientB)

        router.clearFocusEvent()

        assertEquals(patientB, router.state.value.openPatientId)
        assertNull(router.state.value.focusEventId)
    }

    @Test
    fun `switching patients never exposes the old patient with the new event`() = runTest {
        val router = DeepLinkRouter()
        router.openPatient(patientA)
        val seen = mutableListOf<DeepLinkRouter.DeepLinkState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            router.state.toList(seen)
        }

        router.bufferEventTap(eventId = eventB, patientId = patientB)
        collector.cancel()

        // If the two ids ever lived in separate flows, this halfway state would
        // be observable and patient A's events screen would consume (and throw
        // away) patient B's deep link.
        assertTrue(
            "saw a halfway state: $seen",
            seen.none { it.openPatientId == patientA && it.focusEventId != null },
        )
        assertEquals(
            DeepLinkRouter.DeepLinkState(openPatientId = patientB, focusEventId = eventB),
            seen.last(),
        )
    }
}
