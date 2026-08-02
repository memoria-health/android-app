package com.memoria.caregiver.data.livekit

import android.content.Context
import android.util.Log
import com.memoria.caregiver.BuildConfig
import com.memoria.caregiver.data.model.Camera
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Where the `#if DEBUG` prints in the Swift go. */
fun interface LiveKitLog {
    fun debug(message: String)
}

/** Release builds print nothing, matching `#if DEBUG`. */
object AndroidLiveKitLog : LiveKitLog {
    private const val TAG = "NemaraLiveKit"

    override fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}

/** Errors surfaced by [LiveKitClient]. Port of `LiveKitClientError`. */
sealed class LiveKitClientError : Exception() {

    /** The user-facing copy. Swift: `LocalizedError.errorDescription`. */
    abstract val errorDescription: String

    override val message: String
        get() = errorDescription

    /** Asked to publish the microphone with no live room to publish into. */
    data object NotConnected : LiveKitClientError() {
        override val errorDescription: String
            get() = "Not connected to a LiveKit room."
    }
}

/**
 * Wraps a single LiveKit [Room] for the lifetime of the caregiver app.
 * Port of `Services/LiveKitClient.swift`. Spec: PARITY.md §11.
 *
 * One instance lives in `AppEnvironment` and is reused across patients — the
 * caregiver only ever views one room at a time (the patient whose Live tab is
 * open). Opening a different patient's Live tab disconnects the previous room
 * first.
 *
 * Tracks are exposed keyed by participant identity (= the camera's
 * `livekit_identity`, minted at pairing time) so the carousel can render each
 * camera's feed in its own tile and route audio per camera.
 *
 * ### What is deliberately not here
 *
 * The rules that decide anything live next door as pure functions —
 * [cameraConnectivity], [nextConnectionState], [AudioGate], [AudioLevelBuffer]
 * and [pcmPeak] — so they can be tested without a room, a device or an audio
 * thread. This class is the shell that owns the SDK objects and the state
 * flows, and it makes no decisions of its own.
 *
 * ### Threading
 *
 * iOS marks the whole class `@MainActor`. The equivalent here is that every
 * mutation is confined to [dispatcher], which **must** be single-threaded:
 * the room's event stream, the two audio taps and the caller all write the same
 * unsynchronised maps. Same contract as `RealtimeStore`.
 */
