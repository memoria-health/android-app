package com.memoria.caregiver.ui.support

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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SupportContactViewModel] behavior. Spec:
 * `Features/AppSettings/SupportContactView.swift`, PARITY.md §28.
 *
 * The real client under a Ktor [MockEngine], as `AppSettingsViewModelTest` is:
 * what is worth pinning here — the exact bytes posted, and the failure sentence
 * — are properties of this object plus the client together, and a fake client
 * would define both away.
 *
 * Plain JVM: a `ViewModel` needs no Android runtime, and `viewModelScope` needs
 * only the Main dispatcher [setMain] installs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportContactViewModelTest {

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    /** The fake backend behind the real client. */
    private class Backend {

        var status: String = """{"status":"Sent. We'll reply to your account email."}"""
        var sendStatus: HttpStatusCode = HttpStatusCode.OK

        /** Held open, so a test can observe the in-flight window exactly. */
        var gate: CompletableDeferred<Unit>? = null

        /** Bodies of every `POST /v1/support`, in order. */
        val bodies: MutableList<String> = mutableListOf()

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = HttpClient(
                    MockEngine { request ->
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        if (request.url.encodedPath == "/v1/support") {
                            bodies += String(request.body.toByteArray())
                            gate?.await()
                            respond(status, sendStatus, json)
                        } else {
                            respond("[]", headers = json)
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

    /**
     * One dispatcher, shared by `Dispatchers.Main` and by `runTest`, so there is
     * exactly one scheduler in play. `StandardTestDispatcher` rather than an
     * unconfined one for the reason `CameraDetailViewModelTest` records: Ktor's
     * `MockEngine` really does hop threads, and an unconfined dispatcher resumes
     * on whichever thread completed the call, through an event loop this test
     * cannot drain.
     *
     * It also makes the 800ms dwell virtual, so [timeline] can assert it without
     * anything sleeping.
     */
    private val mainDispatcher = StandardTestDispatcher()

    /** Every view model built in a test, so [tearDownMain] can cancel them. */
    private val viewModels = mutableListOf<SupportContactViewModel>()

    @Before fun setUp() {
        backend = Backend()
        Dispatchers.setMain(mainDispatcher)
    }

    /**
     * `viewModelScope` outlives `runTest` — on Android it is cancelled in
     * `onCleared`, which nothing calls here — so a launch still parked on a
     * gated request would leak into the next test. Cancelled before
     * [resetMain], because cancelling afterwards would resume the coroutines on
     * a dispatcher that has already been torn down.
     */
    @After fun tearDownMain() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): SupportContactViewModel =
        SupportContactViewModel(api = backend.api).also { viewModels += it }

    /**
     * Wait for every coroutine an action started, and for nothing else.
     *
     * The send is a fire-and-forget `viewModelScope.launch` and `MockEngine`
     * hops threads, so neither draining the scheduler nor joining alone
     * finishes it. This view model has no never-ending sharing coroutine — its
     * state is a plain `MutableStateFlow` — so every child is fair game.
     */
    private suspend fun ViewModel.settle() {
        while (true) {
            mainDispatcher.scheduler.advanceUntilIdle()
            val pending = viewModelScope.coroutineContext.job.children.toList()
            if (pending.isEmpty()) break
            pending.joinAll()
        }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    /** The last posted body, parsed so key order and spacing cannot fail a test. */
    private fun lastBody() = Json.parseToJsonElement(backend.bodies.last())

    /** The body a send should have produced, built rather than hand-escaped. */
    private fun expected(subject: String, message: String) = buildJsonObject {
        put("subject", JsonPrimitive(subject))
        put("message", JsonPrimitive(message))
    }

    // -----------------------------------------------------------------------
    // What gets posted
    // -----------------------------------------------------------------------

    @Test fun `the typed subject and message are posted`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onSubjectChange("Camera keeps dropping")
        vm.onMessageChange("The kitchen one goes offline every evening.")

        vm.send()
        vm.settle()

        assertEquals(1, backend.bodies.size)
        assertEquals(
            expected("Camera keeps dropping", "The kitchen one goes offline every evening."),
            lastBody(),
        )
    }

    /**
     * The two fields are trimmed with **different** character sets: the subject
     * with Swift's `.whitespaces`, the message with `.whitespacesAndNewlines`.
     * A subject that is nothing but a newline therefore survives trimming and
     * goes out as "\n", while the same characters in the message would be
     * stripped. Collapsing the two would change what lands in the founders'
     * inbox.
     */
    @Test fun `the subject keeps newlines the message loses`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onSubjectChange("  \nHelp\n  ")
        vm.onMessageChange("  \n camera down \n  ")

        vm.send()
        vm.settle()

        assertEquals(expected("\nHelp\n", "camera down"), lastBody())
    }

    /**
     * `canSend` is the whole guard. iOS gets it from `.disabled(!canSend)` on
     * the toolbar button; there is no disabled button in a function call, so it
     * is re-checked here.
     */
    @Test fun `a blank message posts nothing`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onSubjectChange("Help")
        vm.onMessageChange("   \n  ")

        vm.send()
        vm.settle()

        assertEquals(emptyList<String>(), backend.bodies)
        assertFalse(vm.state.value.isSending)
        assertFalse(vm.state.value.dismissed)
    }

    /** A second tap while the first is in flight must not send a duplicate. */
    @Test fun `a second tap mid-flight posts nothing`() = runTest(mainDispatcher) {
        backend.gate = CompletableDeferred()
        val vm = viewModel()
        vm.onMessageChange("camera down")

        vm.send()
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.send()

        backend.gate?.complete(Unit)
        vm.settle()

        assertEquals(1, backend.bodies.size)
    }

    // -----------------------------------------------------------------------
    // The successful tail
    // -----------------------------------------------------------------------

    /**
     * The confirmation has to be readable before the sheet goes, which is the
     * whole reason `send()` sleeps. Recorded as a timeline because the two facts
     * being asserted are ordering and duration, and neither is visible in the
     * final state.
     *
     * The dwell is virtual time on the shared scheduler, so this costs nothing
     * in wall clock.
     */
    @Test fun `the confirmation is readable for 800ms before the sheet closes`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            vm.onMessageChange("camera down")

            val timeline = mutableListOf<Pair<Long, SupportContactState>>()
            val collector = launch {
                vm.state.collect { timeline += testScheduler.currentTime to it }
            }

            vm.send()
            vm.settle()
            collector.cancel()

            val shown = timeline.first { it.second.successMessage != null }
            val closed = timeline.first { it.second.dismissed }

            assertEquals(
                "Sent. We'll reply to your account email.",
                shown.second.successMessage,
            )
            // Shown first, closed after — not both in the same tick.
            assertFalse("the sheet closed on the same tick it confirmed", shown.second.dismissed)
            assertEquals(800L, closed.first - shown.first)
        }

    /**
     * iOS releases `isSending` in a `defer`, so it stays true across the sleep
     * and is only cleared as the sheet goes. If it were released with the
     * confirmation, the spinner would flick back to "Send" for the last
     * fraction of a second before the sheet closed.
     */
    @Test fun `the spinner stays up until the sheet closes`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onMessageChange("camera down")

        val timeline = mutableListOf<SupportContactState>()
        val collector = launch { vm.state.collect { timeline += it } }

        vm.send()
        vm.settle()
        collector.cancel()

        assertTrue(timeline.first { it.successMessage != null }.isSending)
        assertFalse(vm.state.value.isSending)
        assertTrue(vm.state.value.dismissed)
    }

    // -----------------------------------------------------------------------
    // Failure
    // -----------------------------------------------------------------------

    /**
     * The failure sentence is [ApiError.errorDescription] verbatim — iOS shows
     * `localizedDescription` with no prefix. That copy is the product's, so a
     * paraphrase here would be a silent change.
     */
    @Test fun `a failure surfaces the API copy and keeps the sheet open`() =
        runTest(mainDispatcher) {
            backend.sendStatus = HttpStatusCode.Forbidden
            backend.status = ""
            val vm = viewModel()
            vm.onMessageChange("camera down")

            vm.send()
            vm.settle()

            assertEquals(ApiError.Forbidden.errorDescription, vm.state.value.errorMessage)
            assertNull(vm.state.value.successMessage)
            assertFalse(vm.state.value.isSending)
            assertFalse("a failed send must not close the sheet", vm.state.value.dismissed)
        }

    /**
     * A retry starts clean. Leaving the previous sentence up while a new request
     * is in flight would have the caregiver reading a failure that no longer
     * applies.
     */
    @Test fun `a retry clears the previous failure`() = runTest(mainDispatcher) {
        backend.sendStatus = HttpStatusCode.Forbidden
        backend.status = ""
        val vm = viewModel()
        vm.onMessageChange("camera down")
        vm.send()
        vm.settle()

        backend.sendStatus = HttpStatusCode.OK
        backend.status = """{"status":"Sent."}"""
        backend.gate = CompletableDeferred()
        vm.send()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertNull("the stale failure is still on screen", vm.state.value.errorMessage)

        backend.gate?.complete(Unit)
        vm.settle()
        assertEquals("Sent.", vm.state.value.successMessage)
    }

    /**
     * FINDINGS.md F-12, in this object's shape: the send suspends, and a caller
     * who kept typing while it was in flight must not have their edits erased by
     * the write that reports the failure. Every write goes through
     * [kotlinx.coroutines.flow.MutableStateFlow.update], which composes over
     * whatever landed in between; assigning a pre-await snapshot would not.
     */
    @Test fun `an edit made mid-flight survives the failure write`() = runTest(mainDispatcher) {
        backend.sendStatus = HttpStatusCode.Forbidden
        backend.status = ""
        backend.gate = CompletableDeferred()
        val vm = viewModel()
        vm.onMessageChange("camera down")

        vm.send()
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.onMessageChange("camera down every evening")
        vm.onSubjectChange("Kitchen")

        backend.gate?.complete(Unit)
        vm.settle()

        assertEquals("camera down every evening", vm.state.value.message)
        assertEquals("Kitchen", vm.state.value.subject)
        assertEquals(ApiError.Forbidden.errorDescription, vm.state.value.errorMessage)
        // The request carried what was typed when the caregiver tapped, not what
        // the field said by the time it came back.
        assertEquals(expected("", "camera down"), lastBody())
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    @Test fun `cancel closes the sheet without posting`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onMessageChange("camera down")

        vm.cancel()
        vm.settle()

        assertTrue(vm.state.value.dismissed)
        assertEquals(emptyList<String>(), backend.bodies)
    }
}
