package com.memoria.caregiver.data.livekit

/**
 * Connection state and the fused per-camera connectivity predicate, plus the
 * two pure rules that decide them.
 *
 * Both types are nested inside `LiveKitClient` on iOS. They are top-level here
 * so the rules below can be exercised in a plain JVM test — loading a nested
 * type would drag `LiveKitClient` in, and that pulls the whole LiveKit runtime
 * onto a unit-test classpath that has no Android under it.
 */

/**
 * Connection state mirrored from the room's own transitions.
 * Port of `LiveKitClient.State`.
 */
sealed interface LiveKitConnectionState {
    data object Disconnected : LiveKitConnectionState
    data object Connecting : LiveKitConnectionState
    data object Connected : LiveKitConnectionState
    data object Reconnecting : LiveKitConnectionState

    /**
     * Terminal. [reason] is the connect failure, kept so the UI can show it —
     * which is why [nextConnectionState] refuses to overwrite it.
     */
    data class Failed(val reason: String) : LiveKitConnectionState
}

/**
 * Fused per-camera connectivity status. Single source of truth for "is this
 * wall phone actually streaming to us right now" — it folds the signals that
 * can disagree during a reconnect:
 *   - room state (we may not even be connected)
 *   - subscribed video track presence (real-time, from the SDK)
 *   - paired-but-no-identity rows (placeholders issued before first claim)
 *
 * **All UI must switch on this**, never on the raw signals.
 *
 * [JOINING] is declared and never returned — [cameraConnectivity] yields
 * [UNPAIRED] / [ROOM_DOWN] / [LIVE] / [OFFLINE] only. That is true of the iOS
 * enum too, and PARITY.md §11 says to port it as-is rather than invent a
 * branch for it.
 */
enum class CameraConnectivity {
    /** Camera row has no `livekit_identity` yet: pairing code issued, wall phone hasn't claimed it. */
    UNPAIRED,

    /** Our LiveKit room isn't connected at all, so we can't speak to any camera's state. */
    ROOM_DOWN,

    /** Backend says the camera isn't in the room. */
    OFFLINE,

    /** Backend says the camera joined; we haven't subscribed its video track yet. */
    JOINING,

    /** Video track is flowing — the camera is fully live. */
    LIVE,
}

/**
 * The authoritative "is this camera live to us right now" rule.
 *
 * The backend's `cameras.online` column is deliberately **not** an input. The
 * subscribed-video-track map is the most direct evidence that video is flowing
 * from this publisher at this instant, and the backend column can lag it by
 * ~10s (its REST reconcile interval). Using SDK presence keeps the chip honest
 * at the same moment the tile starts or stops painting video.
 *
 * @param identity the camera's `livekit_identity`, or null for an unclaimed row.
 * @param hasVideoTrack whether a video track is currently subscribed for [identity].
 */
fun cameraConnectivity(
    identity: String?,
    state: LiveKitConnectionState,
    hasVideoTrack: Boolean,
): CameraConnectivity = when {
    identity == null -> CameraConnectivity.UNPAIRED
    state != LiveKitConnectionState.Connected -> CameraConnectivity.ROOM_DOWN
    hasVideoTrack -> CameraConnectivity.LIVE
    else -> CameraConnectivity.OFFLINE
}

/** What the room told us. Mirrors the cases of the iOS `didUpdateConnectionState` hook. */
internal enum class ConnectionSignal {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
}

/**
 * Folds a room transition into the client's state.
 *
 * The one rule that is not mechanical: **a disconnect must not clobber a
 * terminal [LiveKitConnectionState.Failed]**. Every failed connect is followed
 * by a disconnect transition, so without this the reason the caregiver needs
 * to read is replaced by a bare "disconnected" a few milliseconds after it
 * appears.
 */
internal fun nextConnectionState(
    current: LiveKitConnectionState,
    signal: ConnectionSignal,
): LiveKitConnectionState = when (signal) {
    ConnectionSignal.CONNECTING -> LiveKitConnectionState.Connecting
    ConnectionSignal.CONNECTED -> LiveKitConnectionState.Connected
    ConnectionSignal.RECONNECTING -> LiveKitConnectionState.Reconnecting
    ConnectionSignal.DISCONNECTED ->
        if (current is LiveKitConnectionState.Failed) current else LiveKitConnectionState.Disconnected
}
