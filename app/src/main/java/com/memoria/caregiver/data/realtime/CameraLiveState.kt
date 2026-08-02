package com.memoria.caregiver.data.realtime

import android.content.SharedPreferences
import com.memoria.caregiver.data.model.Camera

/**
 * The two pieces of derived camera UI state described in PARITY.md §10, kept
 * together because the Realtime camera handler and the 1-second poll both write
 * to both of them and must stay behaviourally identical.
 *
 * - **ever-online** — persisted. A camera that has reported `online == true` at
 *   least once on this install can never regress to "preparing" after a
 *   relaunch.
 * - **reconnecting-until** — in-memory, 10-second window. Drives the Live
 *   tile's "Connecting…" buffer so a reconnect does not read as "nothing
 *   happened".
 *
 * Not thread-safe, by design: iOS holds this state on `@MainActor` and
 * [RealtimeStore] confines every writer to one dispatcher.
 */
class CameraLiveState(
    private val store: EverOnlineStore = InMemoryEverOnlineStore(),
    /**
     * Wall clock, in epoch milliseconds. `Date()` on iOS, so wall clock here
     * too rather than a monotonic clock — a device clock change moves the
     * deadline on both platforms alike.
     */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Camera ids that have reported `online == true` at least once on this
     * install. Loaded once at construction, exactly as the Swift loads it in
     * its property initializer.
     */
    private val everOnline: MutableSet<String> = store.load().toMutableSet()

    /**
     * Sensors the caregiver just asked to reconnect (`desired_state` flipped
     * disconnected → connected), mapped to the epoch-millis deadline after
     * which the "Connecting…" buffer gives up.
     */
    private val reconnectingUntil: MutableMap<String, Long> = mutableMapOf()

    /**
     * Records that a camera has successfully come online at least once.
     * Idempotent and persisted; flips the camera out of "preparing"
     * permanently, across relaunches.
     */
    fun markEverOnline(cameraId: String) {
        if (!everOnline.add(cameraId)) return
        store.save(everOnline)
    }

    /** Opens the "Connecting…" buffer for [RECONNECT_WINDOW_MILLIS]. */
    fun beginReconnectWindow(cameraId: String) {
        reconnectingUntil[cameraId] = now() + RECONNECT_WINDOW_MILLIS
    }

    /** Closes the buffer early — the sensor is up, so there is nothing to wait for. */
    fun clearReconnectWindow(cameraId: String) {
        reconnectingUntil.remove(cameraId)
    }

    /**
     * True while a freshly-connected sensor is still coming up for the FIRST
     * time: paired, the caregiver wants it on, not user-hidden, but it has
     * never reported `online` yet on this install. Drives the "Sensor is
     * preparing to start…" notice. An established camera that is merely offline
     * right now returns false, so it reads as "offline".
     */
    fun isPreparing(camera: Camera): Boolean =
        camera.paired &&
            camera.desiredState == Camera.DesiredState.CONNECTED &&
            !camera.disabled &&
            !camera.online &&
            camera.id !in everOnline

    /**
     * True while a just-reconnected sensor is still coming up: the caregiver
     * flipped it back to connected, it is not `online` yet, and the reconnect
     * window has not elapsed. Distinct from [isPreparing], which is the
     * FIRST-ever connect. Bounded by [RECONNECT_WINDOW_MILLIS] so a sensor that
     * never returns falls through to plain "offline".
     */
    fun isConnecting(camera: Camera): Boolean {
        val until = reconnectingUntil[camera.id] ?: return false
        if (until <= now()) return false
        return camera.isLiveStreaming && !camera.online
    }

    companion object {
        /** iOS `reconnectWindow: TimeInterval = 10`. */
        const val RECONNECT_WINDOW_MILLIS: Long = 10_000L
    }
}

/**
 * Persistence for the ever-online set. iOS uses `UserDefaults`; PORT.md maps
 * that to DataStore Preferences, which is not on the dependency list yet — so
 * the port is declared here and the implementation is a swap of one class, not
 * a change to any caller.
 */
interface EverOnlineStore {
    fun load(): Set<String>

    fun save(ids: Set<String>)
}

/** No persistence. The default, so tests and previews need no storage. */
class InMemoryEverOnlineStore(initial: Set<String> = emptySet()) : EverOnlineStore {
    private var ids: Set<String> = initial

    override fun load(): Set<String> = ids

    override fun save(ids: Set<String>) {
        this.ids = ids.toSet()
    }
}

/**
 * The `UserDefaults` analog. Takes the [SharedPreferences] instance rather than
 * a `Context` so this package does not get to decide which preferences file the
 * app owns.
 *
 * Both directions copy the set. `getStringSet` returns an instance the caller
 * must not modify, and `putStringSet` keeps a reference to whatever it is
 * handed — sharing [CameraLiveState]'s live set with either would corrupt it.
 */
class SharedPrefsEverOnlineStore(
    private val prefs: SharedPreferences,
) : EverOnlineStore {

    override fun load(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, ids.toSet()).apply()
    }

    companion object {
        /**
         * Carried over verbatim from iOS. The applicationId deliberately is not
         * `co.sunny.*` (DECISIONS.md D-006), but this is a preferences key, not
         * an identity — matching it keeps the two ports greppable as one thing.
         */
        const val KEY: String = "co.sunny.nemara.camerasEverOnline"
    }
}
