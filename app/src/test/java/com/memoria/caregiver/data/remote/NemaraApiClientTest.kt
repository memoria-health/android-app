@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.CreateDetectorRequest
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.EventListResponse
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.model.UpdateCameraRequest
import com.memoria.caregiver.data.realtime.CameraLiveState
import com.memoria.caregiver.data.realtime.EverOnlineStore
import com.memoria.caregiver.data.realtime.Fx
import com.memoria.caregiver.data.realtime.RealtimeStore
import com.memoria.caregiver.data.realtime.TestClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Collections
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The observable cache inside [NemaraApiClient]. Spec: the lifecycle, cameras,
 * detectors and events blocks of `Services/NemaraAPIClient.swift`.
 *
 * The six `XxxApi` groups already have their own tests for what goes on the
 * wire, so nothing here re-checks a path or a request body. What is tested is
 * what the client does with the answer: which patient key a row lands under,
 * whether a load replaces or appends, what survives a refresh, what is thrown
 * away on sign-out, and the two pieces of derived camera state the API client
 * writes rather than the Realtime store. Those are the behaviours a caregiver
 * feels — a stale tile, a duplicated feed, a spinner that never stops — and
 * none of them are visible from the request side.
 *
 * The fake backend below answers by route (method, path, and the paging cursor
 * when there is one) rather than by call order, because bootstrap fires every
 * patient's three loads at once and their order is not fixed.
 */
class NemaraApiClientTest {

    private val p1 = "p1"
    private val p2 = "p2"

    /** Routes the fake backend was asked for, in arrival order. */
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** Route to response body. A route with no entry answers with an empty body. */
    private val responses = mutableMapOf<String, String>()

    /** Route to status, for the tests that need a call to fail. */
    private val statuses = mutableMapOf<String, HttpStatusCode>()

    /**
     * Runs just before a route answers. Lets a test hold one response open, or
     * read the client's state at the moment a request is in flight.
     */
    private var beforeRespond: suspend (String) -> Unit = {}

