package com.memoria.caregiver.ui.live

import android.util.Log
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.remote.ApiError
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Talk mode — the "phone call" half of the Live tab.
 *
 * Spec: `Features/Patient/Live/PatientLiveView.swift` (the call-control,
 * mic-publish and waveform sections), `Services/LiveKitClient.swift`
 * (`publishMicrophone`, `setRemoteAudioPlayback`), `PARITY.md` §11 and §18.
 *
 * The caregiver taps a call button on the currently-displayed camera tile and
 * gets full-duplex audio with **that** camera. Swiping the carousel during an
 * active call **transfers** the call rather than hanging up.
 *
 * ## What lives here, and what deliberately does not
 *
 * This file owns the call **lifecycle**: mic publish, `/talk/start` and
 * `/talk/end`, the broadcaster-role token shape, the loudspeaker override, and
 * the rules for when the waveform and the call button are on screen. It owns
 * none of the carousel, the video tiles, the desync reconciler, the
 * reconnect-all loop, or the per-tile listen toggle — those are the live-video
 * unit's, and iOS keeps them in the same view only because SwiftUI has nowhere
 * else to put them.
 *
 * ## The room stays connected; a call is only a mic toggle
 *
 * iOS connects to the LiveKit room with **broadcaster grants up front**
 * (`canPublish = true`) even for plain viewing, so starting and ending a call is
 * a mic toggle with no room reconnect and the video tiles never blink. The wall
 * phone still refuses to play caregiver audio outside a call, because it gates
 * on the backend's `set_talk_active` packet and not on our publish grant. That
 * is why [TalkController] never connects, disconnects, or re-mints anything: the
 * connection is already there and holding broadcaster grants before the call
 * button is ever tapped. See [LiveKitTokens].
 *
 * ## Threading
 *
 * **Single-threaded by contract**, like the rest of the ported state: iOS marks
 * the view `@MainActor`, so every guard, every sequence number bump and every
 * state write is serialized. [TalkController] reproduces that by being called
 * from one dispatcher (the main one). There are no locks here and none are
 * needed as long as that holds — `transferSeq` in particular is read across a
 * suspension point and is only correct under that assumption, which is exactly
 * the assumption Swift's actor gave it.
 */

// ---------------------------------------------------------------------------
// Kill switch
// ---------------------------------------------------------------------------

/**
 * Master switch for the two-way talk feature. `PatientLiveView.talkEnabled`.
 *
 * Kept as a kill switch after the iOS Phase-3 hardening (speakerphone routing on
 * mic publish, plus removal of the per-call room reconnect). Setting it to
 * `false` hides the on-tile call button without removing a line of call code —
 * see [shouldShowCallButton], which is the only thing that reads it.
 */
const val TALK_ENABLED: Boolean = true

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * Lifecycle of the call. iOS: `CallButton.State`.
 *
 * [CONNECTING] covers token-free mic publish plus the first `/talk/start`;
 * [ACTIVE] is the live state where carousel swipes trigger transfers.
 *
 * Named `TalkCallState` rather than nesting it inside a `CallButton` composable
 * because the state is produced here and consumed there, not the other way
 * round; the button is a pure renderer of it.
 */
enum class TalkCallState {
    IDLE,
    CONNECTING,
    ACTIVE,
}

/**
 * Everything the talk half holds. iOS keeps these as two `@State` properties on
 * `PatientLiveView` (`callState`, `callStartedAt`).
 *
 * **The error string is deliberately not here.** iOS has ONE `lkError` shared by
 * the connect path and the call path, last writer wins, rendered in a single
 * bottom overlay on the call-target tile. Duplicating it would give the screen
 * two error fields to reconcile and let a stale call error outlive a fresh
 * connect error. The controller reports through [TalkErrorSink] into the
 * screen's single field instead.
 *
 * @param callStartedAtMillis wall-clock ms at which the call went [ACTIVE], or
 *   null. Feeds the `ON AIR · mm:ss` label via [talkStatusLine]. Wall clock, not
 *   a monotonic source, because iOS uses `Date()` and the label is a duration a
 *   human reads, not a measurement.
 */
