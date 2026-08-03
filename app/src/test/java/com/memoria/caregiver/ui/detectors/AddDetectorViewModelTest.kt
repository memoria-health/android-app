package com.memoria.caregiver.ui.detectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.Detector
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
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

/**
 * [AddDetectorViewModel] behavior. Spec:
 * `Features/Patient/Settings/AddDetectorView.swift`, PARITY.md §17, and the doc
 * comments on `DetectorUiState.kt` and `DetectorsApi.kt`.
 *
 * The real client under a Ktor `MockEngine`, as `RosterViewModelTest` is — which
 * is what makes the central rule here testable at all: an inference describes a
 * detector that does not exist, so it must leave the client's cache untouched,
 * and only a real cache can show that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddDetectorViewModelTest {

    private companion object {
        const val PATIENT = "11111111-1111-4111-8111-111111111111"
        const val CREATED = "22222222-2222-4222-8222-222222222222"

        /** An absence detector with a full schedule, so every parsed time lands. */
        val ABSENCE_INFERENCE = """
            {
              "alert_trigger": "absence",
              "is_sustained": true,
              "duration_seconds": 600,
              "category": "routine",
              "icon_name": "pills",
              "sensitivity": "high",
              "schedule_config": {
                "cadence": "weekly",
                "days_of_week": [0, 2],
                "window_start": "08:00",
                "window_end": "10:00",
                "deadline": "10:30"
              },
              "episode_gap_seconds": 120,
              "plan": {
                "watches": "the kitchen counter",
                "fires": "if the pills are untouched",
                "checks": "every 30 seconds",
                "listens": false
              }
            }
        """.trimIndent()

        fun createdJson(name: String) = """
            {"id":"$CREATED","patient_id":"$PATIENT","name":"$name","icon_name":"pills",
             "description":"Custom detector","category":"routine","source":"custom",
             "alert_trigger":"absence","is_sustained":true,"when_scope":"ALWAYS",
             "sensitivity":"high","is_on":false,
             "created_at":"2026-05-20T10:00:00.000000+00:00",
             "updated_at":"2026-05-20T10:00:00.000000+00:00"}
        """.trimIndent()
    }

    private class Backend {

        var inference: String = ABSENCE_INFERENCE
        var inferStatus: HttpStatusCode = HttpStatusCode.OK

        var created: String = createdJson("Medication check")
        var createStatus: HttpStatusCode = HttpStatusCode.OK

        val seen: MutableList<String> = mutableListOf()
        val bodies: MutableList<String> = mutableListOf()

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        seen += "${request.method.value} $path"
                        bodies += String(request.body.toByteArray())
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        when {
                            path == "/v1/detectors/infer" ->
                                respond(inference, inferStatus, json)
                            path == "/v1/patients/$PATIENT/detectors" ->
                                respond(created, createStatus, json)
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

    /** What the host hands to the detail sheet. iOS: `@State createdDetector`. */
    private val handedOn = mutableListOf<Detector>()

    private fun viewModel() = AddDetectorViewModel(
        api = backend.api,
        patientId = PATIENT,
        onCreated = { handedOn += it },
    )

    private fun lastBody(): Map<String, String> =
        Json.parseToJsonElement(backend.bodies.last()).jsonObject
            .mapValues { (_, v) -> v.toString() }

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
    // Stage 2 is AI-authored
    // -----------------------------------------------------------------------

    /**
     * The inference lands on the review stage in full: the shape, the schedule
     * pickers, the icon, the category, and the plain-words plan card. The three
     * fields the screen never renders — duration, episode gap, sensitivity — are
     * carried too, because the create body needs them and dropping them here
     * would silently send the defaults.
     */
    @Test fun `generate folds the whole inference into the review stage`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPromptChange("tell me if mom misses her pills")

        vm.generate()

        vm.settle()

        val state = vm.state.value
        assertEquals(AddDetectorStage.Review, state.stage)
        assertEquals(Detector.AlertTrigger.ABSENCE, state.alertTrigger)
        assertEquals(Detector.Category.ROUTINE, state.category)
        assertEquals("pills", state.icon)
        assertEquals(Detector.Sensitivity.HIGH, state.sensitivity)
        assertEquals(600, state.durationSeconds)
        assertEquals(120, state.episodeGapSeconds)
        assertEquals(Detector.ScheduleConfig.Cadence.WEEKLY, state.cadence)
        assertEquals(setOf(0, 2), state.days)
        assertEquals(LocalTime.of(8, 0), state.windowStart)
        assertEquals(LocalTime.of(10, 0), state.windowEnd)
        assertEquals(LocalTime.of(10, 30), state.deadline)
        assertFalse(state.timesUncertain)
        assertEquals("the kitchen counter", state.plan?.watches)
        assertFalse(state.busy)
        assertNull(state.error)
    }

    /**
     * **The rule this screen exists to respect.** `/infer` is not scoped to a
     * patient and creates nothing: the response describes a detector that does
     * not exist in that form yet. Writing it into `detectorsByPatient` would put
     * a row in the Detectors tab that the backend has never heard of, which the
     * caregiver could then toggle and assign to a camera. `DetectorsApi` states
     * the same rule for `refineDetector(preview = true)`, which iOS guards with
     * `if !preview`.
     */
    @Test fun `an inferred plan is never written into the cached detector list`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPromptChange("tell me if mom misses her pills")

        vm.generate()

        vm.settle()

        assertEquals(AddDetectorStage.Review, vm.state.value.stage)
        assertTrue(backend.api.detectorsByPatient.isEmpty())
        assertEquals(listOf("POST /v1/detectors/infer"), backend.seen)
    }

    /**
     * The backend was unsure of at least one time on an absence detector, so the
     * screen asks the caregiver to confirm rather than presenting a made-up
     * window as if it were inferred.
     */
    @Test fun `an absence inference with unparseable times asks for confirmation`() = runTest(mainDispatcher) {
        backend.inference = """
            {"alert_trigger":"absence","is_sustained":false,"category":"safety",
             "icon_name":"eye","sensitivity":"standard",
             "schedule_config":{"cadence":"daily","window_start":"99:00"}}
        """.trimIndent()
        val vm = viewModel()
        vm.onPromptChange("check on her")

        vm.generate()

        vm.settle()

        assertTrue(vm.state.value.timesUncertain)
        // The two-step fallback: deadline follows the window end, then 17:00.
        assertEquals(LocalTime.of(9, 0), vm.state.value.windowStart)
        assertEquals(LocalTime.of(17, 0), vm.state.value.windowEnd)
        assertEquals(LocalTime.of(17, 0), vm.state.value.deadline)
    }

    /** The name gives the model context, but a blank one is left off the wire. */
    @Test fun `the typed name is sent, and a blank one is omitted`() = runTest(mainDispatcher) {
        val blank = viewModel()
        blank.onPromptChange("tell me if he falls")
        blank.onNameChange("   ")
        blank.generate()
        blank.settle()
        assertEquals(mapOf("prompt" to "\"tell me if he falls\""), lastBody())

        val named = viewModel()
        named.onPromptChange("tell me if he falls")
        named.onNameChange("  Falls  ")
        named.generate()
        named.settle()
        assertEquals(
            mapOf("name" to "\"Falls\"", "prompt" to "\"tell me if he falls\""),
            lastBody(),
        )
    }

    /**
     * `guard !p.isEmpty` at the top of iOS's `generate()`. The button is disabled
     * too, but the callback is a plain function, so this guard is the real one.
     */
    @Test fun `generate is refused when the prompt is only whitespace`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPromptChange("  \n ")

        vm.generate()

        vm.settle()

        assertTrue(backend.seen.isEmpty())
        assertEquals(AddDetectorStage.Entry, vm.state.value.stage)
    }

    /**
     * A failed inference leaves the caregiver on stage 1 with their words intact
     * and [ApiError.errorDescription] verbatim in the banner.
     */
    @Test fun `a failed generate stays on the entry stage and shows the API copy`() = runTest(mainDispatcher) {
        backend.inferStatus = HttpStatusCode.InternalServerError
        backend.inference = """{"detail":"the model is down"}"""
        val vm = viewModel()
        vm.onPromptChange("tell me if he falls")

        vm.generate()

        vm.settle()

        assertEquals(AddDetectorStage.Entry, vm.state.value.stage)
        assertEquals(
            ApiError.ServerError(null, "the model is down").errorDescription,
            vm.state.value.error,
        )
        assertEquals("tell me if he falls", vm.state.value.prompt)
        assertFalse(vm.state.value.busy)
    }

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    /**
     * Create-as-draft. The detector is made OFF so the caregiver reviews the
     * compiled criteria on the detail sheet before it starts watching, which is
     * also why the created row is handed onward rather than just closing.
     *
     * `schedule_config` is never sent: `when_scope` is the windows mechanism, and
     * an absence detector serializes its pickers into scope text the backend
     * parses directly.
     */
    @Test fun `create sends the draft body and hands the created row on`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPromptChange("tell me if mom misses her pills")
        vm.onNameChange("Medication check")
        vm.onNoteChange("Mom's afternoon pills")
        vm.generate()
        vm.settle()

        vm.create()

        vm.settle()

        assertEquals("POST /v1/patients/$PATIENT/detectors", backend.seen.last())
        val body = lastBody()
        assertEquals("\"Medication check\"", body["name"])
        assertEquals("\"Mom's afternoon pills\"", body["description"])
        assertEquals("false", body["is_on"])
        // Weekly Monday + Wednesday, 08:00-10:00, in the backend's scope grammar.
        assertEquals("\"MON,WED 8:00 AM - 10:00 AM\"", body["when_scope"])
        assertNull(body["schedule_config"])
        // Sent because the activity is sustained; duration is not, because the
        // trigger is absence rather than duration.
        assertEquals("120", body["episode_gap_seconds"])
        assertNull(body["duration_seconds"])

        assertEquals(listOf(CREATED), handedOn.map { it.id })
        assertEquals(
            listOf("Medication check"),
            backend.api.detectorsByPatient[PATIENT]?.map { it.name },
        )
        assertFalse(vm.state.value.busy)
    }

    /**
     * A reactive detector has no window to serialize, so it sends `ALWAYS` and
     * the schedule editor never appears.
     */
    @Test fun `a reactive detector sends ALWAYS as its scope`() = runTest(mainDispatcher) {
        backend.inference = """
            {"alert_trigger":"leading","is_sustained":false,"category":"safety",
             "icon_name":"figure.fall","sensitivity":"standard"}
        """.trimIndent()
        val vm = viewModel()
        vm.onPromptChange("tell me if he falls")
        vm.generate()
        vm.settle()
        assertFalse(vm.state.value.showsScheduleEditor)

        vm.create()

        vm.settle()

        assertEquals("\"ALWAYS\"", lastBody()["when_scope"])
    }

    /** The caregiver's edits on the review stage are what gets created. */
    @Test fun `edits made on the review stage reach the create body`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPromptChange("tell me if mom misses her pills")
        vm.generate()
        vm.settle()

        vm.onCategoryChange(Detector.Category.SAFETY)
        vm.onIconChange("bell")
        vm.onCadenceChange(Detector.ScheduleConfig.Cadence.DAILY)
        vm.onWindowStartChange(LocalTime.of(11, 0))
        vm.onWindowEndChange(LocalTime.of(17, 30))
        vm.create()
        vm.settle()

        val body = lastBody()
        assertEquals("\"safety\"", body["category"])
        assertEquals("\"bell\"", body["icon_name"])
        // Daily drops the day prefix, so the bare window is sent.
        assertEquals("\"11:00 AM - 5:30 PM\"", body["when_scope"])
    }

    @Test fun `a failed create shows the API copy and hands nothing on`() = runTest(mainDispatcher) {
        backend.createStatus = HttpStatusCode.Forbidden
        backend.created = ""
        val vm = viewModel()
        vm.onPromptChange("tell me if mom misses her pills")
        vm.generate()
        vm.settle()

        vm.create()

        vm.settle()

        assertEquals(ApiError.Forbidden.errorDescription, vm.state.value.error)
        assertTrue(handedOn.isEmpty())
        assertTrue(backend.api.detectorsByPatient.isEmpty())
        assertFalse(vm.state.value.busy)
    }

    /**
     * "← Edit" keeps everything inferred so far, so a reworded prompt starts from
     * what the caregiver already adjusted rather than from the defaults.
     */
    @Test fun `going back to the entry stage keeps the inferred fields`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPromptChange("tell me if mom misses her pills")
        vm.generate()
        vm.settle()

        vm.backToEntry()

        assertEquals(AddDetectorStage.Entry, vm.state.value.stage)
        assertEquals("pills", vm.state.value.icon)
        assertEquals(Detector.AlertTrigger.ABSENCE, vm.state.value.alertTrigger)
    }

    /** 0 = Mon .. 6 = Sun, and a second tap clears the day. */
    @Test fun `toggling a day adds it and toggling again removes it`() = runTest(mainDispatcher) {
        val vm = viewModel()

        vm.onToggleDay(3)
        assertEquals(setOf(3), vm.state.value.days)

        vm.onToggleDay(3)
        assertTrue(vm.state.value.days.isEmpty())
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
