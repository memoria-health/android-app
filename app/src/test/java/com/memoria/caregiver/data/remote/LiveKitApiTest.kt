package com.memoria.caregiver.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LiveKit and Talk endpoints. Spec: the
 * `// MARK: - LiveKit (POST /v1/livekit/token) + Talk` section of
 * `Services/NemaraAPIClient.swift`.
 *
 * These methods have no logic to speak of — the only thing that can be wrong is
 * the request that reaches the wire, and nothing downstream would notice a wrong
 * path or a renamed body field until a caregiver taps Talk. So every test here
 * asserts the actual method, path and JSON body a mock engine received, rather
 * than that the method returned.
 *
 * The identity tests are the reason this file exists. See [LiveKitIdentity] and
 * PARITY.md §29 item 1.
 */
class LiveKitApiTest {

    // Fixed ids. The caregiver id is deliberately UPPERCASE, because that is the
    // form Swift's `UUID.uuidString` produces and the form the minted identity
    // must not carry through.
    private val patientId = "9f1c7a20-1111-4c2d-8e3f-aaaaaaaaaaaa"
    private val cameraId = "3b2d8e40-2222-4a1b-9c7d-bbbbbbbbbbbb"
    private val caregiverIdUpper = "7E5A1C90-3333-4D8E-AF11-CCCCCCCCCCCC"
    private val caregiverIdLower = caregiverIdUpper.lowercase()

    /** What the engine saw, captured so the assertions can read it back. */
    private class Wire {
        lateinit var method: String
        lateinit var path: String
        lateinit var body: String
    }