data class TalkUiState(
    val callState: TalkCallState = TalkCallState.IDLE,
    val callStartedAtMillis: Long? = null,
)

// ---------------------------------------------------------------------------
// Ports — what this unit needs from the rest of the app
// ---------------------------------------------------------------------------

/**
 * The backend half of a call: `POST /v1/patients/{id}/talk/start` and
 * `/talk/end`.
 *
 * Declared here, in the shape this unit uses, so the call machine can be built
 * and tested before `NemaraApiClient` grows the two endpoints. The client
 * implements it; nothing in this file knows about HTTP.
 *
 * `startTalk` is what makes a transfer atomic: the backend broadcasts
 * `is_active = true` to [cameraId] and `is_active = false` to every sibling
 * camera, so re-posting with a different camera moves the call without an
 * intervening `/talk/end`.
 */
interface TalkApi {
    suspend fun startTalk(patientId: String, cameraId: String)

    /** Sends `is_active = false` to every camera in the patient's room. Idempotent. */
    suspend fun endTalk(patientId: String)
}

/**
 * The LiveKit half of a call, as narrow as it can be: the two calls the talk
 * lifecycle makes into the room client.
 *
 * Both are implemented by the live-video unit's LiveKit client.
 * `setRemoteAudioPlayback` is **volume gating, not subscription gating** — see
 * PARITY.md §11; do not "fix" it into an (un)subscribe, because churning the
 * subscription leaves the SDK's audio engine in a state the level renderer never
 * recovers from and the waveform stays flat forever.
 */
interface TalkMedia {
    /**
     * Toggle the caregiver's outgoing mic on the existing connection. Throws if
     * the room is not connected.
     */
    suspend fun publishMicrophone(enable: Boolean)

    /**
     * Make exactly one camera identity audible (and its levels flow to the
     * waveform), or none when [identity] is null. The caregiver's per-tile mute
     * choices are preserved: talk *overlays* an unmute, it does not replace the
     * listen set.
     */
    fun setRemoteAudioPlayback(identity: String?)
}

/**
 * The screen's single `lkError` field. Null clears it.
 *
 * A port rather than a field on [TalkUiState] so the connect path and the call
 * path keep writing to the one string iOS has. See [TalkUiState].
 */
fun interface TalkErrorSink {
    fun setTalkError(message: String?)
}

/**
 * Where the `NSLog` lines in the Swift go. Every failure this unit swallows on
 * purpose is reported through it, so "the mic would not unpublish" is a log line
 * rather than silence.
 *
 * Injected rather than calling `Log` directly so the controller stays plain JVM
 * code — `android.util.Log` throws "not mocked" in a unit test.
 *
 * Never pass a token, an email, a password or anything from a video frame
 * through this.
 */
fun interface TalkLog {
    fun warn(message: String)
}

/** Real logging. Unconditional, matching `NSLog` (which is not `#if DEBUG`-gated). */
object AndroidTalkLog : TalkLog {
    private const val TAG = "NemaraTalk"

    override fun warn(message: String) {
        Log.w(TAG, message)
    }
}

// ---------------------------------------------------------------------------
// Audio routing — the loudspeaker guarantee
// ---------------------------------------------------------------------------

/**
 * Forces call audio out of the **loudspeaker** when the mic goes live.
 *
 * This exists because of a specific failure. On iOS, publishing the mic flips
 * the SDK's `AVAudioSession` to `.playAndRecord` / `.voiceChat`, whose default
 * output is the **earpiece** — so without an override the caregiver has to hold
 * the phone to their ear to hear a room they are trying to listen to across the
 * house. iOS calls `overrideOutputAudioPort(.speaker)` immediately after
 * enabling the mic. Android reaches the same routing decision by a different
 * mechanism, and if it is left implicit the symptom on Android is "Talk lost
 * audio", which reads as a LiveKit or a network problem and is a
 * wrong-diagnosis magnet.
 *
 * **There is no restore.** iOS does not restore either: when the mic is
 * disabled the SDK reverts the session to `.playback`, which routes to the
 * loudspeaker anyway. Restoring an earpiece route after a call would send the
 * audio of a passively-unmuted tile to the earpiece, which is the bug this type
 * exists to prevent, one step later.
 */
