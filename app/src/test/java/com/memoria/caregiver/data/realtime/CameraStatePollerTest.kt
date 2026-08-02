package com.memoria.caregiver.data.realtime

import com.memoria.caregiver.data.model.Camera
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1-second camera-state poll — the Realtime backstop. Spec: PARITY.md §10,
 * "The 1-second camera state poll".
 *
 * PARITY.md calls out one property above all: the poll must mirror the Realtime
 * camera handler's side effects, or the same state change produces different UI
 * depending on which path happened to observe it. The two "agree" tests assert
 * exactly that, by driving one state change down both paths and comparing what
 * the caregiver would end up seeing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraStatePollerTest {

    // ---- The diff ----

    @Test
    fun `an unchanged list is not written back`() {
        val cached = listOf(Fx.camera("c1"), Fx.camera("c2", displayId = 2))

        val result = reconcileCameras(cached, cached)

        // Null means "do not assign". Reassigning an equal list still emits on a
        // StateFlow, which would re-render the Live grid once a second forever.
        assertNull(result.cameras)
    }

    @Test
    fun `rows the server no longer has are dropped`() {
        val cached = listOf(Fx.camera("c1"), Fx.camera("c2", displayId = 2))

        val result = reconcileCameras(cached, listOf(cached[0]))

        assertEquals(listOf("c1"), result.cameras?.map { it.id })
    }

    @Test
    fun `rows the cache has never seen are added and the list is sorted by displayId`() {
        val cached = listOf(Fx.camera("c3", displayId = 3))
        val fresh = listOf(
            Fx.camera("c3", displayId = 3),
            Fx.camera("c1", displayId = 1),
            Fx.camera("c2", displayId = 2),
        )

        val result = reconcileCameras(cached, fresh)

        assertEquals(listOf("c1", "c2", "c3"), result.cameras?.map { it.id })
    }

    @Test
    fun `an online row is reported even when nothing about the list changed`() {
        // Ever-online is a fact about the row, not about the diff: a camera that
        // has been online since before the app launched must still leave
        // "preparing", and it will never appear in a diff.
        val cached = listOf(Fx.camera("c1", online = true))

        val result = reconcileCameras(cached, cached)

        assertNull(result.cameras)
        assertEquals(listOf("c1"), result.everOnline)
    }

    // ---- The parity property ----

    @Test
    fun `the poll and Realtime agree on a reconnect`() {
        val off = Fx.camera("c1", desiredState = Camera.DesiredState.DISCONNECTED)
        val on = off.copy(desiredState = Camera.DesiredState.CONNECTED)

        // Path A: Realtime pushed the flip.
        val viaRealtime = CameraLiveState(now = TestClock())
        val cache = FakeCache(camerasByPatient = mapOf("p1" to listOf(off)))
        RealtimeHandlers(
            cache = cache,
            live = viaRealtime,
            log = RecordingLog(),
            onCameraOnline = {},
        ).handleCameraChange(Fx.update(on))

        // Path B: Realtime was dead and the poll noticed instead.
        val viaPoll = CameraLiveState(now = TestClock())
        val result = reconcileCameras(listOf(off), listOf(on))
        result.reconnected.forEach(viaPoll::beginReconnectWindow)
        result.everOnline.forEach(viaPoll::markEverOnline)

        assertTrue(viaRealtime.isConnecting(on))
        assertEquals(viaRealtime.isConnecting(on), viaPoll.isConnecting(on))
        assertEquals(viaRealtime.isPreparing(on), viaPoll.isPreparing(on))
        assertEquals(cache.camerasByPatient.getValue("p1"), result.cameras)
    }

    @Test
    fun `the poll and Realtime agree on a camera coming up`() {
        val down = Fx.camera("c1", online = false)
        val up = down.copy(online = true)

        val viaRealtime = CameraLiveState(now = TestClock())
        RealtimeHandlers(
            cache = FakeCache(camerasByPatient = mapOf("p1" to listOf(down))),
            live = viaRealtime,
            log = RecordingLog(),
            onCameraOnline = {},
        ).handleCameraChange(Fx.update(up))

        val viaPoll = CameraLiveState(now = TestClock())
        val result = reconcileCameras(listOf(down), listOf(up))
        result.reconnected.forEach(viaPoll::beginReconnectWindow)
        result.everOnline.forEach(viaPoll::markEverOnline)

        assertFalse(viaRealtime.isPreparing(down))
        assertEquals(viaRealtime.isPreparing(down), viaPoll.isPreparing(down))
    }

    // ---- The loop ----

    @Test
    fun `the first pass waits a second, so it does not refetch what bootstrap just loaded`() = runTest {
        val fetches = mutableListOf<String>()
        val poller = poller(fetches = fetches)

        poller.start()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(fetches.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("p1"), fetches)
    }

    @Test
    fun `a failed fetch is swallowed and the next tick retries`() = runTest {
        val fetches = mutableListOf<String>()
        val cache = FakeCache(patients = listOf(Fx.patient("p1")))
        var failNext = true
        val poller = poller(fetches = fetches, cache = cache, onFetch = {
            if (failNext) {
                failNext = false
                throw IllegalStateException("401 during a token refresh")
            }
        })

        poller.start()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(cache.camerasByPatient.isEmpty())

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(listOf("p1", "p1"), fetches)
        assertEquals(listOf("c1"), cache.camerasByPatient.getValue("p1").map { it.id })
    }

    @Test
    fun `stop ends the loop`() = runTest {
        val fetches = mutableListOf<String>()
        val poller = poller(fetches = fetches)

        poller.start()
        advanceTimeBy(1_001)
        runCurrent()
        poller.stop()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, fetches.size)
    }

    private fun TestScope.poller(
        fetches: MutableList<String>,
        cache: FakeCache = FakeCache(patients = listOf(Fx.patient("p1"))),
        onFetch: () -> Unit = {},
    ): CameraStatePoller = CameraStatePoller(
        cache = cache,
        cameras = object : CameraFetcher {
            override suspend fun fetchCameras(patientId: String): List<Camera> {
                fetches += patientId
                onFetch()
                return listOf(Fx.camera("c1"))
            }
        },
        live = CameraLiveState(now = TestClock()),
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
    )
}
