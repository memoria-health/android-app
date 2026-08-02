@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.model

import kotlinx.serialization.SerializationException
import com.memoria.caregiver.data.remote.NemaraJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Wire-contract tests for `PatientModels.kt`.
 *
 * Every payload here is a JSON **string literal** shaped like what the backend
 * actually sends — pydantic v2 `snake_case`, 6-digit microsecond timestamps,
 * `YYYY-MM-DD` dates — rather than a round-trip of this file's own encoder,
 * which would prove nothing about the wire format.
 *
 * Two shapes matter and both are exercised: the REST API schema, and the raw DB
 * row Supabase Realtime ships, which carries extra columns and omits every
 * nullable one that happens to be NULL.
 *
 * Serializers are passed explicitly (`Patient.serializer()`) rather than via the
 * reified `decodeFromString<T>()` extension, so the tests do not depend on which
 * kotlinx-serialization release moved those helpers between package-level
 * extensions and members.
 */
class PatientModelsTest {

    /**
     * Mirrors the app-wide decode configuration from `PARITY.md §7`. Lenient on
     * unknown keys because Realtime ships raw DB rows with columns the API
     * schema does not expose.
     */
    // The SHIPPING configuration, not a local one.
    //
    // HARDENING.md H-5: these tests used to build their own Json, so they never
    // exercised the config the app actually uses — a change to
    // ignoreUnknownKeys, explicitNulls or encodeDefaults would have broken the
    // app and left the suite green. PatientModelsTest's local copy was missing
    // two of the three settings outright, so its encode assertions described a
    // wire format that never shipped.
    private val json = NemaraJson.json

    // -----------------------------------------------------------------------
    // Payloads
    // -----------------------------------------------------------------------

    /** A complete `GET /v1/patients` row, exactly as FastAPI serializes it. */
    private val fullPatientJson = """
        {
          "id": "8f4a6e2c-1c3b-4a0e-9a0f-2f7c1d5b6e11",
          "caregiver_id": "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9",
          "full_name": "Margaret Holloway",
          "preferred_name": "Peggy",
          "date_of_birth": "1948-03-02",
          "gender": "female",
          "relation": "parent",
          "home_address": "14 Rowan Lane, Berkeley, CA",
          "country": "US",
          "region": "US",
          "state": "calm",
          "status_line": "All quiet",
          "last_event_at": "2026-05-21T22:18:00.182734+00:00",
          "cameras_paused": false,
          "audio_capture_enabled": true,
          "email_alerts_enabled": true,
          "email_alert_recipients": ["sunny@memoria.care", "ary@memoria.care"],
          "data_collection_enabled": false,
          "data_collection_decided_at": "2026-07-27T09:04:11.500000+00:00",
          "privacy_mode_enabled": false,
          "photo_storage_key": "8f4a6e2c-1c3b-4a0e-9a0f-2f7c1d5b6e11.jpg",
          "created_at": "2026-01-04T11:00:00.000000+00:00",
          "updated_at": "2026-07-27T09:04:11.500000+00:00"
        }
    """.trimIndent()

    /**
     * The same row, field by field, so a test can drop or null exactly one key
     * and prove tolerance for that key alone. Values are raw JSON literals, so
     * the assembled document is real JSON.
     */
    private val patientFields: Map<String, String> = linkedMapOf(
        "id" to "\"8f4a6e2c-1c3b-4a0e-9a0f-2f7c1d5b6e11\"",
        "caregiver_id" to "\"1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9\"",
        "full_name" to "\"Margaret Holloway\"",
        "preferred_name" to "\"Peggy\"",
        "date_of_birth" to "\"1948-03-02\"",
        "gender" to "\"female\"",
        "relation" to "\"parent\"",
        "home_address" to "\"14 Rowan Lane\"",
        "country" to "\"US\"",
        "region" to "\"US\"",
        "state" to "\"calm\"",
        "status_line" to "\"All quiet\"",
        "last_event_at" to "\"2026-05-21T22:18:00.182734+00:00\"",
        "cameras_paused" to "false",
        "audio_capture_enabled" to "true",
        "email_alerts_enabled" to "true",
        "email_alert_recipients" to "[\"sunny@memoria.care\"]",
        "data_collection_enabled" to "false",
        "data_collection_decided_at" to "\"2026-07-27T09:04:11.500000+00:00\"",
        "privacy_mode_enabled" to "false",
        "photo_storage_key" to "\"avatar.jpg\"",
        "created_at" to "\"2026-01-04T11:00:00.000000+00:00\"",
        "updated_at" to "\"2026-07-27T09:04:11.500000+00:00\"",
    )

