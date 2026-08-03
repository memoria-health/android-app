package com.memoria.caregiver.ui.events

import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.UpdateEventRequest
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [EventDetailViewModel] behaviour. Spec:
 * `Features/Patient/Events/EventDetailView.swift` and PARITY.md §15.
 *
 * The clip poll is the whole reason this object exists, and it is the one place
 * where a wrong reading of the API contract looks like working code: a 409 means
 * "archival has not finished, ask again", not "this failed". Every poll test
 * below therefore drives the real [com.memoria.caregiver.data.remote.EventsApi]
 * through a Ktor [MockEngine], because a fake would have to encode that same
 * decision to be useful.
 *
 * Time is virtual throughout. A test that actually waited three seconds for one
 * poll tick would put a minute of sleeping into the suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailViewModelTest {

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private companion object {
        const val BASE = "https://backend.test"
        const val P1 = "11111111-1111-4111-8111-111111111111"

        fun eventJson(
            id: String = "e1",
            patientId: String = P1,
            reviewed: Boolean = false,
        ) = """
            {
              "id": "$id",
              "patient_id": "$patientId",
              "category": "safety",
              "source": "preset",
              "icon_name": "figure.fall",
              "title": "Possible fall",
              "location": "Kitchen",
              "occurred_at": "2026-07-30T18:20:11.482910+00:00",
              "has_clip": true,
              "narrative": "Someone appeared to fall near the counter.",
              "opened": true,
              "reviewed": $reviewed,
              "created_at": "2026-07-30T18:20:13.100000+00:00"
            }
        """.trimIndent()
    }

    private class Backend {

        /** Per-request status for `GET /v1/events/{id}/clip-url`, in order. */
        var clipStatuses: List<HttpStatusCode> = listOf(HttpStatusCode.OK)

        /** Per-request body for the same, in order. The last one repeats. */
        var clipBodies: List<String> =
            listOf("""{"url":"https://signed.test/clip.mp4","expires_in_seconds":600}""")

        /** Items for `GET /v1/patients/{id}/events`. */
        var feed: String = "[]"

        /** Body for `PATCH /v1/events/{id}`. */
        var patchResponse: String = eventJson()

        val sent = mutableListOf<HttpRequestData>()

        val clipRequests: Int get() = sent.count { it.url.encodedPath.endsWith("/clip-url") }

        /**
         * Requests **issued**, counted where [sent] cannot be trusted: the token
         * provider runs inline in the calling coroutine, before Ktor hands the
         * request to the engine's own dispatcher. So this number moves in step
         * with virtual time, while [sent] is written on another thread a moment
         * later — which is the difference between proving the poll asked nothing
         * and merely not having seen the request yet.
         */
        var requestsIssued: Int = 0
            private set

        /**
         * Held so [tearDown] can wait for a request that is still in flight.
         * Ktor runs the engine on its own dispatcher, so a clip poll that
         * outlives the test body would otherwise finish after `resetMain` and
         * report its failure into whichever test is running next.
         */
        val client: HttpClient = HttpClient(
            MockEngine { request ->
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        val path = request.url.encodedPath
                        when {
                            path.endsWith("/clip-url") -> {
                                val index = clipRequests
                                sent += request
                                respond(
                                    clipBodies.getOrElse(index) { clipBodies.last() },
                                    clipStatuses.getOrElse(index) { clipStatuses.last() },
                                    json,
                                )
                            }

                            path.endsWith("/events") -> {
                                sent += request
                                respond("""{"items":$feed}""", headers = json)
                            }

                            else -> {
                                sent += request
                                respond(patchResponse, HttpStatusCode.OK, json)
                            }
                        }
            },
        ) {
            install(ContentNegotiation) { json(NemaraJson.json) }
        }

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = client,
                baseUrl = BASE,
                tokenProvider = { requestsIssued++; "test-jwt" },
            ),
        )
    }

    private val backend = Backend()

    /**
     * Every ViewModel this test built, so [tearDown] can cancel it.
     * `viewModelScope` outlives `runTest` — Android cancels it in `onCleared`,
     * and nothing does that here — so a still-running clip poll would resume
     * after `resetMain` and fail whichever test happened to be running next.
     */
    private val viewModels = mutableListOf<EventDetailViewModel>()

    private fun viewModel(event: DetectionEvent = sampleEvent(id = "e1")) =
        EventDetailViewModel(backend.api, P1, event.copy(patientId = P1))
            .also { viewModels += it }

    @Before fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /**
     * Unwind everything that is still running BEFORE Main goes away, in this
     * order: cancel the ViewModels, then let the HTTP client finish unwinding
     * the requests that cancellation just interrupted. A clip poll that resumes
     * after `resetMain` throws "Dispatchers.Main was accessed…", and because it
     * belongs to no test scope, that lands on whichever test runs next.
     */
    @After fun tearDown() {
        runBlocking {
            withTimeout(5_000) {
                viewModels.forEach {
                    it.viewModelScope.cancel()
                    it.viewModelScope.coroutineContext.job.join()
                }
                backend.client.close()
                backend.client.coroutineContext.job.join()
            }
        }
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // The clip poll
    // -----------------------------------------------------------------------

    /**
     * The archival race, which is the normal case rather than the edge: the clip
     * is written 25-45 seconds after the event fires, so the first ask usually
     * comes back 409. That is control flow — the placeholder keeps saying
     * "Generating clip…" and the next ask goes out three seconds later.
     */
    @Test fun `a 409 is not an error - the poll waits three seconds and asks again`() = runTest {
        backend.clipStatuses = listOf(HttpStatusCode.Conflict, HttpStatusCode.OK)
        backend.clipBodies = listOf(
            """{"detail":"clip_not_ready"}""",
            """{"url":"https://signed.test/clip.mp4","expires_in_seconds":600}""",
        )
        val vm = viewModel()

        // Treated as an error, this never resolves and the test times out.
        val loaded = vm.state.first { it.clip is ClipState.Loaded }

        assertEquals(ClipState.Loaded("https://signed.test/clip.mp4"), loaded.clip)
        assertEquals(2, backend.clipRequests)
        // Exactly one interval of virtual time — three seconds nobody waited for.
        assertEquals(3_000L, currentTime)
    }

    /**
     * What the caregiver sees in the meantime. A 409 surfaces as a null URL, and
     * a null URL is "Generating clip…" — never the failure placard, which would
     * be a lie about the normal case.
     */
    @Test fun `while archival is unfinished the placeholder says generating`() = runTest {
        backend.clipStatuses = listOf(HttpStatusCode.Conflict)
        backend.clipBodies = listOf("""{"detail":"clip_not_ready"}""")
        val vm = viewModel()

        assertEquals(ClipState.Generating, vm.state.first { it.clip !is ClipState.Pending }.clip)
    }

    /** Once the URL resolves there is nothing left to ask about. */
    @Test fun `the poll stops for good once the clip loads`() = runTest {
        val vm = viewModel()

        vm.state.first { it.clip is ClipState.Loaded }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, backend.requestsIssued)
    }

    /**
     * A real failure stops the loop and shows the API layer's own sentence,
     * verbatim — the same copy `ApiError.errorDescription` gives every other
     * screen. A 404 here is a deleted or foreign event, and retrying it forever
     * would be a request every three seconds for as long as the screen is open.
     */
    @Test fun `a real error stops the poll and shows the API's own sentence`() = runTest {
        backend.clipStatuses = listOf(HttpStatusCode.NotFound)
        backend.clipBodies = listOf("""{"detail":"not_found"}""")
        val vm = viewModel()

        val failed = vm.state.first { it.clip is ClipState.Failed }.clip
        assertEquals(ClipState.Failed("That isn't here anymore."), failed)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, backend.requestsIssued)
    }

    /**
     * `clip_suppressed` is terminal: the clip was never recorded and never will
     * be, so the screen shows its final placard. Asking anyway would be a poll
     * that can only ever fail, three seconds apart, forever.
     */
    @Test fun `a suppressed clip is never asked about`() = runTest {
        val vm = viewModel(sampleEvent(id = "e1", clipSuppressed = true))

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(0, backend.requestsIssued)
        assertEquals(ClipState.Pending, vm.state.value.clip)
    }

    // -----------------------------------------------------------------------
    // The event itself
    // -----------------------------------------------------------------------

    /**
     * Derived from the cache, not snapshotted: a review submitted from this
     * screen, a `PATCH {opened: true}` response or a Realtime UPDATE all reach
     * the open detail with nothing else plumbed in between.
     */
    @Test fun `the event follows the cache while the detail is open`() = runTest {
        backend.feed = "[${eventJson()}]"
        backend.api.loadEventsFirstPage(P1)
        val vm = viewModel(sampleEvent(id = "e1", reviewed = false))
        assertEquals(false, vm.state.value.event.reviewed)

        backend.patchResponse = eventJson(reviewed = true)
        backend.api.updateEvent("e1", UpdateEventRequest(reviewed = true))

        assertTrue(vm.state.value.event.reviewed)
    }

    /**
     * The deep-link case: an event fetched by id is legitimately not in the
     * loaded page, and the detail still has to draw it. The passed-in copy is
     * the fallback — never a blank screen.
     */
    @Test fun `an event that is not in the cache still renders`() = runTest {
        backend.feed = "[${eventJson(id = "other")}]"
        backend.api.loadEventsFirstPage(P1)

        val vm = viewModel(sampleEvent(id = "deep-linked", title = "Left the house"))

        assertEquals("Left the house", vm.state.value.event.title)
    }
}
