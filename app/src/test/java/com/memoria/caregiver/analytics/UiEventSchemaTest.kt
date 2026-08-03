package com.memoria.caregiver.analytics

import com.memoria.caregiver.data.remote.NemaraJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact shape of what leaves the device.
 *
 * This app watches people with dementia in their own homes, under India's DPDP
 * regime. The one defence against a patient name, an email, a photo URL, an
 * event narrative or a pairing code ending up in the analytics table is that
 * [UiEvent] has nowhere to put one — and that only holds while nobody adds a
 * field. So the field list is asserted rather than assumed: adding a property to
 * [UiEvent] fails this test and has to be argued for.
 *
 * The names are also the `ui_events` column names. A mismatch is a rejected
 * insert, not a silently dropped column, so this doubles as the contract with
 * `nemara-backend/supabase/schema.sql`.
 */
class UiEventSchemaTest {

    private val event = UiEvent(
        id = "5a1f6f9e-0000-4000-8000-000000000001",
        caregiverId = "11111111-1111-4111-8111-111111111111",
        sessionId = "22222222-2222-4222-8222-222222222222",
        seq = 7,
        eventType = "toggle",
        name = "people_blur.toggle",
        screen = "patient.settings",
        occurredAt = "2026-08-03T10:11:12.345Z",
        appBuild = "0.1.0 (1)",
        osVersion = "15",
        deviceModel = "Pixel 8",
        metadata = mapOf("value" to "on"),
    )

    @Test
    fun `an event serialises to exactly the ui_events columns and nothing more`() {
        val keys = Json.parseToJsonElement(NemaraJson.json.encodeToString(event))
            .jsonObject.keys

        assertEquals(
            "adding a field here is a privacy decision — see the class comment",
            setOf(
                "id",
                "caregiver_id",
                "session_id",
                "seq",
                "event_type",
                "name",
                "screen",
                "occurred_at",
                "app_build",
                "os_version",
                "device_model",
                "metadata",
            ),
            keys,
        )
    }

    @Test
    fun `received_at is left to the server`() {
        val keys = Json.parseToJsonElement(NemaraJson.json.encodeToString(event))
            .jsonObject.keys

        assertEquals(
            "the database defaults it, which is what lets a skewed device clock " +
                "be spotted by comparing it against occurred_at",
            false,
            "received_at" in keys,
        )
    }

    @Test
    fun `an event survives a round trip through the on-disk format`() {
        val encoded = NemaraJson.json.encodeToString(listOf(event))
        assertEquals(listOf(event), NemaraJson.json.decodeFromString<List<UiEvent>>(encoded))
    }
}
