package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.ApnsEnvironment
import com.memoria.caregiver.data.model.FaceEmbedding
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [EnrollmentApi] actually puts on the wire. Spec: the face-enrollment,
 * device-token and feedback sections of `Services/NemaraAPIClient.swift`.
 *
 * A wrong path or a stray body key here is invisible until a real request fails
 * against the real backend, which for face enrollment means an onboarding flow
 * that captures fifteen selfies and then throws them away. So every test asserts
 * the request, not the return value.
 *
 * **No fixture in this file resembles real biometric data or a real token.** The
 * "photos" are short ASCII strings, and the tokens say `dummy` in them.
 */
class EnrollmentApiTest {

    // ---- Harness ----

    /** One request as the engine saw it. */
    private class Sent(
        val method: String,
        val path: String,
        val contentType: String?,
        val bytes: ByteArray,
    ) {
        /** For JSON bodies. */
        val text: String get() = bytes.decodeToString()

        /**
         * For the multipart body. Latin-1 maps every byte to one char, so the
         * part headers stay readable and arbitrary binary survives a `contains`.
         */
        val latin1: String get() = String(bytes, Charsets.ISO_8859_1)
    }

    private val sent = mutableListOf<Sent>()

    private val only: Sent get() {
        assertEquals("expected exactly one request", 1, sent.size)
        return sent[0]
    }

