package com.memoria.caregiver.data.realtime

import com.memoria.caregiver.data.model.Camera
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four per-table Realtime handlers. Spec: PARITY.md §10, "Realtime handlers
 * — ordering rules", and the `handle*Change` methods in `NemaraAPIClient.swift`.
 *
 * The ordering rules are the point. A camera list that is not re-sorted puts
 * "CAM 03" above "CAM 01"; an event appended instead of inserted at 0 buries a
 * fall alert at the bottom of the list; a patient delete that does not cascade
 * leaks every camera, detector and event of a patient who is gone.
 */
class RealtimeHandlersTest {

    private val cache = FakeCache()
    private val log = RecordingLog()
    private val clock = TestClock()
    private val live = CameraLiveState(now = clock)
    private val onlineHookCalls = mutableListOf<String>()

    private val handlers = RealtimeHandlers(
        cache = cache,
        live = live,
        log = log,
        onCameraOnline = { onlineHookCalls += it },
    )

    // ---- patients ----

    @Test
    fun `patient insert is ignored when the row is already cached`() {
        val p = Fx.patient("p1")
        cache.patients = listOf(p)

        handlers.handlePatientChange(Fx.insert(p))

        assertEquals(1, cache.patients.size)
    }

    @Test
    fun `patient update for an unknown row appends it`() {
        handlers.handlePatientChange(Fx.update(Fx.patient("p1")))

        assertEquals(listOf("p1"), cache.patients.map { it.id })
    }

    @Test
    fun `patient delete cascades to that patient's cameras detectors and events`() {
        cache.patients = listOf(Fx.patient("p1"), Fx.patient("p2"))
        cache.camerasByPatient = mapOf(
            "p1" to listOf(Fx.camera("c1")),
            "p2" to listOf(Fx.camera("c2", patientId = "p2")),
        )
        cache.detectorsByPatient = mapOf("p1" to listOf(Fx.detector("d1")))
        cache.eventsByPatient = mapOf("p1" to listOf(Fx.event("e1")))

        handlers.handlePatientChange(Fx.delete("p1"))

        assertEquals(listOf("p2"), cache.patients.map { it.id })
        assertEquals(setOf("p2"), cache.camerasByPatient.keys)
        assertTrue(cache.detectorsByPatient.isEmpty())
        assertTrue(cache.eventsByPatient.isEmpty())
    }

    // ---- cameras ----

    @Test
    fun `camera insert re-sorts the patient's list by displayId`() {
        cache.camerasByPatient = mapOf(
            "p1" to listOf(Fx.camera("c1", displayId = 1), Fx.camera("c3", displayId = 3)),
        )

        handlers.handleCameraChange(Fx.insert(Fx.camera("c2", displayId = 2)))

        assertEquals(listOf("c1", "c2", "c3"), cache.camerasByPatient.getValue("p1").map { it.id })
    }

    @Test
    fun `camera update from disconnected to connected opens the reconnect window`() {
        val off = Fx.camera("c1", desiredState = Camera.DesiredState.DISCONNECTED)
        cache.camerasByPatient = mapOf("p1" to listOf(off))

        val on = off.copy(desiredState = Camera.DesiredState.CONNECTED)
        handlers.handleCameraChange(Fx.update(on))

        assertTrue(live.isConnecting(on))
    }

    @Test
    fun `a camera update that does not flip the intent leaves the window closed`() {
        val on = Fx.camera("c1", desiredState = Camera.DesiredState.CONNECTED, room = "Kitchen")
        cache.camerasByPatient = mapOf("p1" to listOf(on))

        val renamed = on.copy(room = "Den")
        handlers.handleCameraChange(Fx.update(renamed))

        assertFalse(live.isConnecting(renamed))
    }

    @Test
    fun `a camera reporting online closes the reconnect window and leaves preparing for good`() {
        val off = Fx.camera("c1", desiredState = Camera.DesiredState.DISCONNECTED)
        cache.camerasByPatient = mapOf("p1" to listOf(off))
        handlers.handleCameraChange(Fx.update(off.copy(desiredState = Camera.DesiredState.CONNECTED)))

        val up = off.copy(desiredState = Camera.DesiredState.CONNECTED, online = true)
        handlers.handleCameraChange(Fx.update(up))

        // Asked about a row that is not online yet, the buffer must still be
        // shut — if it were merely masked by `online == true` this would pass
        // without the window ever having been cleared.
        assertFalse(live.isConnecting(up.copy(online = false)))
        // And an established camera that goes offline later reads as offline,
        // never as "preparing to start".
        assertFalse(live.isPreparing(up.copy(online = false)))
    }

