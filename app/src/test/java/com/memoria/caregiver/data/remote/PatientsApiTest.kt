package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.CreatePatientRequest
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.model.UpdatePatientRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mergeHeaders
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire tests for [PatientsApi]. Spec: the `// MARK: - Patients` block of
 * `Services/NemaraAPIClient.swift`, plus PARITY.md §10 (swallowed statuses) and
 * the multipart shapes in PARITY.md.
 *
 * Everything runs through a real [HttpClient] on Ktor's [MockEngine], so each
 * test asserts the **actual** method, path, query and body bytes that would go
 * out — not a mock of the transport, which would only prove that this file and
 * the test agree with each other. The client is configured exactly as
 * `AppEnvironment` configures the shipping one (ContentNegotiation with
 * [NemaraJson.json]), so a change to the shared JSON settings shows up here.
 *
 * No Android framework, no Robolectric: this is plain JVM.
 */
class PatientsApiTest {

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private companion object {

        const val BASE_URL = "https://api.memoria.test"

        /** Lowercase, as the backend and the wire always have it. */
        const val PATIENT_ID = "8f4a6e2c-1c3b-4a0e-9a0f-2f7c1d5b6e11"

        /** A complete `Patient` row, shaped like FastAPI serializes one. */
        val PATIENT_JSON = """
            {
              "id": "$PATIENT_ID",
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
              "email_alert_recipients": ["sunny@memoria.care"],
              "data_collection_enabled": false,
              "data_collection_decided_at": null,
              "privacy_mode_enabled": false,
              "photo_storage_key": "$PATIENT_ID.jpg",
              "created_at": "2026-01-04T11:00:00.000000+00:00",
              "updated_at": "2026-07-27T09:04:11.500000+00:00"
            }
        """.trimIndent()

        /** The same row with a different id and name, for order assertions. */
        fun patientJson(id: String, fullName: String): String = PATIENT_JSON
            .replace("\"id\": \"$PATIENT_ID\"", "\"id\": \"$id\"")
            .replace("Margaret Holloway", fullName)
    }

