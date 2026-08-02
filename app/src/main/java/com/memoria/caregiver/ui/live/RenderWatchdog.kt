package com.memoria.caregiver.ui.live

/**
 * The render watchdog's decision logic, lifted out of the view so it can be
 * exercised on the JVM. Spec: `Features/Patient/Live/LiveKitVideoView.swift`
 * (`Coordinator`), PARITY.md §12.
 *
 * ## What it is for
 *
 * A tile can read "LIVE" and paint black while the room and the subscribed track
 * stay perfectly healthy. Every other recovery path in the app is gated on the
 * track being *absent*; here it is present, so nothing else fires. The web client
 * renders the same camera fine — the failure is purely client-local.
 *
 * The watchdog closes that gap without disturbing healthy tiles. It:
 *
 * - never fires on a painting tile, so a normal foreground causes no blip;
 * - ignores the initial connect — a tile that has never painted yet is left
 *   alone, so it cannot fight the first-frame handshake. It only ever repairs a
 *   tile that went black **after** going live ([everRendered]);
 * - is purely local. A repair re-binds this on-device view to the same,
 *   still-good track; it never touches the connection, the SFU, or other clients.
 *
 * ## Why the mechanism is re-derived rather than copied
 *
 * iOS polls `VideoView.isRendering`, a flag the SDK maintains. **Android's
 * `SurfaceViewRenderer` has no equivalent property**, so "is it rendering" has to
 * be synthesised — see [LiveVideoRenderer], which attaches a second, counting
 * `VideoSink` to the same track and reports "frames advanced since the last
 * poll". This class takes that as a plain boolean and is therefore identical in
 * behavior to the Swift coordinator.
 *
 * The synthesised signal shares iOS's blind spot: frames can keep arriving at a
 * sink while the surface behind the renderer is dead, so the detector reports
 * "rendering" over a black tile. That is exactly why the second mechanism —
 * the unconditional rebuild on foreground, [foregroundRebuild] — is load-bearing
 * on Android too and is not an iOS-only workaround.
 *
 * ## Threading
 *
 * Confined to the composition it belongs to, like the Swift's `@MainActor`
 * coordinator. Not thread-safe, and it does not need to be: only the poll
 * coroutine and the lifecycle observer call it, both on the main dispatcher.
 */
internal class RenderWatchdog {

    /** Whether this tile is supposed to be painting (a track is bound). */
    var hasTrack: Boolean = false

    /**
     * Did the CURRENT track ever actually paint a frame? Only a tile that went
     * black after going live is a real stall; one that has never painted is
     * still connecting.
     */
    private var everRendered: Boolean = false

    /** Start of the current uninterrupted "live but not painting" stretch. */
    private var blackSince: Long? = null

    /** Last repair, for the cooldown. */
    private var lastRepair: Long? = null

    /** Set after a repair so recovery can be told apart from self-healing. */
    private var awaitingRenderAfterRepair: Boolean = false

    /**
     * A real track swap (the camera republished) is a healthy re-bind, not a
     * stall. Reset so the transition is not counted as black, and so the new
     * track must prove itself live before the watchdog will ever repair it.
     */
    fun trackChanged(hasTrack: Boolean) {
        this.hasTrack = hasTrack
        everRendered = false
        blackSince = null
        awaitingRenderAfterRepair = false
    }

    /**
     * One poll. [isRendering] is the synthesised "frames advanced since the last
     * tick" signal.
     *
     * The branch order is carried from the Swift exactly, including the fact that
     * the cooldown check sits **before** the threshold check and does not clear
     * [blackSince] — so a tile that is still black when the cooldown expires is
     * repaired immediately rather than restarting its two-second clock.
     */
    fun tick(now: Long, isRendering: Boolean): RenderWatchdogAction {
        if (!hasTrack) {
            blackSince = null
            return RenderWatchdogAction.None
        }
        if (isRendering) {
            awaitingRenderAfterRepair = false
            // It is alive — remember that, and reset the black clock.
            everRendered = true
            blackSince = null
            return RenderWatchdogAction.None
        }
        // Not painting. A tile that has never painted yet is just still
        // connecting, so it is never repaired.
        if (!everRendered) return RenderWatchdogAction.None
        val since = blackSince
        if (since == null) {
            blackSince = now
            return RenderWatchdogAction.None
        }
        val repairedAt = lastRepair
        if (repairedAt != null && now - repairedAt < CooldownMillis) {
            return RenderWatchdogAction.None
        }
        if (now - since >= StuckThresholdMillis) {
            markRepaired(now)
            return RenderWatchdogAction.Repair
        }
        return RenderWatchdogAction.None
    }

    /**
     * The unconditional rebuild, on the known trigger rather than on detection.
     *
     * iOS hangs this off `UIApplication.didBecomeActiveNotification`; Android
     * hangs it off `Lifecycle.Event.ON_START`. Either way the reasoning is the
     * same and it is stated in the Swift: the detector **cannot see** the state
     * where the flag says "rendering" and the surface is dead, so rather than try
     * to detect it we rebuild on the trigger. The cost is a brief black-to-video
     * flicker on each foreground, which was accepted against a permanently stuck
     * black tile.
     *
     * Returns [RenderWatchdogAction.None] when there is no track to rebuild
     * against, so a placeholder tile is never disturbed.
     */
    fun foregroundRebuild(now: Long): RenderWatchdogAction {
        if (!hasTrack) return RenderWatchdogAction.None
        markRepaired(now)
        return RenderWatchdogAction.Repair
    }

    /**
     * True while a repair has been issued and no frame has arrived since. Only
     * used to label the log line, exactly as the Swift does — it distinguishes
     * "recovered after the repair" from "recovered on its own".
     */
    fun isAwaitingRenderAfterRepair(): Boolean = awaitingRenderAfterRepair

    /** True once this track has painted at least one frame. */
    fun hasEverRendered(): Boolean = everRendered

    private fun markRepaired(now: Long) {
        lastRepair = now
        blackSince = null
        awaitingRenderAfterRepair = true
    }

    companion object {
        /**
         * Require the tile to be black this long, continuously, before
         * re-attaching. Two seconds is comfortably above the normal blips around
         * connect, track swaps and the post-foreground first-frame gap, while the
         * [everRendered] guard and the cooldown keep it from acting on anything
         * but a genuine "was live, went black" stall.
         */
        const val StuckThresholdMillis: Long = 2_000L

        /**
         * Do not repair more than once per this window, so a tile that needs a
         * beat to recover after a re-attach is not repaired on a loop.
         */
        const val CooldownMillis: Long = 5_000L

        /**
         * Poll cadence. Half a second keeps detection within ~0.5s of the
         * threshold so recovery feels prompt, rather than the one-second
         * granularity slop the Swift had before.
         */
        const val PollIntervalMillis: Long = 500L

        /**
         * Settle delay before the foreground rebuild. iOS waits 0.4s for the
         * Metal context; Android waits the same for the surface to be recreated
         * and the renderer's EGL surface to be re-established, so the re-attach
         * does not land on a half-built surface.
         */
        const val ForegroundSettleMillis: Long = 400L
    }
}

/** What [RenderWatchdog] wants the view to do. */
internal enum class RenderWatchdogAction {
    None,

    /**
     * Detach the renderer from the track, release and re-initialise it, and
     * re-attach — the Android equivalent of the Swift's `view.track = nil;
     * view.track = track`, which forces `VideoView` to rebuild its renderer
     * against the same track.
     */
    Repair,
}
