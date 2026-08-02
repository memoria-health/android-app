package com.memoria.caregiver.ui.live

import com.memoria.caregiver.data.model.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desync reconciler and the reconnect-all loop's shape. Spec:
 * `PatientLiveView.reconcileDesync(now:)` and the `camerasPaused` onChange,
 * PARITY.md §18.
 *
 * Pure JVM with a fake clock. What is worth pinning is not "it re-subscribes" —
 * it is every case where it must NOT, because each of those was a bug that cost
 * a room: a privacy camera looping forever, a rejoin fighting the reconnect-all
 * loop, a sub-second transient triggering work, and a dead sensor being
 * re-subscribed every tick until the app is killed.
 */
class DesyncReconcilerTest {

    private val reconciler = DesyncReconciler()

    /** Backend says live, this device has no track. The divergence itself. */
    private val desynced = liveCamera(id = "cam-a", livekitIdentity = "cam_aaaa", online = true)

    private fun reconcile(
        now: Long,
        cameras: List<Camera> = listOf(desynced),
        trackIdentities: Set<String> = emptySet(),
        roomConnected: Boolean = true,
        isTogglingMonitoring: Boolean = false,
        isReconnectingAll: Boolean = false,
    ): List<String> = reconciler.reconcile(
        now = now,
        cameras = cameras,
        trackIdentities = trackIdentities,
        roomConnected = roomConnected,
        isTogglingMonitoring = isTogglingMonitoring,
        isReconnectingAll = isReconnectingAll,
    )

    // ---- The two-second gate -----------------------------------------------

    /**
     * `online` routinely flips a beat before the track lands. Acting on that
     * transient would issue a subscribe for something already arriving.
     */
    @Test
    fun `a sub-second divergence is ignored`() {
        assertEquals(emptyList<String>(), reconcile(now = 0))
        assertEquals(emptyList<String>(), reconcile(now = 900))
        assertEquals(emptyList<String>(), reconcile(now = 1_999))
    }

    @Test
    fun `a divergence that persists two seconds is re-subscribed`() {
        reconcile(now = 0)

        assertEquals(listOf("cam_aaaa"), reconcile(now = 2_000))
    }

    /** The clock starts when the divergence is first SEEN, not at time zero. */
    @Test
    fun `the two-second clock starts at first sighting`() {
        reconcile(now = 10_000)

        assertEquals(emptyList<String>(), reconcile(now = 11_500))
        assertEquals(listOf("cam_aaaa"), reconcile(now = 12_000))
    }

    // ---- The per-identity cooldown ------------------------------------------

    /**
     * The re-subscribe is cheap and idempotent, but a sensor that is never coming
     * back would otherwise be re-subscribed on every one-second tick forever.
     */
    @Test
    fun `a still-diverged camera is re-subscribed at most once per cooldown`() {
        reconcile(now = 0)
        assertEquals(listOf("cam_aaaa"), reconcile(now = 2_000))

        // Every second for the next five seconds: nothing.
        for (t in 3_000L..7_000L step 1_000L) {
            assertEquals("at t=$t", emptyList<String>(), reconcile(now = t))
        }
        // Six seconds after the last one, it tries again.
        assertEquals(listOf("cam_aaaa"), reconcile(now = 8_000))
    }

    // ---- Cases that must be left alone ---------------------------------------

    /**
     * The backend severs a private camera's media at the SFU, so a missing track
     * is the CORRECT state. Re-subscribing would loop forever and never land.
     */
    @Test
    fun `a private camera is never re-subscribed`() {
        val private = liveCamera(id = "cam-p", livekitIdentity = "cam_pppp", privacyEnabled = true)

        reconcile(now = 0, cameras = listOf(private))

        assertEquals(emptyList<String>(), reconcile(now = 60_000, cameras = listOf(private)))
    }

    @Test
    fun `a user-hidden camera is never re-subscribed`() {
        val hidden = liveCamera(id = "cam-h", livekitIdentity = "cam_hhhh", disabled = true)

        reconcile(now = 0, cameras = listOf(hidden))

        assertEquals(emptyList<String>(), reconcile(now = 60_000, cameras = listOf(hidden)))
    }