interface TalkAudioRoute {
    fun forceLoudspeaker()

    /** For tests and for a client built without an [AudioSwitchHandler]. */
    object None : TalkAudioRoute {
        override fun forceLoudspeaker() = Unit
    }
}

/**
 * [TalkAudioRoute] over the LiveKit SDK's own routing handler.
 *
 * `livekit-android` installs an [AudioSwitchHandler] by default and starts it
 * when the room reaches `CONNECTED`, in `MODE_IN_COMMUNICATION`. Its default
 * preference order is bluetooth, wired, **speakerphone, earpiece** — so the
 * loudspeaker already wins today. This class is the explicit guarantee, not a
 * fix: `preferredDeviceList` is a mutable property, a future change to it (or a
 * client constructed with a different handler) would silently reintroduce the
 * exact iOS failure, and nothing else in the app would notice.
 *
 * Pass `room.audioHandler`. A handler that is not an [AudioSwitchHandler] makes
 * every call a no-op rather than a crash, because routing is a comfort
 * behaviour and must never take the call down with it.
 *
 * A wired or bluetooth headset is left alone: it is not the earpiece, so it is
 * not the failure being guarded against, and a caregiver who put a headset on
 * did so on purpose.
 */
class AudioSwitchTalkAudioRoute(private val audioHandler: AudioHandler?) : TalkAudioRoute {

    override fun forceLoudspeaker() {
        val handler = audioHandler as? AudioSwitchHandler ?: return
        val current = handler.selectedAudioDevice
        val alreadyAudibleAcrossTheRoom = current is AudioDevice.Speakerphone ||
            current is AudioDevice.WiredHeadset ||
            current is AudioDevice.BluetoothHeadset
        if (alreadyAudibleAcrossTheRoom) return
        val speaker = handler.availableAudioDevices
            .firstOrNull { it is AudioDevice.Speakerphone }
            ?: return
        handler.selectDevice(speaker)
    }
}

// ---------------------------------------------------------------------------
// The broadcaster token
// ---------------------------------------------------------------------------

/**
 * Room name, participant identity and role for `POST /v1/livekit/token`.
 *
 * The Live tab mints the **broadcaster** role on connect, before any call, so
 * that starting one is a mic toggle instead of a reconnect (see the file
 * header). [VIEWER_ROLE] is carried because iOS carries both.
 */
object LiveKitTokens {

    /** Subscribe only. `mintViewerToken`. */
    const val VIEWER_ROLE: String = "viewer"

    /** Subscribe plus `canPublish = true`, so the mic can go live. `mintBroadcasterToken`. */
    const val BROADCASTER_ROLE: String = "broadcaster"

    /** `"patient_\(pid.uuidString.lowercased())"`. */
    fun roomName(patientId: String): String = "patient_${patientId.lowercase(Locale.ROOT)}"

    /**
     * `"caregiver_<uuid>_android"`.
     *
     * **The suffix is load-bearing** (PARITY.md §29, item 1). LiveKit treats
     * `identity` as a unique participant key per room and kicks the older
     * session when a second client connects with the same one. Web uses
     * `_web`, the iOS app uses `_ios`; if Android reused `_ios` it would
     * silently disconnect the caregiver's own iPhone from the stream every time
     * they opened the Android app, and the iPhone would look like it had a
     * network fault.
     *
     * The server validates the prefix and re-mints, so this value is
     * informational to the backend and load-bearing to LiveKit.
     */
    fun caregiverIdentity(caregiverId: String): String =
        "caregiver_${caregiverId.lowercase(Locale.ROOT)}_android"
}

