package com.memoria.caregiver.ui.live

import com.memoria.caregiver.data.livekit.CameraConnectivity
import com.memoria.caregiver.data.livekit.LiveKitConnectionState
import com.memoria.caregiver.data.livekit.LiveRoom
import com.memoria.caregiver.data.model.Camera
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [LiveRoom] with no SDK behind it.
 *
 * The real client calls `LiveKit.create()` in its constructor, so it needs an
 * Android `Context` and cannot be built on a plain JVM. Everything
 * [LiveViewModel] does with the room is a call to make or a flow to read, and
 * both are recorded here.
 *
 * [room] is null, which is honest: there is no SDK room. The one place that
 * matters is the tile, and `LiveVideoState.room` is already nullable because
 * iOS's is too.
 */
internal class FakeLiveRoom : LiveRoom {

    override val state = MutableStateFlow<LiveKitConnectionState>(LiveKitConnectionState.Disconnected)
    override val videoTracksByIdentity = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    override val audioEnabledByIdentity = MutableStateFlow<Set<String>>(emptySet())
    override val audioLevels = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    override val currentRoom = MutableStateFlow<String?>(null)
    override val room: Room? = null

    /** Every call, in order, as `"name(args)"`. */
    val calls: MutableList<String> = mutableListOf()

    /** Connect arguments, so a test can assert the room name and the token. */
    val connects: MutableList<Triple<String, String, String>> = mutableListOf()

    var connectivityResult: CameraConnectivity = CameraConnectivity.LIVE

    /**
     * Set to make [connect] throw. Named for what it simulates rather than for
     * the mechanism: a token that mints fine and a room that will not take it.
     */
    var connectFailure: Throwable? = null

    override suspend fun connect(url: String, token: String, roomName: String) {
        calls += "connect($roomName)"
        connects += Triple(url, token, roomName)
        connectFailure?.let { throw it }
        currentRoom.value = roomName
        state.value = LiveKitConnectionState.Connected
    }

    override fun disconnect() {
        calls += "disconnect()"
        currentRoom.value = null
        state.value = LiveKitConnectionState.Disconnected
    }

    override fun ensureSubscribed(identity: String, reconnectIfAbsent: Boolean) {
        calls += "ensureSubscribed($identity, reconnectIfAbsent=$reconnectIfAbsent)"
    }

    override fun toggleAudio(forIdentity: String): Boolean {
        calls += "toggleAudio($forIdentity)"
        val enabled = audioEnabledByIdentity.value
        val nowAudible = forIdentity !in enabled
        audioEnabledByIdentity.value =
            if (nowAudible) enabled + forIdentity else enabled - forIdentity
        return nowAudible
    }

    override fun revokeAudio(forIdentity: String) {
        calls += "revokeAudio($forIdentity)"
        audioEnabledByIdentity.value = audioEnabledByIdentity.value - forIdentity
    }

    override suspend fun publishMicrophone(enable: Boolean) {
        calls += "publishMicrophone($enable)"
    }

    override fun setRemoteAudioPlayback(enabledFor: String?) {
        calls += "setRemoteAudioPlayback($enabledFor)"
    }

    override fun connectivity(cam: Camera): CameraConnectivity = connectivityResult
}

/** True if any recorded call starts with [prefix]. */
internal fun FakeLiveRoom.called(prefix: String): Boolean = calls.any { it.startsWith(prefix) }

/** How many recorded calls start with [prefix]. */
internal fun FakeLiveRoom.countOf(prefix: String): Int = calls.count { it.startsWith(prefix) }

/** A [SensitiveRoomAckStore] that keeps its set in memory and counts saves. */
internal class RecordingAckStore(initial: Set<String> = emptySet()) : SensitiveRoomAckStore {
    var saved: Set<String> = initial
        private set
    var saveCount: Int = 0
        private set

    override fun load(): Set<String> = saved

    override fun save(cameraIds: Set<String>) {
        saved = cameraIds
        saveCount++
    }
}
