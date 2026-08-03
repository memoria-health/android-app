package com.memoria.caregiver.ui.detectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DetectorLibraryViewModel] behavior. Spec:
 * `Features/Patient/Detectors/DetectorLibraryView.swift`, PARITY.md §22, and the
 * doc comments on `DetectorUiState.kt`.
 *
 * The real client under a Ktor `MockEngine`, as `RosterViewModelTest` is. The
 * onboarding reconcile in particular is a sequence of real calls against real
 * cached rows, and a fake client would let it pass while sending the wrong ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectorLibraryViewModelTest {

    private companion object {
        const val PATIENT = "11111111-1111-4111-8111-111111111111"
        const val FALL_ID = "22222222-2222-4222-8222-222222222222"
        const val WANDERING_ID = "33333333-3333-4333-8333-333333333333"

        fun entryJson(
            key: String,
            name: String,
            recommended: Boolean,
            alreadyAdded: Boolean,
        ) = """
            {"key":"$key","name":"$name","icon_name":"eye",
             "description":"Watches for $key.","category":"safety","cohort":"adult",
             "recommended":$recommended,"already_added":$alreadyAdded}
        """.trimIndent()

        fun detectorJson(id: String, name: String, identifier: String) = """
            {"id":"$id","patient_id":"$PATIENT","name":"$name","icon_name":"eye",
             "description":"Watches something.","category":"safety","source":"preset",
             "alert_trigger":"leading","is_sustained":false,"when_scope":"ALWAYS",
             "sensitivity":"standard","is_on":true,"identifier":"$identifier",
             "created_at":"2026-05-20T10:00:00.000000+00:00",
             "updated_at":"2026-05-20T10:00:00.000000+00:00"}
        """.trimIndent()
    }

    private class Backend {

        /** `GET /v1/patients/{id}/detectors`. */
        var detectors: String = "[]"

        /** `GET /v1/patients/{id}/detectors/library`. */
        var catalog: String = "[]"
        var catalogStatus: HttpStatusCode = HttpStatusCode.OK

        /** `POST /v1/patients/{id}/detectors/from-library`. */
        var created: String = "[]"
        var createStatus: HttpStatusCode = HttpStatusCode.OK

        var deleteStatus: HttpStatusCode = HttpStatusCode.OK

        /** Held open so a test can observe the in-flight window exactly. */
        var addGate: CompletableDeferred<Unit>? = null

        val seen: MutableList<String> = mutableListOf()
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
                            path.endsWith("/detectors/from-library") -> {
                                addGate?.await()
                                respond(created, createStatus, json)
                            }
                            path.endsWith("/detectors/library") ->
                                respond(catalog, catalogStatus, json)
                            path.endsWith("/detectors") && method == "GET" ->
                                respond(detectors, headers = json)
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
        )
    }

    private val backend = Backend()
    private var finishes = 0

    private fun viewModel(mode: DetectorLibraryMode) = DetectorLibraryViewModel(
        api = backend.api,
        patientId = PATIENT,
        mode = mode,
        onFinished = { finishes++ },
    )

    /**
     * The `keys` array of the last `from-library` POST, as a set: `keysToAdd`
     * comes off a Set, so its order is an implementation detail and asserting it
     * would pin something the backend does not care about.
     */
    private fun sentKeys(): Set<String> =
        Json.parseToJsonElement(backend.bodies.last())
            .jsonObject["keys"]!!
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

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

    @After fun tearDownMain() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    /**
     * Add-mode starts with nothing selected. Its already-added rows are LOCKED,
     * not checked — pre-selecting them would make `newlySelectedCount` and the
     * footer title count rows that cannot be added.
     */
    @Test fun `add-mode loads the catalog with nothing selected`() = runTest(mainDispatcher) {
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = true, alreadyAdded = true) + "," +
            entryJson("wandering", "Wandering", recommended = false, alreadyAdded = false) + "]"
        val vm = viewModel(DetectorLibraryMode.Add)

        vm.load()

        vm.settle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertNull(state.loadError)
        assertEquals(listOf("fall", "wandering"), state.entries.map { it.key })
        assertTrue(state.selectedKeys.isEmpty())
        assertEquals("Add detectors", state.footerTitle)
        assertTrue(state.footerDisabled)
    }

    /**
     * Onboarding pre-checks the recommended and the already-seeded set, because
     * unchecking is how the caregiver removes one there.
     */
    @Test fun `onboarding pre-checks the recommended and the already-added`() = runTest(mainDispatcher) {
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = true, alreadyAdded = false) + "," +
            entryJson("wandering", "Wandering", recommended = false, alreadyAdded = true) + "," +
            entryJson("stove", "Stove", recommended = false, alreadyAdded = false) + "]"
        val vm = viewModel(DetectorLibraryMode.Onboarding)

        vm.load()

        vm.settle()

        assertEquals(setOf("fall", "wandering"), vm.state.value.selectedKeys)
        assertEquals("Continue", vm.state.value.footerTitle)
        assertFalse(vm.state.value.footerDisabled)
    }

    /**
     * The patient's detector rows are loaded FIRST, so the reconcile can map a
     * catalog key onto the row it has to delete without waiting on a fetch.
     */
    @Test fun `load reads the patient's detectors before the catalog`() = runTest(mainDispatcher) {
        backend.detectors = "[${detectorJson(FALL_ID, "Falls", "fall")}]"
        val vm = viewModel(DetectorLibraryMode.Onboarding)

        vm.load()

        vm.settle()

        assertEquals(
            listOf(
                "GET /v1/patients/$PATIENT/detectors",
                "GET /v1/patients/$PATIENT/detectors/library",
            ),
            backend.seen,
        )
        assertEquals(listOf("Falls"), backend.api.detectorsByPatient[PATIENT]?.map { it.name })
    }

    /**
     * The sentence under "Couldn't load the detection library." is
     * [ApiError.errorDescription] verbatim, and the spinner has to come down or
     * the error branch is never reached.
     */
    @Test fun `a failed load surfaces the API copy and stops the spinner`() = runTest(mainDispatcher) {
        backend.catalogStatus = HttpStatusCode.Unauthorized
        backend.catalog = ""
        val vm = viewModel(DetectorLibraryMode.Add)

        vm.load()

        vm.settle()

        assertEquals(ApiError.Unauthorized.errorDescription, vm.state.value.loadError)
        assertFalse(vm.state.value.isLoading)
    }

    // -----------------------------------------------------------------------
    // Selection
    // -----------------------------------------------------------------------

    /**
     * `if mode == .add && e.alreadyAdded { return }` at the top of iOS's
     * `toggle`. A locked key that slipped through would be sent to `from-library`
     * and skipped server-side, so the footer would promise an add that never
     * happened.
     */
    @Test fun `an already-added entry is inert in add-mode and toggleable in onboarding`() =
        runTest(mainDispatcher) {
            backend.catalog =
                "[${entryJson("fall", "Fall", recommended = false, alreadyAdded = true)}]"

            val add = viewModel(DetectorLibraryMode.Add)
            add.load()
            add.settle()
            add.toggleEntry(add.state.value.entries.first())
            assertTrue(add.state.value.selectedKeys.isEmpty())

            val onboarding = viewModel(DetectorLibraryMode.Onboarding)
            onboarding.load()
            onboarding.settle()
            assertEquals(setOf("fall"), onboarding.state.value.selectedKeys)
            onboarding.toggleEntry(onboarding.state.value.entries.first())
            assertTrue(onboarding.state.value.selectedKeys.isEmpty())
        }

    @Test fun `the query filters name and description case-insensitively`() = runTest(mainDispatcher) {
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = false, alreadyAdded = false) + "," +
            entryJson("stove", "Stove", recommended = false, alreadyAdded = false) + "]"
        val vm = viewModel(DetectorLibraryMode.Add)
        vm.load()
        vm.settle()

        vm.setQuery("  STOVE ")

        assertEquals(listOf("stove"), vm.state.value.filteredEntries.map { it.key })
    }

    // -----------------------------------------------------------------------
    // Submit — add mode
    // -----------------------------------------------------------------------

    /** Only the keys the patient does not already have are posted. */
    @Test fun `add-mode posts the newly checked keys and then leaves`() = runTest(mainDispatcher) {
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = false, alreadyAdded = true) + "," +
            entryJson("stove", "Stove", recommended = false, alreadyAdded = false) + "]"
        backend.created = "[${detectorJson(WANDERING_ID, "Stove", "stove")}]"
        val vm = viewModel(DetectorLibraryMode.Add)
        vm.load()
        vm.settle()
        vm.toggleEntry(vm.state.value.entries.first { it.key == "stove" })

        vm.submit()

        vm.settle()

        assertEquals("POST /v1/patients/$PATIENT/detectors/from-library", backend.seen.last())
        assertEquals(setOf("stove"), sentKeys())
        assertEquals(1, finishes)
        assertFalse(vm.state.value.isSubmitting)
    }

    /**
     * The server skips keys the patient already has, so the response can be
     * shorter than the request — the cache must grow by what came back, never by
     * what was asked for.
     */
    @Test fun `the patient gains only the rows the server actually created`() = runTest(mainDispatcher) {
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = false, alreadyAdded = false) + "," +
            entryJson("stove", "Stove", recommended = false, alreadyAdded = false) + "]"
        // Two keys asked for, one row created.
        backend.created = "[${detectorJson(WANDERING_ID, "Stove", "stove")}]"
        val vm = viewModel(DetectorLibraryMode.Add)
        vm.load()
        vm.settle()
        vm.state.value.entries.forEach { vm.toggleEntry(it) }

        vm.submit()

        vm.settle()

        assertEquals(setOf("fall", "stove"), sentKeys())
        assertEquals(listOf("Stove"), backend.api.detectorsByPatient[PATIENT]?.map { it.name })
    }

    @Test fun `a failed submit surfaces the API copy and does not leave`() = runTest(mainDispatcher) {
        backend.catalog =
            "[${entryJson("stove", "Stove", recommended = false, alreadyAdded = false)}]"
        backend.createStatus = HttpStatusCode.InternalServerError
        backend.created = ""
        val vm = viewModel(DetectorLibraryMode.Add)
        vm.load()
        vm.settle()
        vm.toggleEntry(vm.state.value.entries.first())

        vm.submit()

        vm.settle()

        assertEquals(
            ApiError.ServerError(null, null).errorDescription,
            vm.state.value.submitError,
        )
        assertEquals(0, finishes)
        assertFalse(vm.state.value.isSubmitting)
    }

    @Test fun `a second submit while one is in flight is refused`() = runTest(mainDispatcher) {
        backend.catalog =
            "[${entryJson("stove", "Stove", recommended = false, alreadyAdded = false)}]"
        backend.created = "[${detectorJson(WANDERING_ID, "Stove", "stove")}]"
        val vm = viewModel(DetectorLibraryMode.Add)
        vm.load()
        vm.settle()
        vm.toggleEntry(vm.state.value.entries.first())
        val gate = CompletableDeferred<Unit>()
        backend.addGate = gate

        vm.submit()
        vm.submit()

        gate.complete(Unit)
        vm.settle()

        assertEquals(1, backend.seen.count { it.endsWith("/from-library") })
        assertEquals(1, finishes)
    }

    // -----------------------------------------------------------------------
    // Submit — the onboarding reconcile
    // -----------------------------------------------------------------------

    /**
     * Adds first, deletes second. Deleting first would leave the patient with
     * fewer detectors than they started with if the add then failed, and the
     * order is what iOS's `submit()` does.
     */
    @Test fun `onboarding adds the newly checked and then deletes the unchecked`() = runTest(mainDispatcher) {
        backend.detectors = "[" +
            detectorJson(FALL_ID, "Falls", "fall") + "," +
            detectorJson(WANDERING_ID, "Wandering", "wandering") + "]"
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = true, alreadyAdded = true) + "," +
            entryJson("wandering", "Wandering", recommended = true, alreadyAdded = true) + "," +
            entryJson("stove", "Stove", recommended = false, alreadyAdded = false) + "]"
        backend.created = "[${detectorJson("66666666-6666-4666-8666-666666666666", "Stove", "stove")}]"
        val vm = viewModel(DetectorLibraryMode.Onboarding)
        vm.load()
        vm.settle()
        // Uncheck wandering, check stove.
        vm.toggleEntry(vm.state.value.entries.first { it.key == "wandering" })
        vm.toggleEntry(vm.state.value.entries.first { it.key == "stove" })

        vm.submit()

        vm.settle()

        assertEquals(
            listOf(
                "POST /v1/patients/$PATIENT/detectors/from-library",
                "DELETE /v1/detectors/$WANDERING_ID",
            ),
            backend.seen.drop(2),
        )
        assertEquals(1, finishes)
    }

    /**
     * Fail loud. The library said the patient has this key and no detector row
     * carries that identifier, so skipping the delete would mean the caregiver
     * unchecks something and it silently stays. The copy lives in
     * `DetectorUiState.kt` so nothing can paraphrase it.
     */
    @Test fun `a key with no detector row fails loud and does not leave`() = runTest(mainDispatcher) {
        // The patient has no row carrying the "wandering" identifier.
        backend.detectors = "[${detectorJson(FALL_ID, "Falls", "fall")}]"
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = true, alreadyAdded = true) + "," +
            entryJson("wandering", "Wandering", recommended = true, alreadyAdded = true) + "]"
        val vm = viewModel(DetectorLibraryMode.Onboarding)
        vm.load()
        vm.settle()
        vm.toggleEntry(vm.state.value.entries.first { it.key == "wandering" })

        vm.submit()

        vm.settle()

        assertEquals(
            missingDetectorForKeyMessage("wandering"),
            vm.state.value.submitError,
        )
        assertEquals(0, finishes)
        assertEquals(0, backend.seen.count { it.startsWith("DELETE") })
    }

    /** Add-mode never removes anything, whatever the caregiver unchecked. */
    @Test fun `add-mode deletes nothing`() = runTest(mainDispatcher) {
        backend.detectors = "[${detectorJson(FALL_ID, "Falls", "fall")}]"
        backend.catalog = "[" +
            entryJson("fall", "Fall", recommended = true, alreadyAdded = true) + "," +
            entryJson("stove", "Stove", recommended = false, alreadyAdded = false) + "]"
        backend.created = "[${detectorJson(WANDERING_ID, "Stove", "stove")}]"
        val vm = viewModel(DetectorLibraryMode.Add)
        vm.load()
        vm.settle()
        vm.toggleEntry(vm.state.value.entries.first { it.key == "stove" })

        vm.submit()

        vm.settle()

        assertEquals(0, backend.seen.count { it.startsWith("DELETE") })
        assertEquals(1, finishes)
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