    /**
     * Builds a [LiveKitApi] whose transport answers every request with
     * [responseBody] at [status], recording what it was asked for.
     *
     * ContentNegotiation is installed exactly as `AppEnvironment` installs it,
     * because the request-body serialization under test is the plugin's work —
     * a test client without it would encode nothing and pass vacuously.
     */
    private fun apiFor(
        responseBody: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<LiveKitApi, Wire> {
        val wire = Wire()
        val engine = MockEngine { request ->
            wire.method = request.method.value
            wire.path = request.url.encodedPath
            wire.body = when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                else -> ""
            }
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(NemaraJson.json) }
        }
        val transport = HttpTransport(
            client = client,
            baseUrl = "https://api.test",
            tokenProvider = { "test-jwt" },
        )
        return LiveKitApi(transport) to wire
    }

    private fun Wire.json(): JsonObject = Json.parseToJsonElement(body) as JsonObject

    /** JUnit's `assertThrows` can't run a suspend block; this can. */
    private suspend fun apiErrorFrom(block: suspend () -> Unit): ApiError = try {
        block()
        throw AssertionError("expected an ApiError, the call succeeded")
    } catch (e: ApiError) {
        e
    }

    private fun Wire.string(key: String): String = json().getValue(key).jsonPrimitive.content

    private val tokenResponse = """
        {"token":"jwt-abc","url":"wss://lk.test","expires_at":"2026-08-01T12:00:00Z"}
    """.trimIndent()

    // ---- The identity suffix. The single highest-consequence string here. ----

    /**
     * **If this fails, do not "fix" the assertion.** An Android client that
     * mints `_ios` shares a LiveKit participant key with the caregiver's iPhone,
     * and LiveKit evicts the older session — the iPhone drops off the stream
     * with no error on either device and nothing server-side to point at.
     */
    @Test fun `the minted identity ends in _android`() = runTest {
        val (api, wire) = apiFor(tokenResponse)
        api.mintViewerToken(patientId = patientId, caregiverId = caregiverIdLower)

        val identity = wire.string("identity")
        assertTrue("identity was '$identity'", identity.endsWith("_android"))
        assertEquals("caregiver_${caregiverIdLower}_android", identity)
    }

    /** The two suffixes that would collide with a live session elsewhere. */
    @Test fun `the minted identity never carries another platform's suffix`() = runTest {
        val (api, wire) = apiFor(tokenResponse)
        api.mintBroadcasterToken(patientId = patientId, caregiverId = caregiverIdLower)

        val identity = wire.string("identity")
        assertTrue("identity was '$identity'", !identity.endsWith("_ios"))
        assertTrue("identity was '$identity'", !identity.endsWith("_web"))
    }

    /**
     * An uppercase caregiver id must be lowercased before it goes out. LiveKit
     * compares identities byte for byte, so the same caregiver arriving in
     * uppercase once and lowercase once would be two participants, and the
     * backend's prefix check expects the lowercase form.
     */
    @Test fun `an uppercase caregiver id is lowercased in the identity`() = runTest {
        val (api, wire) = apiFor(tokenResponse)
        api.mintViewerToken(patientId = patientId, caregiverId = caregiverIdUpper)

        assertEquals("caregiver_${caregiverIdLower}_android", wire.string("identity"))
    }

    // ---- POST /v1/livekit/token ----

    @Test fun `mintViewerToken posts room, identity and role viewer`() = runTest {
        val (api, wire) = apiFor(tokenResponse)
        api.mintViewerToken(patientId = patientId, caregiverId = caregiverIdLower)

        assertEquals("POST", wire.method)
        assertEquals("/v1/livekit/token", wire.path)
        assertEquals("patient_$patientId", wire.string("room"))
        assertEquals("viewer", wire.string("role"))
        // extra="forbid" on the backend: three keys, no more.
        assertEquals(setOf("room", "identity", "role"), wire.json().keys)
    }

    @Test fun `mintBroadcasterToken differs from the viewer token only in role`() = runTest {
        val (viewerApi, viewerWire) = apiFor(tokenResponse)
        viewerApi.mintViewerToken(patientId = patientId, caregiverId = caregiverIdLower)
        val (castApi, castWire) = apiFor(tokenResponse)
        castApi.mintBroadcasterToken(patientId = patientId, caregiverId = caregiverIdLower)

        assertEquals("broadcaster", castWire.string("role"))
        assertEquals(viewerWire.path, castWire.path)
        assertEquals(viewerWire.string("room"), castWire.string("room"))
        assertEquals(viewerWire.string("identity"), castWire.string("identity"))
    }

    @Test fun `the minted token response is decoded`() = runTest {
        val (api, _) = apiFor(tokenResponse)
        val minted = api.mintViewerToken(patientId = patientId, caregiverId = caregiverIdLower)

        assertEquals("jwt-abc", minted.token)
        assertEquals("wss://lk.test", minted.url)
        assertEquals("2026-08-01T12:00:00Z", minted.expiresAt.toString())
    }

    @Test fun `a rejected mint surfaces the mapped error`() = runTest {
        val (api, _) = apiFor("""{"detail":"livekit_not_configured"}""", HttpStatusCode.ServiceUnavailable)
        val error = apiErrorFrom { api.mintViewerToken(patientId = patientId, caregiverId = caregiverIdLower) }
        assertEquals(ApiError.ServerError(code = null, serverMessage = "livekit_not_configured"), error)
    }

    // ---- GET /v1/patients/{id}/active-camera ----

    @Test fun `getActiveCamera gets the suggestion endpoint and returns the id`() = runTest {
        val (api, wire) = apiFor("""{"camera_id":"$cameraId"}""")
        val result = api.getActiveCamera(patientId)

        assertEquals("GET", wire.method)
        assertEquals("/v1/patients/$patientId/active-camera", wire.path)
        assertEquals("", wire.body)
        assertEquals(cameraId, result)
    }

    /**
     * The "no active camera" answer. A 200 carrying an explicit null is the
     * backend saying there is no qualifying analysis row from the last 30s; the
     * Live tab falls back to the first online camera. It must not read as a
     * failure.
     */
    @Test fun `getActiveCamera returns null when camera_id is null`() = runTest {
        val (api, _) = apiFor("""{"camera_id":null}""")
        assertNull(api.getActiveCamera(patientId))
    }

    /** Same answer with the key omitted, which `explicitNulls = false` allows. */
    @Test fun `getActiveCamera returns null when the key is absent`() = runTest {
        val (api, _) = apiFor("{}")
        assertNull(api.getActiveCamera(patientId))
    }

    /**
     * The other half of the contract: a real failure throws rather than
     * returning null, so a caller seeing null knows the request succeeded.
     */
    @Test fun `getActiveCamera throws rather than returning null on a failure`() = runTest {
        val (api, _) = apiFor("", HttpStatusCode.Forbidden)
        assertEquals(ApiError.Forbidden, apiErrorFrom { api.getActiveCamera(patientId) })
    }

    // ---- POST /v1/patients/{id}/talk/start | talk/end ----

    @Test fun `startTalk posts camera_id to the start route`() = runTest {
        val (api, wire) = apiFor("", HttpStatusCode.NoContent)
        api.startTalk(patientId = patientId, cameraId = cameraId)

        assertEquals("POST", wire.method)
        assertEquals("/v1/patients/$patientId/talk/start", wire.path)
        assertEquals(cameraId, wire.string("camera_id"))
        assertEquals(setOf("camera_id"), wire.json().keys)
    }

    /**
     * iOS uses `sendNoBodyVoid` here — no body at all, not `{}`. The backend
     * takes the patient from the path and the caregiver from the JWT, and an
     * unexpected body would be a 422 under `extra="forbid"`.
     */
    @Test fun `endTalk posts the end route with no body`() = runTest {
        val (api, wire) = apiFor("", HttpStatusCode.NoContent)
        api.endTalk(patientId)

        assertEquals("POST", wire.method)
        assertEquals("/v1/patients/$patientId/talk/end", wire.path)
        assertEquals("", wire.body)
    }

    // ---- POST /v1/patients/{id}/cameras/{cid}/record-clip ----

    @Test fun `recordClip posts to the nested camera route with the default duration`() = runTest {
        val (api, wire) = apiFor("", HttpStatusCode.NoContent)
        api.recordClip(patientId = patientId, cameraId = cameraId)

        assertEquals("POST", wire.method)
        assertEquals("/v1/patients/$patientId/cameras/$cameraId/record-clip", wire.path)
        // Swift's synthesized encoder always writes the field, so the default
        // must appear on the wire rather than being dropped as "unset".
        assertEquals("""{"duration_seconds":15}""", wire.body)
    }

    @Test fun `recordClip sends an explicit duration`() = runTest {
        val (api, wire) = apiFor("", HttpStatusCode.NoContent)
        api.recordClip(patientId = patientId, cameraId = cameraId, durationSeconds = 60)

        assertEquals("""{"duration_seconds":60}""", wire.body)
    }

    /**
     * `record-clip` is mounted only when `NEMARA_ENABLE_INTERNALS=true`, so a
     * production backend answers 404. That has to reach the caller as an error,
     * not be swallowed by the void return.
     */
    @Test fun `recordClip surfaces the 404 a production backend returns`() = runTest {
        val (api, _) = apiFor("", HttpStatusCode.NotFound)
        assertEquals(
            ApiError.NotFound,
            apiErrorFrom { api.recordClip(patientId = patientId, cameraId = cameraId) },
        )
    }
}
