package com.memoria.caregiver.ui.cameras

import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CameraListViewModel] behavior. Spec:
 * `Features/Patient/Cameras/PatientCamerasView.swift` and the doc comments on
 * `CameraUiState.kt`.
 *
 * The client under it is the real one, driven by a Ktor `MockEngine` through the
 * real [HttpTransport], as `RosterViewModelTest` and the `data/remote` tests
 * are. A fake client would only prove that this file and the fake agree — and
 * the property under test is precisely that a row written into the client's
 * cache surfaces here with nothing plumbed in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraListViewModelTest {

    private companion object {
        const val P1 = "11111111-1111-4111-8111-111111111111"
        const val P2 = "22222222-2222-4222-8222-222222222222"

        /** Every non-optional field of the camera wire shape has to be present. */
        fun cameraJson(
            id: String,
            patientId: String,
            displayId: Int,
            room: String,
        ) = """
            {"id":"$id","patient_id":"$patientId","display_id":$displayId,"room":"$room",
             "icon_name":"tv","online":true,"disabled":false,"ai_paused":false,
             "desired_state":"connected","source_type":"iphone",
             "livekit_identity":"cam_abc12345","created_at":"2026-07-01T10:00:00.000000Z",
             "paired":true}
        """.trimIndent()
    }

    /** A fake backend whose camera answer each test rewrites per patient. */
    private class Backend {

        val cameras: MutableMap<String, String> = mutableMapOf()

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        val owner = path.removePrefix("/v1/patients/").substringBefore('/')
                        when {
                            path.endsWith("/cameras") ->
                                respond(cameras[owner] ?: "[]", headers = json)
                            else -> respond("[]", headers = json)
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(NemaraJson.json) }
                },
                baseUrl = "https://backend.test",
                tokenProvider = { "test-jwt" },
            ),
        )
    }

    private val backend = Backend()

    /**
     * One dispatcher, shared by `Dispatchers.Main` and by `runTest` below, so
     * there is exactly one scheduler in play.
     *
     * `StandardTestDispatcher` rather than an unconfined one, deliberately.
     * Ktor's `MockEngine` really does hop threads, and an unconfined dispatcher
     * resumes the `stateIn` sharing coroutine on whichever thread completed the
     * call — through that thread's event loop, which the test cannot drain. The
     * result was a `state.value` that lagged its own cache, intermittently.
     * Queueing every Main resumption in one scheduler makes draining it exact.
     */
    private val mainDispatcher = StandardTestDispatcher()

    @Before fun setUpMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    /**
     * Let Main publish what the client just wrote, for the reason above. This
     * screen starts no work of its own, so there is nothing else to wait for.
     */
    private fun idle() = mainDispatcher.scheduler.advanceUntilIdle()

    @After fun tearDownMain() {
        Dispatchers.resetMain()
    }

    /**
     * `env.api.camerasByPatient[patientId] ?? []`, and nothing wider. Two
     * patients loaded at once must not bleed into one another's tab.
     */
    @Test fun `only this patient's cameras reach the state`() = runTest(mainDispatcher) {
        backend.cameras[P1] = "[${cameraJson("cam-1", P1, 1, "Kitchen")}]"
        backend.cameras[P2] = "[${cameraJson("cam-2", P2, 1, "Hallway")}]"
        val vm = CameraListViewModel(backend.api, P1)

        backend.api.loadCameras(P1)
        backend.api.loadCameras(P2)
        idle()

        assertEquals(listOf("Kitchen"), vm.state.value.cameras.map { it.room })
    }

    /**
     * The order is the server's. The two paths that reorder cameras — the poll's
     * write-back and the Realtime handlers — sort by `displayId` before writing,
     * so a sort here would hide a cache bug rather than show it.
     */
    @Test fun `the server's order is preserved, not re-sorted by display id`() = runTest(mainDispatcher) {
        backend.cameras[P1] = "[" + listOf(
            cameraJson("cam-3", P1, 3, "Porch"),
            cameraJson("cam-1", P1, 1, "Kitchen"),
            cameraJson("cam-2", P1, 2, "Hallway"),
        ).joinToString(",") + "]"
        val vm = CameraListViewModel(backend.api, P1)

        backend.api.loadCameras(P1)
        idle()

        assertEquals(listOf(3, 1, 2), vm.state.value.cameras.map { it.displayId })
    }

    /**
     * Derived, never snapshotted. A camera deleted on another device arrives as a
     * Realtime DELETE that rewrites `camerasByPatient`; `deleteCamera` does the
     * same rewrite locally, which is what this stands in for. A state copied once
     * at construction would still be showing the row.
     */
    @Test fun `a camera removed from the cache leaves the state`() = runTest(mainDispatcher) {
        backend.cameras[P1] = "[" +
            cameraJson("cam-1", P1, 1, "Kitchen") + "," +
            cameraJson("cam-2", P1, 2, "Hallway") + "]"
        val vm = CameraListViewModel(backend.api, P1)
        backend.api.loadCameras(P1)
        idle()
        assertEquals(2, vm.state.value.cameras.size)

        backend.api.deleteCamera("cam-1")
        idle()

        assertEquals(listOf("Hallway"), vm.state.value.cameras.map { it.room })
    }

    /**
     * The empty state is the whole "No sensors yet" card, so it has to be the
     * value before anything has loaded as well as after everything is gone.
     */
    @Test fun `a patient with no cameras yet reads empty`() = runTest(mainDispatcher) {
        val vm = CameraListViewModel(backend.api, P1)

        assertTrue(vm.state.value.cameras.isEmpty())
    }
}