// ---------------------------------------------------------------------------
// Product copy
// ---------------------------------------------------------------------------

/*
 * Verbatim from PatientLiveView. Pass `ApiError.errorDescription`, or the
 * platform's own sentence, as `description` — see [talkErrorDescription].
 */

/** `"Call failed: \(error.localizedDescription)"`. */
fun callFailedMessage(description: String): String = "Call failed: $description"

/** `"End call failed: \(error.localizedDescription)"`. */
fun endCallFailedMessage(description: String): String = "End call failed: $description"

/** `"Transfer failed: \(error.localizedDescription)"`. */
fun transferFailedMessage(description: String): String = "Transfer failed: $description"

/**
 * The Android stand-in for `error.localizedDescription`.
 *
 * [ApiError] already carries the product's sentence; anything else falls back to
 * the platform's message the way Swift does.
 */
fun talkErrorDescription(error: Throwable): String = when (error) {
    is ApiError -> error.errorDescription
    else -> error.message ?: error.toString()
}

// ---------------------------------------------------------------------------
// Pure UI rules
// ---------------------------------------------------------------------------

/**
 * How often the screen must re-render the `ON AIR` timer.
 *
 * iOS drives it from `Timer.publish(every: 1, …)`, the same one-second tick the
 * desync reconciler rides. One tick, two consumers.
 */
const val TALK_TIMER_TICK_MILLIS: Long = 1_000L

/** `.frame(width: 100, height: 22)` on the tile's waveform. */
val TALK_WAVEFORM_WIDTH: Dp = 100.dp

/** `.frame(width: 100, height: 22)` on the tile's waveform. */
val TALK_WAVEFORM_HEIGHT: Dp = 22.dp

/**
 * Whether this tile shows the call button.
 *
 * `PatientLiveView.cameraTile`: `talkEnabled && isThisCallTarget &&
 * !cam.privacyEnabled && cam.sourceType == .phone`.
 *
 * The last two clauses are not decoration:
 *  - A **privacy-enabled** camera is a private room. Talk is refused
 *    client-side in [TalkController.startCall] and again server-side, so a
 *    button there could never do anything.
 *  - A **gateway / RTSP** camera has no speaker. The caregiver's voice comes out
 *    of the wall phone, so calling an RTSP camera dials a room where nothing can
 *    answer.
 *
 * @param isThisCallTarget this camera is the carousel's current selection.
 */
fun shouldShowCallButton(
    camera: Camera,
    isThisCallTarget: Boolean,
    talkEnabled: Boolean = TALK_ENABLED,
): Boolean =
    talkEnabled &&
        isThisCallTarget &&
        !camera.privacyEnabled &&
        camera.sourceType == Camera.SourceType.PHONE

/**
 * Whether this tile shows the waveform.
 *
 * `bottomOverlay`: `callState == .active && cam.id == selectedCameraId`. A
 * waveform is only meaningful where audio is actually flowing, and outside a
 * call nothing pushes levels at all, so a second one would sit permanently flat
 * and read as a dead feed.
 */
fun shouldShowTalkWaveform(
    callState: TalkCallState,
    cameraId: String,
    selectedCameraId: String?,
): Boolean = callState == TalkCallState.ACTIVE && cameraId == selectedCameraId

/**
 * Levels for one camera's waveform. `env.liveKit.audioLevels[identity] ?? []`.
 *
 * An unpaired row (null identity) and a camera with no levels yet both give an
 * empty list, which [com.memoria.caregiver.ui.components.NemaraWaveform] renders
 * as the flat baseline rather than nothing.
 */
fun talkWaveformLevels(
    levelsByIdentity: Map<String, List<Float>>,
    identity: String?,
): List<Float> = identity?.let { levelsByIdentity[it] } ?: emptyList()

