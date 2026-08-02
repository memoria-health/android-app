package com.memoria.caregiver.data.realtime

import com.memoria.caregiver.data.remote.NemaraJson
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Supabase Realtime plus the 1-second state poll. Port of the
 * `// MARK: - Supabase Realtime` half of `Services/NemaraAPIClient.swift`.
 *
 * One channel per replicated table. The backend's `supabase_realtime`
 * publication broadcasts INSERT/UPDATE/DELETE for `patients`, `cameras`,
 * `detectors` and `events`. RLS policies filter per-caregiver server-side, so
 * each client only receives rows it is already authorized to read.
 *
 * Everything here is confined to one dispatcher, which is what the iOS
 * `@MainActor` annotation buys: the four channel collectors and the poll loop
 * all mutate the same [RealtimeCache] and the same [CameraLiveState], and
 * neither is thread-safe. Do not hand this a multi-threaded dispatcher.
 *
 * Ownership, so nothing is done twice: this object opens, closes and reopens
 * channels and runs the poll. The API client owns the cache and clears it —
 * iOS `teardown()` also empties the four collections, and that half stays with
 * whoever owns them.
 */
class RealtimeStore(
    private val supabase: SupabaseClient,
    private val cache: RealtimeCache,
    fetcher: CameraFetcher,
    /**
     * Shared with the API client, which needs [CameraLiveState.markEverOnline]
     * from `loadCameras` and [CameraLiveState.beginReconnectWindow] from
     * `updateCamera` — the two of the three reconnect-window writers that are
     * not in this file (PARITY.md §10).
     */
    val live: CameraLiveState,
    private val scope: CoroutineScope,
    /** The `@MainActor` analog. Must be single-threaded. */
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
    private val log: RealtimeLog = AndroidRealtimeLog,
    json: Json = NemaraJson.json,
) {

    /**
     * Set by `AppEnvironment` so the cameras handler can poke the LiveKit
     * reconciler without this class taking a hard dependency on
     * `LiveKitClient`. Called with a camera's `livekit_identity` — see
     * `notifyOnlineIfNeeded` in [RealtimeHandlers] for when, and why it is not
     * gated on `online` alone.
     */
    var onCameraOnline: ((String) -> Unit)? = null

    private val handlers = RealtimeHandlers(
        cache = cache,
        live = live,
        log = log,
        onCameraOnline = { identity -> onCameraOnline?.invoke(identity) },
        json = json,
    )

    private val poller = CameraStatePoller(
        cache = cache,
        cameras = fetcher,
        live = live,
        scope = scope,
        dispatcher = dispatcher,
    )

    /** One channel per replicated table. Held so [teardown] can unsubscribe cleanly. */
    private val channels = mutableListOf<RealtimeChannel>()

    /** The long-running collectors, one per channel. Cancelled in [teardown]. */
    private val streamJobs = mutableListOf<Job>()

    // ---- Channels ----

    /**
     * Opens all four channels.
     *
     * **Call this detached, never inline in the sign-in path.** Realtime is a
     * liveness nicety, not a precondition for using the app: [startStatePolling]
     * is the REST backstop that already carries every change within about a
     * second. Awaiting the subscribes inline is what hung sign-in on
     * 2026-07-28 — the project's Realtime tenant was dead after the database
     * ran out of connections, the subscribe never returned (a hang is not an
     * error, so no catch ran), and the caregiver sat on a spinner with a
     * perfectly healthy roster already loaded.
     */
    suspend fun openChannels() {
        subscribe("patients", handlers::handlePatientChange)
        subscribe("cameras", handlers::handleCameraChange)
        subscribe("detectors", handlers::handleDetectorChange)
        subscribe("events", handlers::handleEventChange)
    }

    /**
     * Tears down and reopens the channels, **not** the cached data. The
     * websocket is severed when the process is backgrounded and Realtime has no
     * replay, so a channel that died while backgrounded stays dead; reopening
     * on foreground with the current (possibly refreshed) session token resumes
     * instant push.
     *
     * No-op until the first bootstrap. Best-effort — the poll keeps state
     * correct regardless.
     */
    suspend fun rebuildChannels() {
        if (cache.patients.isEmpty()) return // not bootstrapped / signed out
        closeChannels()
        openChannels()
    }

    /**
     * Stops the poll and disconnects from Realtime. Called on sign-out.
     * Clearing the cached collections is the API client's half of iOS
     * `teardown()`.
     */
    suspend fun teardown() {
        stopStatePolling()
        closeChannels()
    }

    private suspend fun closeChannels() {
        streamJobs.forEach { it.cancel() }
        streamJobs.clear()
        channels.forEach { it.unsubscribe() }
        channels.clear()
    }

    private suspend fun subscribe(tableName: String, handler: (RowChange) -> Unit) {
        val channel = supabase.channel("nemara:$tableName")
        // `table = tableName`, not `table = table`: inside the filter lambda the
        // receiver's own `table` property shadows an outer parameter of the same
        // name, and the self-assignment would subscribe to nothing.
        val changes = channel.postgresChangeFlow<PostgresAction>("public") { table = tableName }

        // UNDISPATCHED so the flow's `postgres_changes` binding is registered
        // synchronously, before the subscribe below sends the join. The binding
        // is added when the flow is first collected, and the join payload
        // carries the bindings that exist at the moment it is sent — a normal
        // launch may not have run its body by then, and the channel would join
        // subscribed to nothing at all. Only the prologue runs here; every
        // handler call happens after the first suspension, on `dispatcher`.
        val job = scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            changes.collect { action ->
                val change = action.toRowChange() ?: return@collect
                log.debug("[Realtime] $tableName ${change.kind}")
                handler(change)
            }
        }
        streamJobs += job
        channels += channel

        try {
            // Bounded, because the subscribe can HANG rather than fail: on
            // 2026-07-28 the project's Realtime tenant was wedged and the call
            // simply never returned, so no catch ran and every later channel
            // waited behind it. A timeout turns that hang into an ordinary
            // failure the poll and the foreground rebuild recover from.
            withTimeout(SUBSCRIBE_TIMEOUT_MILLIS) { channel.subscribe(true) }
            log.debug("[Realtime] subscribed to $tableName")
        } catch (e: TimeoutCancellationException) {
            // Caught BEFORE the CancellationException arm below, which it
            // extends. Rethrowing the timeout would abort the remaining three
            // channels — exactly the head-of-line blocking the timeout exists
            // to prevent.
            log.debug("[NemaraAPIClient] Realtime subscribe failed for $tableName: $e")
        } catch (e: CancellationException) {
            // Not ours: the caller is going away, so let it.
            throw e
        } catch (e: Exception) {
            log.debug("[NemaraAPIClient] Realtime subscribe failed for $tableName: $e")
        }
    }

    // ---- State poll ----

    /**
     * Starts the 1-second camera-state poll. Idempotent — cancels any existing
     * loop and starts a fresh one. See [CameraStatePoller].
     */
    fun startStatePolling() = poller.start()

    fun stopStatePolling() = poller.stop()

    companion object {
        /** iOS `Task.sleep(nanoseconds: 10_000_000_000)`. */
        const val SUBSCRIBE_TIMEOUT_MILLIS: Long = 10_000L
    }
}
