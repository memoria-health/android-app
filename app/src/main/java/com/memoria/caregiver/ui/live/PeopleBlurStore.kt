package com.memoria.caregiver.ui.live

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-patient "Blur people" preference. Spec: `Services/PeopleBlurStore.swift`,
 * PARITY.md §12 and §24.
 *
 * **Device-local, and deliberately not server-backed.** It is a viewing
 * preference for this one phone: it changes nothing about recording, insights,
 * or what any other device sees, and turning it off restores the untouched
 * normal video path. iOS mirrors the web dashboard's per-patient `localStorage`
 * toggle; this mirrors both. That is also why `PeopleBlurCard` has no busy state
 * and no failure state — the flip cannot fail.
 *
 * iOS backs it with `UserDefaults` and publishes it so live tiles swap the
 * instant it flips. The Android analogue is `SharedPreferences` plus a
 * [StateFlow]; PORT.md maps `UserDefaults` to DataStore, which is not on the
 * dependency list, so this follows the shape `SharedPrefsEverOnlineStore`
 * already established in `data/realtime` — swapping the storage later is one
 * class, not a change to any caller.
 *
 * iOS holds a `static let shared` on the main actor. There is no singleton here:
 * the store takes the [SharedPreferences] instance so this package does not get
 * to decide which preferences file the app owns, and so it is constructible in a
 * test.
 *
 * Not thread-safe; like the iOS `@MainActor` original, use it from one thread.
 */
class PeopleBlurStore(
    private val prefs: SharedPreferences,
) {

    private val _enabledIds = MutableStateFlow(load())

    /**
     * Patient ids currently blurred on this device. Read-only to callers, which
     * is the `@Published private(set)` on the Swift.
     */
    val enabledIds: StateFlow<Set<String>> = _enabledIds.asStateFlow()

    fun isEnabled(patientId: String): Boolean = patientId in _enabledIds.value

    fun setEnabled(on: Boolean, patientId: String) {
        val current = _enabledIds.value
        val updated = if (on) current + patientId else current - patientId
        if (updated == current) return
        _enabledIds.value = updated
        // A defensive copy in both directions: SharedPreferences documents that
        // the set from getStringSet must not be modified and that putStringSet
        // keeps a reference to whatever it is handed, so sharing the live set
        // with either corrupts it.
        prefs.edit().putStringSet(KEY, updated.toSet()).apply()
    }

    private fun load(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    companion object {
        /**
         * Carried over verbatim from iOS. Same key, same semantics, so the two
         * ports stay greppable as one thing.
         */
        const val KEY: String = "peopleBlur.enabledPatientIDs"
    }
}