/**
 * The mono status line under the room name **while a call is up**, or null when
 * there is no call — in which case the caller renders its own `SENSOR nn`.
 *
 * `statusLine(for:)`: `"ON AIR"` with no start stamp, otherwise
 * `String(format: "ON AIR · %02d:%02d", elapsed / 60, elapsed % 60)`. The
 * separator is U+00B7 MIDDLE DOT.
 *
 * Elapsed seconds truncate toward zero, as `Int(TimeInterval)` does. Minutes are
 * not wrapped at 60: a 61-minute call reads `61:00`, which is what iOS prints
 * and is more honest than a silent rollover to `01:00`.
 *
 * Formatted with [Locale.ROOT] because Swift's `String(format:)` with no locale
 * uses the POSIX one, so neither platform substitutes non-ASCII digits.
 */
fun talkStatusLine(
    callState: TalkCallState,
    callStartedAtMillis: Long?,
    nowMillis: Long,
): String? {
    if (callState != TalkCallState.ACTIVE) return null
    if (callStartedAtMillis == null) return "ON AIR"
    val elapsed = ((nowMillis - callStartedAtMillis) / 1_000L).toInt()
    return String.format(Locale.ROOT, "ON AIR · %02d:%02d", elapsed / 60, elapsed % 60)
}

/** What a tap on the call button means in the current state. */
enum class TalkButtonAction {
    START,
    END,

    /** Mid-connect. iOS `case .connecting: break` — and the button is disabled. */
    NONE,
}

/** `handleCallButtonTap()`. */
fun talkButtonAction(callState: TalkCallState): TalkButtonAction = when (callState) {
    TalkCallState.IDLE -> TalkButtonAction.START
    TalkCallState.ACTIVE -> TalkButtonAction.END
    TalkCallState.CONNECTING -> TalkButtonAction.NONE
}

// ---------------------------------------------------------------------------
// The call machine
// ---------------------------------------------------------------------------

/**
 * Start, end and transfer a call. The whole talk lifecycle of `PatientLiveView`.
 *
 * Every method takes the **resolved** target camera rather than an id plus the
 * roster, which collapses two of the Swift's guards (`selectedCameraId` is set,
 * and a row with that id still exists) into one null check with identical
 * behaviour, and keeps the controller from needing to know how the roster is
 * stored.
 *
 * ### Cancellation
 *
 * iOS runs each of these in an **unstructured** `Task { }`, which SwiftUI does
 * not cancel when the view goes away — so a hang-up always finishes unpublishing
 * the mic and posting `/talk/end`. Kotlin's structured scopes do cancel, so the
 * teardown paths run under [NonCancellable]. Same observable behaviour, and it
 * is the difference between "the caregiver tapped end" and "the wall phone is
 * still playing their kitchen".
 *
 * @param now wall-clock source, injectable so the `ON AIR` timer is testable.
 */
