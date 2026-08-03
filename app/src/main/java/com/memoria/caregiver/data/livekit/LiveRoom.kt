package com.memoria.caregiver.data.livekit

import com.memoria.caregiver.data.model.Camera
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the Live tab needs from the room client, and nothing else.
 *
 * ## Why this exists
 *
 * [LiveKitClient] is a concrete class that calls `LiveKit.create()` in its
 * constructor, so anything holding one directly cannot be exercised without an
 * Android `Context` and the SDK. Every other decision in this unit was
 * deliberately kept testable on a plain JVM — `AudioGate`, `DesyncReconciler`,
 * `LiveKitConnection`, the whole of `TalkMode` — and no test in the repository
 * constructs a `LiveKitClient` at all.
 *
 * `LiveViewModel` is the piece that joins the room client to the API client, so
 * it is the piece most worth testing and the one that would have been hardest to
 * test. This interface is the seam that keeps it honest, and it is the same
 * pattern `TalkApi` and `TalkMedia` already use one layer down: name the few
 * calls actually made, and let a fake supply them.
 *
 * Declared here rather than in `ui/live` so the dependency points the usual way:
 * the UI may know about the data layer, never the reverse.
 */
interface LiveRoom {

    /** Connection state, mirrored from the room's own transitions. */
    val state: StateFlow<LiveKitConnectionState>

    /** Subscribed remote video tracks, keyed by publisher identity. */
    val videoTracksByIdentity: StateFlow<Map<String, VideoTrack>>

    /** Cameras the caregiver has unmuted with the per-tile button. */
    val audioEnabledByIdentity: StateFlow<Set<String>>

    /** Rolling per-camera audio peaks, 0.0–1.0, newest last. */
    val audioLevels: StateFlow<Map<String, List<Float>>>

    /** The room name we are connected to; null when disconnected. */
    val currentRoom: StateFlow<String?>

    /**
     * The `Room` the renderer binds tracks to, or null if there is no SDK room
     * (which is the case for every test fake).
     *
     * **Read it, do not drive it.** See [LiveKitClient.room].
     */
    val room: Room?

    suspend fun connect(url: String, token: String, roomName: String)

    fun disconnect()

    /**
     * Best-effort re-subscribe for an identity we have other reasons to believe
     * is publishing.
     *
     * No default argument, unlike [LiveKitClient.ensureSubscribed] — an
     * interface method's default cannot be overridden, and the two callers want
     * different values for genuinely different reasons. Making it explicit at
     * every call site is the point.
     */
    fun ensureSubscribed(identity: String, reconnectIfAbsent: Boolean)

    /** Flip one tile's listen choice. Returns the new audible state. */
    fun toggleAudio(forIdentity: String): Boolean

    /**
     * Remove an identity from the enabled set. Not "set the volume to zero" —
     * see PARITY.md §11.
     */
    fun revokeAudio(forIdentity: String)

    suspend fun publishMicrophone(enable: Boolean)

    fun setRemoteAudioPlayback(enabledFor: String?)

    /** iOS: `LiveKitClient.connectivity(_:)`. */
    fun connectivity(cam: Camera): CameraConnectivity
}
