package com.memoria.caregiver.data.realtime

import com.memoria.caregiver.data.model.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pieces of derived camera UI state. Spec: PARITY.md §10, "Two pieces of
 * derived camera UI state".
 *
 * Both exist to keep a normal, slow moment from reading as a failure: a sensor
 * coming up for the first time says "preparing", a sensor coming back says
 * "Connecting…", and neither is allowed to say it forever.
 */
class CameraLiveStateTest {

    private val clock = TestClock()

    // ---- preparing ----

    @Test
    fun `a paired camera the caregiver wants on, that has never been online, is preparing`() {
        val live = CameraLiveState(now = clock)

        assertTrue(live.isPreparing(Fx.camera("c1")))
    }

    @Test
    fun `a camera that has been online once is never preparing again`() {
        val live = CameraLiveState(now = clock)

        live.markEverOnline("c1")

        assertFalse(live.isPreparing(Fx.camera("c1")))
    }

    @Test
    fun `preparing needs every one of its conditions`() {
        val live = CameraLiveState(now = clock)

        assertFalse(live.isPreparing(Fx.camera("c1", paired = false)))
        assertFalse(live.isPreparing(Fx.camera("c1", disabled = true)))
        assertFalse(live.isPreparing(Fx.camera("c1", online = true)))
        assertFalse(
            live.isPreparing(Fx.camera("c1", desiredState = Camera.DesiredState.DISCONNECTED)),
        )
    }

    @Test
    fun `ever-online outlives the process`() {
        val disk = InMemoryEverOnlineStore()
        CameraLiveState(store = disk, now = clock).markEverOnline("c1")

        // Relaunch: a fresh instance reading the same storage.
        val afterRelaunch = CameraLiveState(store = disk, now = clock)

        assertFalse(afterRelaunch.isPreparing(Fx.camera("c1")))
        assertTrue(afterRelaunch.isPreparing(Fx.camera("c2")))
    }

    // ---- connecting ----

    @Test
    fun `the connecting buffer opens on request and closes ten seconds later`() {
        val live = CameraLiveState(now = clock)
        val cam = Fx.camera("c1")

        live.beginReconnectWindow("c1")

        clock.millis = 9_999
        assertTrue(live.isConnecting(cam))

        // Time-bounded, so a sensor that never returns falls through to plain
        // "offline" instead of spinning forever.
        clock.millis = 10_000
        assertFalse(live.isConnecting(cam))
    }

    @Test
    fun `a camera with no open window is not connecting`() {
        val live = CameraLiveState(now = clock)

        assertFalse(live.isConnecting(Fx.camera("c1")))
    }

    @Test
    fun `connecting needs a camera that is actually supposed to be streaming`() {
        val live = CameraLiveState(now = clock)
        live.beginReconnectWindow("c1")

        // No LiveKit identity: this row is a placeholder holding a pairing code,
        // so there is nothing to connect to.
        assertFalse(live.isConnecting(Fx.camera("c1", livekitIdentity = null)))
        // Already up: there is nothing left to wait for.
        assertFalse(live.isConnecting(Fx.camera("c1", online = true)))
    }

    @Test
    fun `clearing the window ends the buffer immediately`() {
        val live = CameraLiveState(now = clock)
        live.beginReconnectWindow("c1")

        live.clearReconnectWindow("c1")

        assertFalse(live.isConnecting(Fx.camera("c1")))
    }

    // ---- persistence ----

    @Test
    fun `marking the same camera twice does not rewrite storage`() {
        val disk = CountingStore()
        val live = CameraLiveState(store = disk, now = clock)

        live.markEverOnline("c1")
        live.markEverOnline("c1")

        // The camera handler and the poll both call this, the poll once a
        // second for every online camera. A write per call would be a disk
        // write per camera per second.
        assertEquals(1, disk.writes)
    }

    private class CountingStore : EverOnlineStore {
        var writes = 0
        private var ids: Set<String> = emptySet()

        override fun load(): Set<String> = ids

        override fun save(ids: Set<String>) {
            writes++
            this.ids = ids.toSet()
        }
    }
}