class TalkController(
    private val patientId: String,
    private val api: TalkApi,
    private val media: TalkMedia,
    private val audioRoute: TalkAudioRoute = TalkAudioRoute.None,
    private val errors: TalkErrorSink = TalkErrorSink { },
    private val log: TalkLog = TalkLog { },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val describeError: (Throwable) -> String = ::talkErrorDescription,
) {

    private val _state = MutableStateFlow(TalkUiState())
    val state: StateFlow<TalkUiState> = _state.asStateFlow()

    /**
     * Drops stale transfer responses. Rapid swipes spawn overlapping requests
     * and they can land out of order; only the newest is allowed to touch the
     * error line.
     *
     * Wraps on overflow, like the Swift's `&+=`.
     */
    private var transferSeq: Int = 0

    /**
     * Open a call to [target].
     *
     * The room is already connected with broadcaster grants, so this is a mic
     * toggle: no reconnect, and the video tiles stay up throughout.
     *
     * The privacy check is a **re-check** — the button is hidden for a private
     * camera, but a privacy flip can land between the render and the tap, and
     * talk must never start against a private room.
     */
    suspend fun startCall(target: Camera?) {
        if (target == null) return
        if (_state.value.callState != TalkCallState.IDLE) return
        if (target.privacyEnabled) return

        val cameraIdentity = target.livekitIdentity
        _state.value = TalkUiState(
            callState = TalkCallState.CONNECTING,
            callStartedAtMillis = null,
        )
        try {
            media.publishMicrophone(true)
            // Immediately after the mic goes live, exactly where iOS forces
            // `.speaker`. See TalkAudioRoute.
            audioRoute.forceLoudspeaker()
            // Unmute the target's remote audio so the caregiver hears the wall
            // phone and the level renderer starts firing for the waveform.
            // Everything else stays muted.
            media.setRemoteAudioPlayback(cameraIdentity)
            api.startTalk(patientId, target.id)
            _state.value = TalkUiState(
                callState = TalkCallState.ACTIVE,
                callStartedAtMillis = now(),
            )
            errors.setTalkError(null)
        } catch (t: Throwable) {
            // Roll back any partial publish so a failed start never leaves the
            // mic hot. Non-cancellable: a cancelled start that left the mic
            // published is the worst outcome available.
            withContext(NonCancellable) {
                try {
                    media.publishMicrophone(false)
                } catch (inner: Throwable) {
                    log.warn("[startCall] rollback publishMicrophone(false) failed: $inner")
                }
                media.setRemoteAudioPlayback(null)
                _state.value = TalkUiState(TalkCallState.IDLE, null)
            }
            if (t is CancellationException) throw t
            errors.setTalkError(callFailedMessage(describeError(t)))
        }
    }

    /**
     * Hang up.
     *
     * [TalkCallState.CONNECTING] is a valid teardown state too — a privacy flip
     * landing mid-start must not leave the in-flight call to complete against a
     * private room. Only a genuinely idle state bails.
     *
     * No reconnect afterwards: we stay in the room as broadcaster so the video
     * never blinks. The wall phone stops playing our audio the instant
     * `/talk/end` broadcasts `set_talk_active = false`, and that packet is the
     * real playback gate, so even a lingering local mic track would be inaudible
     * in the room.
     */
    suspend fun endCall() {
        if (_state.value.callState == TalkCallState.IDLE) return

        // Stamp the UI first so the button reverts the moment the caregiver
        // taps end, however slow the network is.
        _state.value = TalkUiState(TalkCallState.IDLE, null)

        withContext(NonCancellable) {
            // Mute all remote audio FIRST: the caregiver stops hearing the wall
            // phone instantly and the waveform goes flat instantly. Doing it
            // before the mic teardown also stops the level renderer firing while
            // the rest of the disconnect resolves.
            media.setRemoteAudioPlayback(null)

            // Belt: ask the SDK to drop the local mic track.
            // Nothing inside this block rethrows a CancellationException: it runs
            // under NonCancellable, so one could only come from the port itself,
            // and abandoning a hang-up half-done is the outcome this block exists
            // to prevent.
            try {
                media.publishMicrophone(false)
            } catch (t: Throwable) {
                log.warn("[endCall] publishMicrophone(false) failed: $t")
            }

            // Braces: tell the backend to broadcast set_talk_active=false, so
            // the wall phone gates playback even if our teardown did not fully
            // take on this side.
            try {
                api.endTalk(patientId)
            } catch (t: Throwable) {
                errors.setTalkError(endCallFailedMessage(describeError(t)))
            }
        }
    }

    /**
     * Move an active call to [target].
     *
     * No `/talk/end` in between: `/talk/start` with a new camera sets
     * `is_active = true` there and `false` on every sibling, including the old
     * target, in one backend operation.
     *
     * Same last-moment privacy re-check as [startCall] — [onSelectedCameraChanged]
     * has already screened the target, but it can flip private between the swipe
     * and this call running.
     */
    suspend fun transferCall(target: Camera?) {
        if (target == null || target.privacyEnabled) return

        transferSeq++
        val seq = transferSeq
        val cameraIdentity = target.livekitIdentity
        try {
            api.startTalk(patientId, target.id)
            // Shift the audio gate to the newly-targeted camera so the caregiver
            // hears the right room and the waveform follows.
            media.setRemoteAudioPlayback(cameraIdentity)
            // An older transfer may finish after a newer one; only the latest
            // gets to clear the error indicator.
            if (seq == transferSeq) errors.setTalkError(null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (seq == transferSeq) {
                errors.setTalkError(transferFailedMessage(describeError(t)))
            }
        }
    }

    /**
     * The carousel moved. Transfers the call, or hangs up if the new tile cannot
     * take one.
     *
     * A gateway camera (no speaker) and a privacy-enabled camera both show no
     * call button, so transferring there would strand an active call with no way
     * to end it. Swiping to either hangs up instead.
     *
     * Does nothing unless a call is actually up, and nothing when the selection
     * clears to null (`guard let newValue`) — a carousel that empties out is not
     * a hang-up instruction.
     *
     * @param selectedCameraId the new carousel selection, null if it cleared.
     * @param target the camera row for [selectedCameraId], null if it is gone.
     */
    suspend fun onSelectedCameraChanged(selectedCameraId: String?, target: Camera?) {
        if (_state.value.callState != TalkCallState.ACTIVE) return
        if (selectedCameraId == null) return
        if (target == null ||
            target.sourceType != Camera.SourceType.PHONE ||
            target.privacyEnabled
        ) {
            endCall()
            return
        }
        transferCall(target)
    }

    /**
     * Per-camera privacy flipped on the camera the call is routed to.
     *
     * Only the false -> true edge matters, and only while a call exists. The
     * backend severs media for that camera at the SFU, but the local call state
     * and the backend's `is_active` flag would otherwise dangle and the wall
     * phone would keep showing "on air".
     *
     * There is no matching recovery path: privacy is per-camera and locked
     * server-side, and no other camera's feed was touched.
     */
    suspend fun onCallTargetPrivacyChanged(wasPrivate: Boolean, isPrivate: Boolean) {
        if (wasPrivate || !isPrivate) return
        val callState = _state.value.callState
        if (callState == TalkCallState.ACTIVE || callState == TalkCallState.CONNECTING) {
            endCall()
        }
    }

    /**
     * The Live tab went away — either an inner tab switch (Live -> Events) or the
     * patient closing entirely.
     *
     * Leaving the Live screen during a call is still "hang up" in the phone
     * metaphor. The LiveKit room is deliberately **not** torn down here so that
     * tabbing back resumes video instantly; the room teardown belongs to the
     * patient shell, which only disappears on the real exit.
     *
     * Best-effort and silent, like the Swift's `try?`: this runs while the screen
     * is being dismissed and has nowhere to show an error. It reports through
     * [TalkLog] so a mic that would not unpublish is still visible somewhere.
     */
    suspend fun tabAwayCleanup() {
        withContext(NonCancellable) {
            if (_state.value.callState == TalkCallState.ACTIVE) {
                try {
                    media.publishMicrophone(false)
                } catch (t: Throwable) {
                    log.warn("[tabAwayCleanup] publishMicrophone(false) failed: $t")
                }
                try {
                    api.endTalk(patientId)
                } catch (t: Throwable) {
                    log.warn("[tabAwayCleanup] endTalk failed: $t")
                }
            }
            _state.value = TalkUiState(TalkCallState.IDLE, null)
            // The carousel selection is deliberately preserved by the caller —
            // returning to Live should land on the same tile.
        }
    }

    /** A tap on the call button, resolved against the current state. */
    suspend fun onCallButtonTap(target: Camera?) {
        when (talkButtonAction(_state.value.callState)) {
            TalkButtonAction.START -> startCall(target)
            TalkButtonAction.END -> endCall()
            TalkButtonAction.NONE -> Unit
        }
    }
}
