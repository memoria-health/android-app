package com.memoria.caregiver.ui.events

import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import com.memoria.caregiver.ui.nav.DeepLinkRouter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [EventsListViewModel] behaviour. Spec:
 * `Features/Patient/Events/PatientEventsView.swift`,
 * `Features/Patient/Events/EventReviewView.swift` (the optimistic submit) and
 * PARITY.md §15.
 *
 * The client under it is the real one, driven by a Ktor [MockEngine] through the
 * real [HttpTransport], as `RosterViewModelTest` and the `data/remote` tests are.
 * A fake client would only prove that this file and the fake agree; the property
 * under test is precisely that the cache and the list stay in step, and that the
 * chips do **not** reach the wire.
 *
 * No Android framework and no Robolectric: a `ViewModel` is a plain JVM object
 * and `viewModelScope` needs only a Main dispatcher, which [setMain] supplies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventsListViewModelTest {

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private companion object {
        const val BASE = "https://backend.test"
        const val P1 = "11111111-1111-4111-8111-111111111111"
        const val OTHER_PATIENT = "99999999-9999-4999-8999-999999999999"

        fun eventJson(
            id: String,
            patientId: String = P1,
            opened: Boolean = false,
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
              "opened": $opened,
              "reviewed": $reviewed,
              "created_at": "2026-07-30T18:20:13.100000+00:00"
            }
        """.trimIndent()
    }

    /** A fake backend the tests reconfigure between calls. */
    private class Backend {

        /** Items for `GET /v1/patients/{id}/events`. */
        var feed: String = "[]"

        /** Body for `GET /v1/events/{id}` — the deep-link fallback. */
        var single: String = "{}"
        var singleStatus: HttpStatusCode = HttpStatusCode.OK

        /** Body and status for `PATCH /v1/events/{id}`. */
        var patchResponse: String = "{}"
        var patchStatus: HttpStatusCode = HttpStatusCode.OK

        /** When set, every PATCH waits on it, so the optimistic window is observable. */
        var patchGate: CompletableDeferred<Unit>? = null

        /** Every request that arrived, in order. */
        val sent = mutableListOf<HttpRequestData>()

        /** PATCH bodies, in order. */
        val patchBodies = mutableListOf<String>()

        /**
         * The same bodies, as something a test can wait on. A `PATCH` fired
         * from a `launch` reaches the engine on the engine's own dispatcher, so
         * reading [patchBodies] straight after the call under test would be a
         * race; [awaitPatch] is the version with a happens-before in it.
         */
        private val patches = Channel<String>(Channel.UNLIMITED)

        suspend fun awaitPatch(): String = patches.receive()

        /** The same, for feed requests: waits until one has been answered. */
        private val feeds = Channel<Unit>(Channel.UNLIMITED)

        suspend fun awaitFeedRequest() = feeds.receive()

        val feedRequests: Int get() = sent.count { it.url.encodedPath.endsWith("/events") }

        /**
         * Requests **issued**, counted where [sent] cannot be trusted: the token
         * provider runs inline in the calling coroutine, before Ktor hands the
         * request to the engine's own dispatcher. So this number moves in step
         * with virtual time, while [sent] is written on another thread a moment
         * later — which is the difference between proving a request was not
         * made and merely not having seen it yet.
         */
        var requestsIssued: Int = 0
            private set

        /**
         * Held so [tearDown] can wait for a request that is still in flight.
         * Ktor runs the engine on its own dispatcher, so a call that outlives
         * the test body would otherwise finish after `resetMain` and report its
         * failure into whichever test is running next.
         */
        val client: HttpClient = HttpClient(
            MockEngine { request ->
                        sent += request
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        val path = request.url.encodedPath
                        when {
                            path.endsWith("/events") -> {
                                // The body is captured BEFORE the signal, so a
                                // test that changes `feed` the moment it sees
                                // the request cannot change the answer to that
                                // same request.
                                val body = """{"items":$feed}"""
                                feeds.trySend(Unit)
                                respond(body, headers = json)
                            }

                            request.method.value == "PATCH" -> {
                                val body = (request.body as TextContent).text
                                patchBodies += body
                                patches.trySend(body)
                                patchGate?.await()
                                respond(patchResponse, patchStatus, json)
                            }

                            else -> respond(single, singleStatus, json)
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
    private val router = DeepLinkRouter()

    /**
     * Every ViewModel this test built, so [tearDown] can cancel it.
     * `viewModelScope` outlives `runTest` — Android cancels it in `onCleared`
     * and nothing does that here — so the deep-link collector, or a PATCH still
     * in flight, would otherwise resume after `resetMain` and fail whichever
     * test happened to be running next.
     */
    private val viewModels = mutableListOf<EventsListViewModel>()

    private fun viewModel() =
        EventsListViewModel(backend.api, P1, router).also { viewModels += it }

    /**
     * `viewModelScope` dispatches on Main. Unconfined so a `launch` runs up to
     * its first real suspension inline — which is what lets "the tap patched
     * before it presented" be asserted without a sleep.
     */
    @Before fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /**
     * Unwind everything that is still running BEFORE Main goes away, in this
     * order: cancel the ViewModels, then let the HTTP client finish unwinding
     * the requests that cancellation just interrupted. A coroutine that resumes
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
    // The first page
    // -----------------------------------------------------------------------

    /**
     * `env.api.eventsByPatient[patientId] ?? []`, in server order, with the
     * work-first default filter that is the point of the screen.
     */
    @Test fun `the loaded page becomes the state, in server order, filtered to unreviewed`() =
        runTest {
            backend.feed = "[${eventJson("e1")},${eventJson("e2", reviewed = true)}]"
            val vm = viewModel()

            backend.api.loadEventsFirstPage(P1)

            val state = vm.state.value
            assertEquals(listOf("e1", "e2"), state.events.map { it.id })
            assertEquals(EventsFilter.Unreviewed, state.filter)
            assertEquals(listOf("e1"), state.visible.map { it.id })
            assertEquals(
                mapOf(
                    EventsFilter.All to 2,
                    EventsFilter.Unreviewed to 1,
                    EventsFilter.Reviewed to 1,
                ),
                state.counts,
            )
        }

    /** Another patient's events are not this list's. */
    @Test fun `only this patient's events are read out of the cache`() = runTest {
        backend.feed = "[${eventJson("e1")}]"
        val vm = viewModel()

        backend.api.loadEventsFirstPage(OTHER_PATIENT)

        assertTrue(vm.state.value.events.isEmpty())
    }

    // -----------------------------------------------------------------------
    // The chips
    // -----------------------------------------------------------------------

    /**
     * The chips narrow the already-loaded list and **never re-request**. iOS
     * calls `loadEventsFirstPage` with the default `all` filter and filters
     * `eventsByPatient` in memory; a server-side implementation would show up
     * here as a second request, or as a `filter=` that is not `all`.
     */
    @Test fun `the chips filter in memory and nothing new goes to the server`() = runTest {
        backend.feed = "[${eventJson("e1")},${eventJson("e2", reviewed = true)}]"
        val vm = viewModel()
        backend.api.loadEventsFirstPage(P1)
        val requestsAfterLoad = backend.requestsIssued

        vm.setFilter(EventsFilter.All)
        assertEquals(listOf("e1", "e2"), vm.state.value.visible.map { it.id })

        vm.setFilter(EventsFilter.Reviewed)
        assertEquals(listOf("e2"), vm.state.value.visible.map { it.id })

        assertEquals(requestsAfterLoad, backend.requestsIssued)
        assertEquals(listOf("all"), backend.sent.map { it.url.parameters["filter"] })
    }

    // -----------------------------------------------------------------------
    // The quiet poll
    // -----------------------------------------------------------------------

    /**
     * The reliability net for a Realtime INSERT that was never delivered: refetch
     * every 20 seconds while the tab is on screen.
     *
     * **And the pause while a detail is open**, which is load-bearing: a refetch
     * replaces the cached list, which re-renders the open detail, and that
     * historically killed clip playback mid-watch.
     */
    @Test fun `the poll refreshes every 20 seconds and pauses while a detail is open`() = runTest {
        backend.feed = "[${eventJson("e1")}]"
        val vm = viewModel()
        backgroundScope.launch { vm.runFeedPoll() }

        // Pass one is immediate, as on iOS — the tab appearing is itself a
        // reason to distrust the cache.
        vm.state.first { it.events.isNotEmpty() }
        assertEquals(0L, currentTime)
        assertEquals(1, backend.feedRequests)

        // Nothing one millisecond before the interval elapses.
        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(1, backend.requestsIssued)

        // A second event lands on the server; the poll is how it is noticed,
        // because Realtime has no replay for the INSERT that was missed.
        backend.feed = "[${eventJson("e2")},${eventJson("e1")}]"
        advanceTimeBy(2)
        val second = vm.state.first { it.events.size == 2 }
        assertEquals(listOf("e2", "e1"), second.events.map { it.id })
        // Twenty virtual seconds, not twenty real ones.
        assertEquals(20_001L, currentTime)

        // Detail open: the poll must go quiet however long it stays open. Three
        // whole intervals go by and not one request is issued.
        vm.onEventTap(second.events.first())
        val duringDetail = backend.requestsIssued
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(duringDetail, backend.requestsIssued)

        // Closed again: it resumes.
        backend.feed = "[${eventJson("e3")},${eventJson("e2")},${eventJson("e1")}]"
        vm.closeEvent()
        advanceTimeBy(20_001)
        assertEquals(3, vm.state.first { it.events.size == 3 }.events.size)
    }

    /**
     * A failed poll is silent — nobody asked for it, so nobody is told. And it
     * keeps going: this is the net that catches a missed Realtime INSERT, and
     * one bad response must not take it down for the rest of the session.
     */
    @Test fun `a failing poll says nothing and keeps trying`() = runTest {
        backend.feed = "not json"
        val vm = viewModel()
        backgroundScope.launch { vm.runFeedPoll() }

        // Pass one has been served a body that cannot be decoded.
        backend.awaitFeedRequest()
        assertNull(vm.state.value.reviewError)
        assertTrue(vm.state.value.events.isEmpty())

        // Pass two, twenty seconds later, gets a good one.
        backend.feed = "[${eventJson("e1")}]"
        advanceTimeBy(20_001)

        assertEquals(listOf("e1"), vm.state.first { it.events.isNotEmpty() }.events.map { it.id })
        assertNull(vm.state.value.reviewError)
    }

    // -----------------------------------------------------------------------
    // The row tap
    // -----------------------------------------------------------------------

    /**
     * `PATCH {opened: true}` before presenting, and only for an event that has
     * not been opened. The patched row comes back through the cache, which is
     * what dims the card.
     */
    @Test fun `tapping an unopened event patches it opened and presents it`() = runTest {
        backend.feed = "[${eventJson("e1")}]"
        backend.patchResponse = eventJson("e1", opened = true)
        val vm = viewModel()
        backend.api.loadEventsFirstPage(P1)

        vm.onEventTap(vm.state.value.events.first())

        assertEquals("e1", vm.openEvent.value?.id)
        assertEquals("""{"opened":true}""", backend.awaitPatch())
        // The wait is the assertion: the patched row has to come back through
        // the cache — that is what dims the card — or this never resolves.
        assertEquals("e1", vm.state.first { it.events.first().opened }.events.first().id)
    }

    @Test fun `tapping an already opened event sends nothing`() = runTest {
        backend.feed = "[${eventJson("e1", opened = true)}]"
        val vm = viewModel()
        backend.api.loadEventsFirstPage(P1)
        val issuedBefore = backend.requestsIssued

        vm.onEventTap(vm.state.value.events.first())

        assertEquals("e1", vm.openEvent.value?.id)
        assertEquals(issuedBefore, backend.requestsIssued)
        assertTrue(backend.patchBodies.isEmpty())
    }

    // -----------------------------------------------------------------------
    // The deep link
    // -----------------------------------------------------------------------

    /**
     * The warm case: the event is already in the loaded page, so it is presented
     * from the cache without a round trip, and the latch is spent.
     */
    @Test fun `a deep link to a loaded event opens it from the cache`() = runTest {
        backend.feed = "[${eventJson("e1")}]"
        val vm = viewModel()
        backend.api.loadEventsFirstPage(P1)
        val requestsBefore = backend.requestsIssued

        router.bufferEventTap(eventId = "e1", patientId = P1)

        assertEquals("e1", vm.openEvent.first { it != null }?.id)
        assertEquals(requestsBefore, backend.requestsIssued)
        assertNull(router.state.value.focusEventId)
    }

    /**
     * The cold case: a notification for an event older than the newest 30, or a
     * launch with nothing loaded yet. `GET /v1/events/{id}` is authoritative.
     */
    @Test fun `a deep link to an event outside the page fetches it by id`() = runTest {
        backend.single = eventJson("old")
        val vm = viewModel()

        router.bufferEventTap(eventId = "old", patientId = P1)

        assertEquals("old", vm.openEvent.first { it != null }?.id)
        assertEquals("/v1/events/old", backend.sent.single().url.encodedPath)
        assertNull(router.state.value.focusEventId)
    }

    /**
     * An event that resolves to a different patient must not be presented inside
     * this patient's shell — but the latch is still spent, because a latch that
     * cannot be cleared re-fires on every recomposition.
     */
    @Test fun `a deep-linked event belonging to another patient is not presented`() = runTest {
        backend.single = eventJson("x", patientId = OTHER_PATIENT)
        val vm = viewModel()

        router.bufferEventTap(eventId = "x", patientId = P1)
        router.state.first { it.focusEventId == null }

        assertNull(vm.openEvent.value)
    }

    /** A dead tap leaves the caregiver on the tab; it must not leave a stuck latch. */
    @Test fun `a deep link to a deleted event clears the latch anyway`() = runTest {
        backend.singleStatus = HttpStatusCode.NotFound
        val vm = viewModel()

        router.bufferEventTap(eventId = "gone", patientId = P1)
        router.state.first { it.focusEventId == null }

        assertNull(vm.openEvent.value)
        // Nothing is said to the caregiver about it, exactly as on iOS.
        assertNull(vm.state.value.reviewError)
    }

    // -----------------------------------------------------------------------
    // The review submit
    // -----------------------------------------------------------------------

    /**
     * Optimistic: the row reads reviewed and the detail is dismissed **before**
     * the server answers, which is the whole reason iOS writes the cache by hand
     * instead of waiting for `updateEvent` to patch it.
     */
    @Test fun `a review is written into the cache and dismissed before the server answers`() =
        runTest {
            // Already opened, so the tap below sends nothing and the only PATCH
            // in this test is the review's.
            backend.feed = "[${eventJson("e1", opened = true)}]"
            backend.patchGate = CompletableDeferred()
            backend.patchResponse = eventJson("e1", opened = true, reviewed = true)
            val vm = viewModel()
            backend.api.loadEventsFirstPage(P1)
            val event = vm.state.value.events.first()
            vm.onEventTap(event)

            vm.submitReview(event, DetectionEvent.ReviewChoice.WRONG, "tripped on the rug")

            // The server has not answered and cannot: the gate is still shut.
            val row = vm.state.value.events.first()
            assertTrue(row.reviewed)
            assertEquals(DetectionEvent.ReviewChoice.WRONG, row.reviewChoice)
            assertEquals("tripped on the rug", row.reviewNotes)
            // iOS's onSubmitted dismisses the whole sheet back to the list,
            // which also un-pauses the feed poll.
            assertNull(vm.openEvent.value)
            assertNull(vm.state.value.reviewError)

            // The PATCH carries the four fields it was given and nothing else.
            assertEquals(
                """{"opened":true,"reviewed":true,"review_choice":"wrong",""" +
                    """"review_notes":"tripped on the rug"}""",
                backend.awaitPatch(),
            )
            backend.patchGate?.complete(Unit)
        }

    /**
     * A failed submit rolls the row back to the snapshot and publishes the
     * banner. The sentence is the API layer's own copy, wrapped in the two
     * sentences iOS wraps it in — verbatim, because it is what a caregiver reads.
     */
    @Test fun `a failed review rolls the row back and publishes the banner copy`() = runTest {
        backend.feed = "[${eventJson("e1")}]"
        backend.patchStatus = HttpStatusCode.NotFound
        val vm = viewModel()
        backend.api.loadEventsFirstPage(P1)
        val event = vm.state.value.events.first()

        vm.submitReview(event, DetectionEvent.ReviewChoice.CORRECT, "")

        val state = vm.state.first { it.reviewError != null }
        assertEquals(
            "Couldn't save your review. That isn't here anymore. Tap the event to try again.",
            state.reviewError,
        )
        val row = state.events.first()
        assertFalse(row.reviewed)
        assertFalse(row.opened)
        assertNull(row.reviewChoice)

        vm.dismissReviewError()
        assertNull(vm.state.first { it.reviewError == null }.reviewError)
    }

    /** iOS sends `nil` for empty notes rather than an empty string. */
    @Test fun `empty notes are left out of the request entirely`() = runTest {
        backend.feed = "[${eventJson("e1")}]"
        backend.patchResponse = eventJson("e1", opened = true, reviewed = true)
        val vm = viewModel()
        backend.api.loadEventsFirstPage(P1)

        vm.submitReview(vm.state.value.events.first(), DetectionEvent.ReviewChoice.CORRECT, "")

        assertEquals(
            """{"opened":true,"reviewed":true,"review_choice":"correct"}""",
            backend.awaitPatch(),
        )
    }
}
