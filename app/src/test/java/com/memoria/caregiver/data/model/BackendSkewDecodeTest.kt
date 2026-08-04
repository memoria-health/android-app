@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.memoria.caregiver.data.model

import com.memoria.caregiver.data.remote.NemaraJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding against a backend that is **older than this app build**.
 *
 * ## Why this is its own file
 *
 * The failure this guards is not "one screen looks wrong". `bootstrap()` loads
 * patients and detectors, and `bootstrap()` runs inside sign-in — so a single
 * column the server does not send takes **login** down, for every user, with a
 * decode error and no way forward. iOS shipped that twice and fixed it twice
 * (`0d68af6`, `954dd14`); Android had the same defect and nothing tested for it.
 *
 * Every payload below is a *real* older shape: the required columns present, the
 * post-launch ones simply absent, exactly as a server that predates them
 * responds. The rule the app follows, carried from iOS: **columns that have
 * existed since launch stay required** — without them the payload is not a
 * patient at all — and every column added since falls back to its pre-column
 * default.
 */
class BackendSkewDecodeTest {

    private val json = NemaraJson.json

    /**
     * A patient as a pre-`cameras_paused` backend sends it: no `cameras_paused`,
     * no email-alert columns, no data-collection columns.
     */
    private val olderPatient = """
        {
          "id": "11111111-1111-1111-1111-111111111111",
          "caregiver_id": "22222222-2222-2222-2222-222222222222",
          "full_name": "Margaret Fields",
          "preferred_name": "Maggie",
          "relation": "parent",
          "state": "calm",
          "status_line": "All quiet",
          "created_at": "2026-01-01T00:00:00Z",
          "updated_at": "2026-01-01T00:00:00Z"
        }
    """.trimIndent()

    /** A detector from a backend without `alert_trigger` / `is_sustained`. */
    private val olderDetector = """
        {
          "id": "33333333-3333-3333-3333-333333333333",
          "patient_id": "11111111-1111-1111-1111-111111111111",
          "name": "Possible fall",
          "icon_name": "figure.fall",
          "description": "Someone may have fallen.",
          "category": "safety",
          "source": "preset",
          "when_scope": "always",
          "sensitivity": "standard",
          "is_on": true,
          "created_at": "2026-01-01T00:00:00Z",
          "updated_at": "2026-01-01T00:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `a patient from an older backend decodes rather than killing sign-in`() {
        val patient = json.decodeFromString(Patient.serializer(), olderPatient)
        assertEquals("Maggie", patient.preferredName)
    }

    @Test
    fun `absent cameras_paused means monitoring is live, not paused`() {
        val patient = json.decodeFromString(Patient.serializer(), olderPatient)
        // The pre-column behaviour. Defaulting the other way would show every
        // camera as paused on a backend that has no such concept.
        assertFalse(patient.camerasPaused)
    }

    @Test
    fun `absent data_collection_enabled is NOT consent`() {
        val patient = json.decodeFromString(Patient.serializer(), olderPatient)
        // The one default here that is a privacy decision rather than a
        // convenience: a missing column must never read as "they agreed".
        assertFalse(patient.dataCollectionEnabled)
    }

    @Test
    fun `absent email alert columns mean no fan-out and nobody copied`() {
        val patient = json.decodeFromString(Patient.serializer(), olderPatient)
        assertFalse(patient.emailAlertsEnabled)
        assertTrue(patient.emailAlertRecipients.isEmpty())
    }

    @Test
    fun `a detector from an older backend decodes rather than killing sign-in`() {
        val detector = json.decodeFromString(Detector.serializer(), olderDetector)
        assertEquals("Possible fall", detector.name)
    }

    @Test
    fun `absent alert_trigger is the leading edge and not sustained`() {
        val detector = json.decodeFromString(Detector.serializer(), olderDetector)
        assertEquals(Detector.AlertTrigger.LEADING, detector.alertTrigger)
        assertFalse(detector.isSustained)
    }

    @Test
    fun `columns that predate launch are still required`() {
        // The other half of the rule, and the reason this is not just "make
        // everything optional". A payload with no preferred_name is not a
        // patient, and silently defaulting it would put an empty name on a
        // card instead of surfacing a broken response.
        val noName = olderPatient.replace("\"preferred_name\": \"Maggie\",\n  ", "")
        var threw = false
        try {
            json.decodeFromString(Patient.serializer(), noName)
        } catch (expected: Exception) {
            threw = true
        }
        assertTrue("a patient with no preferred_name decoded anyway", threw)
    }

    @Test
    fun `a full modern payload still decodes with its real values`() {
        // Guards the direction nobody checks: making a field optional must not
        // make it ignored.
        val modern = olderPatient.dropLast(1).trimEnd() + """
            ,
              "cameras_paused": true,
              "email_alerts_enabled": true,
              "email_alert_recipients": ["carer@example.test"],
              "data_collection_enabled": true
            }
        """.trimIndent()
        val patient = json.decodeFromString(Patient.serializer(), modern)
        assertTrue(patient.camerasPaused)
        assertTrue(patient.emailAlertsEnabled)
        assertEquals(listOf("carer@example.test"), patient.emailAlertRecipients)
        assertTrue(patient.dataCollectionEnabled)
    }
}
