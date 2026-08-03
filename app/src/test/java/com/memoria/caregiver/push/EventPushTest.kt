package com.memoria.caregiver.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a routable target out of a push payload.
 *
 * Pure: no Firebase, no Android, no device. The whole reason the parse lives in
 * its own function is that `MemoriaMessagingService` cannot be instantiated in
 * this suite, so everything worth testing has to be somewhere else.
 *
 * The shape of the payload is fixed by the backend
 * (`nemara-backend/src/services/push.py:build_event_payload`): both ids sit at
 * the root, beside `aps`, which is the same position FCM's data map occupies.
 */
class EventPushTest {

    private val event = "3f1b8b1e-2b7c-4c2e-9c1a-6f0f2d4a8b31"
    private val patient = "9a2c7d40-1f3e-4b55-8a6d-0c5e7b21d904"

    @Test
    fun `both ids are read from the payload root`() {
        val push = eventPushFrom(
            mapOf("event_id" to event, "patient_id" to patient),
            title = "Fall detected",
            body = "In the kitchen",
        )

        assertEquals(EventTap(eventId = event, patientId = patient), push?.tap)
    }

    @Test
    fun `the keys are the ones the backend actually sends`() {
        // Not eventId / patientId. The payload is snake_case because it is
        // built by Python and read by both platforms.
        assertEquals("event_id", PUSH_KEY_EVENT_ID)
        assertEquals("patient_id", PUSH_KEY_PATIENT_ID)
    }

    @Test
    fun `server text is carried through untouched`() {
        val push = eventPushFrom(
            mapOf("event_id" to event, "patient_id" to patient),
            title = "Fall detected",
            body = "In the kitchen",
        )

        assertEquals("Fall detected", push?.title)
        assertEquals("In the kitchen", push?.body)
    }

    @Test
    fun `a data-only message carries its text in the data map`() {
        // FCM splits messages two ways: a `notification` block the system can
        // draw itself, and a data map only the app sees. A payload that puts the
        // text in the data map has to work too, because that is the shape the
        // backend will need if it ever wants the app to decide how an alert
        // looks.
        val push = eventPushFrom(
            mapOf(
                "event_id" to event,
                "patient_id" to patient,
                "title" to "From the data map",
                "body" to "Also from the data map",
            ),
        )

        assertEquals("From the data map", push?.title)
        assertEquals("Also from the data map", push?.body)
    }

    @Test
    fun `the notification block wins over the data map`() {
        val push = eventPushFrom(
            mapOf("event_id" to event, "patient_id" to patient, "title" to "data"),
            title = "notification",
        )

        assertEquals("notification", push?.title)
    }

    @Test
    fun `blank text is treated as absent`() {
        // A notification whose title row is an empty string looks like a broken
        // app rather than a quiet one. Absent is a state the caller has copy
        // for; blank is not.
        val push = eventPushFrom(
            mapOf("event_id" to event, "patient_id" to patient),
            title = "   ",
            body = "",
        )

        assertNull(push?.title)
        assertNull(push?.body)
    }

    @Test
    fun `a missing event id makes the push unroutable`() {
        assertNull(eventPushFrom(mapOf("patient_id" to patient)))
    }

    @Test
    fun `a missing patient id makes the push unroutable`() {
        // Half a target is not a target. Opening the patient with no event would
        // take the caregiver somewhere they did not ask to go, and iOS drops the
        // same case.
        assertNull(eventPushFrom(mapOf("event_id" to event)))
    }

    @Test
    fun `an empty payload is unroutable`() {
        assertNull(eventPushFrom(emptyMap()))
    }

    @Test
    fun `a malformed id is rejected rather than repaired`() {
        // This is the case java.util.UUID.fromString gets wrong: it accepts
        // "1-2-3-4-5" and zero-pads it into a real-looking UUID, which would
        // match no row and land the caregiver on an empty screen with nothing
        // logged anywhere.
        assertNull(eventPushFrom(mapOf("event_id" to "1-2-3-4-5", "patient_id" to patient)))
        assertNull(eventPushFrom(mapOf("event_id" to event, "patient_id" to "not-a-uuid")))
    }

    @Test
    fun `an id with the right shape and wrong characters is rejected`() {
        val notHex = "zzzzzzzz-2b7c-4c2e-9c1a-6f0f2d4a8b31"

        assertNull(eventPushFrom(mapOf("event_id" to notHex, "patient_id" to patient)))
    }

    @Test
    fun `uppercase ids are accepted`() {
        // Postgres returns lowercase, but nothing in the contract promises it,
        // and rejecting an id purely for its case would drop a valid alert.
        val upper = event.uppercase()

        assertEquals(upper, eventPushFrom(mapOf("event_id" to upper, "patient_id" to patient))?.tap?.eventId)
    }
}
