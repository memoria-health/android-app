package com.memoria.caregiver.data.livekit

import kotlin.math.max

/**
 * Rolling window of audio peaks the waveform draws, in samples. iOS keeps 80
 * and `NemaraWaveform` reads the tail of the same list.
 */
internal const val AUDIO_LEVEL_CAPACITY: Int = 80

/**
 * The per-camera waveform's sample store, and the rule that fuses the two
 * audio taps into one bar height.
 *
 * There are two peak sources, both pushed here 20-50 times a second:
 *  - **remote** — every subscribed camera's audio, post-mix, just before playback.
 *  - **local** — the caregiver's own microphone, before transmission.
 *
 * The waveform appends `max(local, remote)` rather than appending each source
 * as it arrives. Appending independently makes the two render callbacks comb
 * tall and short bars against each other, which reads as a broken visualizer;
 * taking the louder of the two makes the waveform track whoever is actually
 * speaking.
 *
 * Levels only accumulate while a Talk call is active. A push with no active
 * call still records its peak (so the first bar after a call starts is already
 * correct) but writes nothing.
 *
 * Pure state, single-threaded by contract.
 */
internal class AudioLevelBuffer(private val capacity: Int = AUDIO_LEVEL_CAPACITY) {

    /** Per-identity rolling peaks, newest last. Safe to hand straight to the waveform. */
    var levels: Map<String, List<Float>> = emptyMap()
        private set

    private var lastLocalPeak: Float = 0f
    private var lastRemotePeak: Float = 0f

    /**
     * Record a peak from one tap.
     *
     * @return true if [levels] changed, so the caller only republishes when
     *   there is something new to draw.
     */
    fun push(peak: Float, fromLocal: Boolean, activeCallIdentity: String?): Boolean {
        // Record before the guard: iOS does too, and it is what makes the very
        // first bar after Talk starts reflect real audio instead of zero.
        if (fromLocal) lastLocalPeak = peak else lastRemotePeak = peak

        val identity = activeCallIdentity ?: return false
        val level = max(lastLocalPeak, lastRemotePeak)
        levels = levels + (identity to (levels[identity].orEmpty() + level).takeLast(capacity))
        return true
    }

    /**
     * Talk ended. Drops the drawn levels immediately so the waveform goes flat
     * the instant the caregiver taps end, rather than waiting for a render that
     * is never coming, and zeroes both peaks so a later call does not start
     * from a stale one.
     */
    fun endCall() {
        levels = emptyMap()
        lastLocalPeak = 0f
        lastRemotePeak = 0f
    }

    /**
     * Room teardown. Clears the drawn levels but deliberately leaves the two
     * last-peak values alone — that is what the Swift does, and the next call
     * overwrites them on its first buffer anyway.
     */
    fun clearLevels() {
        levels = emptyMap()
    }

    /** One camera's audio track went away. */
    fun remove(identity: String) {
        levels = levels - identity
    }
}