    @Test
    fun `a camera the caregiver disconnected is not a desync`() {
        val off = liveCamera(
            id = "cam-o",
            livekitIdentity = "cam_oooo",
            desiredState = Camera.DesiredState.DISCONNECTED,
        )

        reconcile(now = 0, cameras = listOf(off))

        assertEquals(emptyList<String>(), reconcile(now = 60_000, cameras = listOf(off)))
    }

    @Test
    fun `a camera we already hold a track for is not a desync`() {
        reconcile(now = 0, trackIdentities = setOf("cam_aaaa"))

        assertEquals(
            emptyList<String>(),
            reconcile(now = 60_000, trackIdentities = setOf("cam_aaaa")),
        )
    }

    // ---- Standing down ---------------------------------------------------------

    /**
     * bootstrap and the reconnect-all loop own recovery while they run. A
     * reconciler firing underneath them re-subscribes against a room that is
     * about to be replaced.
     */
    @Test
    fun `it stands down while the room is not connected`() {
        assertEquals(emptyList<String>(), reconcile(now = 0, roomConnected = false))
        assertEquals(emptyList<String>(), reconcile(now = 60_000, roomConnected = false))
    }

    @Test
    fun `it stands down while the bulk toggle is in flight`() {
        assertEquals(emptyList<String>(), reconcile(now = 0, isTogglingMonitoring = true))
        assertEquals(emptyList<String>(), reconcile(now = 60_000, isTogglingMonitoring = true))
    }

    @Test
    fun `it stands down while reconnect-all owns recovery`() {
        assertEquals(emptyList<String>(), reconcile(now = 0, isReconnectingAll = true))
        assertEquals(emptyList<String>(), reconcile(now = 60_000, isReconnectingAll = true))
    }

    /**
     * Standing down must also FORGET the accumulated divergence, or the first
     * tick after a rejoin would fire immediately against a room that has only
     * just come up and has had no chance to deliver a track.
     */
    @Test
    fun `standing down clears the accumulated divergence`() {
        reconcile(now = 0)
        reconcile(now = 1_000, isReconnectingAll = true)

        assertEquals(emptyList<String>(), reconcile(now = 2_000))
        assertEquals(listOf("cam_aaaa"), reconcile(now = 4_000))
    }

    /** A divergence that heals and returns starts a fresh two seconds. */
    @Test
    fun `a healed divergence restarts the clock when it returns`() {
        reconcile(now = 0)
        reconcile(now = 1_000, trackIdentities = setOf("cam_aaaa"))

        assertEquals(emptyList<String>(), reconcile(now = 2_500))
        assertEquals(listOf("cam_aaaa"), reconcile(now = 4_500))
    }

    // ---- Multiple cameras --------------------------------------------------------

    @Test
    fun `each diverged camera is re-subscribed, in camera order`() {
        val a = liveCamera(id = "cam-a", livekitIdentity = "cam_aaaa")
        val b = liveCamera(id = "cam-b", livekitIdentity = "cam_bbbb")
        val cameras = listOf(a, b)
        reconcile(now = 0, cameras = cameras)

        assertEquals(listOf("cam_aaaa", "cam_bbbb"), reconcile(now = 2_000, cameras = cameras))
    }

    // ---- Constants and the reconnect-all loop -------------------------------------

    @Test fun `the desync gate is two seconds`() =
        assertEquals(2_000L, DesyncReconciler.StuckThresholdMillis)

    @Test fun `the per-identity cooldown is six seconds`() =
        assertEquals(6_000L, DesyncReconciler.CooldownMillis)

    @Test fun `the reconnect loop runs ten passes at two and a half seconds`() {
        assertEquals(10, ReconnectAllLoop.MaxPasses)
        assertEquals(2_500L, ReconnectAllLoop.PassIntervalMillis)
    }

    /**
     * A clean rejoin on the EVEN passes only. The first usually fires before the
     * sensor has finished republishing and finds nothing; the later ones catch
     * it. Rejoining on every pass would tear the room down five more times for
     * no gain.
     */
    @Test
    fun `the reconnect loop forces a clean rejoin on even passes`() {
        assertEquals(
            listOf(0, 2, 4, 6, 8),
            (0 until ReconnectAllLoop.MaxPasses).filter { ReconnectAllLoop.forcesRejoin(it) },
        )
    }

    @Test
    fun `the reconnect loop bails the moment a camera is live`() {
        assertFalse(ReconnectAllLoop.shouldStop(0))
        assertTrue(ReconnectAllLoop.shouldStop(1))
    }
}
