@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.memoria.caregiver.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import java.util.UUID

/**
 * The real analytics sink. Port of `Services/Analytics.swift`.
 *
 * ## The safety contract, unchanged from iOS
 *
 * Every `log*` call is synchronous, non-throwing and side-effect-only: it
 * appends to a buffer and returns. It sits directly in front of a user action —
 * a button's `onClick`, a screen appearing — so it must not be able to delay,
 * alter or fail that action. The only asynchronous part is the flush, which is
 * isolated and swallows every failure. If analytics breaks completely, the app
 * behaves identically and we simply get no data.
 *
 * ## What makes the buffer safe to touch from several threads
 *
 * iOS gets thread safety for free: `Analytics` is a `@MainActor` singleton and
 * every UI event is already on the main thread. Android has no such guarantee
 * here — the log calls arrive on the main thread, but the flush worker runs on
 * whatever dispatcher its coroutine resumes on, and it both reads and empties
 * the same buffer.
 *
 * So the buffer, the sequence counter, the current screen and the flush flag are
 * all guarded by one monitor, [lock], and **every critical section is ordinary
 * blocking code with no `suspend` call inside it.** That is deliberate. A lock
 * held across a suspension point is how FINDINGS.md F-12 happened: a value read,
 * awaited on, then written back, silently discarding whatever else changed in
 * between.
 *
 * The flush is written so that shape cannot occur. It:
 *   1. copies the sendable events out, under the lock;
 *   2. releases the lock for the whole network call;
 *   3. writes back by **removing the specific ids that were confirmed sent**,
 *      under the lock again.
 *
 * Step 3 is a removal, not a replacement. Events logged while the upload was in
 * flight are still in the buffer afterwards, because nothing ever assigns the
 * buffer a stale copy of itself.
 *
 * @param scope lives as long as the process; the flush worker and the disk
 *   writer run in it.
 * @param caregiverId read fresh on every event rather than cached, because it is
 *   null before sign-in and changes on account switch.
 * @param enabled the kill switch, defaulted from [AnalyticsConfig] so production
 *   has exactly one line to flip and tests can construct a disabled instance.
 */