    private fun api(response: String = "{}"): EnrollmentApi {
        val engine = MockEngine { request ->
            sent += Sent(
                method = request.method.value,
                path = request.url.encodedPath,
                // The body's own content type, which is what the engine emits —
                // it overrides the `application/json` HttpTransport sets on the
                // builder. That override is the reason multipart works through
                // a JSON-shaped transport at all.
                contentType = request.body.contentType?.toString(),
                bytes = request.body.toByteArray(),
            )
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(NemaraJson.json) }
        }
        return EnrollmentApi(
            HttpTransport(
                client = client,
                baseUrl = "http://backend.test",
                tokenProvider = { "dummy-jwt" },
            ),
        )
    }

    /** Compares as JSON so key order is not asserted, but extra keys are. */
    private fun assertJsonBody(expected: String, actual: Sent) {
        assertEquals(
            NemaraJson.json.parseToJsonElement(expected),
            NemaraJson.json.parseToJsonElement(actual.text),
        )
    }

    private fun photo(index: Int) = "DUMMY-PHOTO-$index".toByteArray()

    private companion object {
        const val PATIENT = "11111111-1111-1111-1111-111111111111"

        const val EMBEDDING_ROW = """
            {"id":"22222222-2222-2222-2222-222222222222",
             "patient_id":"11111111-1111-1111-1111-111111111111",
             "source":"selfie","status":"processing","det_score":0.0,
             "created_at":"2026-08-01T12:00:00.000000Z"}
        """
    }

    // ---- Face enrollment ----

    @Test fun `submitting selfies POSTs to the patient's face-embeddings route`() = runTest {
        api("[$EMBEDDING_ROW]").submitFaceEmbeddings(PATIENT, listOf(photo(0)))

        assertEquals("POST", only.method)
        assertEquals("/v1/patients/$PATIENT/face-embeddings", only.path)
    }

    /**
     * The shape FastAPI needs to bind `list[UploadFile]`: the field name repeats,
     * it is not `photos[0]` or a single part holding an array. Getting this wrong
     * is a 422 with every photo dropped.
     */
    @Test fun `every photo is a separate part named photos, numbered from zero`() = runTest {
        api("[$EMBEDDING_ROW]").submitFaceEmbeddings(PATIENT, listOf(photo(0), photo(1), photo(2)))

        val body = only.latin1
        assertEquals(3, Regex("""name="photos"""").findAll(body).count())
        listOf(0, 1, 2).forEach { i ->
            // The part header line iOS writes verbatim, minus the Content-Length
            // Ktor adds after it.
            assertTrue(
                "missing part $i",
                body.contains(
                    "Content-Disposition: form-data; name=\"photos\"; " +
                        "filename=\"photo_$i.jpg\"\r\nContent-Type: image/jpeg",
                ),
            )
        }
    }

    /** Multipart, with the boundary the body generated — not the transport's JSON. */
    @Test fun `the selfie upload is multipart form-data`() = runTest {
        api("[$EMBEDDING_ROW]").submitFaceEmbeddings(PATIENT, listOf(photo(0)))

        val type = only.contentType.orEmpty()
        assertTrue(type, type.startsWith("multipart/form-data"))
        assertTrue("no boundary: $type", type.contains("boundary="))
        val boundary = type.substringAfter("boundary=").trim('"')
        assertTrue("body does not use the declared boundary", only.latin1.contains("--$boundary"))
    }

    /**
     * JPEG bytes ride raw. iOS appends `Data` straight into the multipart body,
     * and base64ing them here would inflate the upload by a third and hand the
     * backend an unreadable file. (Feedback attachments ARE base64 — the two are
     * a step apart in the same file, so this is pinned.)
     */
    @Test fun `photo bytes are raw, not base64`() = runTest {
        api("[$EMBEDDING_ROW]").submitFaceEmbeddings(PATIENT, listOf(photo(0)))

        val body = only.latin1
        assertTrue(body.contains("DUMMY-PHOTO-0"))
        assertFalse(body.contains(Base64.getEncoder().encodeToString(photo(0))))
    }

    /** iOS `precondition`s on this. Nothing should reach the network. */
    @Test fun `an empty photo list fails before any request`() = runTest {
        var threw = false
        try {
            api().submitFaceEmbeddings(PATIENT, emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected an IllegalArgumentException", threw)
        assertTrue("a request was sent anyway", sent.isEmpty())
    }

    /** The response is a bare array of placeholder rows, not an envelope. */
    @Test fun `the upload returns the processing placeholder rows`() = runTest {
        val rows = api("[$EMBEDDING_ROW]").submitFaceEmbeddings(PATIENT, listOf(photo(0)))

        assertEquals(1, rows.size)
        assertEquals(FaceEmbedding.Status.PROCESSING, rows[0].status)
        assertEquals(FaceEmbedding.Source.SELFIE, rows[0].source)
    }

    @Test fun `the summary is a GET on the summary sub-route`() = runTest {
        val summary = api(
            """{"selfie_count":12,"harvested_count":3,"processing_count":0,"failed_count":1}""",
        ).faceEnrollmentSummary(PATIENT)

        assertEquals("GET", only.method)
        assertEquals("/v1/patients/$PATIENT/face-embeddings/summary", only.path)
        assertEquals(12, summary.selfieCount)
        assertTrue(summary.isEnrolled)
        assertFalse(summary.isProcessing)
    }

    /** Same path as the upload, different verb. */
    @Test fun `listing embeddings GETs the collection route`() = runTest {
        api("[$EMBEDDING_ROW]").listFaceEmbeddings(PATIENT)

        assertEquals("GET", only.method)
        assertEquals("/v1/patients/$PATIENT/face-embeddings", only.path)
    }

    /**
     * Top-level, NOT nested under the patient. The embedding id is enough to
     * address the row, and the backend route is written that way.
     */
    @Test fun `deleting an embedding uses the top-level route`() = runTest {
        val id = "33333333-3333-3333-3333-333333333333"
        api("").deleteFaceEmbedding(id)

        assertEquals("DELETE", only.method)
        assertEquals("/v1/face-embeddings/$id", only.path)
    }

    /**
     * The Swift builds every path from `uuidString.lowercased()`. An id that
     * arrived uppercase — from a deep link, or typed — must therefore reach the
     * same URL iOS would have used, not a second spelling of it.
     */
    @Test fun `an uppercase id is lowercased into the path`() = runTest {
        api("""{"selfie_count":0,"harvested_count":0,"processing_count":0,"failed_count":0}""")
            .faceEnrollmentSummary(PATIENT.uppercase())

        assertEquals("/v1/patients/$PATIENT/face-embeddings/summary", only.path)
    }

    // ---- Device tokens ----

    /**
     * Exactly three keys. The backend request schema is pydantic with
     * `extra="forbid"`, so a fourth key is a 422 rather than something ignored.
     *
     * TODO(REQ-8): this pins the **iOS** contract. When the backend decides how
     * Android registers (a `platform` discriminator, or a sibling route), this
     * test is the thing that should fail.
     */
    @Test fun `registering a device sends exactly the iOS body`() = runTest {
        api(
            """
            {"id":"44444444-4444-4444-4444-444444444444",
             "caregiver_id":"55555555-5555-5555-5555-555555555555",
             "apns_token":"dummy-token","bundle_id":"com.memoria.caregiver",
             "environment":"sandbox",
             "created_at":"2026-08-01T12:00:00.000000Z",
             "updated_at":"2026-08-01T12:00:00.000000Z"}
            """,
        ).registerDeviceToken(
            apnsToken = "dummy-token",
            bundleId = "com.memoria.caregiver",
            environment = ApnsEnvironment.SANDBOX,
        )

        assertEquals("POST", only.method)
        assertEquals("/v1/me/devices", only.path)
        assertJsonBody(
            """
            {"apns_token":"dummy-token","bundle_id":"com.memoria.caregiver",
             "environment":"sandbox"}
            """,
            only,
        )
    }

    /**
     * Lowercase, and only these two spellings. A Postgres check constraint
     * accepts nothing else, so `SANDBOX` or `Production` on the wire is a write
     * that fails at the database rather than at the API.
     */
    @Test fun `the environment goes out lowercase`() = runTest {
        val client = api(
            """
            {"id":"44444444-4444-4444-4444-444444444444",
             "caregiver_id":"55555555-5555-5555-5555-555555555555",
             "apns_token":"dummy-token","bundle_id":"com.memoria.caregiver",
             "environment":"production",
             "created_at":"2026-08-01T12:00:00.000000Z",
             "updated_at":"2026-08-01T12:00:00.000000Z"}
            """,
        )
        client.registerDeviceToken("dummy-token", "com.memoria.caregiver", ApnsEnvironment.PRODUCTION)

        assertTrue(only.text.contains(""""environment":"production""""))
    }

    /**
     * The token goes in the path verbatim. **Case is preserved** — unlike the
     * UUID path ids, a push token is opaque and case-sensitive, so lowercasing
     * one would de-register nothing and leave the device receiving alerts after
     * sign-out.
     */
    @Test fun `de-registering puts the token in the path, case intact`() = runTest {
        api("").deleteDeviceToken("Dummy-TOKEN-abc")

        assertEquals("DELETE", only.method)
        assertEquals("/v1/me/devices/Dummy-TOKEN-abc", only.path)
    }

    /**
     * TODO(REQ-8): an FCM registration token carries a `:` — the part before it
     * is the app instance id. Pinned because the path builder must not mangle it
     * into something the backend cannot match back to the stored row.
     */
    @Test fun `a token containing a colon survives the path`() = runTest {
        api("").deleteDeviceToken("dummy:token")

        assertEquals("/v1/me/devices/dummy:token", only.path)
    }

    // ---- Feedback ----

    @Test fun `feedback POSTs to the feedback route`() = runTest {
        val response = api("""{"status":"received","id":"66666666-6666-6666-6666-666666666666"}""")
            .submitFeedback(rating = 5)

        assertEquals("POST", only.method)
        assertEquals("/v1/feedback", only.path)
        assertEquals("received", response.status)
    }

    /**
     * Null fields are omitted rather than sent as `null`. Swift's
     * `encodeIfPresent` does the same, and the backend's "a rating OR some text"
     * rule reads an absent key, not a null one.
     */
    @Test fun `unset feedback fields are left out of the body entirely`() = runTest {
        api("""{"status":"received","id":"66666666-6666-6666-6666-666666666666"}""")
            .submitFeedback(rating = 4)

        assertJsonBody("""{"rating":4}""", only)
    }

    @Test fun `a fully populated feedback body carries every field with its wire name`() = runTest {
        api("""{"status":"received","id":"66666666-6666-6666-6666-666666666666"}""")
            .submitFeedback(
                rating = 3,
                whatsWorking = "the roster",
                whatsFrustrating = "the login",
                category = "bug",
                // Stand-ins for base64 JPEGs. Deliberately not real image data.
                attachments = listOf("dummy-base64-a", "dummy-base64-b"),
            )

        assertJsonBody(
            """
            {"rating":3,"whats_working":"the roster","whats_frustrating":"the login",
             "category":"bug","attachments":["dummy-base64-a","dummy-base64-b"]}
            """,
            only,
        )
    }

    /** A rating-only submission is valid; the text fields are optional. */
    @Test fun `text-only feedback omits the rating`() = runTest {
        api("""{"status":"received","id":"66666666-6666-6666-6666-666666666666"}""")
            .submitFeedback(whatsFrustrating = "the login")

        assertJsonBody("""{"whats_frustrating":"the login"}""", only)
    }
}
