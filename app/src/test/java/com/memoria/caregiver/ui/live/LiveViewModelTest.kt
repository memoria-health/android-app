@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.memoria.caregiver.ui.live

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.memoria.caregiver.data.livekit.LiveKitConnectionState
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.realtime.CameraLiveState
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Collections
import kotlin.time.ExperimentalTime

/**
 * The producer for the Live tab.
 *
 * These tests exist because of FINDINGS.md F-40: every part of the live-video
 * unit was written, unit-tested and **unreachable**, because nothing joined the
 * room client to the API client. This class is that join, so leaving it untested
 * would repeat the same mistake one layer up — a wiring layer that compiles and
 * is never exercised.
 *
 * What is checked here is the wiring's own decisions, not the parts either side
 * of it: which room is joined and with what grants, when a join is skipped, what
 * happens when there is nobody signed in, that the bulk levers PATCH the right
 * rows, and that the two teardown moments stay two.
 *
 * Robolectric, for one reason only: [PeopleBlurStore] takes real
 * `SharedPreferences`. There is no Android behaviour under test.
 */
@RunWith(RobolectricTestRunner::class)
class LiveViewModelTest {

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 3_000L
    }

    private val patientId = "pat-1"
    private val caregiverId = "care-1"

    /** Routes the fake backend was asked for, in arrival order. */
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    private val responses = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, HttpStatusCode>()

    private lateinit var store: ViewModelStore
    private val room = FakeLiveRoom()
    private val acks = RecordingAckStore()

    @Before
    fun setUp() {
        // Unconfined so `init`'s launches reach their first suspension point
        // eagerly and a test does not have to advance time just to see a
        // connect.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = ViewModelStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * `runTest`, plus the teardown that has to happen **inside** it.
     *
     * `LiveViewModel.startClock()` is a `while (isActive)` loop with a 1s delay,
     * which is correct in the app and fatal here: when the test body finishes,
     * `runTest` advances the virtual clock until the scheduler is idle, and a
     * loop that always has a next delay is never idle. The first version of this
     * file cancelled the store from `@After` — which runs *after* `runTest` has
     * already hung — and the suite timed out at ten minutes with no failure
     * message, which is a worse outcome than a red test.
     *
     * Clearing the store cancels `viewModelScope`, the loop's `isActive` goes
     * false, and the scheduler drains. In a helper rather than at the end of
     * fifteen test bodies, because the one that forgets it hangs the build.
     */
    private fun liveTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    // ---- Harness ----

    private fun makeApi(): NemaraApiClient {
        val engine = MockEngine { request ->
            val route = "${request.method.value} ${request.url.encodedPath}"
            seen += route
            respond(
                content = responses[route] ?: "",
                status = statuses[route] ?: HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return NemaraApiClient(
            http = HttpTransport(
                client = HttpClient(engine) { install(ContentNegotiation) { json(NemaraJson.json) } },
                baseUrl = "https://api.example.test",
                tokenProvider = { "jwt" },
            ),
            live = CameraLiveState(),
        )
    }

    private fun makeViewModel(
        api: NemaraApiClient,
        caregiver: () -> String? = { caregiverId },
    ): LiveViewModel {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("test-blur", Context.MODE_PRIVATE)
        return ViewModelProvider(
            store,
            viewModelFactory {
                initializer {
                    LiveViewModel(
                        patientId = patientId,
                        api = api,
                        liveKit = room,
                        caregiverId = caregiver,
                        ackStore = acks,
                        peopleBlur = PeopleBlurStore(prefs),
                        clipCaptureEnabled = false,
                        now = { 0L },
                    )
                }
            },
        )[LiveViewModel::class.java]
    }

    private fun tokenResponse() = """
        {"token":"lk-jwt","url":"wss://lk.example.test","expires_at":"2030-01-01T00:00:00Z"}
    """.trimIndent()

    private fun activeCamera(id: String?) =
        if (id == null) """{"camera_id":null}""" else """{"camera_id":"$id"}"""

    /**
     * Puts cameras in the client's cache the way the app does — through the
     * route — rather than through a setter that exists only for tests. A
     * test-only door into the cache would let these tests pass over a client
     * that cannot actually load a camera.
     */
    private suspend fun NemaraApiClient.loadCamerasFrom(cameras: List<Camera>) {
        responses["GET /v1/patients/$patientId/cameras"] =
            NemaraJson.json.encodeToString(ListSerializer(Camera.serializer()), cameras)
        loadCameras(patientId)
    }

    /**
     * Keeps [LiveViewModel.state] hot. `stateIn(WhileSubscribed)` produces
     * nothing without a collector, so every assertion about state needs one.
     */
    private fun TestScope.collectState(vm: LiveViewModel) {
        backgroundScope.launch { vm.state.collect { } }
        settle()
    }

    /**
     * Runs everything already queued at the current virtual time, and does not
     * advance the clock.
     *
     * Needed because `init` starts the connect in a `launch` that suspends on
     * the token request: without this the test body races ahead of it and
     * asserts against a room that has not joined yet. Deliberately
     * `runCurrent()` and never `advanceUntilIdle()` — the 1s clock loop in
     * `startClock()` always has a next delay, so advancing to idle never
     * returns.
     */
    private fun TestScope.settle() {
        testScheduler.runCurrent()
    }

    /**
     * Pumps the scheduler until [condition] holds, or fails naming what it was
     * waiting for.
     *
     * [settle] alone is not enough whenever a round trip is involved: Ktor's
     * `MockEngine` completes the request on its own threads, so `runCurrent()`
     * happily returns having found nothing to do while the response is still in
     * flight, and the assertion then reads a ViewModel that has not connected
     * yet. Six tests in this file failed exactly that way.
     *
     * Real wall-clock, not virtual: the thing being waited on is a real thread,
     * and advancing virtual time would not make it finish any sooner. The
     * deadline is generous because it is only ever reached on a genuine failure,
     * where the cost is one slow red test rather than a flaky green one.
     */
    private fun TestScope.awaitThat(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            if (condition()) return
            Thread.sleep(1)
        }
        testScheduler.runCurrent()
        if (!condition()) throw AssertionError("timed out waiting for: $what")
    }

    // ---- Connect ----

    @Test
    fun `bootstrap mints a BROADCASTER token and joins the patient room`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)

        assertTrue("no token was minted", seen.contains("POST /v1/livekit/token"))
        assertEquals(1, room.connects.size)
        val (url, token, roomName) = room.connects.single()
        assertEquals("wss://lk.example.test", url)
        assertEquals("lk-jwt", token)
        // Broadcaster grants up front, even for plain viewing, so start/end of a
        // call is a mic toggle and the video tiles never blink. The role is in
        // the request body, which the API client builds; what this asserts is
        // that the ViewModel asked for the broadcaster mint and not the viewer
        // one — they are different methods on the same route.
        assertEquals("patient_$patientId", roomName)
    }

    @Test
    fun `a second bootstrap for the same connected room does not rejoin`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)
        assertEquals(1, room.connects.size)

        // The Live -> Events -> Live path. connect() is deliberately NOT
        // idempotent (it pre-disconnects), so a second join would drop every
        // tile and rebuild the room for nothing.
        vm.bootstrap()
        settle()

        assertEquals("rejoined a room it was already in", 1, room.connects.size)
    }

    @Test
    fun `forced bootstrap rejoins even when already connected`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)

        // reconnect-all needs a clean re-join: the wall phones left and
        // rejoined, and the SDK may have silently dropped subscriptions.
        vm.bootstrap(force = true)
        awaitThat("the forced rejoin") { room.connects.size == 2 }

        assertEquals(2, room.connects.size)
    }

    @Test
    fun `with nobody signed in it reports rather than connecting`() = liveTest {
        val vm = makeViewModel(makeApi(), caregiver = { null })
        collectState(vm)

        assertTrue("connected with no caregiver", room.connects.isEmpty())
        assertFalse("minted a token with no caregiver", seen.contains("POST /v1/livekit/token"))
        assertEquals("Not signed in.", vm.state.value.lkError)
    }

    @Test
    fun `a failed join surfaces the error and leaves the tab usable`() = liveTest {
        statuses["POST /v1/livekit/token"] = HttpStatusCode.InternalServerError
        val vm = makeViewModel(makeApi())
        collectState(vm)

        awaitThat("the failed mint to be reported") { vm.state.value.lkError != null }
        assertNotNull("a failed mint said nothing", vm.state.value.lkError)
        assertTrue(room.connects.isEmpty())
    }

    // ---- Selection ----

    @Test
    fun `the server's active-camera guess wins when that camera is online`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera("cam-2")
        val api = makeApi()
        val vm = makeViewModel(api)
        collectState(vm)
        // Two online cameras; the server prefers the second.
        api.loadCamerasFrom(
            listOf(
                liveCamera(id = "cam-1", livekitIdentity = "cam_one"),
                liveCamera(id = "cam-2", livekitIdentity = "cam_two"),
            ),
        )
        settle()
        vm.bootstrap(force = true)
        awaitThat("the carousel to settle on the server's suggestion") {
            vm.state.value.selectedCameraId != null
        }

        assertEquals("cam-2", vm.state.value.selectedCameraId)
    }

    @Test
    fun `an offline suggestion falls back to the first online camera`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        // The server names a camera that is not in the roster at all.
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera("cam-gone")
        val api = makeApi()
        val vm = makeViewModel(api)
        collectState(vm)
        api.loadCamerasFrom(listOf(liveCamera(id = "cam-1", livekitIdentity = "cam_one")))
        vm.bootstrap(force = true)
        awaitThat("the carousel to fall back to the first online camera") {
            vm.state.value.selectedCameraId != null
        }

        assertEquals("cam-1", vm.state.value.selectedCameraId)
    }

    // ---- Bulk levers ----

    @Test
    fun `disconnectAll PATCHes the cameras that were connected, not zero of them`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val api = makeApi()
        val vm = makeViewModel(api)
        collectState(vm)
        api.loadCamerasFrom(
            listOf(
                liveCamera(id = "cam-1", livekitIdentity = "cam_one"),
                liveCamera(id = "cam-2", livekitIdentity = "cam_two"),
                // Hidden: the caregiver has already said they do not want it, so
                // a bulk button must not touch it in either direction.
                liveCamera(id = "cam-3", livekitIdentity = "cam_three", disabled = true),
            ),
        )
        settle()
        seen.clear()

        vm.disconnectAll()
        awaitThat("both connected cameras to be PATCHed") {
            seen.count { it.startsWith("PATCH /v1/cameras/") } == 2
        }

        // The bug this guards: iOS flipped the local rows FIRST, so its
        // PATCH loop — filtered on desired_state == connected — found nothing
        // and disconnected nothing, and the next loadCameras brought them all
        // back. One call returns the rows and flips them, so the order cannot be
        // got wrong. See NemaraApiClient.flipDesiredStateLocally.
        assertEquals(1, seen.count { it == "PATCH /v1/cameras/cam-1" })
        assertEquals(1, seen.count { it == "PATCH /v1/cameras/cam-2" })
        assertEquals("touched a hidden camera", 0, seen.count { it == "PATCH /v1/cameras/cam-3" })
        assertTrue(seen.contains("PATCH /v1/patients/$patientId"))
    }

    @Test
    fun `reconnectAll re-asserts connect on the disconnected cameras`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val api = makeApi()
        val vm = makeViewModel(api)
        collectState(vm)
        api.loadCamerasFrom(
            listOf(
                liveCamera(id = "cam-1", desiredState = Camera.DesiredState.DISCONNECTED),
                liveCamera(id = "cam-2", desiredState = Camera.DesiredState.CONNECTED),
            ),
        )
        settle()
        seen.clear()

        vm.reconnectAll()
        awaitThat("the disconnected camera to be PATCHed") {
            seen.any { it == "PATCH /v1/cameras/cam-1" }
        }

        // Only the disconnected one needs re-asserting; the connected one is
        // already where the caregiver wants it.
        assertEquals(1, seen.count { it == "PATCH /v1/cameras/cam-1" })
        assertEquals(0, seen.count { it == "PATCH /v1/cameras/cam-2" })
    }

    // ---- Privacy ----

    @Test
    fun `a private camera has its audio revoked, not merely silenced`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val api = makeApi()
        val vm = makeViewModel(api)
        collectState(vm)

        api.loadCamerasFrom(
            listOf(liveCamera(id = "cam-1", livekitIdentity = "cam_one", privacyEnabled = true)),
        )
        settle()

        // revokeAudio removes the identity from the enabled set. Zeroing the
        // volume instead would let the next resubscribe re-arm playback in a
        // private room — PARITY.md §11, §29 item 5.
        assertTrue("private camera was not revoked", room.called("revokeAudio(cam_one)"))
    }

    // ---- Acknowledgements ----

    @Test
    fun `dismissing the private-room explainer persists it`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)

        vm.acknowledgeSensitiveRoom("cam-1")
        settle()

        assertEquals(setOf("cam-1"), acks.saved)
        assertEquals(setOf("cam-1"), vm.state.value.acknowledgedSensitiveRoomIds)
    }

    // ---- The two teardown moments ----

    @Test
    fun `leaving the Live tab does NOT drop the room`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)

        vm.onLeaveLiveTab()
        settle()

        // This is what makes Live -> Events -> Live instant. Disconnecting here
        // would rejoin the room on every sub-tab switch.
        assertFalse("a sub-tab switch tore the room down", room.called("disconnect("))
    }

    @Test
    fun `closing the patient drops the room`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)

        vm.onClosePatient()
        settle()

        assertTrue(room.called("disconnect("))
        assertEquals(LiveKitConnectionState.Disconnected, room.state.value)
    }

    // ---- Tiles ----

    @Test
    fun `no room is handed to the tiles while disconnected`() = liveTest {
        val vm = makeViewModel(makeApi(), caregiver = { null })
        collectState(vm)

        // A track bound to a torn-down room renders black and never recovers,
        // so the state carries the room only while connected. The fake has no
        // SDK room at all, which is why this asserts the disconnected half.
        assertNull(vm.state.value.room)
    }

    @Test
    fun `toggling a tile's audio reaches the room client`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)

        vm.toggleAudio("cam_one")
        settle()

        assertTrue(room.called("toggleAudio(cam_one)"))
        assertEquals(setOf("cam_one"), vm.state.value.audioEnabledIdentities)
    }

    @Test
    fun `clip capture is inert when the build has it disabled`() = liveTest {
        responses["POST /v1/livekit/token"] = tokenResponse()
        responses["GET /v1/patients/$patientId/active-camera"] = activeCamera(null)
        val vm = makeViewModel(makeApi())
        collectState(vm)
        seen.clear()

        vm.captureClip("cam-1")
        settle()

        // Release builds hard-false the flag and never render the button. Belt
        // and braces: reaching the method anyway must not POST to a route that
        // only exists when NEMARA_ENABLE_INTERNALS is on.
        assertTrue(seen.none { it.contains("record-clip") })
        assertNull(vm.state.value.captureNote)
    }
}
