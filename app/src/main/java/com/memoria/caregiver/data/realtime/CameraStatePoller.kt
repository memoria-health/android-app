package com.memoria.caregiver.data.realtime

import com.memoria.caregiver.data.model.Camera
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The 1-second camera-state poll — the always-on BACKSTOP to Realtime.
 *
 * Realtime pushes instantly when healthy, but its websocket can die silently
 * (suspend, network, stale auth → RLS) with no replay, so a badge could
 * otherwise sit stale indefinitely. This reconciles every patient's cached
 * camera rows against the backend each second, so a badge can never lag more
 * than about a second no matter what Realtime does.
 *
 * Split out of [RealtimeStore] only so the backstop can be tested without a
 * websocket — it is REST and cache, and touches no part of the SDK.
 */
internal class CameraStatePoller(
    private val cache: RealtimeCache,
    private val cameras: CameraFetcher,
    private val live: CameraLiveState,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineContext,
) {

    private var job: Job? = null

    /** Idempotent: cancels any existing loop and starts a fresh one. */
    fun start() {
        job?.cancel()
        job = scope.launch(dispatcher) {
            while (isActive) {
                // Sleeps FIRST, like the Swift: bootstrap has just loaded every
                // camera, so an immediate pass would refetch what it already
                // has.
                delay(POLL_INTERVAL_MILLIS)
                pollOnce()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One reconcile pass: fetch each patient's cameras and update ONLY the rows
     * that disagree with the backend, so nothing changes when nothing changed
     * and the Live grid does not thrash.
     *
     * A failed fetch (a 401 during a token refresh, a transient network blip) is
     * swallowed and the next tick retries.
     */
    suspend fun pollOnce() {
        for (p in cache.patients) {
            val fresh = try {
                cameras.fetchCameras(p.id)
            } catch (e: CancellationException) {
                // Never swallowed: cancellation is how stop() ends the loop, and
                // catching it here would keep polling a signed-out session.
                throw e
            } catch (_: Exception) {
                continue
            }

            val result = reconcileCameras(cache.camerasByPatient[p.id].orEmpty(), fresh)

            // The two side effects the Realtime camera handler also performs.
            // They run whether or not the list itself changed, because
            // ever-online is a fact about the row, not about the diff.
            result.reconnected.forEach(live::beginReconnectWindow)
            result.everOnline.forEach(live::markEverOnline)

            val reconciled = result.cameras ?: continue
            cache.camerasByPatient = cache.camerasByPatient + (p.id to reconciled)
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS: Long = 1_000L
    }
}

/**
 * The outcome of diffing one patient's cached cameras against the server's.
 *
 * [cameras] is null when nothing differed, which is the signal NOT to write
 * back — reassigning an equal list would still emit on a `StateFlow` and
 * re-render the grid every second.
 */
internal data class CameraReconciliation(
    val cameras: List<Camera>?,
    /** Ids that flipped disconnected → connected: open the "Connecting…" buffer. */
    val reconnected: List<String>,
    /** Ids the server reports as `online`: mark them out of "preparing" for good. */
    val everOnline: List<String>,
)

/**
 * Pure diff, in the Swift's order: drop rows the server no longer has, then
 * walk the fresh rows replacing what differs and appending what is new, then
 * sort by `displayId`.
 *
 * **This is the function PARITY.md §10 warns about.** It must produce the same
 * side effects as the Realtime camera handler, or the same state change looks
 * different depending on which path observed it. The two it produces are the
 * two the handler produces on a row change: the reconnect window and the
 * ever-online mark.
 *
 * It deliberately does NOT produce the other two things the handler does — it
 * never clears a reconnect window, and it never fires the LiveKit
 * `onCameraOnline` hook. iOS does not either. The hook exists to recover a lost
 * `track_subscribed` event, and firing it from a loop that runs every second
 * for every camera would turn a repair into a heartbeat. Clearing the window is
 * left to the handler for the same reason it is safe to: the window is bounded,
 * so the worst case is a "Connecting…" label that lingers for the remainder of
 * ten seconds on a camera that is already up.
 */
internal fun reconcileCameras(
    cached: List<Camera>,
    fresh: List<Camera>,
): CameraReconciliation {
    var changed = false
    val freshIds = fresh.mapTo(HashSet()) { it.id }
    val arr = cached.toMutableList()

    // Deleted server-side.
    if (arr.removeAll { it.id !in freshIds }) changed = true

    val reconnected = mutableListOf<String>()
    val everOnline = mutableListOf<String>()

    for (f in fresh) {
        val idx = arr.indexOfFirst { it.id == f.id }
        if (idx >= 0) {
            if (arr[idx] != f) {
                // Another node flipped it back on — open the reconnect window,
                // exactly like the Realtime handler.
                if (arr[idx].desiredState == Camera.DesiredState.DISCONNECTED &&
                    f.desiredState == Camera.DesiredState.CONNECTED
                ) {
                    reconnected += f.id
                }
                arr[idx] = f
                changed = true
            }
        } else {
            arr += f
            changed = true
        }
        if (f.online) everOnline += f.id
    }

    return CameraReconciliation(
        cameras = if (changed) arr.sortedBy { it.displayId } else null,
        reconnected = reconnected,
        everOnline = everOnline,
    )
}
