package com.memoria.caregiver.data.livekit

/** Speaker volume for an audible remote track. iOS writes the same two values. */
internal const val AUDIBLE_VOLUME: Double = 1.0

/** Speaker volume for a muted-but-still-subscribed remote track. */
internal const val MUTED_VOLUME: Double = 0.0

/**
 * Who the caregiver can hear right now, and why.
 *
 * Two independent inputs, overlaid rather than replacing each other:
 *  - [audioEnabledByIdentity] — the per-tile mute button on the Live tab. A
 *    caregiver can unmute a tile to listen in without starting a Talk call.
 *  - [activeCallIdentity] — the camera currently in a Talk call, or null.
 *
 * The rule is `identity in audioEnabledByIdentity || identity == activeCallIdentity`.
 * Talk wins, and it *overlays*: starting and ending a call leaves the
 * caregiver's per-tile choices exactly as they were, so silencing a tile
 * sticks.
 *
 * **Audibility is applied by writing a track's volume, never by unsubscribing.**
 * That is a deliberate trade recorded on iOS and it must not be "fixed" here:
 * toggling the subscription churns the SDK's audio engine in a way the
 * per-buffer renderer never recovers from, and the waveform stays flat forever
 * after re-enabling. Tracks stay subscribed and decoding continuously, costing
 * ~20kbps per camera, and only the speaker output is gated.
 *
 * Pure state, no SDK types: [LiveKitClient] asks this object what each track's
 * volume should be and writes it.
 *
 * Single-threaded by contract, like everything else ported off `@MainActor`.
 */
internal class AudioGate {

    /** The camera in a Talk call, or null. */
    var activeCallIdentity: String? = null
        private set

    /** Cameras the caregiver has unmuted via the per-tile button. */
    var audioEnabledByIdentity: Set<String> = emptySet()
        private set

    /** True iff the caregiver will hear this camera right now. */
    fun isAudible(identity: String): Boolean =
        identity in audioEnabledByIdentity || identity == activeCallIdentity

    /** The volume to write to this identity's remote track. */
    fun volumeFor(identity: String): Double =
        if (isAudible(identity)) AUDIBLE_VOLUME else MUTED_VOLUME

    /**
     * Talk started on [identity], or ended when null. Does **not** touch
     * [audioEnabledByIdentity] — the per-tile choices outlive the call.
     */
    fun setActiveCall(identity: String?) {
        activeCallIdentity = identity
    }

    /**
     * Per-tile mute toggle. Returns the new state (true = audible) so the UI
     * can update its icon without a second read.
     */
    fun toggle(identity: String): Boolean {
        val nowEnabled = identity !in audioEnabledByIdentity
        audioEnabledByIdentity =
            if (nowEnabled) audioEnabledByIdentity + identity else audioEnabledByIdentity - identity
        return nowEnabled
    }

    /**
     * Hard-mute one camera and **forget** the caregiver's listen choice for it.
     * Called when per-camera privacy flips on (a bathroom or bedroom
     * classification).
     *
     * Removing the identity from the set is the load-bearing half, not zeroing
     * the volume. `LiveKitClient.handleSubscribedAudio` restores volume *from
     * this set*, so an entry left behind here re-arms playback the moment any
     * resubscribe delivers an audio track — audio from a private room, after
     * the caregiver was told it was cut. Zeroing alone is a privacy hole; this
     * makes the mute survive any rejoin.
     *
     * The Talk overlay is dropped too, as a local belt. The view's call
     * teardown still does the full `/talk/end` plus mic unpublish.
     */
    fun revoke(identity: String) {
        audioEnabledByIdentity = audioEnabledByIdentity - identity
        if (activeCallIdentity == identity) activeCallIdentity = null
    }

    /**
     * Connection-scoped reset, called from `disconnect()`. Per-tile mute state
     * does not survive a room: opening a different patient starts with every
     * tile muted again.
     */
    fun clear() {
        audioEnabledByIdentity = emptySet()
        activeCallIdentity = null
    }
}
