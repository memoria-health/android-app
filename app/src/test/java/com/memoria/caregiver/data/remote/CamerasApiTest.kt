@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.UpdateCameraRequest
import com.memoria.caregiver.data.model.WanderingDoorInput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.ExperimentalTime

/**
 * What [CamerasApi] actually puts on the wire. Spec: the "Cameras" and
 * "Wandering exit-door picker" sections of `Services/NemaraAPIClient.swift`,
 * plus the endpoint inventory in PARITY.md §10.
 *
 * Every test asserts the request — method, path, query, body — rather than that
 * the method returned what the fake server was told to return. A wrong verb, a
 * missing path segment, or a request key the backend does not recognise are the
 * failures a port actually produces, and none of them are visible from the
 * response side. The backend uses pydantic `extra="forbid"`, so an extra key in
 * a body is a 422 in production and a silently passing test here unless the JSON
 * is compared whole.
 */
class CamerasApiTest {

    private val patientId = "11111111-1111-1111-1111-111111111111"
    private val cameraId = "22222222-2222-2222-2222-222222222222"

    /** The requests the fake server saw, in order. */
    private lateinit var recorded: MutableList<HttpRequestData>

    private fun apiReturning(vararg bodies: String): CamerasApi {
        recorded = mutableListOf()
        var next = 0
        val engine = MockEngine { request ->
            recorded += request
            val body = bodies.getOrElse(next) { "" }
            next += 1
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(NemaraJson.json) }
        }
        return CamerasApi(
            HttpTransport(client = client, baseUrl = "https://api.example.test", tokenProvider = { "jwt" }),
        )
    }

    private val request: HttpRequestData get() = recorded.single()
    private val method: String get() = request.method.value
    private val path: String get() = request.url.encodedPath

    private suspend fun requestBody(): JsonObject =
        Json.parseToJsonElement(String(request.body.toByteArray())) as JsonObject

    /** No body at all, which is different from an empty JSON object. */
    private suspend fun requestBodyIsAbsent(): Boolean =
        request.body.toByteArray().isEmpty()

    // A camera row as the REST endpoint sends it. Kept minimal but complete:
    // every non-optional field of CameraWire has to be here or decoding throws.
    private fun cameraJson(id: String, displayId: Int, room: String) = """
        {"id":"$id","patient_id":"$patientId","display_id":$displayId,"room":"$room",
         "icon_name":"tv","online":true,"disabled":false,"ai_paused":false,
         "desired_state":"connected","source_type":"iphone",
         "livekit_identity":"cam_abc12345","created_at":"2026-07-01T10:00:00.000000Z",
         "paired":true}
    """.trimIndent()

    // ---- GET /v1/patients/{id}/cameras ----

    @Test fun `fetchCameras gets the patient's camera collection`() = runTest {
        val api = apiReturning("[${cameraJson(cameraId, 1, "Kitchen")}]")

        val cameras = api.fetchCameras(patientId)

        assertEquals("GET", method)
        assertEquals("/v1/patients/$patientId/cameras", path)
        assertEquals("", request.url.encodedQuery)
        assertEquals(listOf("Kitchen"), cameras.map { it.room })
    }

    /**
     * Swift builds every path with `id.uuidString.lowercased()`, and
     * `UUID.uuidString` is upper-case. The backend's routes are case-sensitive,
     * so an upper-case id would 404 on iOS too if the lowercasing were dropped.
     */
    @Test fun `an upper-case id is lowercased into the path, as Swift does`() = runTest {
        val api = apiReturning("[]")

        api.fetchCameras(patientId.uppercase())

        assertEquals("/v1/patients/$patientId/cameras", path)
    }

    /**
     * The Swift's `fetchCameras` returns the server's array untouched and
     * `loadCameras` assigns it without sorting. The displayId sort belongs to
     * the cache-writing paths, so a fetch that reordered rows here would make
     * this layer disagree with the poll's diff.
     */
    @Test fun `fetchCameras preserves the server's order, it does not sort by displayId`() = runTest {
        val api = apiReturning(
            "[${cameraJson(cameraId, 3, "Hall")}," +
                "${cameraJson("33333333-3333-3333-3333-333333333333", 1, "Kitchen")}]",
        )

        val cameras = api.fetchCameras(patientId)

        assertEquals(listOf(3, 1), cameras.map { it.displayId })
    }

    // ---- POST /v1/patients/{id}/cameras/pairing-code ----

    @Test fun `createPairingCode posts room and icon_name and nothing else`() = runTest {
        val api = apiReturning(
            """{"pairing_code":"482915","expires_at":"2026-07-01T10:10:00.000000Z",
                "camera_id":"$cameraId"}""",
        )

        val response = api.createPairingCode(patientId, room = "Living Room")

        assertEquals("POST", method)
        assertEquals("/v1/patients/$patientId/cameras/pairing-code", path)
        assertEquals(
            Json.parseToJsonElement("""{"room":"Living Room","icon_name":"tv"}"""),
            requestBody(),
        )
        assertEquals("482915", response.pairingCode)
        assertEquals(cameraId, response.cameraId)
    }

    @Test fun `createPairingCode sends the caller's icon over the tv default`() = runTest {
        val api = apiReturning(
            """{"pairing_code":"100000","expires_at":"2026-07-01T10:10:00.000000Z",
                "camera_id":"$cameraId"}""",
        )

        api.createPairingCode(patientId, room = "Bedroom", iconName = "bed.double")

        assertEquals(
            Json.parseToJsonElement("""{"room":"Bedroom","icon_name":"bed.double"}"""),
            requestBody(),
        )
    }

    // ---- POST /v1/cameras/{id}/pairing-code ----

    /**
     * A different path from [createPairingCode] — camera-scoped, not
     * patient-scoped — and Swift sends it through `sendNoBody`, so there must be
     * no JSON body. An empty object would be an unknown-shape body to an
     * endpoint that takes none.
     */
    @Test fun `regeneratePairingCode posts to the camera path with no body`() = runTest {
        val api = apiReturning(
            """{"pairing_code":"999999","expires_at":"2026-07-01T10:10:00.000000Z",
                "camera_id":"$cameraId"}""",
        )

        val response = api.regeneratePairingCode(cameraId)

        assertEquals("POST", method)
        assertEquals("/v1/cameras/$cameraId/pairing-code", path)
        assertTrue("expected no request body", requestBodyIsAbsent())
        assertEquals("999999", response.pairingCode)
    }

    /** 409 means "already paired". It must reach the caller as Conflict, which the UI reads. */
    @Test fun `regeneratePairingCode surfaces the already-paired conflict`() = runTest {
        recorded = mutableListOf()
        val engine = MockEngine { request ->
            recorded += request
            respond(
                content = """{"detail":"camera_already_paired"}""",
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = CamerasApi(
            HttpTransport(
                client = HttpClient(engine) { install(ContentNegotiation) { json(NemaraJson.json) } },
                baseUrl = "https://api.example.test",
                tokenProvider = { null },
            ),
        )

        val error = assertThrows(ApiError::class.java) {
            kotlinx.coroutines.runBlocking { api.regeneratePairingCode(cameraId) }
        }
        assertEquals(ApiError.Conflict("camera_already_paired"), error)
    }

    // ---- GET /v1/cameras/discovered ----

    @Test fun `listDiscoveredCameras unwraps the pis envelope`() = runTest {
        val api = apiReturning(
            """{"pis":[{"discovery_id":"d1","hardware_id":"hw1","device_label":"Pi in hall",
                "current_url":"rtsp://10.0.0.9/live","first_seen_at":"2026-07-01T09:00:00.000000Z",
                "last_seen_at":"2026-07-01T09:04:00.000000Z"}]}""",
        )

        val discovered = api.listDiscoveredCameras()

        assertEquals("GET", method)
        assertEquals("/v1/cameras/discovered", path)
        assertEquals(listOf("Pi in hall"), discovered.map { it.deviceLabel })
        // `id` is an alias for discovery_id, which is what pi-provision wants.
        assertEquals("d1", discovered.single().id)
        assertNull(discovered.single().currentAudioUrl)
    }

    // ---- POST /v1/patients/{id}/cameras/pi-provision ----

    @Test fun `provisionSensorCamera posts room, icon_name and discovery_id`() = runTest {
        val api = apiReturning(
            """{"camera_id":"$cameraId","patient_id":"$patientId",
                "livekit_identity":"cam_9f8e7d6c","livekit_room":"patient_$patientId"}""",
        )

        val response = api.provisionSensorCamera(
            patientId = patientId,
            room = "Hallway",
            discoveryId = "d1",
        )

        assertEquals("POST", method)
        assertEquals("/v1/patients/$patientId/cameras/pi-provision", path)
        assertEquals(
            Json.parseToJsonElement(
                """{"room":"Hallway","icon_name":"tv","discovery_id":"d1"}""",
            ),
            requestBody(),
        )
        assertEquals(cameraId, response.cameraId)
        assertEquals("cam_9f8e7d6c", response.livekitIdentity)
    }

    // ---- DELETE /v1/cameras/{id} ----

    @Test fun `deleteCamera deletes the camera path with no query`() = runTest {
        val api = apiReturning("")

        api.deleteCamera(cameraId)

        assertEquals("DELETE", method)
        assertEquals("/v1/cameras/$cameraId", path)
        assertEquals("", request.url.encodedQuery)
    }

    // ---- POST /v1/cameras/{id}/unpair ----

    /**
     * Unpair is a POST to a sub-path, NOT a DELETE. Confusing the two would
     * destroy the row the caregiver expected to keep, along with its room,
     * detector assignments and history.
     */
    @Test fun `unpairCamera posts to the unpair sub-path, it does not delete`() = runTest {
        val api = apiReturning("")

        api.unpairCamera(cameraId)

        assertEquals("POST", method)
        assertEquals("/v1/cameras/$cameraId/unpair", path)
        assertTrue("expected no request body", requestBodyIsAbsent())
    }

    // ---- PATCH /v1/cameras/{id} ----

    @Test fun `updateCamera patches and returns the server's row`() = runTest {
        val api = apiReturning(cameraJson(cameraId, 1, "Kitchen"))

        val updated = api.updateCamera(cameraId, UpdateCameraRequest(disabled = true))

        assertEquals("PATCH", method)
        assertEquals("/v1/cameras/$cameraId", path)
        assertEquals(Json.parseToJsonElement("""{"disabled":true}"""), requestBody())
        assertEquals(Camera.DesiredState.CONNECTED, updated.desiredState)
    }

    /**
     * A PATCH body means "change exactly the fields I sent". Sending
     * `ai_paused: null` alongside would ask the backend to clear a column the
     * caregiver did not touch, and `extra="forbid"` does not save us here
     * because the key is a real one.
     */
    @Test fun `updateCamera omits the fields the caller left null`() = runTest {
        val api = apiReturning(cameraJson(cameraId, 1, "Kitchen"))

        api.updateCamera(
            cameraId,
            UpdateCameraRequest(desiredState = Camera.DesiredState.DISCONNECTED),
        )

        assertEquals(
            Json.parseToJsonElement("""{"desired_state":"disconnected"}"""),
            requestBody(),
        )
    }

    // ---- GET / PUT /v1/cameras/{id}/detectors ----

    @Test fun `getCameraDetectors unwraps the detector_ids envelope`() = runTest {
        val api = apiReturning("""{"detector_ids":["d-1","d-2"]}""")

        val ids = api.getCameraDetectors(cameraId)

        assertEquals("GET", method)
        assertEquals("/v1/cameras/$cameraId/detectors", path)
        assertEquals(listOf("d-1", "d-2"), ids)
    }

    /** PUT, because this replaces the whole assignment set rather than adding to it. */
    @Test fun `setCameraDetectors puts the full id list`() = runTest {
        val api = apiReturning("""{"detector_ids":["d-1"]}""")

        api.setCameraDetectors(cameraId, listOf("d-1"))

        assertEquals("PUT", method)
        assertEquals("/v1/cameras/$cameraId/detectors", path)
        assertEquals(Json.parseToJsonElement("""{"detector_ids":["d-1"]}"""), requestBody())
    }

    /**
     * The result comes from the RESPONSE, not from the argument. The server is
     * free to normalise the set, and echoing the request back would hide that
     * from the UI — which is exactly the case where the toggles would then
     * disagree with what is stored.
     */
    @Test fun `setCameraDetectors returns the server's list, not the one it was given`() = runTest {
        val api = apiReturning("""{"detector_ids":["d-1"]}""")

        val result = api.setCameraDetectors(cameraId, listOf("d-1", "d-2", "d-1"))

        assertEquals(listOf("d-1"), result)
    }

    /** An empty list is a real instruction: unassign everything from this camera. */
    @Test fun `setCameraDetectors sends an empty array rather than omitting the key`() = runTest {
        val api = apiReturning("""{"detector_ids":[]}""")

        val result = api.setCameraDetectors(cameraId, emptyList())

        assertEquals(Json.parseToJsonElement("""{"detector_ids":[]}"""), requestBody())
        assertEquals(emptyList<String>(), result)
    }

    // ---- POST /v1/detect-doors ----

    /**
     * Plain JSON with the image inline, not multipart, and the path carries no
     * camera or patient — the endpoint only looks at the pixels it was handed.
     */
    @Test fun `detectDoors posts the base64 frame as a JSON body`() = runTest {
        val api = apiReturning("""{"doors":[{"box":[0.1,0.2,0.3,0.4],"label":"front door"}]}""")

        val doors = api.detectDoors("AAAABBBB")

        assertEquals("POST", method)
        assertEquals("/v1/detect-doors", path)
        assertEquals(Json.parseToJsonElement("""{"image_base64":"AAAABBBB"}"""), requestBody())
        assertEquals(listOf("front door"), doors.map { it.label })
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), doors.single().box)
    }

    // ---- GET /v1/patients/{id}/wandering-doors ----

    @Test fun `wanderingDoors gets the patient's per-camera configs`() = runTest {
        val api = apiReturning(
            """[{"camera_id":"$cameraId",
                 "door_config":{"doors":[{"box":[0.1,0.1,0.2,0.2],"label":"front","source":"vlm"}],
                                "no_exit_door":false}}]""",
        )

        val configs = api.wanderingDoors(patientId)

        assertEquals("GET", method)
        assertEquals("/v1/patients/$patientId/wandering-doors", path)
        assertEquals(cameraId, configs.single().cameraId)
        assertEquals("front", configs.single().doorConfig?.doors?.single()?.label)
    }

    // ---- POST /v1/cameras/{id}/wandering-door ----

    /** Singular `wandering-door` on the camera path; the patient GET is plural. */
    @Test fun `setCameraWanderingDoors posts doors and no_exit_door to the camera path`() = runTest {
        val api = apiReturning(
            """{"camera_id":"$cameraId","door_config":{"doors":[],"no_exit_door":true}}""",
        )

        val saved = api.setCameraWanderingDoors(
            cameraId = cameraId,
            doors = listOf(
                WanderingDoorInput(box = listOf(0.5, 0.5, 0.1, 0.2), label = "patio", source = "manual"),
            ),
            noExitDoor = false,
        )

        assertEquals("POST", method)
        assertEquals("/v1/cameras/$cameraId/wandering-door", path)
        assertEquals(
            Json.parseToJsonElement(
                """{"doors":[{"box":[0.5,0.5,0.1,0.2],"label":"patio","source":"manual"}],
                    "no_exit_door":false}""",
            ),
            requestBody(),
        )
        assertTrue(saved.doorConfig!!.noExitDoor)
    }

    /**
     * "No door here" is a saved choice, so `no_exit_door: true` has to travel
     * with an empty `doors` array. Omitting either key is a 422 — both are
     * required on the backend schema.
     */
    @Test fun `setCameraWanderingDoors sends the no-exit-door choice with an empty doors array`() = runTest {
        val api = apiReturning(
            """{"camera_id":"$cameraId","door_config":{"doors":[],"no_exit_door":true}}""",
        )

        api.setCameraWanderingDoors(cameraId, doors = emptyList(), noExitDoor = true)

        assertEquals(
            Json.parseToJsonElement("""{"doors":[],"no_exit_door":true}"""),
            requestBody(),
        )
    }

    /** A door with no label leaves the key off entirely rather than sending null. */
    @Test fun `a door without a label omits the key`() = runTest {
        val api = apiReturning("""{"camera_id":"$cameraId"}""")

        api.setCameraWanderingDoors(
            cameraId,
            doors = listOf(WanderingDoorInput(box = listOf(0.0, 0.0, 1.0, 1.0), source = "vlm")),
            noExitDoor = false,
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"doors":[{"box":[0.0,0.0,1.0,1.0],"source":"vlm"}],"no_exit_door":false}""",
            ),
            requestBody(),
        )
    }

    // ---- The authorization header, which every one of these needs ----

    @Test fun `the bearer token rides on a camera request`() = runTest {
        val api = apiReturning("[]")

        api.fetchCameras(patientId)

        assertEquals("Bearer jwt", request.headers[HttpHeaders.Authorization])
    }
}
