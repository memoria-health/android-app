package com.memoria.caregiver.ui.live

import android.content.SharedPreferences

/**
 * Which cameras' one-time "private room" explainer the caregiver has dismissed
 * **on this device**. Spec: `PatientLiveView.sensitiveRoomAcks` /
 * `loadSensitiveRoomAcks()` / `acknowledgeSensitiveRoom(_:)`, PARITY.md §18.
 *
 * **Device-local by design, like the People-blur preference.** The privacy state
 * itself is server-derived and locked (a bathroom or bedroom classification, no
 * user toggle); only the acknowledgement of the explainer is local. Dismissing it
 * on this phone changes nothing about the camera, and it must not travel to
 * another device, because "I have read this" is a fact about a reader.
 *
 * Same shape as `data/realtime/CameraLiveState.kt`'s `EverOnlineStore`: an
 * interface plus an in-memory default plus a `SharedPreferences` implementation,
 * so tests and previews need no storage and the persistence choice is a swap of
 * one class rather than a change to any caller.
 */
interface SensitiveRoomAckStore {
    fun load(): Set<String>

    fun save(cameraIds: Set<String>)
}

/** No persistence. The default, so tests and previews need no storage. */
class InMemorySensitiveRoomAckStore(initial: Set<String> = emptySet()) : SensitiveRoomAckStore {
    private var ids: Set<String> = initial

    override fun load(): Set<String> = ids

    override fun save(cameraIds: Set<String>) {
        ids = cameraIds.toSet()
    }
}

/**
 * The `UserDefaults` analog. Takes the [SharedPreferences] instance rather than a
 * `Context` so this package does not get to decide which preferences file the app
 * owns — the same reasoning as `SharedPrefsEverOnlineStore`.
 *
 * Both directions copy the set. `getStringSet` returns an instance the caller
 * must not modify, and `putStringSet` keeps a reference to whatever it is handed,
 * so sharing a live set with either would corrupt it.
 */
class SharedPrefsSensitiveRoomAckStore(
    private val prefs: SharedPreferences,
) : SensitiveRoomAckStore {

    override fun load(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    override fun save(cameraIds: Set<String>) {
        prefs.edit().putStringSet(KEY, cameraIds.toSet()).apply()
    }

    companion object {
        /**
         * Carried verbatim from iOS's `sensitiveRoomAckKey`.
         *
         * iOS stores a **sorted array** of UUID strings; a `Set<String>` is the
         * natural Android shape and ordering carries no meaning here, so the sort
         * is dropped rather than faked. The key stays identical so the two ports
         * remain greppable as one thing.
         */
        const val KEY: String = "sensitiveRoomAcknowledgedCameraIds"
    }
}