    @Test
    fun `the livekit hook fires on caregiver intent alone, before the worker marks it online`() {
        // Gating on `online` left tiles black: `online` is written by the
        // detector worker's reconciler and lags the publisher.
        handlers.handleCameraChange(
            Fx.update(Fx.camera("c1", online = false, livekitIdentity = "cam_11112222")),
        )

        assertEquals(listOf("cam_11112222"), onlineHookCalls)
    }

    @Test
    fun `the livekit hook stays silent for a row with no livekit identity`() {
        handlers.handleCameraChange(
            Fx.update(Fx.camera("c1", online = true, livekitIdentity = null)),
        )

        assertTrue(onlineHookCalls.isEmpty())
    }

    @Test
    fun `the livekit hook stays silent when the caregiver has turned the camera off`() {
        handlers.handleCameraChange(
            Fx.update(
                Fx.camera(
                    "c1",
                    online = false,
                    desiredState = Camera.DesiredState.DISCONNECTED,
                ),
            ),
        )

        assertTrue(onlineHookCalls.isEmpty())
    }

    @Test
    fun `camera delete finds the row without being told which patient owns it`() {
        cache.camerasByPatient = mapOf(
            "p1" to listOf(Fx.camera("c1")),
            "p2" to listOf(Fx.camera("c2", patientId = "p2")),
        )

        handlers.handleCameraChange(Fx.delete("c2"))

        assertEquals(listOf("c1"), cache.camerasByPatient.getValue("p1").map { it.id })
        assertTrue(cache.camerasByPatient.getValue("p2").isEmpty())
    }

    // ---- detectors ----

    @Test
    fun `detector insert is idempotent`() {
        val d = Fx.detector("d1")
        cache.detectorsByPatient = mapOf("p1" to listOf(d))

        handlers.handleDetectorChange(Fx.insert(d))

        assertEquals(1, cache.detectorsByPatient.getValue("p1").size)
    }

    @Test
    fun `detector update replaces in place, keeping the list order`() {
        cache.detectorsByPatient = mapOf(
            "p1" to listOf(Fx.detector("d1"), Fx.detector("d2"), Fx.detector("d3")),
        )

        handlers.handleDetectorChange(Fx.update(Fx.detector("d2", isOn = false)))

        val arr = cache.detectorsByPatient.getValue("p1")
        assertEquals(listOf("d1", "d2", "d3"), arr.map { it.id })
        assertFalse(arr[1].isOn)
    }

    // ---- events ----

    @Test
    fun `event insert lands at the top, matching the newest-first list sort`() {
        cache.eventsByPatient = mapOf("p1" to listOf(Fx.event("old")))

        handlers.handleEventChange(Fx.insert(Fx.event("new")))

        assertEquals(listOf("new", "old"), cache.eventsByPatient.getValue("p1").map { it.id })
    }

    @Test
    fun `an update for an event we never saw is inserted at the top, not appended`() {
        cache.eventsByPatient = mapOf("p1" to listOf(Fx.event("old")))

        handlers.handleEventChange(Fx.update(Fx.event("missed")))

        assertEquals(listOf("missed", "old"), cache.eventsByPatient.getValue("p1").map { it.id })
    }

    @Test
    fun `an event update replaces in place rather than reordering the list`() {
        cache.eventsByPatient = mapOf("p1" to listOf(Fx.event("e2"), Fx.event("e1")))

        handlers.handleEventChange(Fx.update(Fx.event("e1", title = "Fall (reviewed)")))

        val arr = cache.eventsByPatient.getValue("p1")
        assertEquals(listOf("e2", "e1"), arr.map { it.id })
        assertEquals("Fall (reviewed)", arr[1].title)
    }

    // ---- the decode canary ----

    @Test
    fun `a failed event decode is logged, because a swallowed one loses a real alert`() {
        handlers.handleEventChange(RowChange.Insert(undecodableRow()))

        assertTrue(cache.eventsByPatient.isEmpty())
        assertTrue(log.lines.any { it.startsWith("[Realtime] events decode failed:") })
    }

