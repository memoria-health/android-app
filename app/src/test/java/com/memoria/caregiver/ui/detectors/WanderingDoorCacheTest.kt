@file:OptIn(ExperimentalCoroutinesApi::class)

package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.livekit.LiveKitConnectionState
import com.memoria.caregiver.data.model.DetectedDoor
import com.memoria.caregiver.ui.live.FakeLiveRoom
import com.memoria.caregiver.ui.live.called
import com.memoria.caregiver.ui.live.countOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame and doors behind the exit-door picker.
 *
 * The interesting behaviour is not the caching, it is the **room rule**: this
 * class may open a LiveKit room, and if it does it must close it — but if the
 * Live tab already had one up, it must reuse it and leave it alone. Getting that
 * wrong is visible either way. Disconnecting a room we did not open kills the
 * Live tab's video underneath the caregiver; leaving one we did open holds a
 * subscription for the rest of the session.
 *
 * No LiveKit and no HTTP here: the room is [FakeLiveRoom] and the three calls
 * that reach outside are lambdas, which is what lets this run on a plain JVM.
 */
class WanderingDoorCacheTest {

    private val cameraId = "cam-1"
    private val patientId = "pat-1"
    private val identity = "cam_1a2b3c4d"
    private val roomName = "patient_pat-1"

    private val room = FakeLiveRoom()
    private val jpeg = byteArrayOf(1, 2, 3)

    /** Identities the room is currently receiving video for. */
    private var publishing: Set<String> = emptySet()

    private var detectCalls = 0
    private var captureCalls = 0
    private var mintCalls = 0

    private fun makeCache(
        capture: suspend (String) -> ByteArray? = { captureCalls++; jpeg },
        detect: suspend (String) -> List<DetectedDoor> = {
            detectCalls++
            listOf(DetectedDoor(box = listOf(0.1, 0.2, 0.3, 0.4), label = "door"))
        },
    ) = WanderingDoorCache(
        liveKit = room,
        detectDoors = detect,
        mintViewerToken = { mintCalls++; "wss://lk.test" to "viewer-jwt" },
        captureJpeg = capture,
        hasVideoTrack = { it in publishing },
        // The real waits are 30 x 200ms. Kept short here because these tests use
        // real delays, and the thing under test is the decision, not the budget.
        trackWaitAttempts = 3,
        trackWaitIntervalMillis = 1,
    )

    private fun roomIsLiveWithTrack() {
        room.currentRoom.value = roomName
        room.state.value = LiveKitConnectionState.Connected
        publishing = setOf(identity)
    }

    // ---- The room rule ----

    @Test
    fun `a room the Live tab already had open is reused and left open`() = runTest {
        roomIsLiveWithTrack()
        val cache = makeCache()

        val outcome = cache.ensureFrame(cameraId, patientId, identity)

        assertEquals(DoorLoadOutcome.Ok, outcome)
        assertEquals("minted a token for a room already open", 0, mintCalls)
        assertFalse("reconnected a room that was already up", room.called("connect("))
        // The one that matters: the caregiver's Live video is still running.
        assertFalse("tore down a room it did not open", room.called("disconnect("))
    }

    @Test
    fun `a room this class opened is torn down again`() = runTest {
        // Nothing connected, but the track appears once we join.
        publishing = setOf(identity)
        val cache = makeCache()

        val outcome = cache.ensureFrame(cameraId, patientId, identity)

        assertEquals(DoorLoadOutcome.Ok, outcome)
        assertEquals(1, mintCalls)
        assertEquals(1, room.countOf("connect("))
        assertEquals("left its own room connected", 1, room.countOf("disconnect("))
    }

    @Test
    fun `a room opened here is torn down even when no track ever arrives`() = runTest {
        val cache = makeCache()

        val outcome = cache.ensureFrame(cameraId, patientId, identity)

        assertTrue(outcome is DoorLoadOutcome.Blocked)
        assertEquals(
            WanderingDoorCache.NOT_STREAMING,
            (outcome as DoorLoadOutcome.Blocked).message,
        )
        assertEquals("leaked a room on the no-track path", 1, room.countOf("disconnect("))
    }

