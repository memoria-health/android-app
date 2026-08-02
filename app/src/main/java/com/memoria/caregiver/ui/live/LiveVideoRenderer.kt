package com.memoria.caregiver.ui.live

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicLong

/**
 * Renders one remote camera, with the render watchdog attached. Spec:
 * `Features/Patient/Live/LiveKitVideoView.swift`, PARITY.md §12.
 *
 * ## Layout
 *
 * `SCALE_ASPECT_FILL` and mirroring off, matching iOS's `layoutMode = .fill` /
 * `mirrorMode = .off`. **Fill rather than fit is deliberate**: the wall phone
 * publishes portrait and the caregiver app renders landscape, so fitting would
 * letterbox away half the tile.
 *
 * ## Z-order
 *
 * iOS puts the video in `.background` rather than as a peer in the ZStack,
 * because a UIKit view inside a SwiftUI ZStack can render above sibling SwiftUI
 * content — which is what made the Call button vanish the moment video appeared.
 *
 * Android arrives at the same place by a different route and it is worth stating,
 * because it looks like luck: a `SurfaceView` that is not in media-overlay mode
 * sits **behind** the window and punches a transparent hole through it at its own
 * bounds. Anything the parent drew before it is cleared away inside that rect;
 * anything drawn after it — every Compose sibling later in the draw order — paints
 * over the video. So the pills, the error overlay and the Call button are
 * provably above the feed, and any opaque color drawn *behind* the renderer is
 * provably invisible.
 *
 * **Known consequence:** the punch-through is a rectangle and ignores the
 * parent's rounded clip, so the tile's 14dp corners do not cut the live video the
 * way they cut every other tile state. `TextureViewRenderer` clips correctly and
 * is a drop-in swap here ([newRenderer] is the only place that names the type),
 * at the cost of the extra copy a TextureView composite makes. Flagged for the
 * screenshot pass rather than decided silently.
 *
 * ## The watchdog
 *
 * See [RenderWatchdog] for what it is for and why it never disturbs a healthy
 * tile. This file supplies the two things Android does not hand over for free:
 *
 * 1. **The "is it rendering" signal.** iOS reads `VideoView.isRendering`; the
 *    Android renderer exposes no such property. A second, counting [VideoSink] is
 *    attached to the same track, and "rendering" means the frame count advanced
 *    since the previous poll.
 *
 * 2. **The poll's lifecycle scope.** iOS's `Timer` simply stops firing while the
 *    app is suspended. A coroutine does not, so a backgrounded tile — whose
 *    frames legitimately stop — would look stalled and be "repaired" on a loop.
 *    The poll therefore runs only while the lifecycle is at least STARTED, which
 *    is the faithful port of the platform behavior iOS gets implicitly.
 *
 * The repair itself is the Android shape of `view.track = nil; view.track =
 * track`: detach both sinks, release the renderer's EGL surface, re-initialise it
 * against the room's EGL context, re-attach. It only ever runs against a tile
 * that is already black, so the one visible transition is black to video.
 *
 * @param room supplies the EGL context. Null renders an unattached surface; the
 *   caller shows its own placeholder in that case.
 * @param identity labels the watchdog logs. A `cam_<hex>` participant identity,
 *   which is not a credential — iOS logs the same value.
 */
@Composable
internal fun LiveVideoRenderer(
    room: Room?,
    track: VideoTrack?,
    identity: String?,
    modifier: Modifier = Modifier,
) {
    val binding = remember { RendererBinding() }
    val logId = identity ?: "tile"

    // Frames only ever stop legitimately while the app is not in the foreground,
    // so both the poll and the rebuild key off this.
    var isStarted by remember { mutableStateOf(false) }
    var foregroundEpoch by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    isStarted = true
                    // A fresh epoch each foreground, so the rebuild effect below
                    // re-runs even when the track object never changed — which is
                    // the whole point: the track is fine, the surface is not.
                    foregroundEpoch += 1
                }
                Lifecycle.Event.ON_STOP -> isStarted = false
                else -> Unit
            }
        }
        // A composition created while already resumed never receives ON_START, so
        // seed from the current state rather than waiting for a transition.
        isStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { context -> newRenderer(context) },
        update = { view -> binding.bind(view = view, room = room, track = track, logId = logId) },
        onRelease = { binding.release() },
    )

    // Mechanism 1 — poll, and repair a tile that went black after going live.
    LaunchedEffect(track, isStarted) {
        if (!isStarted) return@LaunchedEffect
        binding.resetFrameBaseline()
        while (isActive) {
            delay(RenderWatchdog.PollIntervalMillis)
            binding.poll(logId)
        }
    }

    // Mechanism 2 — rebuild unconditionally on every foreground, after a settle
    // delay. We do not try to DETECT the dead-surface state, because the frame
    // counter cannot see it: frames keep arriving at the sink while the surface
    // behind the renderer is gone. Cost is a brief black-to-video flicker per
    // foreground, which is the trade iOS made too.
    LaunchedEffect(foregroundEpoch, track) {
        if (foregroundEpoch == 0 || track == null) return@LaunchedEffect
        delay(RenderWatchdog.ForegroundSettleMillis)
        binding.foregroundRebuild(logId)
    }
}