    @Test
    fun `failed decodes on the other three tables stay silent`() {
        handlers.handlePatientChange(RowChange.Insert(undecodableRow()))
        handlers.handleCameraChange(RowChange.Insert(undecodableRow()))
        handlers.handleDetectorChange(RowChange.Insert(undecodableRow()))

        assertTrue(cache.patients.isEmpty())
        assertTrue(cache.camerasByPatient.isEmpty())
        assertTrue(cache.detectorsByPatient.isEmpty())
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun `a delete with no usable id changes nothing`() {
        cache.patients = listOf(Fx.patient("p1"))

        handlers.handlePatientChange(RowChange.Delete(buildJsonObject { put("id", 42) }))

        assertEquals(1, cache.patients.size)
    }

    /** Well-formed JSON, but missing every required column. */
    private fun undecodableRow(): JsonObject = buildJsonObject { put("id", "not-a-row") }
}

/**
 * Sign-out must leave nothing of the previous caregiver behind.
 *
 * The Swift `teardown()` ends by emptying all four cached collections. The
 * first Android port stopped the poll and closed the channels but kept the
 * cache — so a caregiver's patients, cameras, detectors and events survived
 * their own sign-out, and the next person to sign in on the same device was one
 * recomposition away from a household they have no relationship to. A shared
 * device is the normal case for a care team, so this is a privacy boundary
 * rather than housekeeping.
 */
class RealtimeCacheClearTest {

    private class FakeCache : RealtimeCache {
        override var patients: List<com.memoria.caregiver.data.model.Patient> = emptyList()
        override var camerasByPatient: Map<String, List<com.memoria.caregiver.data.model.Camera>> = emptyMap()
        override var detectorsByPatient: Map<String, List<com.memoria.caregiver.data.model.Detector>> = emptyMap()
        override var eventsByPatient: Map<String, List<com.memoria.caregiver.data.model.DetectionEvent>> = emptyMap()
    }

    /**
     * The wiring, not just the helper: `teardown()` must be the thing that
     * clears. An earlier version of this test called `clear()` directly, so
     * deleting the call from `teardown()` left it green — it proved the method
     * worked while the bug it was written for went unnoticed.
     *
     * A SupabaseClient is constructed but never connected. With no channels
     * open and no poll running, teardown's other two steps iterate empty
     * collections, so this exercises the real method offline.
     */
    @org.junit.Test
    fun `teardown clears the cache`() = kotlinx.coroutines.test.runTest {
        val cache = FakeCache().apply {
            patients = listOf(Fx.patient("p1"))
            camerasByPatient = mapOf("p1" to listOf(Fx.camera("c1")))
        }
        val store = RealtimeStore(
            supabase = io.github.jan.supabase.createSupabaseClient(
                supabaseUrl = "https://example.supabase.co",
                supabaseKey = "test-anon-key",
            ) { install(io.github.jan.supabase.realtime.Realtime) },
            cache = cache,
            fetcher = { emptyList() },
            live = CameraLiveState(InMemoryEverOnlineStore()),
            scope = this,
            dispatcher = kotlin.coroutines.EmptyCoroutineContext,
            log = {},
        )

        store.teardown()

        org.junit.Assert.assertTrue("patients survived teardown", cache.patients.isEmpty())
        org.junit.Assert.assertTrue("cameras survived teardown", cache.camerasByPatient.isEmpty())
    }

    @org.junit.Test
    fun `clear empties every cached collection`() {
        val cache = FakeCache().apply {
            patients = listOf(Fx.patient("p1"))
            camerasByPatient = mapOf("p1" to listOf(Fx.camera("c1")))
            detectorsByPatient = mapOf("p1" to listOf(Fx.detector("d1")))
            eventsByPatient = mapOf("p1" to listOf(Fx.event("e1")))
        }

        cache.clear()

        org.junit.Assert.assertTrue("patients survived sign-out", cache.patients.isEmpty())
        org.junit.Assert.assertTrue("cameras survived sign-out", cache.camerasByPatient.isEmpty())
        org.junit.Assert.assertTrue("detectors survived sign-out", cache.detectorsByPatient.isEmpty())
        org.junit.Assert.assertTrue("events survived sign-out", cache.eventsByPatient.isEmpty())
    }
}