    private val caregiverFields: Map<String, String> = linkedMapOf(
        "id" to "\"1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9\"",
        "display_name" to "\"Aryaman Darda\"",
        "email" to "\"aryaman@memoria.care\"",
        "phone" to "\"+15105550143\"",
        "created_at" to "\"2026-01-02T18:30:00.000000+00:00\"",
        "updated_at" to "\"2026-07-26T15:12:44.913201+00:00\"",
        "tos_accepted_at" to "\"2026-07-26T15:12:44.913201+00:00\"",
        "tos_version" to "\"2026-07-26-placeholder\"",
        "country" to "\"US\"",
        "region" to "\"US\"",
    )

    private fun document(
        fields: Map<String, String>,
        omit: Set<String>,
        nullify: Set<String>,
    ): String =
        fields.entries
            .filterNot { it.key in omit }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                "\"$key\":" + if (key in nullify) "null" else value
            }

    private fun patientJson(omit: Set<String> = emptySet(), nullify: Set<String> = emptySet()): String =
        document(patientFields, omit, nullify)

    private fun decodePatient(omit: Set<String> = emptySet(), nullify: Set<String> = emptySet()): Patient =
        json.decodeFromString(Patient.serializer(), patientJson(omit, nullify))

    private fun decodeCaregiver(omit: Set<String> = emptySet(), nullify: Set<String> = emptySet()): Caregiver =
        json.decodeFromString(Caregiver.serializer(), document(caregiverFields, omit, nullify))

    /** Builds the expected instant through java.time, never through the code under test. */
    private fun instant(iso: String): Instant {
        val parsed = java.time.Instant.parse(iso)
        return Instant.fromEpochSeconds(parsed.epochSecond, parsed.nano.toLong())
    }

    // -----------------------------------------------------------------------
    // Full REST decode
    // -----------------------------------------------------------------------

    @Test
    fun `full REST patient payload decodes every field`() {
        val patient = json.decodeFromString(Patient.serializer(), fullPatientJson)

        assertEquals("8f4a6e2c-1c3b-4a0e-9a0f-2f7c1d5b6e11", patient.id)
        assertEquals("1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9", patient.caregiverId)
        assertEquals("Margaret Holloway", patient.fullName)
        assertEquals("Peggy", patient.preferredName)
        assertEquals(instant("1948-03-02T00:00:00Z"), patient.dateOfBirth)
        assertEquals("female", patient.gender)
        assertEquals(Patient.Relation.PARENT, patient.relation)
        assertEquals("14 Rowan Lane, Berkeley, CA", patient.homeAddress)
        assertEquals("US", patient.country)
        assertEquals(Patient.Region.US, patient.region)
        assertEquals(Patient.State.CALM, patient.state)
        assertEquals("All quiet", patient.statusLine)
        assertEquals(instant("2026-05-21T22:18:00.182Z"), patient.lastEventAt)
        assertEquals(false, patient.camerasPaused)
        assertEquals(true, patient.audioCaptureEnabled)
        assertEquals(true, patient.emailAlertsEnabled)
        assertEquals(listOf("sunny@memoria.care", "ary@memoria.care"), patient.emailAlertRecipients)
        assertEquals(false, patient.dataCollectionEnabled)
        assertEquals(instant("2026-07-27T09:04:11.500000Z"), patient.dataCollectionDecidedAt)
        assertEquals(false, patient.privacyModeEnabled)
        assertEquals("8f4a6e2c-1c3b-4a0e-9a0f-2f7c1d5b6e11.jpg", patient.photoStorageKey)
        assertEquals(instant("2026-01-04T11:00:00Z"), patient.createdAt)
        assertEquals(instant("2026-07-27T09:04:11.500000Z"), patient.updatedAt)
    }

    @Test
    fun `full REST caregiver payload decodes every field`() {
        val caregiver = json.decodeFromString(
            Caregiver.serializer(),
            """
            {
              "id": "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9",
              "display_name": "Aryaman Darda",
              "email": "aryaman@memoria.care",
              "phone": "+15105550143",
              "created_at": "2026-01-02T18:30:00.000000+00:00",
              "updated_at": "2026-07-26T15:12:44.913201+00:00",
              "tos_accepted_at": "2026-07-26T15:12:44.913201+00:00",
              "tos_version": "2026-07-26-placeholder",
              "country": "US",
              "region": "US"
            }
            """.trimIndent(),
        )

        assertEquals("1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9", caregiver.id)
        assertEquals("Aryaman Darda", caregiver.displayName)
        assertEquals("aryaman@memoria.care", caregiver.email)
        assertEquals("+15105550143", caregiver.phone)
        assertEquals(instant("2026-01-02T18:30:00Z"), caregiver.createdAt)
        assertEquals(instant("2026-07-26T15:12:44.913Z"), caregiver.updatedAt)
        assertEquals(instant("2026-07-26T15:12:44.913Z"), caregiver.tosAcceptedAt)
        assertEquals("2026-07-26-placeholder", caregiver.tosVersion)
        assertEquals("US", caregiver.country)
        assertEquals("US", caregiver.region)
    }

    // -----------------------------------------------------------------------
    // Lenient decode — Patient. One test per property iOS declares Optional,
    // each proving BOTH an absent key and an explicit null are tolerated.
    // -----------------------------------------------------------------------

    @Test
    fun `patient tolerates absent and null date_of_birth`() {
        assertNull(decodePatient(omit = setOf("date_of_birth")).dateOfBirth)
        assertNull(decodePatient(nullify = setOf("date_of_birth")).dateOfBirth)
    }

    @Test
    fun `patient tolerates absent and null gender`() {
        assertNull(decodePatient(omit = setOf("gender")).gender)
        assertNull(decodePatient(nullify = setOf("gender")).gender)
    }

    @Test
    fun `patient tolerates absent and null home_address`() {
        assertNull(decodePatient(omit = setOf("home_address")).homeAddress)
        assertNull(decodePatient(nullify = setOf("home_address")).homeAddress)
    }

    @Test
    fun `patient tolerates absent and null country`() {
        assertNull(decodePatient(omit = setOf("country")).country)
        assertNull(decodePatient(nullify = setOf("country")).country)
    }

    @Test
    fun `patient tolerates absent and null region`() {
        assertNull(decodePatient(omit = setOf("region")).region)
        assertNull(decodePatient(nullify = setOf("region")).region)
    }

    @Test
    fun `patient tolerates absent and null last_event_at`() {
        assertNull(decodePatient(omit = setOf("last_event_at")).lastEventAt)
        assertNull(decodePatient(nullify = setOf("last_event_at")).lastEventAt)
    }

    /**
     * The facilities audio kill. `PARITY.md §7`: optional, and null means
     * **true** at the call site — the model keeps the three-valued column so a
     * caller can tell "explicitly off" from "not sent".
     */
    @Test
    fun `patient tolerates absent and null audio_capture_enabled`() {
        assertNull(decodePatient(omit = setOf("audio_capture_enabled")).audioCaptureEnabled)
        assertNull(decodePatient(nullify = setOf("audio_capture_enabled")).audioCaptureEnabled)
    }

    @Test
    fun `patient tolerates absent and null data_collection_decided_at`() {
        assertNull(decodePatient(omit = setOf("data_collection_decided_at")).dataCollectionDecidedAt)
        assertNull(decodePatient(nullify = setOf("data_collection_decided_at")).dataCollectionDecidedAt)
    }

    /**
     * The retired patient-level privacy flag. The backend is dropping the
     * column; a non-nullable Boolean here would fail **every** patient decode
     * the day it disappears from the payload.
     */
    @Test
    fun `patient tolerates absent and null privacy_mode_enabled`() {
        assertNull(decodePatient(omit = setOf("privacy_mode_enabled")).privacyModeEnabled)
        assertNull(decodePatient(nullify = setOf("privacy_mode_enabled")).privacyModeEnabled)
    }

    @Test
    fun `patient tolerates absent and null photo_storage_key`() {
        assertNull(decodePatient(omit = setOf("photo_storage_key")).photoStorageKey)
        assertNull(decodePatient(nullify = setOf("photo_storage_key")).photoStorageKey)
    }

    /**
     * The Realtime shape: the raw DB row carries columns the API schema never
     * exposes and omits the nullable ones. The same class must parse it.
     */
    @Test
    fun `patient decodes a raw Realtime row with unknown columns and missing optionals`() {
        val patient = json.decodeFromString(
            Patient.serializer(),
            """
            {
              "id": "8f4a6e2c-1c3b-4a0e-9a0f-2f7c1d5b6e11",
              "caregiver_id": "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9",
              "full_name": "Margaret Holloway",
              "preferred_name": "Peggy",
              "relation": "parent",
              "state": "calm",
              "status_line": "All quiet",
              "cameras_paused": false,
              "email_alerts_enabled": true,
              "email_alert_recipients": [],
              "data_collection_enabled": false,
              "created_at": "2026-01-04T11:00:00+00:00",
              "updated_at": "2026-07-27T09:04:11.500+00:00",
              "deleted_at": null,
              "internal_notes": "a row-level column the API schema does not expose",
              "tenant_shard": 4
            }
            """.trimIndent(),
        )

        assertEquals("Peggy", patient.preferredName)
        assertNull(patient.dateOfBirth)
        assertNull(patient.audioCaptureEnabled)
        assertNull(patient.privacyModeEnabled)
        assertEquals(emptyList<String>(), patient.emailAlertRecipients)
        assertEquals(instant("2026-01-04T11:00:00Z"), patient.createdAt)
        assertEquals(instant("2026-07-27T09:04:11.500Z"), patient.updatedAt)
    }

    // -----------------------------------------------------------------------
    // Lenient decode — Caregiver
    // -----------------------------------------------------------------------

    @Test
    fun `caregiver tolerates absent and null phone`() {
        assertNull(decodeCaregiver(omit = setOf("phone")).phone)
        assertNull(decodeCaregiver(nullify = setOf("phone")).phone)
    }

    @Test
    fun `caregiver tolerates absent and null tos_accepted_at`() {
        assertNull(decodeCaregiver(omit = setOf("tos_accepted_at")).tosAcceptedAt)
        assertNull(decodeCaregiver(nullify = setOf("tos_accepted_at")).tosAcceptedAt)
    }

    @Test
    fun `caregiver tolerates absent and null tos_version`() {
        assertNull(decodeCaregiver(omit = setOf("tos_version")).tosVersion)
        assertNull(decodeCaregiver(nullify = setOf("tos_version")).tosVersion)
    }

    @Test
    fun `relation wire values map both ways and cover every case`() {
        val expected = mapOf(
            "parent" to Patient.Relation.PARENT,
            "spouse" to Patient.Relation.SPOUSE,
            "grandparent" to Patient.Relation.GRANDPARENT,
            "sibling" to Patient.Relation.SIBLING,
            "client" to Patient.Relation.CLIENT,
            "other" to Patient.Relation.OTHER,
        )
        for ((wire, value) in expected) {
            assertEquals(value, json.decodeFromString(Patient.Relation.serializer(), "\"$wire\""))
            assertEquals("\"$wire\"", json.encodeToString(Patient.Relation.serializer(), value))
        }
        assertEquals(expected.size, Patient.Relation.entries.size)
    }

    @Test
    fun `relation displayName capitalizes the wire value`() {
        assertEquals("Parent", Patient.Relation.PARENT.displayName)
        assertEquals("Grandparent", Patient.Relation.GRANDPARENT.displayName)
        assertEquals("Other", Patient.Relation.OTHER.displayName)
    }

    @Test
    fun `region wire values are the two-letter codes and not the Kotlin names`() {
        assertEquals(Patient.Region.US, json.decodeFromString(Patient.Region.serializer(), "\"US\""))
        assertEquals(Patient.Region.INDIA, json.decodeFromString(Patient.Region.serializer(), "\"IN\""))
        assertEquals("\"IN\"", json.encodeToString(Patient.Region.serializer(), Patient.Region.INDIA))
        assertEquals("United States", Patient.Region.US.displayName)
        assertEquals("India", Patient.Region.INDIA.displayName)
    }

    @Test
    fun `state decodes the three aggregated statuses`() {
        assertEquals(Patient.State.CALM, json.decodeFromString(Patient.State.serializer(), "\"calm\""))
        assertEquals(Patient.State.CRITICAL, json.decodeFromString(Patient.State.serializer(), "\"critical\""))
        assertEquals(Patient.State.OFFLINE, json.decodeFromString(Patient.State.serializer(), "\"offline\""))
        assertEquals(3, Patient.State.entries.size)
    }

    /**
     * iOS declares these as plain `Codable` enums with no unknown-value
     * fallback, so an unrecognized value fails the decode. `PARITY.md §7` grants
     * the unknown-to-default fallback to `Camera.RoomClass` and
     * `Camera.SourceType` only; inventing one here would diverge.
     */
    @Test
    fun `an unknown enum value fails the decode rather than falling back`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(Patient.Relation.serializer(), "\"neighbour\"")
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(Patient.Region.serializer(), "\"GB\"")
        }
    }

    // -----------------------------------------------------------------------
    // Computed properties
    // -----------------------------------------------------------------------

    @Test
    fun `avatarLetter prefers the preferred name and falls back to the full name`() {
        val patient = decodePatient()
        assertEquals("P", patient.avatarLetter)
        assertEquals("M", patient.copy(preferredName = "").avatarLetter)
        assertEquals("?", patient.copy(preferredName = "", fullName = "").avatarLetter)
    }

    /**
     * Swift's `String.first` is one grapheme cluster. A `Char`-based first would
     * hand back half a surrogate pair in the first case, and would drop the
     * combining accent in the second.
     */
    @Test
    fun `avatarLetter takes a whole grapheme cluster`() {
        val patient = decodePatient()
        assertEquals("👵", patient.copy(preferredName = "👵Grandma").avatarLetter)
        // "e" + COMBINING ACUTE ACCENT (U+0301), written decomposed on
        // purpose: a precomposed U+00E9 would pass even with a Char-based
        // first(), so it would not prove the cluster boundary.
        assertEquals("e\u0301", patient.copy(preferredName = "e\u0301va").avatarLetter)
    }

    @Test
    fun `age is zero when the date of birth is unknown`() {
        assertEquals(0, decodePatient(omit = setOf("date_of_birth")).age)
    }

    @Test
    fun `age counts whole years in the device time zone`() {
        val zone = ZoneId.systemDefault()
        // Two days clear of the birthday, so no zone offset can round the count
        // up or down across the boundary.
        val born = LocalDate.now(zone).minusYears(30).minusDays(2)
        val bornWire = born.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        val patient = json.decodeFromString(
            Patient.serializer(),
            patientJson().replace("\"date_of_birth\":\"1948-03-02\"", "\"date_of_birth\":\"$bornWire\""),
        )

        assertEquals(30, patient.age)
    }

    // -----------------------------------------------------------------------
    // Request encoding. The backend runs extra="forbid" on request schemas, so
    // an unexpected key is a 422 — these assert the exact document.
    // -----------------------------------------------------------------------

    @Test
    fun `CreatePatientRequest sends date_of_birth as a bare date`() {
        val body = Json.encodeToString(
            CreatePatientRequest.serializer(),
            CreatePatientRequest(
                fullName = "Margaret Holloway",
                preferredName = "Peggy",
                relation = Patient.Relation.PARENT,
                dateOfBirth = instant("1948-03-02T00:00:00Z"),
                gender = "female",
                homeAddress = "14 Rowan Lane",
                country = "US",
            ),
        )

        assertEquals(
            "{\"full_name\":\"Margaret Holloway\",\"preferred_name\":\"Peggy\",\"relation\":\"parent\"," +
                "\"date_of_birth\":\"1948-03-02\",\"gender\":\"female\",\"home_address\":\"14 Rowan Lane\"," +
                "\"country\":\"US\"}",
            body,
        )
    }

    @Test
    fun `CreatePatientRequest omits every unset optional even when defaults are encoded`() {
        val request = CreatePatientRequest(
            fullName = "Margaret Holloway",
            preferredName = "Peggy",
            relation = Patient.Relation.PARENT,
            country = "IN",
        )
        val expected = "{\"full_name\":\"Margaret Holloway\",\"preferred_name\":\"Peggy\"," +
            "\"relation\":\"parent\",\"country\":\"IN\"}"

        assertEquals(expected, Json.encodeToString(CreatePatientRequest.serializer(), request))
        // @EncodeDefault(NEVER) holds even against a Json that encodes defaults,
        // so a change to the shared configuration cannot start sending nulls
        // into columns the PATCH would then clear.
        assertEquals(
            expected,
            Json { encodeDefaults = true }.encodeToString(CreatePatientRequest.serializer(), request),
        )
    }

    @Test
    fun `UpdatePatientRequest with nothing set is an empty document`() {
        assertEquals("{}", Json.encodeToString(UpdatePatientRequest.serializer(), UpdatePatientRequest()))
        assertEquals(
            "{}",
            Json { encodeDefaults = true }.encodeToString(UpdatePatientRequest.serializer(), UpdatePatientRequest()),
        )
    }

    @Test
    fun `UpdatePatientRequest sends only the fields that changed`() {
        assertEquals(
            "{\"cameras_paused\":true}",
            Json.encodeToString(UpdatePatientRequest.serializer(), UpdatePatientRequest(camerasPaused = true)),
        )
        assertEquals(
            "{\"email_alerts_enabled\":true,\"email_alert_recipients\":[\"a@b.com\",\"c@d.com\"]}",
            Json.encodeToString(
                UpdatePatientRequest.serializer(),
                UpdatePatientRequest(
                    emailAlertsEnabled = true,
                    emailAlertRecipients = listOf("a@b.com", "c@d.com"),
                ),
            ),
        )
        // false is a value, not an absence: it must be sent.
        assertEquals(
            "{\"data_collection_enabled\":false}",
            Json.encodeToString(
                UpdatePatientRequest.serializer(),
                UpdatePatientRequest(dataCollectionEnabled = false),
            ),
        )
    }

    @Test
    fun `UpdatePatientRequest also sends date_of_birth as a bare date`() {
        assertEquals(
            "{\"date_of_birth\":\"1948-03-02\"}",
            Json.encodeToString(
                UpdatePatientRequest.serializer(),
                UpdatePatientRequest(dateOfBirth = instant("1948-03-02T00:00:00Z")),
            ),
        )
        // A DOB carrying a time of day is still narrowed to the DATE column's
        // shape; pydantic's date validator rejects anything else.
        assertEquals(
            "{\"date_of_birth\":\"1948-03-02\"}",
            Json.encodeToString(
                UpdatePatientRequest.serializer(),
                UpdatePatientRequest(dateOfBirth = instant("1948-03-02T17:45:31.250000Z")),
            ),
        )
    }

    @Test
    fun `CreateCaregiverRequest sends display_name and omits the unset rest`() {
        assertEquals(
            "{\"display_name\":\"Aryaman Darda\"}",
            Json.encodeToString(
                CreateCaregiverRequest.serializer(),
                CreateCaregiverRequest(displayName = "Aryaman Darda"),
            ),
        )
        assertEquals(
            "{\"display_name\":\"Aryaman Darda\",\"phone\":\"+15105550143\"," +
                "\"tos_version\":\"2026-07-26-placeholder\",\"country\":\"IN\"}",
            Json.encodeToString(
                CreateCaregiverRequest.serializer(),
                CreateCaregiverRequest(
                    displayName = "Aryaman Darda",
                    phone = "+15105550143",
                    tosVersion = "2026-07-26-placeholder",
                    country = "IN",
                ),
            ),
        )
    }

    @Test
    fun `UpdateCaregiverRequest omits null fields`() {
        assertEquals("{}", Json.encodeToString(UpdateCaregiverRequest.serializer(), UpdateCaregiverRequest()))
        assertEquals(
            "{\"display_name\":\"Peggy H\"}",
            Json.encodeToString(
                UpdateCaregiverRequest.serializer(),
                UpdateCaregiverRequest(displayName = "Peggy H"),
            ),
        )
        assertEquals(
            "{\"phone\":\"+15105550143\"}",
            Json.encodeToString(UpdateCaregiverRequest.serializer(), UpdateCaregiverRequest(phone = "+15105550143")),
        )
    }

    // -----------------------------------------------------------------------
    // Small responses
    // -----------------------------------------------------------------------

    @Test
    fun `PhotoURLResponse decodes the signed url and its lifetime`() {
        val response = json.decodeFromString(
            PhotoURLResponse.serializer(),
            """
            {
              "url": "https://xyz.supabase.co/storage/v1/object/sign/patient-photos/a.jpg?token=eyJ",
              "expires_in_seconds": 600
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://xyz.supabase.co/storage/v1/object/sign/patient-photos/a.jpg?token=eyJ",
            response.url,
        )
        assertEquals(600, response.expiresInSeconds)
    }

    @Test
    fun `TestEmailResponse decodes the echoed status`() {
        val response = json.decodeFromString(
            TestEmailResponse.serializer(),
            """{"status":"sent to aryaman@memoria.care"}""",
        )
        assertEquals("sent to aryaman@memoria.care", response.status)
    }
}
