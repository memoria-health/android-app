package com.memoria.caregiver.data.livekit

import com.twilio.audioswitch.AudioDevice

/**
 * The one rule that decides whether publishing the microphone should move call
 * audio to the loudspeaker.
 *
 * Split out from [LiveKitClient] for one reason: the client needs a live
 * `Room`, so the rule inside it cannot be exercised by a JVM test. The rule is
 * the part that can be wrong in a way a caregiver notices, so it is the part
 * that has to be testable. `LiveKitClient` keeps the SDK calls and this file
 * keeps the judgement.
 */

/**
 * The output routes that matter to the decision, independent of the SDK's
 * type hierarchy so a JVM test can name one.
 */
internal enum class AudioRouteKind {
    Speakerphone,
    WiredHeadset,
    BluetoothHeadset,
    Earpiece,

    /** No device selected yet, or one this app does not model. */
    Unknown,
}

/** Maps the SDK's device type onto [AudioRouteKind]. */
internal fun audioRouteKind(device: AudioDevice?): AudioRouteKind = when (device) {
    is AudioDevice.Speakerphone -> AudioRouteKind.Speakerphone
    is AudioDevice.WiredHeadset -> AudioRouteKind.WiredHeadset
    is AudioDevice.BluetoothHeadset -> AudioRouteKind.BluetoothHeadset
    is AudioDevice.Earpiece -> AudioRouteKind.Earpiece
    else -> AudioRouteKind.Unknown
}

/**
 * Whether the mic going live must force the loudspeaker.
 *
 * Publishing the mic puts the audio session into two-way communication mode,
 * whose default output is the **earpiece** on both platforms. A caregiver would
 * then have to hold the phone to their ear to hear a room they are monitoring
 * from across the house, which reads to them as "Talk broke the audio". iOS
 * calls `overrideOutputAudioPort(.speaker)`; this is the Android equivalent.
 *
 * Two routes are left alone:
 *
 * - **Speakerphone** — already the goal.
 * - **A wired or bluetooth headset** — not the earpiece, so not the failure
 *   being guarded against. A caregiver who put a headset on did it on purpose,
 *   and overriding them would throw a private conversation about someone's
 *   parent onto a loudspeaker in public. That is a worse failure than the one
 *   this function exists to prevent.
 *
 * [AudioRouteKind.Unknown] forces the speaker. When the route is not known, the
 * recoverable mistake is being too loud; the unrecoverable one is a caregiver
 * concluding the feature is broken.
 */
internal fun shouldForceSpeakerphone(current: AudioRouteKind): Boolean = when (current) {
    AudioRouteKind.Speakerphone,
    AudioRouteKind.WiredHeadset,
    AudioRouteKind.BluetoothHeadset,
    -> false

    AudioRouteKind.Earpiece,
    AudioRouteKind.Unknown,
    -> true
}