class LiveKitClient(
    context: Context,
    private val scope: CoroutineScope,
    /** The `@MainActor` analog. Must be single-threaded. */
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
    private val log: LiveKitLog = AndroidLiveKitLog,
) {

    /**
     * The underlying room. One instance, reused across connect / disconnect
     * cycles, exactly as the Swift keeps one `Room()` for the app's lifetime.
     */
    private val room: Room = LiveKit.create(context.applicationContext)

    private val _state = MutableStateFlow<LiveKitConnectionState>(LiveKitConnectionState.Disconnected)

    /** Connection state, mirrored from the room's own transitions. */
    val state: StateFlow<LiveKitConnectionState> = _state.asStateFlow()

    private val _videoTracksByIdentity = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())

    /** Subscribed remote video tracks, keyed by publisher identity. The carousel fills its tiles from this. */
    val videoTracksByIdentity: StateFlow<Map<String, VideoTrack>> = _videoTracksByIdentity.asStateFlow()

    private val _audioTracksByIdentity = MutableStateFlow<Map<String, AudioTrack>>(emptyMap())

    /**
     * Subscribed remote audio tracks, keyed by publisher identity. This class
     * taps the combined signal to drive the waveform, so consumers should not
     * need to touch these directly.
     */
    val audioTracksByIdentity: StateFlow<Map<String, AudioTrack>> = _audioTracksByIdentity.asStateFlow()

    private val _audioLevels = MutableStateFlow<Map<String, List<Float>>>(emptyMap())

    /**
     * Rolling per-camera audio peaks, 0.0-1.0, newest last, trimmed to
     * [AUDIO_LEVEL_CAPACITY]. Safe to hand straight to `NemaraWaveform`.
     */
    val audioLevels: StateFlow<Map<String, List<Float>>> = _audioLevels.asStateFlow()

    private val _isMicrophonePublished = MutableStateFlow(false)

    /** True iff the caregiver's microphone is currently being published. */
    val isMicrophonePublished: StateFlow<Boolean> = _isMicrophonePublished.asStateFlow()

    private val _currentRoom = MutableStateFlow<String?>(null)

    /** The room name (`patient_<patient_id>`) we're connected to; null when disconnected. */
    val currentRoom: StateFlow<String?> = _currentRoom.asStateFlow()

    private val _audioEnabledByIdentity = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Cameras the caregiver has unmuted with the per-tile button. Talk overlays
     * an extra unmute on top of this without changing it — see [AudioGate].
     */
    val audioEnabledByIdentity: StateFlow<Set<String>> = _audioEnabledByIdentity.asStateFlow()

    /**
     * Concrete remote-audio refs, which the protocol-typed [AudioTrack] in
     * [audioTracksByIdentity] does not give us a volume control on. Speaker
     * playback is gated by writing volume; the tracks stay subscribed and
     * decoding so the combined tap keeps firing — see [AudioGate].
     */
    private val remoteAudioTracksByIdentity = mutableMapOf<String, RemoteAudioTrack>()

    private val gate = AudioGate()
    private val levels = AudioLevelBuffer()

    /** Tap on the mixed remote signal, just before playback. */
    private val remoteTap = AudioLevelTap("_remote_") { peak -> pushAudioLevel(peak, fromLocal = false) }

    /** Tap on the caregiver's own microphone, before transmission. */
    private val localTap = AudioLevelTap("_local_") { peak -> pushAudioLevel(peak, fromLocal = true) }

    private var acceptedPushCount: Long = 0
    private var droppedPushCount: Long = 0

    init {
        // Both taps and the event collector are wired at construction, not at
        // connect, so nothing can race the first connect and be missed.
        room.audioProcessingController.renderPreProcessor = remoteTap
        room.audioProcessingController.capturePostProcessor = localTap
        log.debug("[LiveKit] remote + local audio taps registered with the room's processing controller")
        scope.launch(dispatcher) {
            room.events.collect { event -> handle(event) }
        }
    }

    // ---- Connectivity predicate ----

    /**
     * Authoritative "is this camera live to us right now" check. Use this from
     * any UI deciding between "ready to view / call" and "show offline /
     * waiting"; do not read [state], `cam.online` or [videoTracksByIdentity]
     * directly, because they disagree during transient reconnects.
     *
     * **Reads a snapshot, so it does not recompose on its own.** SwiftUI gets
     * that for free from `@Observable`; Compose does not. From a composable,
     * collect [state] and [videoTracksByIdentity] and call the free function
     * [cameraConnectivity] with them — same rule, same answer, and the tile
     * repaints when either signal moves.
     */
    fun connectivity(cam: Camera): CameraConnectivity = cameraConnectivity(
        identity = cam.livekitIdentity,
        state = _state.value,
        hasVideoTrack = cam.livekitIdentity?.let { _videoTracksByIdentity.value[it] } != null,
    )

    /** Convenience for the common "is fully live" gate: call buttons, full-screen entry, waveform. */
    fun isLive(cam: Camera): Boolean = connectivity(cam) == CameraConnectivity.LIVE

    // ---- Connection ----

    suspend fun connect(url: String, token: String, roomName: String) {
        log.debug("[LiveKit] connect → room=$roomName url=$url (prevState=${_state.value})")
        if (_state.value != LiveKitConnectionState.Disconnected) {
            room.disconnect()
        }
        _state.value = LiveKitConnectionState.Connecting
        _currentRoom.value = roomName
        try {
            room.connect(url, token)
            // Terminal state arrives on the event stream; don't pre-flip.
            log.debug(
                "[LiveKit] connect → room.connect() returned ok; remoteParticipants=" +
                    room.remoteParticipants.values.mapNotNull { it.identity?.value },
            )
        } catch (cancellation: CancellationException) {
            // Structured concurrency, not a connect failure: the caller's scope
            // is going away and must be allowed to. Swift has no equivalent
            // distinction, so this branch has no counterpart in the original.
            throw cancellation
        } catch (error: Exception) {
            _state.value = LiveKitConnectionState.Failed(error.toString())
            _currentRoom.value = null
            log.debug("[LiveKit] connect FAILED room=$roomName: $error")
        }
    }

    /**
     * Best-effort re-subscribe for an identity we have other reasons to believe
     * is publishing — Supabase Realtime said so, or the SDK just told us the
     * participant published or joined.
     *
     * It exists because the SDK's in-memory, event-sourced view of a room can
     * drift after its WebRTC engine restarts internally. This is the client-side
     * analogue of the backend's REST-poll reconciler.
     *
     * Escalation, in order:
     *  1. Already have a video track → no-op.
     *  2. Room not connected → bail; the connect-time backfill will pick it up.
     *  3. Participant present but tracks missing → walk the publications and
     *     re-subscribe, so the SDK re-issues the subscribe RPC.
     *  4. Participant absent from the SDK entirely (a true desync) → force a
     *     disconnect, **only if [reconnectIfAbsent]**.
     *
     * @param reconnectIfAbsent defaults true, but the Realtime hook passes
     *   **false**. That path fires on caregiver intent, before the publisher has
     *   necessarily rejoined, so an absent participant there is normal rather
     *   than a desync — and tearing the room down would strand the app, because
     *   nothing re-mints a token outside the view's bootstrap.
     *
     * Idempotent and cheap; safe to call from every Realtime `cameras` update.
     */
    fun ensureSubscribed(identity: String, reconnectIfAbsent: Boolean = true) {
        if (_state.value != LiveKitConnectionState.Connected) {
            log.debug("[LiveKit] ensureSubscribed: identity=$identity skipped — room not connected (state=${_state.value})")
            return
        }
        if (_videoTracksByIdentity.value[identity] != null) return

        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == identity }
        if (participant == null) {
            log.debug("[LiveKit] ensureSubscribed: identity=$identity absent from SDK (reconnectIfAbsent=$reconnectIfAbsent)")
            if (reconnectIfAbsent) {
                room.disconnect()
            }
            return
        }

        val publications = participant.trackPublications.values
        log.debug("[LiveKit] ensureSubscribed: identity=$identity found; re-subscribing ${publications.size} publication(s)")
        for (publication in publications) {
            val remotePublication = publication as? RemoteTrackPublication ?: continue
            try {
                remotePublication.setSubscribed(true)
            } catch (error: Exception) {
                log.debug("[LiveKit] ensureSubscribed: setSubscribed failed for sid=${publication.sid}: $error")
            }
        }
    }

    fun disconnect() {
        room.disconnect()
        // Null the tracks now so the UI shows empty tiles immediately, rather
        // than after the next event round-trip.
        _videoTracksByIdentity.value = emptyMap()
        _audioTracksByIdentity.value = emptyMap()
        remoteAudioTracksByIdentity.clear()
        levels.clearLevels()
        _audioLevels.value = levels.levels
        // Per-tile mute state is connection-scoped: a fresh patient session
        // starts with every tile muted again.
        gate.clear()
        _audioEnabledByIdentity.value = gate.audioEnabledByIdentity
        _currentRoom.value = null
        _state.value = LiveKitConnectionState.Disconnected
    }

    // ---- Remote audio gate (Talk mode) ----

    /**
     * Start or end the Talk overlay. Pass null when the call ends.
     *
     * The per-tile mute state is preserved: the active call identity only
     * overlays an additional unmute, it does not clear the caregiver's listen
     * choices.
     */
    fun setRemoteAudioPlayback(enabledFor: String?) {
        gate.setActiveCall(enabledFor)
        if (enabledFor == null) {
            // Drop the drawn levels immediately so the waveform goes flat the
            // instant the caregiver taps end, rather than waiting for a render
            // that is not coming.
            levels.endCall()
            _audioLevels.value = levels.levels
        }
        applyAudioGate()
        log.debug("[LiveKit] setRemoteAudioPlayback active=${enabledFor ?: "nil"}, tracks=${remoteAudioTracksByIdentity.size}")
    }

    /**
     * Per-tile mute toggle, independent of Talk: the caregiver can unmute a
     * passive tile to listen in without starting a call, and the choice
     * survives a call starting and ending.
     *
     * @return the new state, true = audible.
     */
    fun toggleAudio(forIdentity: String): Boolean {
        val nowEnabled = gate.toggle(forIdentity)
        _audioEnabledByIdentity.value = gate.audioEnabledByIdentity
        applyAudioGate()
        return nowEnabled
    }

    /**
     * Hard-mute one camera and forget the caregiver's listen choice for it.
     * Called when per-camera privacy flips on: the backend severs the media
     * server-side, and this makes the mute survive any rejoin. See
     * [AudioGate.revoke] for why dropping the set entry is the load-bearing
     * half.
     */
    fun revokeAudio(forIdentity: String) {
        gate.revoke(forIdentity)
        _audioEnabledByIdentity.value = gate.audioEnabledByIdentity
        remoteAudioTracksByIdentity[forIdentity]?.setVolume(MUTED_VOLUME)
    }

    /** True iff the caregiver will hear audio from this camera right now. */
    fun isAudioAudible(forIdentity: String): Boolean = gate.isAudible(forIdentity)

    /**
     * Write the combined Talk + per-tile state to every subscribed remote
     * track's volume. Volume rather than subscription — see [AudioGate].
     */
    private fun applyAudioGate() {
        for ((identity, track) in remoteAudioTracksByIdentity) {
            track.setVolume(gate.volumeFor(identity))
        }
    }

    // ---- Microphone publish (Talk mode) ----

    /**
     * Toggle the caregiver's microphone on the existing connection. The
     * broadcaster-role token minted by `/v1/livekit/token` already permits
     * publishing.
     *
     * Requires `RECORD_AUDIO`; the caller is responsible for holding it before
     * calling with `enable = true`.
     */
    suspend fun publishMicrophone(enable: Boolean) {
        val current = _state.value
        if (current != LiveKitConnectionState.Connected && current != LiveKitConnectionState.Reconnecting) {
            throw LiveKitClientError.NotConnected
        }
        room.localParticipant.setMicrophoneEnabled(enable)
        _isMicrophonePublished.value = enable
        routeAudioForMicrophone(enable)
    }

    /**
     * Force the loudspeaker while the microphone is published.
     *
     * Publishing the mic puts the audio session into a two-way communication
     * mode, and both platforms then default output to the **earpiece**. The
     * caregiver would have to hold the phone to their ear to hear the patient,
     * which is wrong for a room-monitoring app and reads to them as "Talk
     * broke the audio". iOS forces `overrideOutputAudioPort(.speaker)`; the
     * Android equivalent is to select the speakerphone device on the SDK's own
     * routing handler.
     *
     * Releasing the choice on disable hands routing back to automatic
     * selection, which is what iOS gets for free when the session reverts.
     */
    private fun routeAudioForMicrophone(enable: Boolean) {
        val handler = room.audioSwitchHandler ?: return
        if (enable) {
            val speaker = handler.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }
            if (speaker == null) {
                log.debug("[LiveKit] no speakerphone device available; leaving routing alone")
                return
            }
            handler.selectDevice(speaker)
        } else {
            handler.selectDevice(null)
        }
    }

    // ---- Track lifecycle ----

    private fun handleSubscribedVideo(track: VideoTrack, identity: String) {
        _videoTracksByIdentity.value = _videoTracksByIdentity.value + (identity to track)
    }

    private fun handleSubscribedAudio(track: AudioTrack, identity: String) {
        _audioTracksByIdentity.value = _audioTracksByIdentity.value + (identity to track)
        if (track is RemoteAudioTrack) {
            remoteAudioTracksByIdentity[identity] = track
            // Silent speaker by default: the track stays subscribed and
            // decoding, but nothing is audible until the caregiver starts Talk
            // or unmutes the tile. Continuous decode is what keeps the combined
            // tap firing reliably.
            track.setVolume(gate.volumeFor(identity))
        }
        log.debug("[LiveKit] subscribed audio: identity=$identity, audible=${gate.isAudible(identity)}")
    }

    private fun handleUnsubscribedVideo(identity: String) {
        _videoTracksByIdentity.value = _videoTracksByIdentity.value - identity
    }

    private fun handleUnsubscribedAudio(identity: String) {
        _audioTracksByIdentity.value = _audioTracksByIdentity.value - identity
        remoteAudioTracksByIdentity.remove(identity)
        levels.remove(identity)
        _audioLevels.value = levels.levels
    }

    /**
     * Called from the audio thread by both taps. Hops onto [dispatcher] first,
     * exactly as the Swift closures hop onto the main actor.
     */
    private fun pushAudioLevel(peak: Float, fromLocal: Boolean) {
        scope.launch(dispatcher) {
            val changed = levels.push(peak, fromLocal, gate.activeCallIdentity)
            if (!changed) {
                // No active call — the tap fired but the waveform should not
                // move. Counted and logged sparsely, because "is the tap firing
                // at all" is the first question when the waveform is flat and
                // it is otherwise unanswerable from outside.
                droppedPushCount++
                if (droppedPushCount % LOG_EVERY_N_BUFFERS == 1L) {
                    log.debug("[LiveKit] pushAudioLevel DROPPED (no active call), dropped#=$droppedPushCount")
                }
                return@launch
            }
            _audioLevels.value = levels.levels
            acceptedPushCount++
            if (acceptedPushCount <= FIRST_BUFFERS_TO_LOG || acceptedPushCount % LOG_EVERY_N_BUFFERS == 0L) {
                log.debug(
                    "[LiveKit] pushAudioLevel id=${gate.activeCallIdentity} peak=$peak " +
                        "local=$fromLocal accepted#=$acceptedPushCount",
                )
            }
        }
    }

    // ---- Room events ----

    /**
     * The SDK's event stream stands in for the iOS `RoomDelegate`.
     *
     * [ensureSubscribed] is reached from **three** of these, and all three are
     * required. With auto-subscribe the SDK usually follows a publish with a
     * subscribe, but it can miss a publisher who joins *after* we connected,
     * and that was the intermittent "a camera someone just switched on never
     * shows up" bug. Track-subscribed covers the normal path,
     * track-published covers any camera turning on regardless of who triggered
     * it, and participant-connected covers the publish/connect ordering race.
     */
    private fun handle(event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> applySignal(ConnectionSignal.CONNECTED)
            is RoomEvent.Reconnecting -> applySignal(ConnectionSignal.RECONNECTING)
            is RoomEvent.Reconnected -> applySignal(ConnectionSignal.CONNECTED)
            is RoomEvent.Disconnected -> applySignal(ConnectionSignal.DISCONNECTED)

            is RoomEvent.TrackSubscribed -> {
                val identity = event.participant.identity?.value.orEmpty()
                if (identity.isEmpty()) return
                log.debug("[LiveKit] trackSubscribed identity=$identity kind=${event.publication.kind}")
                when (val track = event.track) {
                    is VideoTrack -> handleSubscribedVideo(track, identity)
                    is AudioTrack -> handleSubscribedAudio(track, identity)
                    else -> Unit
                }
            }

            is RoomEvent.TrackPublished -> {
                val identity = event.participant.identity?.value.orEmpty()
                if (identity.isEmpty()) return
                log.debug("[LiveKit] trackPublished identity=$identity kind=${event.publication.kind} — ensuring subscribed")
                ensureSubscribed(identity, reconnectIfAbsent = false)
            }

            is RoomEvent.ParticipantConnected -> {
                val identity = event.participant.identity?.value.orEmpty()
                if (identity.isEmpty()) return
                log.debug("[LiveKit] participantConnected identity=$identity — ensuring subscribed")
                ensureSubscribed(identity, reconnectIfAbsent = false)
            }

            is RoomEvent.TrackUnsubscribed -> {
                val identity = event.participant.identity?.value.orEmpty()
                if (identity.isEmpty()) return
                when (event.publications.kind) {
                    Track.Kind.VIDEO -> handleUnsubscribedVideo(identity)
                    Track.Kind.AUDIO -> handleUnsubscribedAudio(identity)
                    else -> Unit
                }
            }

            else -> Unit
        }
    }

    private fun applySignal(signal: ConnectionSignal) {
        val previous = _state.value
        val next = nextConnectionState(previous, signal)
        log.debug("[LiveKit] connState $previous → $next (signal=$signal)")
        _state.value = next
    }

    private companion object {
        /** Log the first few accepted buffers, to answer "did the tap ever fire". */
        const val FIRST_BUFFERS_TO_LOG = 5L

        /** Then one line per this many, to answer "is it still firing" without flooding. */
        const val LOG_EVERY_N_BUFFERS = 256L
    }
}