class BufferedAnalytics(
    private val scope: CoroutineScope,
    private val store: AnalyticsStore,
    private val uploader: AnalyticsUploader,
    private val caregiverId: () -> String?,
    private val device: DeviceContext,
    private val enabled: Boolean = AnalyticsConfig.ENABLED,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = { Clock.System.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** New per launch, so one run's events group together. */
    private val sessionId: String = UUID.randomUUID().toString(),
) : Analytics {

    private val lock = Any()

    // ---- all of the following are guarded by `lock` ----

    private val buffer = ArrayDeque<UiEvent>()

    /** Monotonic per-session counter, so ordering survives identical timestamps. */
    private var seq = 0L

    /** The screen the caregiver is on, stamped onto button/toggle/lifecycle events. */
    private var currentScreen: String? = null

    private var eventsSincePersist = 0

    /** One flush at a time. A second caller returns immediately rather than queueing. */
    private var isFlushing = false

    // ---- not guarded; only touched from `start()` ----

    private var started = false
    private var flushJob: Job? = null

    /**
     * Snapshots waiting to be written to disk.
     *
     * Conflated on purpose: only the newest snapshot is worth writing, and an
     * older one arriving late would undo a newer one. Sending is non-blocking,
     * so a persist never slows down the caller, and a single consumer means two
     * writes can never interleave in the file.
     */
    private val persistRequests = Channel<List<UiEvent>>(Channel.CONFLATED)

    /**
     * Load anything a previous run left behind, start the hourly worker, and
     * drain immediately so a buffer that survived a kill goes out at launch
     * rather than an hour later.
     *
     * Idempotent: calling it twice does not reload the file or start a second
     * worker.
     */
    fun start() {
        if (!enabled || started) return
        started = true

        scope.launch(ioDispatcher) {
            for (snapshot in persistRequests) {
                runCatching { store.write(snapshot) }
            }
        }

        scope.launch {
            loadPersisted()
            flush()
        }

        flushJob = scope.launch {
            while (isActive) {
                delay(AnalyticsConfig.FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    // ------------------------------------------------------------------
    // Logging — synchronous, non-throwing. The safety core.
    // ------------------------------------------------------------------

    override fun logButton(name: String) = append(type = "button", name = name)

    override fun logToggle(name: String, isOn: Boolean) = append(
        type = "toggle",
        name = name,
        // The only metadata this app ever sends. Two fixed strings, chosen here
        // rather than taken from a caller, so there is no route by which a typed
        // value could ride along with a toggle.
        metadata = mapOf("value" to if (isOn) "on" else "off"),
    )

    override fun logScreen(name: String) {
        if (!enabled) return
        append(type = "screen", name = name, screenOverride = name, becomeCurrentScreen = true)
    }

    override fun logLifecycle(name: String) = append(type = "lifecycle", name = name)

    private fun append(
        type: String,
        name: String,
        metadata: Map<String, String>? = null,
        screenOverride: String? = null,
        becomeCurrentScreen: Boolean = false,
    ) {
        if (!enabled) return

        // Read outside the lock. `caregiverId` is somebody else's lambda and the
        // clock is a system call; neither belongs inside a critical section that
        // the flush worker is waiting on.
        val stampedCaregiver = caregiverId()
        val id = newId()
        val occurredAt = now().toString()

        val snapshot: List<UiEvent>? = synchronized(lock) {
            if (becomeCurrentScreen) currentScreen = name
            val event = UiEvent(
                id = id,
                caregiverId = stampedCaregiver,
                sessionId = sessionId,
                seq = seq,
                eventType = type,
                name = name,
                screen = screenOverride ?: currentScreen,
                occurredAt = occurredAt,
                appBuild = device.appBuild,
                osVersion = device.osVersion,
                deviceModel = device.deviceModel,
                metadata = metadata,
            )
            seq += 1
            buffer.addLast(event)
            // Bound memory: a long offline stretch drops the oldest events
            // rather than growing until the app is killed.
            while (buffer.size > AnalyticsConfig.MAX_BUFFERED_EVENTS) buffer.removeFirst()

            eventsSincePersist += 1
            if (eventsSincePersist >= AnalyticsConfig.PERSIST_EVERY_N) {
                eventsSincePersist = 0
                buffer.toList()
            } else {
                null
            }
        }

        if (snapshot != null) persistRequests.trySend(snapshot)
    }

    // ------------------------------------------------------------------
    // Lifecycle hook
    // ------------------------------------------------------------------

    /**
     * Snapshot to disk and kick a flush. Called when the app goes to the
     * background.
     *
     * iOS calls this "the last moment before suspension". On Android it matters
     * more, not less: a backgrounded process is killed without warning and
     * without any further callback, so whatever is not on disk when this returns
     * is simply gone. Both halves are fire-and-forget and failure-safe.
     */
    override fun persistAndFlush() {
        if (!enabled) return
        persist()
        scope.launch { flush() }
    }

    private fun persist() {
        val snapshot = synchronized(lock) {
            eventsSincePersist = 0
            buffer.toList()
        }
        persistRequests.trySend(snapshot)
    }

    /**
     * Merge in what a previous run left on disk.
     *
     * Prepended, not assigned. The disk read is a suspending call, so events can
     * be logged while it is running; replacing the buffer with the file's
     * contents would throw those away. Saved events are older, so the front is
     * also the correct place for them.
     */
    private suspend fun loadPersisted() {
        val saved = withContext(ioDispatcher) {
            runCatching { store.read() }.getOrDefault(emptyList())
        }
        if (saved.isEmpty()) return
        synchronized(lock) {
            buffer.addAll(0, saved)
            while (buffer.size > AnalyticsConfig.MAX_BUFFERED_EVENTS) buffer.removeFirst()
        }
    }

    // ------------------------------------------------------------------
    // Flush — the only asynchronous part, isolated and failure-safe
    // ------------------------------------------------------------------

    /**
     * Send the current caregiver's buffered events in batches.
     *
     * Only events stamped with the caregiver who is signed in right now are
     * sent, because those are the only rows row-level security will accept.
     * Anything else — events logged before sign-in, or by whoever used the
     * device before — stays buffered and is never uploaded under someone else's
     * identity.
     *
     * The first failed batch stops the run. Everything not confirmed sent stays
     * in the buffer for next time.
     */
    private suspend fun flush() {
        if (!enabled) return
        val currentId = caregiverId() ?: return

        val claimed = synchronized(lock) {
            if (isFlushing) false else { isFlushing = true; true }
        }
        if (!claimed) return

        try {
            val sendable = synchronized(lock) { buffer.filter { it.caregiverId == currentId } }
            if (sendable.isEmpty()) return

            val sentIds = mutableSetOf<String>()
            for (chunk in sendable.chunked(AnalyticsConfig.BATCH_SIZE)) {
                try {
                    uploader.upload(chunk)
                } catch (cancelled: CancellationException) {
                    // The scope is going away. Not an upload failure, and
                    // swallowing it would leave a coroutine that ignores
                    // cancellation.
                    throw cancelled
                } catch (failure: Throwable) {
                    // Anything else — offline, a 500, a bad token — means this
                    // batch did not land. Stop here and keep everything that is
                    // not yet confirmed sent.
                    break
                }
                sentIds += chunk.map { it.id }
            }
            if (sentIds.isEmpty()) return

            val snapshot = synchronized(lock) {
                // Removal by id, never assignment. See the class comment: this is
                // what keeps events logged during the upload above.
                buffer.removeAll { it.id in sentIds }
                eventsSincePersist = 0
                buffer.toList()
            }
            persistRequests.trySend(snapshot)
        } finally {
            synchronized(lock) { isFlushing = false }
        }
    }

    /** Test seam: what is buffered right now. */
    internal fun bufferedForTest(): List<UiEvent> = synchronized(lock) { buffer.toList() }
}

/**
 * The app and device context stamped onto every event.
 *
 * None of this identifies a person. It answers "which build, on what, on which
 * Android" so a funnel drop can be traced to a release or a device class.
 */
data class DeviceContext(
    val appBuild: String?,
    val osVersion: String?,
    val deviceModel: String?,
)