    @Test
    fun `a room opened here is torn down even when the capture fails`() = runTest {
        publishing = setOf(identity)
        val cache = makeCache(capture = { null })

        val outcome = cache.ensureFrame(cameraId, patientId, identity)

        assertTrue(outcome is DoorLoadOutcome.Failed)
        assertEquals(
            WanderingDoorCache.CAPTURE_FAILED,
            (outcome as DoorLoadOutcome.Failed).message,
        )
        assertEquals("leaked a room on the failed-capture path", 1, room.countOf("disconnect("))
    }

    @Test
    fun `a failed connect reports rather than waiting for a track`() = runTest {
        room.connectFailure = IllegalStateException("no route")
        val cache = makeCache()

        val outcome = cache.ensureFrame(cameraId, patientId, identity)

        assertEquals(
            WanderingDoorCache.CONNECT_FAILED,
            (outcome as DoorLoadOutcome.Failed).message,
        )
        assertEquals("captured from a room it never joined", 0, captureCalls)
    }

    // ---- Blocked vs failed ----

    @Test
    fun `a sleeping sensor is BLOCKED, not failed`() = runTest {
        // Room is up, but this camera publishes nothing: its app is backgrounded.
        room.currentRoom.value = roomName
        room.state.value = LiveKitConnectionState.Connected
        val cache = makeCache()

        val outcome = cache.ensureFrame(cameraId, patientId, identity)

        // The distinction is the screen's: Blocked tells the caregiver what to
        // do and offers no Retry, because retrying will not wake the sensor.
        assertTrue("a sleeping sensor read as our failure", outcome is DoorLoadOutcome.Blocked)
    }

    // ---- Caching ----

    @Test
    fun `a cached frame is not grabbed again`() = runTest {
        roomIsLiveWithTrack()
        val cache = makeCache()
        cache.ensureFrame(cameraId, patientId, identity)

        val outcome = cache.ensureFrame(cameraId, patientId, identity)

        assertEquals(DoorLoadOutcome.Ok, outcome)
        assertEquals("re-grabbed a frame it already had", 1, captureCalls)
    }

    @Test
    fun `rescan drops the old frame AND the doors that went with it`() = runTest {
        roomIsLiveWithTrack()
        val cache = makeCache()
        cache.ensureFrame(cameraId, patientId, identity)
        cache.ensureDoors(cameraId)
        assertEquals(1, detectCalls)

        cache.reloadFrame(cameraId, patientId, identity)

        assertEquals("did not re-grab on rescan", 2, captureCalls)
        // Doors are boxes in the coordinate space of one specific picture.
        // Keeping them would draw the old scene's doorways over the new scene.
        assertNull("kept doors from the previous frame", cache.cachedDoors(cameraId))
    }

    @Test
    fun `doors are detected once and then cached`() = runTest {
        roomIsLiveWithTrack()
        val cache = makeCache()
        cache.ensureFrame(cameraId, patientId, identity)

        val first = cache.ensureDoors(cameraId)
        val second = cache.ensureDoors(cameraId)

        assertEquals(1, first.size)
        assertEquals(first, second)
        assertEquals("spent a second VLM call for the same frame", 1, detectCalls)
    }

    @Test
    fun `a failed detection caches empty rather than re-asking`() = runTest {
        roomIsLiveWithTrack()
        val cache = makeCache(detect = { detectCalls++; throw IllegalStateException("VLM down") })
        cache.ensureFrame(cameraId, patientId, identity)

        val first = cache.ensureDoors(cameraId)
        val second = cache.ensureDoors(cameraId)

        // Tap-placing doors is a complete way to use this screen, not a degraded
        // one, so a failed detection is an answer. Re-asking on every reopen
        // would spend a VLM call to be told the same thing.
        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertEquals(1, detectCalls)
    }

    @Test
    fun `detection with no frame does not call the VLM`() = runTest {
        val cache = makeCache()

        val doors = cache.ensureDoors(cameraId)

        assertTrue(doors.isEmpty())
        assertEquals(0, detectCalls)
    }
}