/**
 * The renderer type, named in exactly one place so swapping to
 * `TextureViewRenderer` is a one-line change — see the corner-clipping note on
 * [LiveVideoRenderer].
 */
private fun newRenderer(context: android.content.Context): SurfaceViewRenderer =
    SurfaceViewRenderer(context).apply {
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        setMirror(false)
    }

/**
 * Owns the renderer's attachment to a track and the watchdog's state.
 *
 * Deliberately a plain object held by `remember` rather than state read during
 * composition: attaching, detaching and repairing are side effects on a View, and
 * routing them through recomposition would make a repair a layout event.
 *
 * Confined to the main dispatcher, like the iOS coordinator's `@MainActor`.
 */
private class RendererBinding {

    private val watchdog = RenderWatchdog()

    /** Advanced by the SDK's video thread; read by the poll. */
    private val frameCount = AtomicLong(0L)

    private val frameCounter = VideoSink { _: VideoFrame -> frameCount.incrementAndGet() }

    private var view: SurfaceViewRenderer? = null
    private var room: Room? = null

    /** The track this binding is responsible for, attached or not yet. */
    private var boundTrack: VideoTrack? = null

    /** Whether [boundTrack]'s sinks are currently registered. */
    private var isAttached = false

    /** Whether the renderer holds a live EGL surface. */
    private var initialised = false
    private var lastFrameCount = 0L

    /**
     * Called from `AndroidView`'s update. Idempotent: the common case is the same
     * track on the same view, which must not re-attach anything.
     *
     * **[isAttached] is separate from [boundTrack] on purpose.** A track can
     * arrive before the room does — the tile composes while the token is still
     * being minted — and without the EGL context there is nothing to attach to.
     * Treating "same track" as "already attached" would leave that tile black
     * forever, because the next update carries the same track object and would
     * return early. Attachment is therefore retried until it succeeds.
     */
    fun bind(view: SurfaceViewRenderer, room: Room?, track: VideoTrack?, logId: String) {
        val viewChanged = this.view !== view
        this.view = view
        this.room = room
        if (viewChanged) initialised = false

        if (boundTrack !== track) {
            detach()
            boundTrack = track
            // A real track swap (the camera republished) is a healthy re-bind,
            // not a stall: reset so the transition is not counted as black, and
            // so the new track has to prove itself live before a repair is ever
            // possible.
            watchdog.trackChanged(hasTrack = track != null)
            frameCount.set(0L)
            lastFrameCount = 0L
        } else {
            watchdog.hasTrack = track != null
        }
        if (!isAttached) attach(logId)
    }

    fun resetFrameBaseline() {
        lastFrameCount = frameCount.get()
    }

    fun poll(logId: String) {
        val frames = frameCount.get()
        val isRendering = frames != lastFrameCount
        lastFrameCount = frames
        // Read before the tick clears it, so the log can tell "recovered after a
        // repair" apart from "recovered on its own", exactly as the Swift does.
        val wasAwaitingRender = watchdog.isAwaitingRenderAfterRepair()
        when (watchdog.tick(now = System.currentTimeMillis(), isRendering = isRendering)) {
            RenderWatchdogAction.Repair -> {
                Log.i(WatchdogTag, "$logId REPAIR — re-attaching renderer after black")
                repair(logId)
            }
            RenderWatchdogAction.None ->
                if (isRendering && wasAwaitingRender) {
                    Log.i(WatchdogTag, "$logId recovered — rendering again after repair")
                }
        }
    }

    fun foregroundRebuild(logId: String) {
        if (watchdog.foregroundRebuild(System.currentTimeMillis()) != RenderWatchdogAction.Repair) {
            return
        }
        Log.i(WatchdogTag, "$logId FOREGROUND rebuild — re-attaching renderer")
        repair(logId)
    }

    fun release() {
        detach()
        boundTrack = null
        view?.release()
        initialised = false
        view = null
        room = null
    }

    /**
     * Detach, tear the renderer's EGL surface down, rebuild it against the same
     * track. The Android shape of the Swift's `view.track = nil; view.track =
     * track`.
     */
    private fun repair(logId: String) {
        if (boundTrack == null) return
        detach()
        view?.release()
        initialised = false
        // Frames restart from a clean baseline, so the next poll measures the
        // rebuilt renderer rather than the dead one's tail.
        frameCount.set(0L)
        lastFrameCount = 0L
        attach(logId)
    }

    private fun attach(logId: String) {
        val view = view ?: return
        val track = boundTrack ?: return
        val room = room
        if (room == null) {
            // No EGL context yet. Leaving the track unattached is correct — the
            // caller renders its own placeholder — and the next bind() with a
            // room retries, because isAttached is still false.
            Log.i(WatchdogTag, "$logId no room yet — renderer left unattached")
            return
        }
        if (!initialised) {
            room.initVideoRenderer(view)
            initialised = true
        }
        track.addRenderer(view)
        track.addRenderer(frameCounter)
        isAttached = true
    }

    private fun detach() {
        val track = boundTrack ?: return
        if (!isAttached) return
        view?.let { track.removeRenderer(it) }
        track.removeRenderer(frameCounter)
        isAttached = false
    }

    private companion object {
        /** Matches the `[watchdog]` prefix iOS NSLogs, so the two are greppable. */
        const val WatchdogTag = "watchdog"
    }
}