    private fun makeClient(live: CameraLiveState = CameraLiveState()): NemaraApiClient {
        val engine = MockEngine { request ->
            val cursor = request.url.parameters["cursor"]
            val route = "${request.method.value} ${request.url.encodedPath}" +
                (cursor?.let { "?cursor=$it" } ?: "")
            seen += route
            beforeRespond(route)
            respond(
                content = responses[route] ?: "",
                status = statuses[route] ?: HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }
        return NemaraApiClient(
            http = HttpTransport(
                client = HttpClient(engine) { install(ContentNegotiation) { json(NemaraJson.json) } },
                baseUrl = "https://api.example.test",
                tokenProvider = { "jwt" },
            ),
            live = live,
        )
    }

    // Response bodies are ENCODED from real models through the app's own JSON
    // config, the same way the Realtime fixtures are built, so a field renamed
    // on the model cannot leave a hand-written body silently disagreeing with
    // the wire.

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
        NemaraJson.json.encodeToString(serializer, value)

    private fun servePatients(vararg patients: Patient) {
        responses["GET /v1/patients"] =
            encode(ListSerializer(Patient.serializer()), patients.toList())
    }

    private fun serveCameras(patientId: String, vararg cameras: Camera) {
        responses["GET /v1/patients/$patientId/cameras"] =
            encode(ListSerializer(Camera.serializer()), cameras.toList())
    }

    private fun serveDetectors(patientId: String, vararg detectors: Detector) {
        responses["GET /v1/patients/$patientId/detectors"] =
            encode(ListSerializer(Detector.serializer()), detectors.toList())
    }

    private fun serveEvents(
        patientId: String,
        vararg events: DetectionEvent,
        nextCursor: String? = null,
        cursor: String? = null,
    ) {
        val route = "GET /v1/patients/$patientId/events" + (cursor?.let { "?cursor=$it" } ?: "")
        responses[route] = encode(
            EventListResponse.serializer(),
            EventListResponse(items = events.toList(), nextCursor = nextCursor),
        )
    }

    /** Everything one patient needs for a bootstrap or refresh fan-out to succeed. */
    private fun serveEmptyCollections(patientId: String) {
        serveCameras(patientId)
        serveDetectors(patientId)
        serveEvents(patientId)
    }

    /** Remembers what was written, so a "survives a relaunch" claim can be checked. */
    private class RecordingEverOnlineStore(private var ids: Set<String> = emptySet()) :
        EverOnlineStore {
        var saves = 0
            private set

        override fun load(): Set<String> = ids

        override fun save(ids: Set<String>) {
            this.ids = ids.toSet()
            saves += 1
        }
    }

    // ---- loadCameras and the ever-online mark ----

    /**
     * The mark is what stops an established camera falling back to "Sensor is
     * preparing to start…" the next time it happens to be offline. A camera
     * that has never been online must NOT be marked, or a genuinely new sensor
     * would skip the preparing notice on its very first connect and just look
     * broken.
     */
    @Test fun `loadCameras marks the online cameras as ever-online and leaves the offline ones alone`() =
        runTest {
            val store = RecordingEverOnlineStore()
            val client = makeClient(CameraLiveState(store))
            val up = Fx.camera("cam-up", patientId = p1, online = true)
            val down = Fx.camera("cam-down", patientId = p1, online = false)
            serveCameras(p1, up, down)

            client.loadCameras(p1)

            assertEquals(setOf("cam-up"), store.load())
            assertFalse("a camera seen online must not read as preparing", client.isPreparing(up))
            assertTrue("a camera never seen online is still preparing", client.isPreparing(down))
        }

    /**
     * The half that is invisible until a cold start: the mark is persisted, so
     * a camera that was online yesterday still reads as "offline" rather than
     * "preparing" after the app is relaunched. Losing the write would look
     * fine for the whole of the session that lost it.
     */
    @Test fun `the ever-online mark survives a relaunch, so an offline camera does not regress to preparing`() =
        runTest {
            val store = RecordingEverOnlineStore()
            val client = makeClient(CameraLiveState(store))
            serveCameras(p1, Fx.camera("cam-up", patientId = p1, online = true))

            client.loadCameras(p1)

            // A new CameraLiveState over the same storage is what the next
            // launch builds.
            val afterRelaunch = CameraLiveState(RecordingEverOnlineStore(store.load()))
            val nowOffline = Fx.camera("cam-up", patientId = p1, online = false)
            assertFalse(
                "a camera that was online yesterday must not read as preparing today",
                afterRelaunch.isPreparing(nowOffline),
            )
        }

    @Test fun `loadCameras caches the rows under their patient`() = runTest {
        val client = makeClient()
        serveCameras(p1, Fx.camera("c1", patientId = p1))

        client.loadCameras(p1)

        assertEquals(listOf("c1"), client.camerasByPatient[p1]?.map { it.id })
    }

    // ---- updateCamera and the reconnect window ----

    /**
     * "Connecting…" only makes sense when the caregiver has just asked for the
     * camera back. The window is opened by comparing the server's row against
     * the CACHED one, and the comparison has to happen before the cached row is
     * replaced: after the swap both sides are the server's row, the edge never
     * reads as an edge, and the Live tile sits blank instead of saying it is
     * coming up.
     */
    @Test fun `updateCamera opens the reconnect window on the disconnected to connected edge`() =
        runTest {
            val clock = TestClock(1_000L)
            val client = makeClient(CameraLiveState(now = clock))
            val cached = Fx.camera(
                "c1",
                patientId = p1,
                desiredState = Camera.DesiredState.DISCONNECTED,
                online = false,
            )
            client.camerasByPatient = mapOf(p1 to listOf(cached))
            val reconnected = cached.copy(desiredState = Camera.DesiredState.CONNECTED)
            responses["PATCH /v1/cameras/c1"] = encode(Camera.serializer(), reconnected)

            val updated = client.updateCamera(
                "c1",
                UpdateCameraRequest(desiredState = Camera.DesiredState.CONNECTED),
            )

            assertTrue(
                "the tile must say Connecting… while the sensor comes back up",
                client.isConnecting(updated),
            )
        }

    /**
     * The other side of the edge. A camera that was already connected — a room
     * rename, an AI pause — must not put the tile into "Connecting…", which
     * would read as an outage that is not happening.
     */
    @Test fun `updateCamera does not open the window when the camera was already connected`() =
        runTest {
            val clock = TestClock(1_000L)
            val client = makeClient(CameraLiveState(now = clock))
            val cached = Fx.camera(
                "c1",
                patientId = p1,
                desiredState = Camera.DesiredState.CONNECTED,
                online = false,
            )
            client.camerasByPatient = mapOf(p1 to listOf(cached))
            val renamed = cached.copy(room = "Kitchen")
            responses["PATCH /v1/cameras/c1"] = encode(Camera.serializer(), renamed)

            val updated = client.updateCamera("c1", UpdateCameraRequest(disabled = false))

            assertFalse(
                "a camera that never went away must not claim to be reconnecting",
                client.isConnecting(updated),
            )
        }

    @Test fun `updateCamera swaps the server's row into the cache and leaves the others alone`() =
        runTest {
            val client = makeClient()
            client.camerasByPatient = mapOf(
                p1 to listOf(
                    Fx.camera("c1", patientId = p1, room = "Hall"),
                    Fx.camera("c2", patientId = p1, room = "Den"),
                ),
            )
            responses["PATCH /v1/cameras/c1"] =
                encode(Camera.serializer(), Fx.camera("c1", patientId = p1, room = "Kitchen"))

            client.updateCamera("c1", UpdateCameraRequest(disabled = false))

            assertEquals(listOf("Kitchen", "Den"), client.camerasByPatient[p1]?.map { it.room })
        }

    // ---- Server order ----
    //
    // None of these three sort. The Live grid, the detector list and the roster
    // are ordered by the backend, which is the only place that knows the
    // caregiver's arrangement; a sort added here would quietly reorder every
    // screen and disagree with the poll's diff.

    @Test fun `loadPatients keeps the server's order`() = runTest {
        val client = makeClient()
        servePatients(
            Fx.patient(p1, preferredName = "Zoe"),
            Fx.patient(p2, preferredName = "Ann"),
        )

        client.loadPatients()

        assertEquals(listOf("Zoe", "Ann"), client.patients.map { it.preferredName })
    }

    @Test fun `loadCameras keeps the server's order`() = runTest {
        val client = makeClient()
        serveCameras(
            p1,
            Fx.camera("c3", patientId = p1, displayId = 3),
            Fx.camera("c1", patientId = p1, displayId = 1),
        )

        client.loadCameras(p1)

        assertEquals(listOf(3, 1), client.camerasByPatient[p1]?.map { it.displayId })
    }

    @Test fun `loadDetectors keeps the server's order`() = runTest {
        val client = makeClient()
        serveDetectors(
            p1,
            Fx.detector("d1", patientId = p1).copy(name = "Zebra"),
            Fx.detector("d2", patientId = p1).copy(name = "Ant"),
        )

        client.loadDetectors(p1)

        assertEquals(listOf("Zebra", "Ant"), client.detectorsByPatient[p1]?.map { it.name })
    }

    // ---- Deletes ----

    /**
     * A deleted patient takes their whole household with them. Leaving any of
     * the three behind keeps that home's cameras, detectors and events in
     * memory under an id nothing points at any more.
     */
    @Test fun `deletePatient removes the patient and all three sub-collections`() = runTest {
        val client = makeClient()
        client.patients = listOf(Fx.patient(p1), Fx.patient(p2))
        client.camerasByPatient = mapOf(p1 to listOf(Fx.camera("c1")), p2 to listOf(Fx.camera("c2")))
        client.detectorsByPatient =
            mapOf(p1 to listOf(Fx.detector("d1")), p2 to listOf(Fx.detector("d2")))
        client.eventsByPatient = mapOf(p1 to listOf(Fx.event("e1")), p2 to listOf(Fx.event("e2")))

        client.deletePatient(p1)

        assertEquals(listOf(p2), client.patients.map { it.id })
        assertEquals(setOf(p2), client.camerasByPatient.keys)
        assertEquals(setOf(p2), client.detectorsByPatient.keys)
        assertEquals(setOf(p2), client.eventsByPatient.keys)
    }

    /**
     * The Swift stops at the first patient holding the id, and this matches it.
     * An id can only belong to one patient in practice, so the point of the
     * stop is that a delete never sweeps a second household's list — pinned
     * here by planting the same id twice.
     */
    @Test fun `deleteCamera removes the id from only the first patient holding it`() = runTest {
        val client = makeClient()
        client.camerasByPatient = mapOf(
            p1 to listOf(Fx.camera("shared", patientId = p1)),
            p2 to listOf(Fx.camera("shared", patientId = p2)),
        )

        client.deleteCamera("shared")

        assertEquals(emptyList<String>(), client.camerasByPatient[p1]?.map { it.id })
        assertEquals(listOf("shared"), client.camerasByPatient[p2]?.map { it.id })
    }

    @Test fun `deleteDetector removes the id from only the first patient holding it`() = runTest {
        val client = makeClient()
        client.detectorsByPatient = mapOf(
            p1 to listOf(Fx.detector("shared", patientId = p1)),
            p2 to listOf(Fx.detector("shared", patientId = p2)),
        )

        client.deleteDetector("shared")

        assertEquals(emptyList<String>(), client.detectorsByPatient[p1]?.map { it.id })
        assertEquals(listOf("shared"), client.detectorsByPatient[p2]?.map { it.id })
    }

    // ---- Events paging ----

    /**
     * The first page is a "start over": it is what bootstrap, pull-to-refresh,
     * the foreground resync and the Events-tab poll all call. Appending it
     * instead would show the newest events twice, over and over, every time
     * the tab polls.
     */
    @Test fun `loadEventsFirstPage replaces the cached page`() = runTest {
        val client = makeClient()
        serveEvents(p1, Fx.event("e1", title = "Fall"))
        client.loadEventsFirstPage(p1)

        serveEvents(p1, Fx.event("e2", title = "Left the house"))
        client.loadEventsFirstPage(p1)

        assertEquals(listOf("e2"), client.eventsByPatient[p1]?.map { it.id })
    }

    /** Scrolling back through history adds to the feed; it must not restart it. */
    @Test fun `loadMoreEvents appends the next page and hands back the next cursor`() = runTest {
        val client = makeClient()
        serveEvents(p1, Fx.event("e1"), nextCursor = "c1")
        client.loadEventsFirstPage(p1)
        serveEvents(p1, Fx.event("e2"), nextCursor = "c2", cursor = "c1")

        val next = client.loadMoreEvents(p1, cursor = "c1")

        assertEquals(listOf("e1", "e2"), client.eventsByPatient[p1]?.map { it.id })
        assertEquals("c2", next)
    }

    /** The end of the feed is the server's null cursor, and it is passed straight through. */
    @Test fun `loadMoreEvents reports the end of the feed`() = runTest {
        val client = makeClient()
        client.eventsByPatient = mapOf(p1 to listOf(Fx.event("e1")))
        serveEvents(p1, Fx.event("e2"), nextCursor = null, cursor = "c1")

        assertNull(client.loadMoreEvents(p1, cursor = "c1"))
    }

    // ---- Detector adds ----

    @Test fun `createCustomDetector appends to the patient's list`() = runTest {
        val client = makeClient()
        client.detectorsByPatient = mapOf(p1 to listOf(Fx.detector("d1", patientId = p1)))
        responses["POST /v1/patients/$p1/detectors"] =
            encode(Detector.serializer(), Fx.detector("d2", patientId = p1))

        client.createCustomDetector(
            p1,
            CreateDetectorRequest(name = "Stove left on", customPrompt = "stove is on"),
        )

        assertEquals(listOf("d1", "d2"), client.detectorsByPatient[p1]?.map { it.id })
    }

    /**
     * The server skips keys the patient already has, so it can return fewer
     * rows than were asked for. Appending one row per requested key would put
     * detectors in the list that do not exist, and toggling one would 404.
     */
    @Test fun `addDetectorsFromLibrary appends what the server created, not what was asked for`() =
        runTest {
            val client = makeClient()
            client.detectorsByPatient = mapOf(p1 to listOf(Fx.detector("d1", patientId = p1)))
            responses["POST /v1/patients/$p1/detectors/from-library"] = encode(
                ListSerializer(Detector.serializer()),
                listOf(Fx.detector("d-new", patientId = p1)),
            )

            val created = client.addDetectorsFromLibrary(p1, listOf("fall", "wandering", "stove"))

            assertEquals(listOf("d-new"), created.map { it.id })
            assertEquals(listOf("d1", "d-new"), client.detectorsByPatient[p1]?.map { it.id })
        }

    // ---- refresh vs bootstrap ----

    /**
     * A patient deleted on another device leaves their cameras, detectors and
     * events sitting in memory under an id the roster no longer contains.
     * Pull-to-refresh is where that is cleaned up.
     */
    @Test fun `refresh drops the sub-collections of a patient who is no longer in the roster`() =
        runTest {
            val client = makeClient()
            client.camerasByPatient = mapOf(p2 to listOf(Fx.camera("c2", patientId = p2)))
            client.detectorsByPatient = mapOf(p2 to listOf(Fx.detector("d2", patientId = p2)))
            client.eventsByPatient = mapOf(p2 to listOf(Fx.event("e2", patientId = p2)))
            servePatients(Fx.patient(p1))
            serveEmptyCollections(p1)

            client.refresh()

            assertEquals(setOf(p1), client.camerasByPatient.keys)
            assertEquals(setOf(p1), client.detectorsByPatient.keys)
            assertEquals(setOf(p1), client.eventsByPatient.keys)
        }

    /**
     * Bootstrap deliberately does NOT drop them. It runs on a cache that is
     * either empty or was just torn down, so there is nothing stale to remove,
     * and adding the filter here would be a second place to keep in step with
     * the Swift for no gain.
     */
    @Test fun `bootstrap leaves sub-collections it did not load in place`() = runTest {
        val client = makeClient()
        client.camerasByPatient = mapOf(p2 to listOf(Fx.camera("c2", patientId = p2)))
        servePatients(Fx.patient(p1))
        serveEmptyCollections(p1)

        client.bootstrap()

        assertEquals(setOf(p1, p2), client.camerasByPatient.keys)
    }

    // ---- bootstrap ordering and fan-out ----

    /**
     * The roster has to land first, because it is what the fan-out iterates.
     * Fanning out before it would load whatever roster was left over from the
     * previous session — on a shared device, someone else's.
     */
    @Test fun `bootstrap loads the roster before it fans out`() = runTest {
        val client = makeClient()
        client.patients = listOf(Fx.patient("stale-p9"))
        servePatients(Fx.patient(p1))
        serveEmptyCollections(p1)

        client.bootstrap()

        assertEquals("GET /v1/patients", seen.first())
        assertTrue(
            "bootstrap fanned out over a roster it had not refreshed yet: $seen",
            seen.none { it.contains("stale-p9") },
        )
    }

    /**
     * The whole fan-out is awaited, so the caregiver never lands on a roster
     * with empty rooms that fill in a moment later. The slow response below is
     * the one that would still be in flight if the fan-out were launched and
     * forgotten.
     */
    @Test fun `bootstrap waits for every patient's collections before it returns`() = runTest {
        val client = makeClient()
        servePatients(Fx.patient(p1), Fx.patient(p2))
        serveEmptyCollections(p1)
        serveCameras(p2, Fx.camera("c2", patientId = p2))
        serveDetectors(p2, Fx.detector("d2", patientId = p2))
        serveEvents(p2, Fx.event("e2", patientId = p2))
        beforeRespond = { route ->
            if (route == "GET /v1/patients/$p2/events") delay(50)
        }

        client.bootstrap()

        assertEquals(setOf(p1, p2), client.camerasByPatient.keys)
        assertEquals(setOf(p1, p2), client.detectorsByPatient.keys)
        assertEquals(listOf("e2"), client.eventsByPatient[p2]?.map { it.id })
    }

    /**
     * Regression test for the fix in "concurrent detector loads dropped one
     * patient's entire list".
     *
     * Every cache writer here reads the map and writes it back. That is only
     * safe while nothing suspends in between, so the response has to be bound
     * to a local BEFORE the map is touched. Written as one expression —
     * `detectorsByPatient + (patientId to detectorsApi.loadDetectors(id))` —
     * the map is read before the request suspends, and with two patients
     * loading at once the later write erases the earlier one. Nothing throws:
     * a caregiver with two patients just finds one Detectors tab empty after
     * sign-in until something reloads it.
     *
     * Bootstrap, refresh and resync all load every patient at once, so this is
     * the ordinary path on any account with more than one patient, not an edge
     * case. Being single-threaded does not help — a suspension point between
     * the read and the write is an interleaving point.
     */
    @Test fun `loading two patients' detectors at once keeps both`() = runTest {
        val client = makeClient()
        serveDetectors(p1, Fx.detector("d1", patientId = p1))
        serveDetectors(p2, Fx.detector("d2", patientId = p2))
        // Holds p1's answer open so p2 finishes in the middle of it — the
        // ordinary case when a roster has more than one patient.
        beforeRespond = { route ->
            if (route == "GET /v1/patients/$p1/detectors") delay(50)
        }

        coroutineScope {
            listOf(
                async { client.loadDetectors(p1) },
                async { client.loadDetectors(p2) },
            ).awaitAll()
        }

        assertEquals(setOf(p1, p2), client.detectorsByPatient.keys)
    }

    /**
     * Realtime is opened detached on purpose. On 2026-07-28 the project's
     * Realtime tenant was wedged, the subscribe never returned, and awaiting it
     * left sign-in on a spinner with a perfectly healthy roster already loaded.
     *
     * The store here is given a dispatcher this test never runs, so the channel
     * open and the poll can only be queued, never executed. Bootstrap returning
     * with the roster in hand and both of those still waiting is the detachment.
     */
    @Test fun `bootstrap does not wait for Realtime to open its channels`() = runTest {
        val client = makeClient()
        servePatients(Fx.patient(p1))
        serveEmptyCollections(p1)
        val parked = StandardTestDispatcher()
        val realtimeScope = CoroutineScope(parked)
        client.realtime = RealtimeStore(
            supabase = io.github.jan.supabase.createSupabaseClient(
                supabaseUrl = "https://example.supabase.co",
                supabaseKey = "test-anon-key",
            ) { install(io.github.jan.supabase.realtime.Realtime) },
            cache = client,
            fetcher = client,
            live = client.live,
            scope = realtimeScope,
            dispatcher = parked,
            log = {},
        )

        client.bootstrap()

        assertEquals(listOf(p1), client.patients.map { it.id })
        assertFalse("sign-in must not still be spinning", client.isLoading.value)
        assertEquals(
            "the channel open and the poll should both still be queued, unrun",
            2,
            realtimeScope.coroutineContext.job.children.count(),
        )
        realtimeScope.cancel()
    }

    // ---- resync ----

    /**
     * Resync runs on every return to the foreground, without anyone asking. One
     * patient's failed fetch must not stop the others and must not raise an
     * error the caregiver did not trigger.
     */
    @Test fun `resync keeps going when one patient's fetch fails`() = runTest {
        val client = makeClient()
        client.patients = listOf(Fx.patient(p1), Fx.patient(p2))
        statuses["GET /v1/patients/$p1/cameras"] = HttpStatusCode.InternalServerError
        serveDetectors(p1, Fx.detector("d1", patientId = p1))
        serveEvents(p1, Fx.event("e1", patientId = p1))
        serveCameras(p2, Fx.camera("c2", patientId = p2))
        serveDetectors(p2, Fx.detector("d2", patientId = p2))
        serveEvents(p2, Fx.event("e2", patientId = p2))

        client.resync()

        // The patient whose cameras failed still got their detectors and
        // events, and the other patient was untouched by the failure.
        assertEquals(listOf("d1"), client.detectorsByPatient[p1]?.map { it.id })
        assertEquals(listOf("e1"), client.eventsByPatient[p1]?.map { it.id })
        assertEquals(listOf("c2"), client.camerasByPatient[p2]?.map { it.id })
        assertEquals(listOf("e2"), client.eventsByPatient[p2]?.map { it.id })
    }

    /** Before the first bootstrap there is no roster, so there is nothing to catch up on. */
    @Test fun `resync makes no requests when the roster is empty`() = runTest {
        val client = makeClient()

        client.resync()

        assertEquals(emptyList<String>(), seen.toList())
    }

    /**
     * The other half of the same difference: pull-to-refresh was asked for, so
     * a failure has to reach the caregiver rather than leaving them looking at
     * a list that quietly did not update.
     */
    @Test fun `refresh propagates the failure resync swallows`() = runTest {
        val client = makeClient()
        servePatients(Fx.patient(p1))
        statuses["GET /v1/patients/$p1/cameras"] = HttpStatusCode.InternalServerError
        serveDetectors(p1)
        serveEvents(p1)

        val thrown = runCatching { client.refresh() }.exceptionOrNull()

        assertTrue("refresh swallowed a failure it should report: $thrown", thrown is ApiError)
    }

    // ---- teardown ----

    /**
     * A privacy boundary, not housekeeping. Without it the previous caregiver's
     * whole roster stays in memory after sign-out, and a shared device is the
     * normal case for a care team. Asserted on the client directly rather than
     * through the Realtime store, because either owner could drop it.
     */
    @Test fun `teardown empties every cached collection`() = runTest {
        val client = makeClient()
        client.patients = listOf(Fx.patient(p1))
        client.camerasByPatient = mapOf(p1 to listOf(Fx.camera("c1")))
        client.detectorsByPatient = mapOf(p1 to listOf(Fx.detector("d1")))
        client.eventsByPatient = mapOf(p1 to listOf(Fx.event("e1")))

        client.teardown()

        assertTrue("patients survived sign-out", client.patients.isEmpty())
        assertTrue("cameras survived sign-out", client.camerasByPatient.isEmpty())
        assertTrue("detectors survived sign-out", client.detectorsByPatient.isEmpty())
        assertTrue("events survived sign-out", client.eventsByPatient.isEmpty())
    }

    // ---- isLoading ----

    @Test fun `isLoading is on while bootstrap runs and off once it finishes`() = runTest {
        val client = makeClient()
        servePatients(Fx.patient(p1))
        serveEmptyCollections(p1)
        val duringRoster = mutableListOf<Boolean>()
        beforeRespond = { route ->
            if (route == "GET /v1/patients") duringRoster += client.isLoading.value
        }

        assertFalse("the spinner should not be up before sign-in starts", client.isLoading.value)
        client.bootstrap()

        assertEquals(listOf(true), duringRoster)
        assertFalse(client.isLoading.value)
    }

    /**
     * The flag is cleared in a `finally`. If it were cleared on the success
     * path only, one failed pull-to-refresh would leave the roster spinning
     * for the rest of the session with no way back.
     */
    @Test fun `isLoading is cleared even when refresh throws`() = runTest {
        val client = makeClient()
        statuses["GET /v1/patients"] = HttpStatusCode.InternalServerError

        val thrown = runCatching { client.refresh() }.exceptionOrNull()

        assertTrue("expected the failure to propagate", thrown is ApiError)
        assertFalse("the spinner outlived a failed refresh", client.isLoading.value)
    }

    /** Same guarantee on the sign-in path, where a stuck spinner blocks the whole app. */
    @Test fun `isLoading is cleared even when bootstrap throws`() = runTest {
        val client = makeClient()
        statuses["GET /v1/patients"] = HttpStatusCode.InternalServerError

        val thrown = runCatching { client.bootstrap() }.exceptionOrNull()

        assertTrue("expected the failure to propagate", thrown is ApiError)
        assertFalse("sign-in was left spinning after a failed bootstrap", client.isLoading.value)
    }
}
