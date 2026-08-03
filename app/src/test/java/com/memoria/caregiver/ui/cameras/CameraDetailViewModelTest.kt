package com.memoria.caregiver.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.realtime.CameraLiveState
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CameraDetailViewModel] behavior. Spec:
 * `Features/Patient/Cameras/CameraDetailView.swift`, PARITY.md §10 and §21, and
 * the doc comments on `CameraUiState.kt`.
 *
 * The real client under a Ktor `MockEngine`, as `RosterViewModelTest` is: the
 * things worth pinning here — that a delete empties the screen, that a reconnect
 * opens exactly one "Connecting…" window — are properties of this object plus
 * the client together, and a fake client would define them away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraDetailViewModelTest {

    private companion object {
        const val PATIENT = "11111111-1111-4111-8111-111111111111"
        const val CAMERA = "22222222-2222-4222-8222-222222222222"
        const val OTHER_CAMERA = "33333333-3333-4333-8333-333333333333"
        const val FALL = "44444444-4444-4444-8444-444444444444"
        const val WANDERING = "55555555-5555-4555-8555-555555555555"

        fun cameraJson(
            id: String = CAMERA,
            displayId: Int = 7,
            room: String = "Kitchen",
            online: Boolean = true,
            paired: Boolean = true,
            aiPaused: Boolean = false,
            desiredState: String = "connected",
            sourceType: String = "iphone",
        ) = """
            {"id":"$id","patient_id":"$PATIENT","display_id":$displayId,"room":"$room",
             "icon_name":"tv","online":$online,"disabled":false,"ai_paused":$aiPaused,
             "desired_state":"$desiredState","source_type":"$sourceType",
             "livekit_identity":"cam_abc12345","created_at":"2026-07-01T10:00:00.000000Z",
             "paired":$paired}
        """.trimIndent()

        fun detectorJson(
            id: String,
            name: String,
            identifier: String,
            isOn: Boolean = true,
        ) = """
            {"id":"$id","patient_id":"$PATIENT","name":"$name","icon_name":"eye",
             "description":"Watches something.","category":"safety","source":"preset",
             "alert_trigger":"leading","is_sustained":false,"when_scope":"ALWAYS",
             "sensitivity":"standard","is_on":$isOn,"identifier":"$identifier",
             "created_at":"2026-05-20T10:00:00.000000+00:00",
             "updated_at":"2026-05-20T10:00:00.000000+00:00"}
        """.trimIndent()
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    /**
     * Wall clock for [CameraLiveState], counted rather than merely fixed.
     *
     * `beginReconnectWindow` is the only thing on the client's side of a
     * `reconnect()` that reads it, so the number of reads during one action is
     * the number of windows opened — which is how the "do not open a second one
     * here" rule becomes observable at all.
     */
    private var clockReads = 0
    private val nowMillis = 1_700_000_000_000L
    private val live = CameraLiveState(now = { clockReads++; nowMillis })

    private class Backend(live: CameraLiveState) {

        var cameras: String = "[]"
        var detectors: String = "[]"

        /** `GET /v1/cameras/{id}/detectors`. */
        var assignments: String = """{"detector_ids":[]}"""
        var assignmentsStatus: HttpStatusCode = HttpStatusCode.OK

        /** `PUT /v1/cameras/{id}/detectors` — the echoed, possibly normalised set. */
        var settledAssignments: String = """{"detector_ids":[]}"""
        var setStatus: HttpStatusCode = HttpStatusCode.OK

        /** Held open, so a test can observe the in-flight window exactly. */
        var setGate: CompletableDeferred<Unit>? = null

        /** `PATCH /v1/cameras/{id}`. */
        var updated: String = cameraJson()
        var updateStatus: HttpStatusCode = HttpStatusCode.OK

        /** Held open, so a state flip can be observed mid-flight. */
        var updateGate: CompletableDeferred<Unit>? = null

        var unpairStatus: HttpStatusCode = HttpStatusCode.OK
        var deleteStatus: HttpStatusCode = HttpStatusCode.OK

        /** `GET /v1/patients/{id}/wandering-doors`. */
        var wanderingDoors: String = "[]"
        var doorsStatus: HttpStatusCode = HttpStatusCode.OK

        /** Every request the fake server saw, as "METHOD path". */
        val seen: MutableList<String> = mutableListOf()

        /** Bodies of the requests that carried one, in the same order as [seen]. */
        val bodies: MutableList<String> = mutableListOf()

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        val method = request.method.value
                        seen += "$method $path"
                        bodies += String(request.body.toByteArray())
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        when {
                            path.endsWith("/wandering-doors") ->
                                respond(wanderingDoors, doorsStatus, json)
                            path.endsWith("/cameras") -> respond(cameras, headers = json)
                            path.endsWith("/detectors") && method == "GET" &&
                                path.startsWith("/v1/patients") ->
                                respond(detectors, headers = json)
                            path.endsWith("/detectors") && method == "GET" ->
                                respond(assignments, assignmentsStatus, json)
                            path.endsWith("/detectors") && method == "PUT" -> {
                                setGate?.await()
                                respond(settledAssignments, setStatus, json)
                            }
                            path.endsWith("/unpair") -> respond("", unpairStatus, json)
                            method == "PATCH" -> {
                                updateGate?.await()
                                respond(updated, updateStatus, json)
                            }
                            method == "DELETE" -> respond("", deleteStatus, json)
                            else -> respond("[]", headers = json)
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(NemaraJson.json) }
                },
                baseUrl = "https://backend.test",
                tokenProvider = { "test-jwt" },
            ),
            live = live,
        )
    }

    private lateinit var backend: Backend

    /** Set by [viewModel]'s `onDeleted`, which is the screen's `dismiss()`. */
    private var dismissals = 0

    private fun viewModel(): CameraDetailViewModel {
        val vm = CameraDetailViewModel(
            api = backend.api,
            patientId = PATIENT,
            cameraId = CAMERA,
            onDeleted = { dismissals++ },
        )
        // `stateIn(started = Eagerly)` is already collecting by now, and that
        // coroutine never ends.
        background = vm.viewModelScope.coroutineContext.job.children.toSet()
        return vm
    }

    /** Compares the last request body as JSON, so key order and spacing cannot fail it. */
    private fun assertLastBody(expected: String) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(backend.bodies.last()),
        )
    }

    private fun cachedCamera(id: String = CAMERA): Camera =
        backend.api.camerasByPatient[PATIENT].orEmpty().first { it.id == id }

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

    @Before fun setUp() {
        backend = Backend(live)
        Dispatchers.setMain(mainDispatcher)
    }

    /**
     * Let Main publish what a direct client call just wrote. Only needed where a
     * test drives the cache itself rather than through the view model; [settle]
     * does the same drain for the view model's own actions.
     */
    private fun idle() = mainDispatcher.scheduler.advanceUntilIdle()

    @After fun tearDownMain() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Derived halves
    // -----------------------------------------------------------------------

    /**
     * The camera is looked up by id inside the patient's list, so a delete
     * landing from anywhere — Realtime, the poll, this screen — turns it null and
     * the screen stops offering controls for a row that is gone.
     */
    @Test fun `the camera and the patient's detectors are read off the cache`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson()},${cameraJson(id = OTHER_CAMERA, room = "Porch")}]"
        backend.detectors = "[${detectorJson(FALL, "Falls", "fall")}]"
        val vm = viewModel()

        backend.api.loadCameras(PATIENT)
        backend.api.loadDetectors(PATIENT)
        idle()

        assertEquals("Kitchen", vm.state.value.camera?.room)
        assertEquals(listOf("Falls"), vm.state.value.detectors.map { it.name })
    }

    /** Wandering is excluded from the plain toggles; it owns the exit-door section. */
    @Test fun `the wandering detector gates the exit-door section`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson()}]"
        backend.detectors = "[" +
            detectorJson(FALL, "Falls", "fall") + "," +
            detectorJson(WANDERING, "Left the house", "wandering") + "]"
        val vm = viewModel()

        backend.api.loadCameras(PATIENT)
        backend.api.loadDetectors(PATIENT)
        idle()

        assertEquals(listOf("Falls"), vm.state.value.togglableDetectors.map { it.name })
        assertTrue(vm.state.value.showsExitDoors)
    }

    // -----------------------------------------------------------------------
    // load()
    // -----------------------------------------------------------------------

    /**
     * Both per-screen fetches. The door config is per SENSOR, so the entry for
     * another camera under the same patient must not be picked up.
     */
    @Test fun `load reads the assignment set and this sensor's exit doors`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson()}]"
        backend.assignments = """{"detector_ids":["$FALL"]}"""
        backend.wanderingDoors = """
            [{"camera_id":"$OTHER_CAMERA","door_config":{"doors":[{"label":"Front"}]}},
             {"camera_id":"$CAMERA","door_config":{"doors":[{"label":"Back"},{"label":"Side"}]}}]
        """.trimIndent()
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)

        vm.load()

        vm.settle()

        assertEquals(setOf(FALL), vm.state.value.assignedDetectorIds)
        assertEquals(listOf("Back", "Side"), vm.state.value.doorConfig?.doors?.map { it.label })
        assertTrue(vm.state.value.isWanderingActiveHere)
    }

    /**
     * The failure sentence is iOS's prefix plus [ApiError.errorDescription]
     * verbatim. That copy is the product's and is pinned by test, so a
     * paraphrase here would be a silent product change.
     */
    @Test fun `a failed assignment load surfaces the API copy behind its prefix`() = runTest(mainDispatcher) {
        backend.assignmentsStatus = HttpStatusCode.Forbidden
        backend.assignments = ""
        val vm = viewModel()

        vm.load()

        vm.settle()

        assertEquals(
            "Couldn't load detector assignments: " + ApiError.Forbidden.errorDescription,
            vm.state.value.assignmentsError,
        )
    }

    /**
     * The exit-door read is silent on failure, exactly as iOS's
     * `guard let list = try? …` is. It is one row on a screen full of working
     * controls, and the caregiver did not ask for it.
     */
    @Test fun `a failed door read says nothing and leaves the door config unset`() = runTest(mainDispatcher) {
        backend.doorsStatus = HttpStatusCode.InternalServerError
        backend.wanderingDoors = ""
        val vm = viewModel()

        vm.load()

        vm.settle()

        assertNull(vm.state.value.doorConfig)
        assertNull(vm.state.value.actionError)
        assertNull(vm.state.value.assignmentsError)
    }

    // -----------------------------------------------------------------------
    // Detector assignment
    // -----------------------------------------------------------------------

    /**
     * The PUT is a whole-set replace and the server is free to normalise it, so
     * the response — not the optimistic guess — is adopted as truth.
     */
    @Test fun `toggling adopts the set the server echoed back`() = runTest(mainDispatcher) {
        backend.detectors = "[${detectorJson(FALL, "Falls", "fall")}]"
        backend.settledAssignments = """{"detector_ids":["$FALL","$WANDERING"]}"""
        val vm = viewModel()
        backend.api.loadDetectors(PATIENT)
        val fall = backend.api.detectorsByPatient[PATIENT]!!.first()

        vm.toggleDetector(fall)

        vm.settle()

        assertEquals("PUT /v1/cameras/$CAMERA/detectors", backend.seen.last())
        assertEquals(setOf(FALL, WANDERING), vm.state.value.assignedDetectorIds)
        assertNull(vm.state.value.togglingDetectorId)
    }

    /**
     * The rollback target is the set captured BEFORE the optimistic write. Reading
     * it in the catch instead would read the optimistic value back and the failed
     * flip would stick.
     */
    @Test fun `a failed toggle rolls back and surfaces the API copy`() = runTest(mainDispatcher) {
        backend.detectors = "[${detectorJson(FALL, "Falls", "fall")}]"
        backend.assignments = """{"detector_ids":["$FALL"]}"""
        backend.setStatus = HttpStatusCode.Unauthorized
        backend.settledAssignments = ""
        val vm = viewModel()
        backend.api.loadDetectors(PATIENT)
        vm.load()
        vm.settle()
        val fall = backend.api.detectorsByPatient[PATIENT]!!.first()

        vm.toggleDetector(fall)

        vm.settle()

        assertEquals(setOf(FALL), vm.state.value.assignedDetectorIds)
        assertEquals(
            "Couldn't update: " + ApiError.Unauthorized.errorDescription,
            vm.state.value.assignmentsError,
        )
        assertNull(vm.state.value.togglingDetectorId)
    }

    /**
     * `guard togglingDetectorId == nil else { return }`. The screen only disables
     * the row that is saving, so a tap on a different row is reachable — and two
     * overlapping whole-set replaces would settle on whichever landed last.
     */
    @Test fun `a second toggle while one is in flight is refused`() = runTest(mainDispatcher) {
        backend.detectors = "[" +
            detectorJson(FALL, "Falls", "fall") + "," +
            detectorJson(WANDERING, "Left the house", "wandering") + "]"
        val gate = CompletableDeferred<Unit>()
        backend.setGate = gate
        val vm = viewModel()
        backend.api.loadDetectors(PATIENT)
        val (fall, wandering) = backend.api.detectorsByPatient[PATIENT]!!

        vm.toggleDetector(fall)
        // Set synchronously, before the launch, which is what makes it a guard
        // rather than a hope. The drain is only so `state` has published it.
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(FALL, vm.state.value.togglingDetectorId)
        vm.toggleDetector(wandering)

        gate.complete(Unit)
        vm.settle()

        assertEquals(1, backend.seen.count { it.startsWith("PUT") })
    }

    // -----------------------------------------------------------------------
    // The two state flips, and the reconnect window
    // -----------------------------------------------------------------------

    @Test fun `pausing AI sends the negation of the cached flag`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson(aiPaused = false)}]"
        backend.updated = cameraJson(aiPaused = true)
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)

        vm.toggleAiPause()

        vm.settle()

        assertEquals("PATCH /v1/cameras/$CAMERA", backend.seen.last())
        assertLastBody("""{"ai_paused":true}""")
        assertTrue(vm.state.value.camera!!.aiPaused)
        assertFalse(vm.state.value.isTogglingAiPause)
    }

    @Test fun `disconnect sends the desired state and surfaces its own copy on failure`() =
        runTest(mainDispatcher) {
            backend.cameras = "[${cameraJson()}]"
            backend.updateStatus = HttpStatusCode.NotFound
            backend.updated = ""
            val vm = viewModel()
            backend.api.loadCameras(PATIENT)

            vm.disconnect()

            vm.settle()

            assertLastBody("""{"desired_state":"disconnected"}""")
            assertEquals(
                "Couldn't disconnect: " + ApiError.NotFound.errorDescription,
                vm.state.value.actionError,
            )
            assertFalse(vm.state.value.isTogglingDisconnect)
        }

    /**
     * **Exactly one reconnect window.** `NemaraApiClient.updateCamera` opens it on
     * the disconnected → connected edge, comparing against the cached row before
     * replacing it. Opening a second one here would push the ten-second deadline
     * out by however long the round trip took, so a sensor that never comes back
     * would sit on "Connecting…" past the bound PARITY.md §10 fixes.
     *
     * The clock is read once per window opened, so one read is one window.
     */
    @Test fun `reconnect opens exactly one connecting window`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson(desiredState = "disconnected", online = false)}]"
        backend.updated = cameraJson(desiredState = "connected", online = false)
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)
        clockReads = 0

        vm.reconnect()

        vm.settle()

        assertLastBody("""{"desired_state":"connected"}""")
        assertEquals(1, clockReads)
    }

    /**
     * What that window is for: the tile reads "Connecting…" rather than nothing
     * while the wall phone rejoins. And a sensor that has been online before is
     * NOT "preparing" — `markEverOnline` from the earlier load is what keeps an
     * established camera out of the first-connect state.
     */
    @Test fun `a reconnected sensor reads as connecting, not as preparing`() = runTest(mainDispatcher) {
        // Seen online once, which is what `loadCameras` records.
        backend.cameras = "[${cameraJson(online = true)}]"
        backend.api.loadCameras(PATIENT)
        backend.cameras = "[${cameraJson(desiredState = "disconnected", online = false)}]"
        backend.api.loadCameras(PATIENT)
        backend.updated = cameraJson(desiredState = "connected", online = false)
        val vm = viewModel()

        vm.reconnect()

        vm.settle()

        val camera = cachedCamera()
        assertTrue(backend.api.isConnecting(camera))
        assertFalse(backend.api.isPreparing(camera))
    }

    /**
     * The other side of the same pair. A sensor that has never reported online on
     * this install is "preparing" — still coming up for the first time — and is
     * not "connecting", because no reconnect was ever asked for.
     */
    @Test fun `a sensor that has never been online reads as preparing`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson(online = false)}]"
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)

        vm.load()

        vm.settle()

        val camera = cachedCamera()
        assertTrue(backend.api.isPreparing(camera))
        assertFalse(backend.api.isConnecting(camera))
    }

    // -----------------------------------------------------------------------
    // Unpair and delete
    // -----------------------------------------------------------------------

    /**
     * Unpair keeps the screen: the row flips to unpaired and the pairing-code
     * section takes over from the unpair button. The reload of the owning patient
     * is the client's, so this asserts the state it produced rather than a second
     * fetch issued here.
     */
    @Test fun `unpair stays on the screen and the pairing-code section takes over`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson(paired = true)}]"
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)
        idle()
        assertTrue(vm.state.value.showsUnpair)

        backend.cameras = "[${cameraJson(paired = false, online = false)}]"
        vm.unpair()
        vm.settle()

        assertTrue(vm.state.value.showsPairingCode)
        assertFalse(vm.state.value.showsUnpair)
        assertFalse(vm.state.value.isUnpairing)
        assertEquals(0, dismissals)
    }

    /**
     * Delete is the one action that leaves. The client drops the row from the
     * cache itself, so the camera is already null by the time the pop is asked
     * for — which is why the screen does not flash "Sensor not found".
     */
    @Test fun `a successful delete empties the camera and pops the screen`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson()}]"
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)

        vm.delete()

        vm.settle()

        assertNull(vm.state.value.camera)
        assertEquals(1, dismissals)
        assertFalse(vm.state.value.isDeleting)
    }

    @Test fun `a failed delete stays put and surfaces the API copy`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson()}]"
        backend.deleteStatus = HttpStatusCode.InternalServerError
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)

        vm.delete()

        vm.settle()

        assertEquals("Kitchen", vm.state.value.camera?.room)
        assertEquals(0, dismissals)
        assertEquals(
            "Couldn't delete: " + ApiError.ServerError(null, null).errorDescription,
            vm.state.value.actionError,
        )
        assertFalse(vm.state.value.isDeleting)
    }

    /**
     * The busy guard repeats the screen's own `enabled` condition, per action.
     * Pause AI, Disconnect and Reconnect share one; unpair and delete each gate
     * on themselves. A blanket guard would silently drop a delete tapped during a
     * reconnect, which the screen permits.
     */
    @Test fun `delete is not blocked by a state flip in flight`() = runTest(mainDispatcher) {
        backend.cameras = "[${cameraJson()}]"
        val vm = viewModel()
        backend.api.loadCameras(PATIENT)
        // Hold the PATCH open so Disconnect is genuinely still in flight.
        val gate = CompletableDeferred<Unit>()
        backend.updateGate = gate
        vm.disconnect()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.isTogglingDisconnect)

        vm.delete()

        gate.complete(Unit)
        vm.settle()

        assertEquals(1, backend.seen.count { it.startsWith("DELETE") })
    }

    /**
     * Coroutines that were already running before any action — the `stateIn`
     * sharing coroutine, which by design never completes. Captured per view
     * model in the factory above, and empty for a view model that has none.
     */
    private var background: Set<Job> = emptySet()

    /**
     * Wait for every coroutine an action started, and for nothing else.
     *
     * The actions are fire-and-forget `viewModelScope.launch`es and Ktor's
     * `MockEngine` genuinely hops threads, so neither an unconfined dispatcher
     * nor advancing virtual time finishes them. Joining the scope's children is
     * exact — no timeouts, no sleeps — as long as the never-ending sharing
     * coroutine is excluded, which is what [background] is for.
     *
     * The scheduler is drained around the joins because the launches are queued
     * on it, and drained once more at the end so `state` has published every
     * update before anything is asserted.
     */
    private suspend fun ViewModel.settle() {
        while (true) {
            mainDispatcher.scheduler.advanceUntilIdle()
            val pending = viewModelScope.coroutineContext.job.children
                .filterNot { it in background }
                .toList()
            if (pending.isEmpty()) break
            pending.joinAll()
        }
        mainDispatcher.scheduler.advanceUntilIdle()
    }
}
