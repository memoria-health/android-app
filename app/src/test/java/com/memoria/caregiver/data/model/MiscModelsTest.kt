package com.memoria.caregiver.data.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Wire-format tests for the five ported model files.
 *
 * Every decode case starts from a JSON string literal shaped like something the
 * backend (or a raw Supabase Realtime row) actually sends. Round-tripping our own
 * encoder would prove nothing about the wire.
 */
class MiscModelsTest {

    /**
     * The configuration `PARITY.md §7` mandates for the shared client:
     * responses decode leniently, nulls are never written on the way out.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Wire carries "...182734"; Foundation truncates below the millisecond,
    // so the decoded instant is 182 ms exactly. Verified against the real
    // JSONCoders.swift logic, not inferred from the format string.
    private val referenceInstant = Instant.fromEpochSeconds(1_779_401_880L, 182_000_000L)

    // -----------------------------------------------------------------------
    // Date/JSON contract — PARITY.md §6, risk register R4
    // -----------------------------------------------------------------------

    private fun decodeExpiry(raw: String): Instant =
        json.decodeFromString(
            MintTokenResponse.serializer(),
            """{"token":"jwt","url":"wss://livekit.example","expires_at":"$raw"}""",
        ).expiresAt

    @Test
    fun `pattern 1 - pydantic six-digit microseconds with numeric offset`() {
        assertEquals(referenceInstant, decodeExpiry("2026-05-21T22:18:00.182734+00:00"))
    }

    @Test
    fun `pattern 1 - six-digit microseconds with Z offset`() {
        assertEquals(referenceInstant, decodeExpiry("2026-05-21T22:18:00.182734Z"))
    }

    @Test
    fun `pattern 2 - three-digit milliseconds`() {
        assertEquals(
            Instant.fromEpochSeconds(1_779_401_880L, 182_000_000L),
            decodeExpiry("2026-05-21T22:18:00.182+00:00"),
        )
    }

    @Test
    fun `fractional seconds with trailing zeros trimmed by postgres still decode`() {
        // Postgres renders .180000 as .18, so a raw Realtime row can carry a
        // two-digit fraction. ICU parses it on iOS; an exact-width java.time
        // pattern would not.
        assertEquals(
            Instant.fromEpochSeconds(1_779_401_880L, 180_000_000L),
            decodeExpiry("2026-05-21T22:18:00.18+00:00"),
        )
    }

    @Test
    fun `pattern 3 - no fractional part`() {
        assertEquals(
            Instant.fromEpochSeconds(1_779_401_880L, 0L),
            decodeExpiry("2026-05-21T22:18:00+00:00"),
        )
    }

    @Test
    fun `non-UTC offset resolves to the same instant`() {
        assertEquals(
            decodeExpiry("2026-05-21T22:18:00+00:00"),
            decodeExpiry("2026-05-22T03:48:00+05:30"),
        )
    }

    @Test
    fun `pattern 4 - date only resolves to midnight UTC`() {
        assertEquals(
            Instant.fromEpochSeconds(1_779_321_600L, 0L),
            decodeExpiry("2026-05-21"),
        )
    }

    @Test
    fun `an unparseable date fails the decode rather than defaulting`() {
        val error = assertThrows(SerializationException::class.java) {
            decodeExpiry("21 May 2026")
        }
        assertTrue(error.message!!.contains("Unrecognized date string"))
    }

    @Test
    fun `a naive timestamp with no offset is rejected, as on iOS`() {
        assertThrows(SerializationException::class.java) {
            decodeExpiry("2026-05-21T22:18:00")
        }
    }

    @Test
    fun `encoding writes the six-digit microsecond pattern and round-trips`() {
        val row = json.decodeFromString(
            FaceEmbedding.serializer(),
            """
            {"id":"e1","patient_id":"p1","source":"selfie","status":"ready",
             "det_score":0.5,"failure_reason":null,
             "created_at":"2026-05-21T22:18:00.182734+00:00"}
            """.trimIndent(),
        )

        val encoded = json.encodeToString(FaceEmbedding.serializer(), row)

        assertEquals(
            """{"id":"e1","patient_id":"p1","source":"selfie","status":"ready",""" +
                """"det_score":0.5,"created_at":"2026-05-21T22:18:00.182000Z"}""",
            encoded,
        )
        assertEquals(row, json.decodeFromString(FaceEmbedding.serializer(), encoded))
    }

    @Test
    fun `a whole-second instant still encodes six fraction digits`() {
        val row = json.decodeFromString(
            FaceEmbedding.serializer(),
            """{"id":"e1","patient_id":"p1","source":"selfie","status":"ready",""" +
                """"det_score":0.5,"created_at":"2026-05-21T22:18:00Z"}""",
        )
        assertTrue(
            json.encodeToString(FaceEmbedding.serializer(), row)
                .contains(""""created_at":"2026-05-21T22:18:00.000000Z""""),
        )
    }

    // -----------------------------------------------------------------------
    // LiveKit — Models/LiveKit.swift
    // -----------------------------------------------------------------------

    @Test
    fun `mint token request sends exactly room identity role`() {
        assertEquals(
            """{"room":"patient_11111111-1111-1111-1111-111111111111",""" +
                """"identity":"caregiver_22222222-2222-2222-2222-222222222222_android",""" +
                """"role":"viewer"}""",
            json.encodeToString(
                MintTokenRequest.serializer(),
                MintTokenRequest(
                    room = "patient_11111111-1111-1111-1111-111111111111",
                    identity = "caregiver_22222222-2222-2222-2222-222222222222_android",
                    role = "viewer",
                ),
            ),
        )
    }

    @Test
    fun `mint token response ignores keys the backend adds later`() {
        val decoded = json.decodeFromString(
            MintTokenResponse.serializer(),
            """
            {"token":"jwt","url":"wss://livekit.example",
             "expires_at":"2026-05-21T22:18:00.182734+00:00",
             "region":"in","ttl_seconds":3600}
            """.trimIndent(),
        )
        assertEquals("jwt", decoded.token)
        assertEquals("wss://livekit.example", decoded.url)
        assertEquals(referenceInstant, decoded.expiresAt)
    }

    @Test
    fun `talk start request sends only camera_id`() {
        assertEquals(
            """{"camera_id":"33333333-3333-3333-3333-333333333333"}""",
            json.encodeToString(
                TalkStartRequest.serializer(),
                TalkStartRequest("33333333-3333-3333-3333-333333333333"),
            ),
        )
    }

    @Test
    fun `record clip request writes its default duration on the wire`() {
        // Swift's synthesized encoder always emits the field; kotlinx would drop
        // a value equal to its default without the EncodeDefault annotation.
        assertEquals(
            """{"duration_seconds":15}""",
            json.encodeToString(RecordClipRequest.serializer(), RecordClipRequest()),
        )
        assertEquals(
            """{"duration_seconds":30}""",
            json.encodeToString(RecordClipRequest.serializer(), RecordClipRequest(30)),
        )
    }

    @Test
    fun `active camera response accepts a value, an explicit null, and an absent key`() {
        assertEquals(
            "44444444-4444-4444-4444-444444444444",
            json.decodeFromString(
                ActiveCameraResponse.serializer(),
                """{"camera_id":"44444444-4444-4444-4444-444444444444"}""",
            ).cameraId,
        )
        assertNull(
            json.decodeFromString(ActiveCameraResponse.serializer(), """{"camera_id":null}""").cameraId,
        )
        assertNull(
            json.decodeFromString(ActiveCameraResponse.serializer(), """{}""").cameraId,
        )
    }

    // -----------------------------------------------------------------------
    // Device registration — Models/DeviceRegistration.swift
    // -----------------------------------------------------------------------

    @Test
    fun `register device request uses snake_case and lowercase environment`() {
        assertEquals(
            """{"apns_token":"a1b2c3","bundle_id":"co.sunny.nemara.caregiver","environment":"sandbox"}""",
            json.encodeToString(
                RegisterDeviceRequest.serializer(),
                RegisterDeviceRequest(
                    apnsToken = "a1b2c3",
                    bundleId = "co.sunny.nemara.caregiver",
                    environment = ApnsEnvironment.SANDBOX,
                ),
            ),
        )
        assertTrue(
            json.encodeToString(
                RegisterDeviceRequest.serializer(),
                RegisterDeviceRequest("t", "b", ApnsEnvironment.PRODUCTION),
            ).contains(""""environment":"production""""),
        )
    }

    @Test
    fun `device response decodes the canonical row`() {
        val decoded = json.decodeFromString(
            DeviceResponse.serializer(),
            """
            {"id":"55555555-5555-5555-5555-555555555555",
             "caregiver_id":"66666666-6666-6666-6666-666666666666",
             "apns_token":"a1b2c3","bundle_id":"co.sunny.nemara.caregiver",
             "environment":"production",
             "created_at":"2026-05-21T22:18:00.182734+00:00",
             "updated_at":"2026-05-21T22:18:00+00:00"}
            """.trimIndent(),
        )
        assertEquals("55555555-5555-5555-5555-555555555555", decoded.id)
        assertEquals("66666666-6666-6666-6666-666666666666", decoded.caregiverId)
        assertEquals("a1b2c3", decoded.apnsToken)
        assertEquals("co.sunny.nemara.caregiver", decoded.bundleId)
        assertEquals(ApnsEnvironment.PRODUCTION, decoded.environment)
        assertEquals(referenceInstant, decoded.createdAt)
        assertEquals(Instant.fromEpochSeconds(1_779_401_880L, 0L), decoded.updatedAt)
    }

    @Test
    fun `an environment outside the backend check constraint fails the decode`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(
                DeviceResponse.serializer(),
                """
                {"id":"i","caregiver_id":"c","apns_token":"t","bundle_id":"b",
                 "environment":"fcm",
                 "created_at":"2026-05-21T22:18:00Z","updated_at":"2026-05-21T22:18:00Z"}
                """.trimIndent(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Face embeddings — Models/FaceEmbedding.swift
    // -----------------------------------------------------------------------

    @Test
    fun `face embedding decodes both row kinds and all three statuses`() {
        val selfie = json.decodeFromString(
            FaceEmbedding.serializer(),
            """
            {"id":"e1","patient_id":"p1","source":"selfie","status":"ready",
             "det_score":0.9739,"failure_reason":null,
             "created_at":"2026-05-21T22:18:00.182734+00:00"}
            """.trimIndent(),
        )
        assertEquals(FaceEmbedding.Source.SELFIE, selfie.source)
        assertEquals(FaceEmbedding.Status.READY, selfie.status)
        assertEquals(0.9739f, selfie.detScore, 0.0f)
        assertNull(selfie.failureReason)

        val harvested = json.decodeFromString(
            FaceEmbedding.serializer(),
            """
            {"id":"e2","patient_id":"p1","source":"harvested","status":"processing",
             "det_score":0.5,"created_at":"2026-05-21T22:18:00Z"}
            """.trimIndent(),
        )
        assertEquals(FaceEmbedding.Source.HARVESTED, harvested.source)
        assertEquals(FaceEmbedding.Status.PROCESSING, harvested.status)

        val failed = json.decodeFromString(
            FaceEmbedding.serializer(),
            """
            {"id":"e3","patient_id":"p1","source":"selfie","status":"failed",
             "det_score":0.1,"failure_reason":"no_face_detected",
             "created_at":"2026-05-21T22:18:00Z"}
            """.trimIndent(),
        )
        assertEquals(FaceEmbedding.Status.FAILED, failed.status)
        assertEquals("no_face_detected", failed.failureReason)
    }

    @Test
    fun `failure_reason tolerates an absent key as well as an explicit null`() {
        val absent = json.decodeFromString(
            FaceEmbedding.serializer(),
            """{"id":"e1","patient_id":"p1","source":"selfie","status":"ready",""" +
                """"det_score":0.5,"created_at":"2026-05-21T22:18:00Z"}""",
        )
        val explicitNull = json.decodeFromString(
            FaceEmbedding.serializer(),
            """{"id":"e1","patient_id":"p1","source":"selfie","status":"ready",""" +
                """"det_score":0.5,"failure_reason":null,"created_at":"2026-05-21T22:18:00Z"}""",
        )
        assertNull(absent.failureReason)
        assertNull(explicitNull.failureReason)
        assertEquals(absent, explicitNull)
    }

    @Test
    fun `an unknown source value fails the decode, matching Swift`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(
                FaceEmbedding.serializer(),
                """{"id":"e1","patient_id":"p1","source":"imported","status":"ready",""" +
                    """"det_score":0.5,"created_at":"2026-05-21T22:18:00Z"}""",
            )
        }
    }

    @Test
    fun `enrollment summary decodes and derives its two flags`() {
        val enrolled = json.decodeFromString(
            FaceEnrollmentSummary.serializer(),
            """
            {"selfie_count":12,"harvested_count":34,"processing_count":0,"failed_count":1,
             "last_update":"2026-05-21T22:18:00.182734+00:00"}
            """.trimIndent(),
        )
        assertEquals(12, enrolled.selfieCount)
        assertEquals(34, enrolled.harvestedCount)
        assertEquals(0, enrolled.processingCount)
        assertEquals(1, enrolled.failedCount)
        assertEquals(referenceInstant, enrolled.lastUpdate)
        assertTrue(enrolled.isEnrolled)
        assertFalse(enrolled.isProcessing)

        val fresh = json.decodeFromString(
            FaceEnrollmentSummary.serializer(),
            """{"selfie_count":0,"harvested_count":0,"processing_count":3,"failed_count":0,"last_update":null}""",
        )
        assertNull(fresh.lastUpdate)
        assertFalse(fresh.isEnrolled)
        assertTrue(fresh.isProcessing)
    }

    @Test
    fun `enrollment summary tolerates an absent last_update`() {
        val decoded = json.decodeFromString(
            FaceEnrollmentSummary.serializer(),
            """{"selfie_count":0,"harvested_count":0,"processing_count":0,"failed_count":0}""",
        )
        assertNull(decoded.lastUpdate)
    }

    // -----------------------------------------------------------------------
    // Feedback — Models/Feedback.swift
    // -----------------------------------------------------------------------

    @Test
    fun `feedback request omits every null field`() {
        assertEquals(
            """{"rating":5}""",
            json.encodeToString(FeedbackRequest.serializer(), FeedbackRequest(rating = 5)),
        )
        assertEquals(
            """{"whats_working":"the alerts","whats_frustrating":"the noise","category":"detectors"}""",
            json.encodeToString(
                FeedbackRequest.serializer(),
                FeedbackRequest(
                    whatsWorking = "the alerts",
                    whatsFrustrating = "the noise",
                    category = "detectors",
                ),
            ),
        )
    }

    @Test
    fun `feedback attachments are a bare base64 array`() {
        assertEquals(
            """{"rating":4,"attachments":["/9j/4AAQ","/9j/4BBQ"]}""",
            json.encodeToString(
                FeedbackRequest.serializer(),
                FeedbackRequest(rating = 4, attachments = listOf("/9j/4AAQ", "/9j/4BBQ")),
            ),
        )
    }

    @Test
    fun `feedback response decodes status and id`() {
        val decoded = json.decodeFromString(
            FeedbackResponse.serializer(),
            """{"status":"sent","id":"77777777-7777-7777-7777-777777777777"}""",
        )
        assertEquals("sent", decoded.status)
        assertEquals("77777777-7777-7777-7777-777777777777", decoded.id)
    }

    // -----------------------------------------------------------------------
    // Support — Models/Support.swift
    // -----------------------------------------------------------------------

    @Test
    fun `support request sends exactly subject and message`() {
        assertEquals(
            """{"subject":"Camera offline","message":"The kitchen camera went dark."}""",
            json.encodeToString(
                SupportRequest.serializer(),
                SupportRequest(subject = "Camera offline", message = "The kitchen camera went dark."),
            ),
        )
    }

    @Test
    fun `support response decodes the echoed status`() {
        assertEquals(
            "sent",
            json.decodeFromString(SupportResponse.serializer(), """{"status":"sent"}""").status,
        )
    }
}
