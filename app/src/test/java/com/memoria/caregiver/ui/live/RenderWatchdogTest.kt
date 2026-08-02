package com.memoria.caregiver.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The render watchdog. Spec: `LiveKitVideoView.Coordinator`, PARITY.md §12.
 *
 * Pure JVM: the whole decision surface is a clock, a boolean and four fields, so
 * none of this needs a composition or a renderer. That is the point of lifting it
 * out — the Swift version could only be checked by watching a console.
 *
 * The properties that matter are the ones the Swift comments spend their words
 * on: it must never fire on a healthy tile, never fight the first frame, and
 * never loop.
 */
class RenderWatchdogTest {

    private val watchdog = RenderWatchdog()

    /** Drives the poll forward in [RenderWatchdog.PollIntervalMillis] steps. */
    private class Clock(var now: Long = 1_000_000L) {
        fun advance(millis: Long): Long {
            now += millis
            return now
        }
    }

    private val clock = Clock()

    private fun live() {
        watchdog.trackChanged(hasTrack = true)
        // One painting tick is what makes the tile "was live".
        assertEquals(
            RenderWatchdogAction.None,
            watchdog.tick(clock.advance(RenderWatchdog.PollIntervalMillis), isRendering = true),
        )
    }

    private fun black(millis: Long): List<RenderWatchdogAction> =
        (1..(millis / RenderWatchdog.PollIntervalMillis)).map {
            watchdog.tick(clock.advance(RenderWatchdog.PollIntervalMillis), isRendering = false)
        }

    // ---- It never disturbs a healthy tile ---------------------------------

    @Test
    fun `a painting tile is never repaired`() {
        live()
        repeat(40) {
            assertEquals(
                RenderWatchdogAction.None,
                watchdog.tick(clock.advance(RenderWatchdog.PollIntervalMillis), isRendering = true),
            )
        }
    }

    @Test
    fun `a tile with no track is never repaired`() {
        watchdog.trackChanged(hasTrack = false)
        assertEquals(RenderWatchdogAction.None, black(10_000).distinct().single())
    }

    // ---- It never fights the first frame ----------------------------------

    /**
     * The everRendered guard. A tile that has never painted is still connecting,
     * and repairing it would tear down the handshake that is about to succeed.
     */
    @Test
    fun `a tile that has never painted is never repaired however long it stays black`() {
        watchdog.trackChanged(hasTrack = true)

        assertEquals(listOf(RenderWatchdogAction.None), black(30_000).distinct())
    }

    /**
     * A track swap is the camera republishing — a healthy re-bind. Counting it as
     * black would repair a tile that is doing exactly the right thing.
     */
    @Test
    fun `a track swap clears the memory that the tile was ever live`() {
        live()
        black(1_500)

        watchdog.trackChanged(hasTrack = true)

        assertEquals(listOf(RenderWatchdogAction.None), black(30_000).distinct())
    }

    // ---- It repairs a real stall -------------------------------------------

    @Test
    fun `a tile that goes black after going live is repaired once the threshold passes`() {
        live()

        // First black tick only starts the clock; the repair lands once the
        // stretch reaches the threshold.
        val actions = black(RenderWatchdog.StuckThresholdMillis + RenderWatchdog.PollIntervalMillis)

        assertEquals(1, actions.count { it == RenderWatchdogAction.Repair })
    }

    @Test
    fun `it does not repair before the threshold`() {
        live()

        val actions = black(RenderWatchdog.StuckThresholdMillis - RenderWatchdog.PollIntervalMillis)

        assertEquals(listOf(RenderWatchdogAction.None), actions.distinct())
    }

    /**
     * A blip that recovers on its own must reset the clock, or two unrelated
     * one-second gaps would add up to a repair.
     */
    @Test
    fun `a recovered frame resets the black clock`() {
        live()
        black(1_500)

        watchdog.tick(clock.advance(RenderWatchdog.PollIntervalMillis), isRendering = true)
        val actions = black(1_500)

        assertEquals(listOf(RenderWatchdogAction.None), actions.distinct())
    }

    // ---- It never loops -----------------------------------------------------

    /**
     * The cooldown. A tile that needs a beat to come back after a re-attach must
     * not be repaired every poll while it does.
     */
    @Test
    fun `a still-black tile is repaired at most once per cooldown`() {
        live()

        // Twelve seconds black is 24 polls. Without a cooldown that is twenty
        // repairs; with it, the first lands at +2.5s and the second at +7.5s.
        val actions = black(12_000)

        val repairs = actions.count { it == RenderWatchdogAction.Repair }
        assertEquals(2, repairs)
    }

    /**
     * The Swift checks the cooldown BEFORE the threshold and does not clear
     * `blackSince` on that early return, so a tile that is still black when the
     * cooldown expires is repaired on the very next poll rather than restarting
     * its two-second clock. Pinned because the natural "tidier" ordering changes
     * the recovery cadence.
     */
    @Test
    fun `the cooldown does not restart the black clock`() {
        live()
        // Repair at t+2.5s (relative to the first black tick).
        black(RenderWatchdog.StuckThresholdMillis + RenderWatchdog.PollIntervalMillis)

        // The very next poll after the cooldown expires must repair again.
        val duringCooldown = black(RenderWatchdog.CooldownMillis - RenderWatchdog.PollIntervalMillis)
        val afterCooldown = watchdog.tick(
            clock.advance(RenderWatchdog.PollIntervalMillis),
            isRendering = false,
        )

        assertEquals(listOf(RenderWatchdogAction.None), duringCooldown.distinct())
        assertEquals(RenderWatchdogAction.Repair, afterCooldown)
    }

    // ---- Foreground rebuild -------------------------------------------------

    /**
     * Mechanism 2 is unconditional by design: the frame counter cannot see a dead
     * surface that is still being fed, so the rebuild fires on the trigger rather
     * than on a detection.
     */
    @Test
    fun `foreground rebuilds a healthy tile too`() {
        live()

        assertEquals(RenderWatchdogAction.Repair, watchdog.foregroundRebuild(clock.advance(10)))
    }

    /** But it must not disturb a tile with nothing to rebuild against. */
    @Test
    fun `foreground does nothing without a track`() {
        watchdog.trackChanged(hasTrack = false)

        assertEquals(RenderWatchdogAction.None, watchdog.foregroundRebuild(clock.advance(10)))
    }

    /**
     * The rebuild arms the same cooldown a detected repair does, so the first
     * poll after a foreground — which legitimately sees no frames yet — cannot
     * repair again on top of it.
     */
    @Test
    fun `the foreground rebuild arms the cooldown`() {
        live()
        watchdog.foregroundRebuild(clock.advance(10))

        val actions = black(RenderWatchdog.CooldownMillis - RenderWatchdog.PollIntervalMillis)

        assertEquals(listOf(RenderWatchdogAction.None), actions.distinct())
    }

    // ---- Tuning constants ---------------------------------------------------

    @Test fun `stuck threshold is two seconds`() =
        assertEquals(2_000L, RenderWatchdog.StuckThresholdMillis)

    @Test fun `cooldown is five seconds`() =
        assertEquals(5_000L, RenderWatchdog.CooldownMillis)

    @Test fun `poll cadence is half a second`() =
        assertEquals(500L, RenderWatchdog.PollIntervalMillis)

    @Test fun `foreground settle delay is four hundred milliseconds`() =
        assertEquals(400L, RenderWatchdog.ForegroundSettleMillis)
}
