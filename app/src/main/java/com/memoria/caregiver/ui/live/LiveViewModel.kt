package com.memoria.caregiver.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.livekit.LiveRoom
import com.memoria.caregiver.data.livekit.LiveKitConnectionState
import com.memoria.caregiver.data.livekit.LiveKitIdentity
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.UpdateCameraRequest
import com.memoria.caregiver.data.model.UpdatePatientRequest
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Produces [LiveVideoState] for [LiveVideoScreen], and runs everything
 * `PatientLiveView` runs: the room connect, the carousel selection, the desync
 * reconciler's 1s clock, the call machine, the bulk monitoring levers and clip
 * capture.
 *
 * ## Why this class exists at all
 *
 * Every part below it was already written, tested and unreachable: 18 files in
 * `ui/live/` and 8 in `data/livekit/` with nothing joining them, and
 * `MainActivity` handing `PatientShell` a `TabStub("Live")` (FINDINGS.md F-40).
 * This is the producer. It owns no rendering and no HTTP — it wires
 * [NemaraApiClient] to [LiveRoom] and hands the result to a screen that
 * already knew what to do with it.
 *
 * ## What is deliberately NOT here
 *
 * **Room teardown.** iOS splits cleanup in two: `PatientLiveView.onDisappear`
 * ends a call but leaves the room up, and only `PatientShell.onDisappear` calls
 * `liveKit.disconnect()`. That split is the whole reason Live → Events → Live
 * feels instant instead of re-joining the room on every sub-tab switch. So
 * [onLeaveLiveTab] ends the call, and disconnecting is the patient shell's job
 * via [onClosePatient] — not `onCleared`, which fires on both paths and would
 * collapse the distinction.
 *
 * @param clipCaptureEnabled `AppConfig.enableClipCapture`. Hard-false in
 *   release; when false the record button is never rendered, so [captureClip]
 *   is unreachable rather than merely inert.
 * @param now injectable wall clock. The `ON AIR` timer, the desync reconciler's
 *   2s gate and its per-identity cooldown all read it, and none of them are
 *   testable against a real one.
 */