    /**
     * One canned response, and the request that asked for it.
     *
     * [request] is captured inside the engine handler, so it is populated by the
     * time the suspending call returns.
     */
    private class Wire(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "",
    ) {
        lateinit var request: HttpRequestData
            private set

        private val engine = MockEngine { incoming ->
            request = incoming
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val api = PatientsApi(
            HttpTransport(
                client = HttpClient(engine) {
                    install(ContentNegotiation) { json(NemaraJson.json) }
                },
                baseUrl = BASE_URL,
                tokenProvider = { "test-jwt" },
            ),
        )

        /** The request body as it would be written to the socket. */
        suspend fun bodyBytes(): ByteArray = request.body.toByteArray()

        suspend fun bodyText(): String = String(bodyBytes(), Charsets.UTF_8)

        /**
         * The Content-Type that actually reaches the wire, resolved the way the
         * engines resolve it: the body's own type wins over the header the
         * request builder set. Returns every value found, so a test can prove
         * there is exactly one.
         */
        @OptIn(InternalAPI::class)
        fun wireContentTypes(): List<String> = buildList {
            mergeHeaders(request.headers, request.body) { key, value ->
                if (key == HttpHeaders.ContentType) add(value)
            }
        }
    }

    // -----------------------------------------------------------------------
    // GET /v1/patients
    // -----------------------------------------------------------------------

    @Test fun `loadPatients gets the roster collection with no query`() = runTest {
        val wire = Wire(responseBody = "[$PATIENT_JSON]")

        val patients = wire.api.loadPatients()

        assertEquals(HttpMethod.Get, wire.request.method)
        assertEquals("/v1/patients", wire.request.url.encodedPath)
        assertEquals("", wire.request.url.encodedQuery)
        assertEquals(1, patients.size)
        assertEquals("Margaret Holloway", patients[0].fullName)
    }

    /**
     * The Swift is a bare `patients = list` — no sort, unlike cameras (sorted by
     * `displayId`) and events (newest first). Server order out, server order in.
     * These two rows are deliberately reverse-alphabetical so any sort slipped
     * in later flips them and fails.
     */
    @Test fun `loadPatients preserves server order`() = runTest {
        val zed = patientJson("11111111-1111-4111-8111-111111111111", "Zed Alvarez")
        val abe = patientJson("22222222-2222-4222-8222-222222222222", "Abe Yardley")
        val wire = Wire(responseBody = "[$zed,$abe]")

        val patients = wire.api.loadPatients()

        assertEquals(listOf("Zed Alvarez", "Abe Yardley"), patients.map { it.fullName })
    }

    @Test fun `loadPatients surfaces a failure status as an ApiError`() = runTest {
        val wire = Wire(status = HttpStatusCode.Unauthorized, responseBody = "")

        assertEquals(ApiError.Unauthorized, wire.api.runCatchingApi { loadPatients() })
    }

    // -----------------------------------------------------------------------
    // POST /v1/patients
    // -----------------------------------------------------------------------

    /**
     * The backend's request schemas are `extra="forbid"`, so an extra key is a
     * 422 rather than a warning. The body is asserted verbatim: absent optional
     * fields must be **omitted**, not sent as null.
     */
    @Test fun `createPatient posts exactly the keys the caller set`() = runTest {
        val wire = Wire(status = HttpStatusCode.Created, responseBody = PATIENT_JSON)

        val created = wire.api.createPatient(
            CreatePatientRequest(
                fullName = "Margaret Holloway",
                preferredName = "Peggy",
                relation = Patient.Relation.PARENT,
                country = "US",
            ),
        )

        assertEquals(HttpMethod.Post, wire.request.method)
        assertEquals("/v1/patients", wire.request.url.encodedPath)
        assertEquals(
            """{"full_name":"Margaret Holloway","preferred_name":"Peggy",""" +
                """"relation":"parent","country":"US"}""",
            wire.bodyText(),
        )
        assertEquals(PATIENT_ID, created.id)
    }

    @Test fun `createPatient sends the optional fields when they are set`() = runTest {
        val wire = Wire(status = HttpStatusCode.Created, responseBody = PATIENT_JSON)

        wire.api.createPatient(
            CreatePatientRequest(
                fullName = "Margaret Holloway",
                preferredName = "Peggy",
                relation = Patient.Relation.PARENT,
                gender = "female",
                homeAddress = "14 Rowan Lane",
                country = "US",
            ),
        )

        val body = wire.bodyText()
        assertTrue(body, body.contains(""""gender":"female""""))
        assertTrue(body, body.contains(""""home_address":"14 Rowan Lane""""))
    }

    // -----------------------------------------------------------------------
    // PATCH /v1/patients/{id}
    // -----------------------------------------------------------------------

    @Test fun `updatePatient patches the row with only the fields it was given`() = runTest {
        val wire = Wire(responseBody = PATIENT_JSON)

        wire.api.updatePatient(PATIENT_ID, UpdatePatientRequest(camerasPaused = true))

        assertEquals(HttpMethod.Patch, wire.request.method)
        assertEquals("/v1/patients/$PATIENT_ID", wire.request.url.encodedPath)
        assertEquals("""{"cameras_paused":true}""", wire.bodyText())
    }

    /**
     * Swift builds every patient path from `id.uuidString.lowercased()` because
     * `UUID.uuidString` is uppercase. Ids are Strings here and normally arrive
     * lowercase, but a caller holding an uppercase one (a deep link, a pasted
     * id) must still hit the same URL iOS would.
     */
    @Test fun `every patient path is lowercased like the Swift uuidString`() = runTest {
        val upper = PATIENT_ID.uppercase()

        Wire(responseBody = PATIENT_JSON).let {
            it.api.updatePatient(upper, UpdatePatientRequest(gender = "female"))
            assertEquals("/v1/patients/$PATIENT_ID", it.request.url.encodedPath)
        }
        Wire(status = HttpStatusCode.NoContent).let {
            it.api.deletePatient(upper)
            assertEquals("/v1/patients/$PATIENT_ID", it.request.url.encodedPath)
        }
        Wire(responseBody = """{"status":"sent"}""").let {
            it.api.sendPatientTestEmail(upper)
            assertEquals("/v1/patients/$PATIENT_ID/test-email", it.request.url.encodedPath)
        }
        Wire(responseBody = PATIENT_JSON).let {
            it.api.uploadPatientPhoto(upper, byteArrayOf(0x01), "image/jpeg")
            assertEquals("/v1/patients/$PATIENT_ID/photo", it.request.url.encodedPath)
        }
        Wire(responseBody = """{"url":"https://x.test/a","expires_in_seconds":600}""").let {
            it.api.patientPhotoUrl(upper)
            assertEquals("/v1/patients/$PATIENT_ID/photo-url", it.request.url.encodedPath)
        }
        Wire(status = HttpStatusCode.NoContent).let {
            it.api.deletePatientPhoto(upper)
            assertEquals("/v1/patients/$PATIENT_ID/photo", it.request.url.encodedPath)
        }
    }

    // -----------------------------------------------------------------------
    // DELETE /v1/patients/{id}
    // -----------------------------------------------------------------------

    @Test fun `deletePatient deletes the row`() = runTest {
        val wire = Wire(status = HttpStatusCode.NoContent)

        wire.api.deletePatient(PATIENT_ID)

        assertEquals(HttpMethod.Delete, wire.request.method)
        assertEquals("/v1/patients/$PATIENT_ID", wire.request.url.encodedPath)
    }

    /**
     * PARITY.md §10: a 404 means the row is already gone server-side, and the
     * caller's intent is satisfied. Returning normally is what lets the caller
     * run its local cleanup; throwing would strand a deleted patient on the
     * roster behind an error banner.
     */
    @Test fun `deletePatient treats 404 as success`() = runTest {
        val wire = Wire(status = HttpStatusCode.NotFound, responseBody = """{"detail":"not_found"}""")

        wire.api.deletePatient(PATIENT_ID)

        assertEquals(HttpMethod.Delete, wire.request.method)
    }

    /** Only 404 is swallowed. Everything else still reaches the caller. */
    @Test fun `deletePatient still throws on a non-404 failure`() = runTest {
        val wire = Wire(status = HttpStatusCode.Forbidden)

        assertEquals(ApiError.Forbidden, wire.api.runCatchingApi { deletePatient(PATIENT_ID) })
    }

    // -----------------------------------------------------------------------
    // POST /v1/patients/{id}/test-email
    // -----------------------------------------------------------------------

    @Test fun `sendPatientTestEmail posts an empty body and returns the status`() = runTest {
        val wire = Wire(responseBody = """{"status":"sent to 2 recipients"}""")

        val response = wire.api.sendPatientTestEmail(PATIENT_ID)

        assertEquals(HttpMethod.Post, wire.request.method)
        assertEquals("/v1/patients/$PATIENT_ID/test-email", wire.request.url.encodedPath)
        assertEquals("", wire.bodyText())
        assertEquals("sent to 2 recipients", response.status)
    }

    // -----------------------------------------------------------------------
    // POST /v1/patients/{id}/photo — multipart
    // -----------------------------------------------------------------------

    @Test fun `uploadPatientPhoto posts one part named photo with the image bytes`() = runTest {
        val wire = Wire(responseBody = PATIENT_JSON)
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())

        val updated = wire.api.uploadPatientPhoto(PATIENT_ID, image, "image/jpeg")

        assertEquals(HttpMethod.Post, wire.request.method)
        assertEquals("/v1/patients/$PATIENT_ID/photo", wire.request.url.encodedPath)

        // The exact Content-Disposition line the Swift hand-rolls, byte for
        // byte. Ktor adds a per-part Content-Length that iOS does not, which the
        // multipart spec allows and starlette ignores; not asserted either way.
        val body = wire.bodyText()
        assertTrue(
            body,
            body.contains("""Content-Disposition: form-data; name="photo"; filename="avatar.jpg""""),
        )
        assertTrue(body, body.contains("Content-Type: image/jpeg"))
        // The image itself, byte for byte, not just its headers.
        assertTrue("image bytes missing from the part", wire.bodyBytes().containsSubsequence(image))
        assertEquals(PATIENT_ID, updated.id)
    }

    /**
     * The backend picks the storage key's extension off the filename, so this is
     * contract. Swift compares the content type to `"image/png"` exactly.
     */
    @Test fun `uploadPatientPhoto names the part avatar png only for image png`() = runTest {
        val png = Wire(responseBody = PATIENT_JSON)
        png.api.uploadPatientPhoto(PATIENT_ID, byteArrayOf(0x89.toByte()), "image/png")
        assertTrue(png.bodyText().contains("""name="photo"; filename="avatar.png""""))

        val heic = Wire(responseBody = PATIENT_JSON)
        heic.api.uploadPatientPhoto(PATIENT_ID, byteArrayOf(0x00), "image/heic")
        assertTrue(heic.bodyText().contains("""name="photo"; filename="avatar.jpg""""))
    }

    /**
     * [HttpTransport] sets `Content-Type: application/json` on the builder for
     * any non-null body. For a multipart body that header is discarded: Ktor
     * resolves the outgoing type as `content.contentType` first. If a Ktor
     * upgrade ever reversed that precedence, every avatar upload would 422 with
     * "no multipart boundary" — so it is asserted rather than assumed.
     */
    @Test fun `uploadPatientPhoto sends exactly one multipart content type with a boundary`() = runTest {
        val wire = Wire(responseBody = PATIENT_JSON)

        wire.api.uploadPatientPhoto(PATIENT_ID, byteArrayOf(0x01), "image/jpeg")

        val types = wire.wireContentTypes()
        assertEquals("expected one Content-Type, got $types", 1, types.size)
        assertTrue(types[0], types[0].startsWith("multipart/form-data;"))
        assertTrue(types[0], types[0].contains("boundary="))
    }

    // -----------------------------------------------------------------------
    // GET /v1/patients/{id}/photo-url
    // -----------------------------------------------------------------------

    @Test fun `patientPhotoUrl returns the signed url`() = runTest {
        val wire = Wire(
            responseBody = """{"url":"https://storage.test/signed/abc","expires_in_seconds":600}""",
        )

        val url = wire.api.patientPhotoUrl(PATIENT_ID)

        assertEquals(HttpMethod.Get, wire.request.method)
        assertEquals("/v1/patients/$PATIENT_ID/photo-url", wire.request.url.encodedPath)
        assertEquals("https://storage.test/signed/abc", url)
    }

    /**
     * PARITY.md §10: 404 means "no avatar uploaded", which is the normal state
     * for most patients. It must not reach the UI as an error.
     */
    @Test fun `patientPhotoUrl returns null when there is no photo`() = runTest {
        val wire = Wire(status = HttpStatusCode.NotFound, responseBody = """{"detail":"no_photo"}""")

        assertNull(wire.api.patientPhotoUrl(PATIENT_ID))
    }

    @Test fun `patientPhotoUrl still throws on any other failure`() = runTest {
        val wire = Wire(status = HttpStatusCode.InternalServerError)

        assertEquals(
            ApiError.ServerError(code = null, serverMessage = null),
            wire.api.runCatchingApi { patientPhotoUrl(PATIENT_ID) },
        )
    }

    // -----------------------------------------------------------------------
    // DELETE /v1/patients/{id}/photo
    // -----------------------------------------------------------------------

    @Test fun `deletePatientPhoto deletes the avatar`() = runTest {
        val wire = Wire(status = HttpStatusCode.NoContent)

        wire.api.deletePatientPhoto(PATIENT_ID)

        assertEquals(HttpMethod.Delete, wire.request.method)
        assertEquals("/v1/patients/$PATIENT_ID/photo", wire.request.url.encodedPath)
    }

    /**
     * Unlike [PatientsApi.deletePatient], this one does not swallow 404 — the
     * server is idempotent here and answers 204 even with no prior photo, so a
     * 404 means something else went wrong and the caller should see it.
     */
    @Test fun `deletePatientPhoto does not swallow 404`() = runTest {
        val wire = Wire(status = HttpStatusCode.NotFound)

        assertEquals(ApiError.NotFound, wire.api.runCatchingApi { deletePatientPhoto(PATIENT_ID) })
    }

    // -----------------------------------------------------------------------

    /**
     * Runs [block] and hands back the [ApiError] it threw, or null when it did
     * not throw. `assertThrows` cannot be used for these: the calls suspend, and
     * nesting a second `runTest` inside the test's own one is not allowed.
     */
    private suspend fun PatientsApi.runCatchingApi(
        block: suspend PatientsApi.() -> Any?,
    ): ApiError? = try {
        block()
        null
    } catch (e: ApiError) {
        e
    }

    /** True when [other] appears contiguously somewhere in this array. */
    private fun ByteArray.containsSubsequence(other: ByteArray): Boolean {
        if (other.isEmpty()) return true
        outer@ for (start in 0..(size - other.size)) {
            for (i in other.indices) {
                if (this[start + i] != other[i]) continue@outer
            }
            return true
        }
        return false
    }
}
