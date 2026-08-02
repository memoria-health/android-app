package com.memoria.caregiver.ui.live

import com.memoria.caregiver.data.model.Camera

/**
 * Drives THIS device back toward the backend's "online" truth. Spec:
 * `PatientLiveView.reconcileDesync(now:)`, PARITY.md §18.
 *
 * When the backend says a camera is live (`online` plus intended-connected) but
 * this device has **no video track** for it — the SDK missed the subscribe, web
 * has the feed and we do not — that is a divergence. Once the mismatch persists
 * for [StuckThresholdMillis] the host surgically re-subscribes just that one
 * camera via `ensureSubscribed(identity, reconnectIfAbsent = false)`, which
 * re-issues the subscribe RPC for that participant's publications without
 * touching the room or any other tile.
 *
 * ## Deliberately not a full room rejoin
 *
 * A forced rejoin tears the whole room down, dropping every tile, and — the part
 * that matters — it cannot tell a fixable subscribe-miss from a **stale backend
 * `online` flag** (the sensor really did drop and the presence sweeper has not
 * caught up). In the stale case a rejoin never produces a track, so it would
 * loop every cooldown and nuke the room forever. With `reconnectIfAbsent = false`
 * the absent case is a no-op instead: the placeholder honestly stays until the
 * sensor republishes, and the room and the working tiles are never disturbed.
 *
 * ## Why this is a class and not a function
 *
 * It carries two pieces of memory that a pure call cannot: when each camera
 * entered the divergent state, and when each identity was last re-subscribed.
 * Both are `@State` dictionaries on the iOS view. Keeping them here, behind a
 * plain `reconcile(...)` call, is what lets the whole reconciler be tested with a
 * fake clock and no LiveKit and no composition.
 *
 * Not thread-safe, matching the iOS `@MainActor` view state. The host calls
 * [reconcile] from the one-second clock tick.
 */
internal class DesyncReconciler {

    /** First moment each camera was seen diverging. iOS: `desyncSince`. */
    private val desyncSince: MutableMap<String, Long> = mutableMapOf()

    /** Per-identity cooldown. iOS: `lastResubscribe`. */
    private val lastResubscribe: MutableMap<String, Long> = mutableMapOf()

    /**
     * One pass. Returns the LiveKit identities to re-subscribe, in camera order.
     *
     * @param roomConnected only meaningful while actually in the room.
     * @param isTogglingMonitoring the bulk disconnect/reconnect PATCH is in
     *   flight, so intent is mid-change and a missing track is expected.
     * @param isReconnectingAll the reconnect-all loop owns recovery right now, so
     *   the reconciler stands down rather than fighting it.
     */
    fun reconcile(
        now: Long,
        cameras: List<Camera>,
        trackIdentities: Set<String>,
        roomConnected: Boolean,
        isTogglingMonitoring: Boolean,
        isReconnectingAll: Boolean,
    ): List<String> {
        // Bail while (re)connecting: bootstrap or the reconnect-all loop own
        // recovery then, and the accumulated divergence timers are meaningless
        // once the room itself has moved.
        if (!roomConnected || isTogglingMonitoring || isReconnectingAll) {
            desyncSince.clear()
            return emptyList()
        }

        val toResubscribe = mutableListOf<String>()
        for (camera in cameras) {
            val identity = camera.livekitIdentity
            // Privacy-enabled cameras are excluded: the backend severs their
            // media at the SFU, so a missing track is the CORRECT state, not a
            // desync. Re-subscribing would loop forever and never land.
            if (identity == null || camera.disabled || camera.privacyEnabled) {
                desyncSince.remove(camera.id)
                continue
            }
            val desynced = camera.online &&
                camera.desiredState == Camera.DesiredState.CONNECTED &&
                identity !in trackIdentities
            if (!desynced) {
                desyncSince.remove(camera.id)
                continue
            }
            val since = desyncSince.getOrPut(camera.id) { now }
            // The two-second gate ignores the normal sub-second "online flips
            // just before the track lands" transient.
            if (now - since < StuckThresholdMillis) continue
            // The re-subscribe is cheap and idempotent, but there is no need to
            // re-issue it every tick while we wait for the track — or for a
            // sensor that is never coming back.
            val last = lastResubscribe[identity]
            if (last != null && now - last < CooldownMillis) continue
            lastResubscribe[identity] = now
            toResubscribe += identity
        }
        return toResubscribe
    }

    companion object {
        /** iOS: `now.timeIntervalSince(since) >= 2`. */
        const val StuckThresholdMillis: Long = 2_000L

        /** iOS: `now.timeIntervalSince(last) < 6`. */
        const val CooldownMillis: Long = 6_000L
    }
}

/**
 * The reconnect-all loop's shape, from `onChange(of: patient?.camerasPaused)`.
 *
 * **A loop, not a one-shot.** Each pass REST-refetches the camera rows rather
 * than trusting a Realtime echo, because the most likely reason the old one-shot
 * reconnect still showed an empty grid was that the track was back while
 * `desired_state` stayed stale. A clean LiveKit rejoin is forced on the **even**
 * passes because the SDK can drop `track_subscribed` across a publisher
 * leave/rejoin and will not re-fire it, so the only reliable recovery is a fresh
 * connect that re-runs autoSubscribe against the current participant list. The
 * first rejoin usually fires before the sensor has finished republishing and
 * finds nothing; the later ones catch it.
 *
 * The host owns the I/O; these are the numbers and the two predicates.
 */
internal object ReconnectAllLoop {
    /** iOS: `for attempt in 0..<10`. Ten passes at 2.5s is about 25 seconds. */
    const val MaxPasses: Int = 10

    /**
     * iOS: `Task.sleep(nanoseconds: 2_500_000_000)`. A slow sensor republish was
     * occasionally landing just after the older ~15s cap, leaving the tile stuck
     * on the loading spinner.
     */
    const val PassIntervalMillis: Long = 2_500L

    /** Force a clean rejoin on passes 0, 2, 4, 6, 8 — about 0s, 5s, 10s, 15s, 20s. */
    fun forcesRejoin(pass: Int): Boolean = pass % 2 == 0

    /**
     * Bail the moment a camera is actually live, so when the first rejoin already
     * worked the extra passes never run.
     */
    fun shouldStop(onlineCameraCount: Int): Boolean = onlineCameraCount > 0
}