class LiveViewModel(
    private val patientId: String,
    private val api: NemaraApiClient,
    private val liveKit: LiveRoom,
    /**
     * The signed-in caregiver's Supabase user id, as a function rather than a
     * value: the token mint needs whoever is signed in *at mint time*, and a
     * captured id would outlive a sign-out.
     */
    private val caregiverId: () -> String?,
    private val ackStore: SensitiveRoomAckStore,
    peopleBlur: PeopleBlurStore,
    private val clipCaptureEnabled: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _selectedCameraId = MutableStateFlow<String?>(null)
    private val _lkError = MutableStateFlow<String?>(null)
    private val _acknowledged = MutableStateFlow(ackStore.load())
    private val _isTogglingMonitoring = MutableStateFlow(false)
    private val _capturingCameraId = MutableStateFlow<String?>(null)
    private val _captureNote = MutableStateFlow<LiveCaptureNote?>(null)
    private val _nowMillis = MutableStateFlow(now())

    /**
     * True while [reconnectAll] is re-joining. Separate from
     * [_isTogglingMonitoring] because the reconciler stands down for both but
     * the UI only disables the buttons for the latter — same split as iOS's
     * `isReconnectingAll` vs `isTogglingMonitoring`.
     */
    private val _isReconnectingAll = MutableStateFlow(false)

    private val reconciler = DesyncReconciler()

    private val talk = TalkController(
        patientId = patientId,
        api = object : TalkApi {
            override suspend fun startTalk(patientId: String, cameraId: String) =
                api.liveKit.startTalk(patientId, cameraId)

            override suspend fun endTalk(patientId: String) =
                api.liveKit.endTalk(patientId)
        },
        // An adapter rather than `LiveKitClient : TalkMedia`, so the dependency
        // keeps pointing the right way: `data/livekit` must not import `ui/live`.
        media = object : TalkMedia {
            override suspend fun publishMicrophone(enable: Boolean) =
                liveKit.publishMicrophone(enable)

            override fun setRemoteAudioPlayback(identity: String?) =
                liveKit.setRemoteAudioPlayback(identity)
        },
        errors = TalkErrorSink { message -> _lkError.value = message },
        now = now,
    )

    /**
     * Signals that come from the room. Split into three groups only because
     * `combine` is typed up to five arguments and this state has fifteen inputs;
     * the grouping carries no meaning beyond that.
     */
    private val roomSignals = combine(
        liveKit.state,
        liveKit.videoTracksByIdentity,
        liveKit.audioEnabledByIdentity,
        liveKit.audioLevels,
    ) { connection, tracks, audioEnabled, levels ->
        RoomSignals(connection, tracks, audioEnabled, levels)
    }

    private val localSignals = combine(
        _selectedCameraId,
        _lkError,
        _acknowledged,
        _isTogglingMonitoring,
        combine(_capturingCameraId, _captureNote, _nowMillis) { capturing, note, millis ->
            Triple(capturing, note, millis)
        },
    ) { selected, error, acks, toggling, capture ->
        LocalSignals(selected, error, acks, toggling, capture.first, capture.second, capture.third)
    }

    private val dataSignals = combine(
        api.camerasByPatientFlow.map { it[patientId].orEmpty() }.distinctUntilChanged(),
        api.patientsFlow
            .map { patients -> patients.firstOrNull { it.id == patientId }?.camerasPaused ?: false }
            .distinctUntilChanged(),
        peopleBlur.enabledIds.map { patientId in it }.distinctUntilChanged(),
        talk.state,
    ) { cameras, paused, blurOn, talkState ->
        DataSignals(cameras, paused, blurOn, talkState)
    }

    val state: StateFlow<LiveVideoState> = combine(
        roomSignals,
        localSignals,
        dataSignals,
    ) { room, local, data ->
        LiveVideoState(
            cameras = data.cameras,
            camerasPaused = data.camerasPaused,
            // The renderer binds a track to the room that owns it. Handed over
            // only while connected: a track from a torn-down room renders black
            // and never recovers.
            room = if (room.connection == LiveKitConnectionState.Connected) liveKit.room else null,
            videoTracksByIdentity = room.videoTracks,
            connectionState = room.connection,
            selectedCameraId = local.selectedCameraId,
            talk = data.talk,
            nowMillis = local.nowMillis,
            lkError = local.lkError,
            peopleBlurEnabled = data.peopleBlurEnabled,
            audioEnabledIdentities = room.audioEnabledIdentities,
            audioLevels = room.audioLevels,
            acknowledgedSensitiveRoomIds = local.acknowledged,
            isTogglingMonitoring = local.isTogglingMonitoring,
            capturingCameraId = local.capturingCameraId,
            captureNote = local.captureNote,
            clipCaptureEnabled = clipCaptureEnabled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LiveVideoState(clipCaptureEnabled = clipCaptureEnabled),
    )

    init {
        bootstrap()
        startClock()
        watchPrivacy()
    }

    // ---- Connect ----

    /**
     * Port of `PatientLiveView.bootstrap(force:)`.
     *
     * Connects regardless of `camerasPaused`. The room is a subscription: with
     * every camera disconnected we simply receive no tracks and show the empty
     * card. Gating the connect on the pause flag meant a camera that came back
     * online while paused had nowhere to surface, because the app was not in the
     * room to hear about it.
     *
     * @param force bypass the already-connected short circuit. [reconnectAll]
     *   passes true: it needs a clean re-join so every track the wall phones
     *   re-publish is re-subscribed, rather than trusting a session whose
     *   subscriptions the SDK may have dropped while the publishers left.
     */
    fun bootstrap(force: Boolean = false) {
        viewModelScope.launch {
            val expectedRoom = LiveKitIdentity.roomName(patientId)
            // Sub-tab return path. `connect()` is deliberately NOT idempotent —
            // it pre-disconnects every time — so this gate has to live here
            // rather than inside the client. The state check doubles as the
            // token-expiry escape hatch: if the SDK dropped the room we fall
            // through to a fresh mint.
            if (!force &&
                liveKit.currentRoom.value == expectedRoom &&
                liveKit.state.value == LiveKitConnectionState.Connected
            ) {
                _lkError.value = null
                val online = state.value.onlineCameras
                if (_selectedCameraId.value == null ||
                    online.none { it.id == _selectedCameraId.value }
                ) {
                    resolveSelectedCamera()
                }
                return@launch
            }

            val caregiver = caregiverId()
            if (caregiver == null) {
                // Not an error the caregiver can act on, and not a crash. iOS
                // throws `.unauthorized` from inside its API client here; the
                // Android client takes the id as a parameter, so the check lands
                // here instead. See LiveKitApi.mintViewerToken.
                _lkError.value = "Not signed in."
                return@launch
            }
            try {
                // BROADCASTER grants up front, even for plain viewing. We never
                // publish the mic until the caregiver taps Call, but holding the
                // publish grant makes start/end of a call a mic toggle with no
                // room reconnect, so the video tiles never blink. The wall phone
                // still will not play our audio outside a call: it gates on the
                // backend's set_talk_active packet, not on our grant.
                val token = api.liveKit.mintBroadcasterToken(patientId, caregiver)
                liveKit.connect(url = token.url, token = token.token, roomName = expectedRoom)
                _lkError.value = null
                resolveSelectedCamera()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _lkError.value = error.message ?: error.toString()
            }
        }
    }

    /**
     * Server's best guess wins; otherwise the first online camera.
     * iOS: `resolveSelectedCamera()`.
     */
    private suspend fun resolveSelectedCamera() {
        val suggested = runCatching { api.liveKit.getActiveCamera(patientId) }.getOrNull()
        val online = state.value.onlineCameras
        _selectedCameraId.value = when {
            suggested != null && online.any { it.id == suggested } -> suggested
            else -> online.firstOrNull()?.id
        }
    }

    // ---- The 1s clock ----

    /**
     * Drives the `ON AIR` timer and the desync reconciler, exactly as iOS's
     * `TimelineView`-driven 1s tick does.
     *
     * Runs for as long as this ViewModel lives, which is the life of the patient
     * shell — **not** the life of the Live tab. That is on purpose: the
     * reconciler is what force-resubscribes a camera the SDK missed, and a
     * caregiver who tabs to Events for a minute should come back to live tiles,
     * not to a placeholder waiting for the reconciler to start.
     */
    private fun startClock() {
        viewModelScope.launch {
            while (isActive) {
                val tick = now()
                _nowMillis.value = tick
                val snapshot = state.value
                val toResubscribe = reconciler.reconcile(
                    now = tick,
                    cameras = snapshot.cameras,
                    trackIdentities = snapshot.videoTracksByIdentity.keys,
                    roomConnected = snapshot.connectionState == LiveKitConnectionState.Connected,
                    isTogglingMonitoring = _isTogglingMonitoring.value,
                    isReconnectingAll = _isReconnectingAll.value,
                )
                toResubscribe.forEach { identity ->
                    // `reconnectIfAbsent = false`: this path cannot tell a
                    // fixable subscribe-miss from a stale `online` flag, and for
                    // the stale case a rejoin never produces a track — so it
                    // would nuke the room every cooldown, forever.
                    liveKit.ensureSubscribed(identity, reconnectIfAbsent = false)
                }
                delay(TICK_MILLIS)
            }
        }
    }

    // ---- Privacy watchers ----

    /**
     * The two `onChange` watchers `PatientLiveView` runs on privacy.
     *
     * Both matter more than they look. A camera flipped to private must go
     * silent on this device immediately, and a call must not survive its target
     * becoming private — including a flip that lands mid-start, which is why
     * `CONNECTING` counts as well as `ACTIVE`.
     */
    private fun watchPrivacy() {
        viewModelScope.launch {
            state
                .map { it.privateCameraIdentities }
                .distinctUntilChanged()
                .collect { identities ->
                    // revokeAudio, not "set the volume to zero": the identity has
                    // to leave the enabled set, or the next resubscribe re-arms
                    // playback in a private room (PARITY.md §11, §29 item 5).
                    identities.forEach { liveKit.revokeAudio(it) }
                }
        }
        viewModelScope.launch {
            // The controller wants the EDGE, not the level: only false -> true
            // tears a call down, and re-sending `true` every recomposition would
            // repeat the teardown. `zipWithPrevious` in one line, seeded false so
            // a room that is already private on entry does not read as a flip.
            var wasPrivate = false
            state
                .map { it.callTargetPrivacyOn }
                .distinctUntilChanged()
                .collect { isPrivate ->
                    talk.onCallTargetPrivacyChanged(wasPrivate = wasPrivate, isPrivate = isPrivate)
                    wasPrivate = isPrivate
                }
        }
    }

    // ---- Screen callbacks ----

    fun onSelectCamera(cameraId: String?) {
        if (_selectedCameraId.value == cameraId) return
        _selectedCameraId.value = cameraId
        viewModelScope.launch {
            talk.onSelectedCameraChanged(
                selectedCameraId = cameraId,
                target = state.value.cameras.firstOrNull { it.id == cameraId },
            )
        }
    }

    fun toggleAudio(identity: String) {
        liveKit.toggleAudio(identity)
    }

    fun acknowledgeSensitiveRoom(cameraId: String) {
        val updated = _acknowledged.value + cameraId
        _acknowledged.value = updated
        ackStore.save(updated)
    }

    fun onCallButtonTap() {
        viewModelScope.launch {
            val target = state.value.cameras.firstOrNull { it.id == _selectedCameraId.value }
            when (talkButtonAction(state.value.talk.callState)) {
                TalkButtonAction.START -> talk.startCall(target)
                TalkButtonAction.END -> talk.endCall()
                TalkButtonAction.NONE -> Unit
            }
        }
    }

    /**
     * Port of `PatientLiveView.tabAwayCleanup()`. Ends a call and leaves the room
     * up — see the class comment for why those are separate.
     *
     * `selectedCameraId` is deliberately preserved, so returning to Live puts the
     * caregiver back on the tile they were watching.
     */
    fun onLeaveLiveTab() {
        viewModelScope.launch { talk.endCall() }
    }

    /**
     * The real exit: the patient shell is closing. This is the only place the
     * room comes down.
     */
    fun onClosePatient() {
        liveKit.disconnect()
    }

    // ---- Clip capture ----

    fun captureClip(cameraId: String) {
        if (!clipCaptureEnabled) return
        if (_capturingCameraId.value != null) return
        _capturingCameraId.value = cameraId
        viewModelScope.launch {
            _captureNote.value = try {
                api.liveKit.recordClip(patientId, cameraId)
                LiveCaptureNote(cameraId = cameraId, text = CAPTURE_STARTED_NOTE)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                LiveCaptureNote(cameraId = cameraId, text = "Capture failed: ${error.message}")
            }
            _capturingCameraId.value = null
        }
    }

    // ---- Bulk monitoring ----

    /**
     * Port of `PatientLiveView.disconnectAll()`.
     *
     * The optimistic flip and the list to PATCH come from one call to
     * [NemaraApiClient.flipDesiredStateLocally], which returns the rows as they
     * were before the flip. iOS needs two statements in a fixed order here and
     * shipped them the wrong way round once; see that method for the detail.
     */
    fun disconnectAll() {
        if (_isTogglingMonitoring.value) return
        val toDisconnect = api.flipDesiredStateLocally(
            patientId = patientId,
            from = Camera.DesiredState.CONNECTED,
            to = Camera.DesiredState.DISCONNECTED,
        )
        api.setCamerasPausedLocally(patientId, paused = true)
        _isTogglingMonitoring.value = true
        viewModelScope.launch {
            try {
                runCatching {
                    api.updatePatient(patientId, UpdatePatientRequest(camerasPaused = true))
                }
                toDisconnect.forEach { camera ->
                    runCatching {
                        api.updateCamera(
                            camera.id,
                            UpdateCameraRequest(desiredState = Camera.DesiredState.DISCONNECTED),
                        )
                    }
                }
                runCatching { api.loadCameras(patientId) }
                // iOS's 600ms tail. It keeps the button disabled just past the
                // reload so the count settles before it can be tapped again.
                delay(SETTLE_MILLIS)
            } finally {
                _isTogglingMonitoring.value = false
            }
        }
    }

    /**
     * Port of `PatientLiveView.reconnectAll()`. Best-effort "bring everything
     * back", whether or not monitoring was paused: unpause at the patient level,
     * re-assert connect on any sensor still disconnected (so one that dropped on
     * its own also recovers), then force a clean re-join.
     *
     * Sensors whose app is closed cannot be revived from here. They stay offline
     * until it is reopened, which is what the offline card says.
     */
    fun reconnectAll() {
        if (_isTogglingMonitoring.value) return
        val wasPaused = state.value.camerasPaused
        if (wasPaused) api.setCamerasPausedLocally(patientId, paused = false)
        val toConnect = api.flipDesiredStateLocally(
            patientId = patientId,
            from = Camera.DesiredState.DISCONNECTED,
            to = Camera.DesiredState.CONNECTED,
        )
        _isTogglingMonitoring.value = true
        _isReconnectingAll.value = true
        viewModelScope.launch {
            try {
                if (wasPaused) {
                    runCatching {
                        api.updatePatient(patientId, UpdatePatientRequest(camerasPaused = false))
                    }
                }
                toConnect.forEach { camera ->
                    runCatching {
                        api.updateCamera(
                            camera.id,
                            UpdateCameraRequest(desiredState = Camera.DesiredState.CONNECTED),
                        )
                    }
                }
                bootstrap(force = true)
                runCatching { api.loadCameras(patientId) }
                delay(SETTLE_MILLIS)
            } finally {
                _isTogglingMonitoring.value = false
                _isReconnectingAll.value = false
            }
        }
    }

    // ---- Grouping types for `combine` ----

    private data class RoomSignals(
        val connection: LiveKitConnectionState,
        val videoTracks: Map<String, io.livekit.android.room.track.VideoTrack>,
        val audioEnabledIdentities: Set<String>,
        val audioLevels: Map<String, List<Float>>,
    )

    private data class LocalSignals(
        val selectedCameraId: String?,
        val lkError: String?,
        val acknowledged: Set<String>,
        val isTogglingMonitoring: Boolean,
        val capturingCameraId: String?,
        val captureNote: LiveCaptureNote?,
        val nowMillis: Long,
    )

    private data class DataSignals(
        val cameras: List<Camera>,
        val camerasPaused: Boolean,
        val peopleBlurEnabled: Boolean,
        val talk: TalkUiState,
    )

    private companion object {
        const val TICK_MILLIS = 1_000L

        /** iOS's `Task.sleep(600_000_000)` after each bulk lever. */
        const val SETTLE_MILLIS = 600L

        /**
         * Long enough that a configuration change does not tear the room state
         * down and rebuild it, matching every other screen in this app.
         */
        const val STOP_TIMEOUT_MILLIS = 5_000L

        const val CAPTURE_STARTED_NOTE = "Saving clip…"
    }
}
