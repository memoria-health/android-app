package com.memoria.caregiver.ui.detectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PatientDetectorsViewModel] behaviour. Spec: `PatientDetectorsView.swift`,
 * `DetectorRow.swift`'s inline actions, and the doc comments on
 * `PatientDetectorsUiState.kt`.
 *
 * The real client under a Ktor `MockEngine`, as `CameraListViewModelTest` and
 * `CameraDetailViewModelTest` are. The properties worth pinning here — that a
 * row written into the cache surfaces with nothing plumbed in between, that a
 * delete empties the row out of the tab, that a failed call leaves the list
 * exactly as it was — belong to this object plus the client together, and a fake
 * client would define them away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientDetectorsViewModelTest {

    private companion object {
        const val P1 = "11111111-1111-4111-8111-111111111111"
        const val P2 = "22222222-2222-4222-8222-222222222222"
        const val FALL = "33333333-3333-4333-8333-333333333333"
        const val KETTLE = "44444444-4444-4444-8444-444444444444"

        /** Every non-optional field of the detector wire shape has to be present. */
        fun detectorJson(
            id: String,
            name: String,
            patientId: String = P1,
            source: String = "preset",
            identifier: String = "fall",
            isOn: Boolean = true,
        ) = """
            {"id":"$id","patient_id":"$patientId","name":"$name","icon_name":"eye",
             "description":"Watches something.","category":"safety","source":"$source",
             "alert_trigger":"leading","is_sustained":false,"when_scope":"ALWAYS",
             "sensitivity":"standard","is_on":$isOn,"identifier":"$identifier",
             "created_at":"2026-05-20T10:00:00.000000+00:00",
             "updated_at":"2026-05-20T10:00:00.000000+00:00"}
        """.trimIndent()
    }

    /** A fake backend whose detector answer each test rewrites per patient. */
    private class Backend {

        val detectors: MutableMap<String, String> = mutableMapOf()

        /** `PATCH /v1/detectors/{id}` — the server's echo of the whole row. */
        var patched: String = detectorJson(FALL, "Falls")
        var patchStatus: HttpStatusCode = HttpStatusCode.OK

        var deleteStatus: HttpStatusCode = HttpStatusCode.NoContent

        /** Held open, so a delete can be observed mid-flight. */
        var deleteGate: CompletableDeferred<Unit>? = null

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
                        val owner = path.removePrefix("/v1/patients/").substringBefore('/')
                        when {
                            path.endsWith("/wandering-doors") ->
                                respond(wanderingDoors, doorsStatus, json)
                            path.endsWith("/detectors") && method == "GET" ->
                                respond(detectors[owner] ?: "[]", headers = json)
                            method == "PATCH" -> respond(patched, patchStatus, json)
                            method == "DELETE" -> {
                                deleteGate?.await()
                                respond("", deleteStatus, json)
                            }
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

    private lateinit var backend: Backend

    private fun viewModel(patientId: String = P1): PatientDetectorsViewModel {
        val vm = PatientDetectorsViewModel(backend.api, patientId)
        // `stateIn(started = Eagerly)` is already collecting by now, and that
        // coroutine never ends.
        background = vm.viewModelScope.coroutineContext.job.children.toSet()
        return vm
    }

    private fun assertLastBody(expected: String) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(backend.bodies.last()),
        )
    }

    /**
     * One dispatcher, shared by `Dispatchers.Main` and by `runTest` below, so
     * there is exactly one scheduler in play. `StandardTestDispatcher` rather
     * than an unconfined one, for the reason `CameraListViewModelTest` gives:
     * Ktor's `MockEngine` really does hop threads, and an unconfined dispatcher
     * resumes the `stateIn` sharing coroutine on a thread the test cannot drain.
     */
    private val mainDispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        backend = Backend()
        Dispatchers.setMain(mainDispatcher)
    }

    @After fun tearDownMain() {
        Dispatchers.resetMain()
    }

    /** Let Main publish what the client just wrote. */
    private fun idle() = mainDispatcher.scheduler.advanceUntilIdle()

    /**
     * Coroutines that were already running before any action — the `stateIn`
     * sharing coroutine, which by design never completes.
     */
    private var background: Set<Job> = emptySet()

    /**
     * Wait for every coroutine an action started, and for nothing else. Same
     * shape and same reasoning as `CameraDetailViewModelTest.settle`: the
     * actions are fire-and-forget launches over an engine that hops threads, so
     * joining the scope's children is the only exact wait.
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

    // -----------------------------------------------------------------------
    // The derived list
    // -----------------------------------------------------------------------

    /**
     * `env.api.detectorsByPatient[patientId] ?? []`, and nothing wider. Two
     * patients loaded at once must not bleed into one another's tab.
     */
    @Test fun `only this patient's detectors reach the state`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[${detectorJson(FALL, "Falls")}]"
        backend.detectors[P2] = "[${detectorJson(KETTLE, "Kettle", patientId = P2)}]"
        val vm = viewModel()

        backend.api.loadDetectors(P1)
        backend.api.loadDetectors(P2)
        idle()

        assertEquals(listOf("Falls"), vm.state.value.detectors.map { it.name })
    }

    /**
     * The Swift sorts detectors nowhere and `loadDetectors` keeps the server's
     * order deliberately, so a sort here would hide a cache bug rather than show
     * it. The names below are in reverse alphabetical order on purpose.
     */
    @Test fun `the server's order is preserved, not sorted`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[" + listOf(
            detectorJson(FALL, "Wandering"),
            detectorJson(KETTLE, "Falls"),
        ).joinToString(",") + "]"
        val vm = viewModel()

        backend.api.loadDetectors(P1)
        idle()

        assertEquals(listOf("Wandering", "Falls"), vm.state.value.detectors.map { it.name })
    }

    /** The grouping is a filter over that same order, so it survives the split. */
    @Test fun `the two groups keep the server's order too`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[" + listOf(
            detectorJson(FALL, "Wandering", source = "custom"),
            detectorJson(KETTLE, "Falls", source = "custom"),
        ).joinToString(",") + "]"
        val vm = viewModel()

        backend.api.loadDetectors(P1)
        idle()

        assertEquals(listOf("Wandering", "Falls"), vm.state.value.customs.map { it.name })
        assertTrue(vm.state.value.presets.isEmpty())
    }

    /**
     * Derived, never snapshotted. A detector removed anywhere — the library
     * reconcile, another device, this screen's own delete — rewrites the cache,
     * and a state copied once at construction would still be showing the row.
     */
    @Test fun `a detector removed from the cache leaves the state`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[" +
            detectorJson(FALL, "Falls") + "," + detectorJson(KETTLE, "Kettle") + "]"
        val vm = viewModel()
        backend.api.loadDetectors(P1)
        idle()
        assertEquals(2, vm.state.value.detectors.size)

        backend.api.deleteDetector(FALL)
        idle()

        assertEquals(listOf("Kettle"), vm.state.value.detectors.map { it.name })
    }

    /** The empty state is the whole "No detectors yet" card, so it has to be the
     * value before anything has loaded as well as after everything is gone. */
    @Test fun `a patient with no detectors yet reads empty`() = runTest(mainDispatcher) {
        val vm = viewModel()

        assertTrue(vm.state.value.detectors.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Toggle
    // -----------------------------------------------------------------------

    /**
     * The PATCH carries only `is_on` — the backend uses `extra="forbid"` and a
     * PATCH means "change only these" — and the server's echo replaces the
     * cached row, which is what actually moves the switch.
     */
    @Test fun `toggling patches is_on and adopts the server's echo`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[${detectorJson(FALL, "Falls", isOn = true)}]"
        backend.patched = detectorJson(FALL, "Falls", isOn = false)
        val vm = viewModel()
        backend.api.loadDetectors(P1)
        idle()

        vm.toggleDetector(vm.state.value.detectors.first(), isOn = false)
        vm.settle()

        assertEquals("PATCH /v1/detectors/$FALL", backend.seen.last())
        assertLastBody("""{"is_on":false}""")
        assertFalse(vm.state.value.detectors.first().isOn)
        assertEquals(0, vm.state.value.activeCount)
    }

    /**
     * A flip that never lands leaves the switch where it was, because nothing is
     * written locally first. iOS swallows the error the same way — the row has
     * no error surface on either platform — so this also pins that the failure
     * does not escape the launch and take the app with it.
     */
    @Test fun `a failed toggle leaves the row alone`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[${detectorJson(FALL, "Falls", isOn = true)}]"
        backend.patchStatus = HttpStatusCode.InternalServerError
        val vm = viewModel()
        backend.api.loadDetectors(P1)
        idle()

        vm.toggleDetector(vm.state.value.detectors.first(), isOn = false)
        vm.settle()

        assertTrue(vm.state.value.detectors.first().isOn)
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    /**
     * The row's overflow menu goes inert for as long as the DELETE is running,
     * and the row itself disappears when it lands.
     */
    @Test fun `a delete marks its own row busy and then removes it`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[" +
            detectorJson(FALL, "Falls") + "," + detectorJson(KETTLE, "Kettle") + "]"
        val gate = CompletableDeferred<Unit>()
        backend.deleteGate = gate
        val vm = viewModel()
        backend.api.loadDetectors(P1)
        idle()
        val falls = vm.state.value.detectors.first { it.id == FALL }
        val kettle = vm.state.value.detectors.first { it.id == KETTLE }

        vm.deleteDetector(falls)
        idle()

        assertTrue(vm.state.value.rowState(falls, isLast = false).isDeleting)
        assertFalse(vm.state.value.rowState(kettle, isLast = true).isDeleting)

        gate.complete(Unit)
        vm.settle()

        assertEquals(listOf("Kettle"), vm.state.value.detectors.map { it.name })
        assertTrue(vm.state.value.deletingDetectorIds.isEmpty())
    }

    /**
     * The menu is disabled while its delete runs, but the callback is a plain
     * function. A second DELETE of the same id would 404, and the cache cleanup
     * would then find nothing to remove.
     */
    @Test fun `a second delete of the same row is not sent`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[${detectorJson(FALL, "Falls")}]"
        val gate = CompletableDeferred<Unit>()
        backend.deleteGate = gate
        val vm = viewModel()
        backend.api.loadDetectors(P1)
        idle()
        val falls = vm.state.value.detectors.first()

        vm.deleteDetector(falls)
        idle()
        vm.deleteDetector(falls)
        idle()

        gate.complete(Unit)
        vm.settle()

        assertEquals(1, backend.seen.count { it.startsWith("DELETE") })
    }

    /**
     * Cleared in `finally`, as iOS clears it in `defer`. A success-only reset
     * would leave the failed row's menu dead — and the row survives the failure
     * precisely so the caregiver can try again.
     */
    @Test fun `a failed delete leaves the row present and its menu usable`() = runTest(mainDispatcher) {
        backend.detectors[P1] = "[${detectorJson(FALL, "Falls")}]"
        backend.deleteStatus = HttpStatusCode.InternalServerError
        val vm = viewModel()
        backend.api.loadDetectors(P1)
        idle()

        vm.deleteDetector(vm.state.value.detectors.first())
        vm.settle()

        assertEquals(listOf("Falls"), vm.state.value.detectors.map { it.name })
        assertTrue(vm.state.value.deletingDetectorIds.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Exit-door configs
    // -----------------------------------------------------------------------

    /**
     * The wandering row's hint counts sensors with at least one exit door, so a
     * camera the caregiver never configured must not arrive as an entry.
     */
    @Test fun `the door configs are read per sensor, skipping unconfigured ones`() = runTest(mainDispatcher) {
        backend.wanderingDoors = """
            [{"camera_id":"cam-a","door_config":{"doors":[{"label":"front door"}]}},
             {"camera_id":"cam-b"}]
        """.trimIndent()
        val vm = viewModel()

        vm.loadDoorConfigs()
        vm.settle()

        assertEquals("GET /v1/patients/$P1/wandering-doors", backend.seen.last())
        assertEquals(setOf("cam-a"), vm.state.value.doorConfigs.keys)
    }

    /**
     * Silent on failure, as iOS's `guard let list = try? … else { return }` is,
     * and the map is left alone rather than emptied — a transient failure must
     * not blank a hint that was already correct.
     */
    @Test fun `a failed door fetch keeps the configs already on screen`() = runTest(mainDispatcher) {
        backend.wanderingDoors = """[{"camera_id":"cam-a","door_config":{"doors":[{"label":"front"}]}}]"""
        val vm = viewModel()
        vm.loadDoorConfigs()
        vm.settle()
        assertEquals(setOf("cam-a"), vm.state.value.doorConfigs.keys)

        backend.doorsStatus = HttpStatusCode.InternalServerError
        vm.loadDoorConfigs()
        vm.settle()

        assertEquals(setOf("cam-a"), vm.state.value.doorConfigs.keys)
    }
}
