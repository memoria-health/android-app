package com.memoria.caregiver.ui.live

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.data.livekit.CameraConnectivity
import com.memoria.caregiver.data.livekit.LiveKitConnectionState
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Warning
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import java.util.Locale

/**
 * State and pure logic for the Live sub-tab. Spec:
 * `Features/Patient/Live/PatientLiveView.swift`, PARITY.md §18 and §12.
 *
 * Same contract as `ui/auth/AuthUiState.kt` and `ui/cameras/CameraUiState.kt`:
 * the screen is a pure `@Composable` over these types, holds no ViewModel, does
 * no I/O, and never reaches for the API client or mints a token. Everything iOS
 * does inside `PatientLiveView` that touches the network or the SDK — the
 * `bootstrap` connect, `/talk/start` and `/talk/end`, the transfer, the
 * reconnect-all loop, `revokeAudio`, `ensureSubscribed`, the clip-capture
 * trigger — is the host's job and reaches this screen as state or as a callback.
 *
 * Everything that is a *decision* rather than a layout lives here as a pure
 * function: which cameras belong in the carousel, which pill a tile earns, which
 * of the three offline sentences applies. Those are the parts a refactor can
 * quietly break and the parts a JVM test can pin without a composition.
 *
 * **The call half lives in `TalkMode.kt`, not here.** `TALK_ENABLED`,
 * `TalkCallState`, `TalkUiState`, `shouldShowCallButton`, `shouldShowTalkWaveform`,
 * `talkStatusLine` and `TalkController` are that unit's, and this file uses them
 * rather than restating them — one screen must not carry two notions of whether a
 * call is up.
 */

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * The dev-only clip-capture toast. iOS keeps this as a
 * `(cameraId: UUID, text: String)` tuple in `@State`.
 */
data class LiveCaptureNote(
    val cameraId: String,
    val text: String,
)

/**
 * Everything the Live tab renders from. iOS holds most of this in `@State` on
 * `PatientLiveView` and the rest in `@Environment(AppEnvironment.self)`; this is
 * that same state, hoisted.
 *
 * @param cameras the patient's full camera list, already sorted by `displayId`
 *   by the API cache. The screen must not re-sort — doing so would hide a cache
 *   bug rather than show it.
 * @param camerasPaused the patient-level bulk lever. It **never** gates what the
 *   carousel shows; it only picks the offline card's copy and drives the bulk
 *   button. A camera that comes back online while "paused" still appears.
 * @param room the connected LiveKit room, needed only to hand each renderer an
 *   EGL context. Null renders placeholders. This is the host's single reusable
 *   room, matching iOS's one `LiveKitClient` instance.
 * @param videoTracksByIdentity subscribed remote video tracks keyed by publisher
 *   identity. iOS: `env.liveKit.videoTracksByIdentity`.
 * @param connectionState the client's own room state. Separate from [room] being
 *   non-null because a room object outlives a dropped connection, and it is the
 *   whole state rather than a boolean because `cameraConnectivity` takes it and
 *   because a terminal `Failed` carries a reason the UI must not lose.
 * @param audioEnabledIdentities the per-tile listen choices.
 *   iOS: `env.liveKit.audioEnabledByIdentity`. **Audio is volume-gated, not
 *   subscription-gated** (PARITY.md §11), so this is a playback choice, not a
 *   subscribe list.
 * @param audioLevels rolling per-camera audio peaks (0..1), capacity 80 on iOS.
 * @param talk the call half, owned by `TalkController`. Carried whole rather
 *   than unpacked, so there is exactly one place the call's lifecycle is read
 *   from.
 * @param nowMillis the per-second clock tick that drives the ON AIR label. iOS
 *   keeps this as `clockTick` because `Date()` is not a reactive dependency and
 *   the label would otherwise freeze at first render. One tick, two consumers:
 *   this label and the desync reconciler.
 * @param lkError the sentence from a failed LiveKit or network operation,
 *   mirrored into the current tile's overlay so it is visible without dismissing
 *   the carousel. The host composes it ("Call failed: …", "Transfer failed: …",
 *   "End call failed: …"); the screen only renders it.
 * @param acknowledgedSensitiveRoomIds camera ids whose one-time private-room
 *   explainer has been dismissed on this device. See [SensitiveRoomAckStore].
 * @param isTogglingMonitoring true while the bulk disconnect/reconnect PATCH is
 *   in flight, so a rapid double-tap cannot fire overlapping toggles.
 * @param clipCaptureEnabled `AppConfig.enableClipCapture`. Hard-false in release;
 *   when false the record button is not rendered at all.
 */
data class LiveVideoState(
    val cameras: List<Camera> = emptyList(),
    val camerasPaused: Boolean = false,
    val room: Room? = null,
    val videoTracksByIdentity: Map<String, VideoTrack> = emptyMap(),
    val connectionState: LiveKitConnectionState = LiveKitConnectionState.Disconnected,
    val selectedCameraId: String? = null,
    val talk: TalkUiState = TalkUiState(),
    val nowMillis: Long = 0L,
    val lkError: String? = null,
    val peopleBlurEnabled: Boolean = false,
    val audioEnabledIdentities: Set<String> = emptySet(),
    val audioLevels: Map<String, List<Float>> = emptyMap(),
    val acknowledgedSensitiveRoomIds: Set<String> = emptySet(),
    val isTogglingMonitoring: Boolean = false,
    val capturingCameraId: String? = null,
    val captureNote: LiveCaptureNote? = null,
    val clipCaptureEnabled: Boolean = false,
) {
    /** The identities the pure derivations below switch on. */
    internal val trackIdentities: Set<String>
        get() = videoTracksByIdentity.keys

    /** iOS: `onlineCameras`. */
    val onlineCameras: List<Camera>
        get() = onlineCameras(cameras, trackIdentities)

    /** iOS: `unacknowledgedSensitiveCameras`. Shows one at a time. */
    val unacknowledgedSensitiveCameras: List<Camera>
        get() = unacknowledgedSensitiveCameras(cameras, acknowledgedSensitiveRoomIds)

    /** iOS: `callTargetPrivacyOn`. Watched so a flip tears the call down. */
    val callTargetPrivacyOn: Boolean
        get() = callTargetPrivacyOn(cameras, selectedCameraId)

    /** iOS: `privateCameraIdentities`. Sorted, so unrelated reorders are no-ops. */
    val privateCameraIdentities: List<String>
        get() = privateCameraIdentities(cameras)
}

// ---------------------------------------------------------------------------
// Which cameras are in the carousel
// ---------------------------------------------------------------------------

/**
 * The sensors shown in the carousel right now. iOS: `PatientLiveView.onlineCameras`.
 *
 * A camera appears iff it has a LiveKit identity and is not user-hidden
 * (`disabled`), AND either:
 *
 * - **this device holds a live video track for it** — the actual feed, ground
 *   truth. This always shows, and is never hidden by intent or by `online` lag;
 *   or
 * - central state says it is streaming (`online`) **and** the intent is still
 *   connected (`desired_state == connected`).
 *
 * The intent check on the `online` branch is what makes a just-disconnected
 * camera drop out immediately: after disconnect-all the track tears down but
 * `online` lags stale-true for seconds, so without it the tile would linger as a
 * placeholder instead of falling back to the offline card.
 *
 * `desired_state` is used **only additively** — never as a gate that could hide a
 * present track. That was a real bug on iOS and it was removed. There is no
 * `cameras_paused` gate here at all.
 */
internal fun onlineCameras(
    cameras: List<Camera>,
    trackIdentities: Set<String>,
): List<Camera> = cameras.filter { camera ->
    val identity = camera.livekitIdentity
    if (identity == null || camera.disabled) return@filter false
    identity in trackIdentities ||
        (camera.online && camera.desiredState == Camera.DesiredState.CONNECTED)
}

/**
 * Privacy-enabled cameras whose one-time explainer has not been dismissed on
 * this device. iOS: `unacknowledgedSensitiveCameras`.
 *
 * Keyed on `privacyEnabled`, **not** on `roomClass`, so a fail-closed
 * classification (privacy on with an unknown room) gets the explainer too.
 * User-hidden cameras never surface a card.
 */
internal fun unacknowledgedSensitiveCameras(
    cameras: List<Camera>,
    acknowledgedIds: Set<String>,
): List<Camera> = cameras.filter {
    it.privacyEnabled && !it.disabled && it.id !in acknowledgedIds
}

/**
 * LiveKit identities of every privacy-enabled camera, sorted.
 *
 * The host watches this and calls `revokeAudio` for each — which must **remove**
 * the identity from the enabled set, not merely zero the volume, or the next
 * resubscribe re-arms playback in a private room (PARITY.md §11, §29 item 5).
 *
 * Sorted so the value is stable across unrelated roster reorders, which is what
 * makes "watch this and react to changes" cheap.
 */
internal fun privateCameraIdentities(cameras: List<Camera>): List<String> =
    cameras.filter { it.privacyEnabled }.mapNotNull { it.livekitIdentity }.sorted()

/**
 * Per-camera privacy of the camera the call is routed to (the carousel
 * selection). The host watches this: a false → true flip while the call is
 * `TalkCallState.ACTIVE` **or** `TalkCallState.CONNECTING` must tear the call down
 * (`TalkController.onCallTargetPrivacyChanged`), so a
 * flip landing mid-start cannot complete against a private room.
 */
internal fun callTargetPrivacyOn(cameras: List<Camera>, selectedCameraId: String?): Boolean =
    cameras.firstOrNull { it.id == selectedCameraId }?.privacyEnabled ?: false

// ---------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------

/**
 * Keep a still-valid selection, otherwise fall to the first online camera.
 *
 * iOS runs this from `onChange(of: onlineCameras.map(\.id))` so the Call button
 * appears the instant a sensor goes live: `bootstrap` may have run against an
 * empty roster, and a paged `TabView` never writes back a selection on its own,
 * so with no selection no tile is the call target and the button stayed hidden
 * until the caregiver tabbed away and back.
 *
 * Not yanking a still-valid selection is the other half — the caregiver must not
 * be moved off their tile mid-swipe or mid-call.
 */
internal fun resolveSelection(currentSelection: String?, onlineIds: List<String>): String? =
    if (currentSelection != null && currentSelection in onlineIds) {
        currentSelection
    } else {
        onlineIds.firstOrNull()
    }

/**
 * iOS `resolveSelectedCamera`: the server's `GET /v1/patients/{id}/active-camera`
 * guess wins when it is actually online, otherwise the first online camera.
 *
 * The host calls this only when the current selection is stale, because
 * re-resolving on every Live-tab return would yank the caregiver off whatever
 * tile they were last looking at.
 */
internal fun resolveSelectionFromServer(suggested: String?, onlineIds: List<String>): String? =
    if (suggested != null && suggested in onlineIds) suggested else onlineIds.firstOrNull()

// ---------------------------------------------------------------------------
// Tile chrome
// ---------------------------------------------------------------------------

/** What the top-left of a tile shows. iOS: `statePill(for:connectivity:)`. */
internal sealed interface LiveStatePill {
    /**
     * The terra LIVE chip with its pulse, or the red ON AIR chip during a call.
     *
     * iOS reads the **global** `callState` here, not "is this tile the call
     * target", so every live tile reads ON AIR while a call is up. Carried
     * verbatim.
     */
    data class Live(val onAir: Boolean) : LiveStatePill

    /** The muted "APP CLOSED" chip: actionable, the phone must be reopened. */
    data object AppClosed : LiveStatePill

    /**
     * No chip. The "WAITING", "CONNECTING" and "Connecting…" pills were all
     * removed on iOS — there is deliberately no loading indication in the pill.
     */
    data object None : LiveStatePill
}

internal fun statePill(
    camera: Camera,
    connectivity: CameraConnectivity,
    callActive: Boolean,
): LiveStatePill = when (connectivity) {
    CameraConnectivity.LIVE -> LiveStatePill.Live(onAir = callActive)
    CameraConnectivity.JOINING, CameraConnectivity.ROOM_DOWN -> LiveStatePill.None
    CameraConnectivity.OFFLINE, CameraConnectivity.UNPAIRED ->
        if (camera.isAppClosed) LiveStatePill.AppClosed else LiveStatePill.None
}

/**
 * The tile's mono subtitle. iOS: `statusLine(for:)`.
 *
 * The active call target reads `ON AIR · MM:SS` off the one-second clock tick;
 * every other tile reads `SENSOR NN`. The ON AIR half is `talkStatusLine`'s, so
 * the timer's formatting lives with the call machine that produces the start
 * stamp; this only decides which of the two a given tile gets.
 *
 * `Locale.ROOT`, not the default locale: Swift's `String(format:)` is
 * locale-independent, while Kotlin's follows the default and would render
 * Arabic-Indic digits on an `ar-EG` device inside an otherwise-English string.
 * Same reasoning as `ui/cameras/CameraUiState.kt`'s `sensorLabel`.
 */
internal fun tileStatusLine(
    camera: Camera,
    talk: TalkUiState,
    selectedCameraId: String?,
    nowMillis: Long,
): String {
    if (camera.id == selectedCameraId) {
        talkStatusLine(
            callState = talk.callState,
            callStartedAtMillis = talk.callStartedAtMillis,
            nowMillis = nowMillis,
        )?.let { return it }
    }
    return "SENSOR " + String.format(Locale.ROOT, "%02d", camera.displayId)
}

/**
 * Color of the small dot beside each sensor row. iOS: `cameraStatusDot(for:)`.
 *
 * **A pure function of the shared, Realtime-synced camera fields only.** It does
 * not read this device's LiveKit connection or the bulk `cameras_paused` lever,
 * because those are local or non-shared: the web dashboard, this phone and
 * another phone must all show the same dot for the same sensor, so it can only
 * depend on central state. Using this phone's room state is exactly what made
 * the dot lie after a background — room down here, centrally still online.
 *
 * AI-pause and monitoring-pause never change the color: the feed keeps streaming
 * when AI is paused, so an online sensor is green either way — which is why
 * resuming AI never has to "turn it back green", it never left.
 *
 * The branch order is load-bearing. Deliberate disconnect wins over a lagging
 * `online` flag, because the backend's stale-online sweeper takes seconds to
 * write `online = false` and checking `online` first kept the dot green right
 * after a disconnect while the header already read "0 ONLINE".
 */
internal fun cameraStatusDotColor(camera: Camera): Color {
    // 0) Deliberately disconnected → rust, and it wins.
    if (camera.desiredState == Camera.DesiredState.DISCONNECTED) return Danger
    // 1) Streaming (the backend observes frames) → green.
    if (camera.online) return Success
    // 2) iPhone sensor with its app closed → dark. The one state a remote
    //    Reconnect cannot fix; the app has to be reopened.
    if (camera.isAppClosed) return Ink3
    // 3) Intent is connected but not online yet → amber "connecting".
    if (camera.desiredState == Camera.DesiredState.CONNECTED) return Warning
    // 4) Unknown intent → rust.
    return Danger
}

/** The sensor list's trailing label. iOS spells the app-closed state out here. */
internal fun cameraRowTrailingLabel(camera: Camera): String =
    if (camera.isAppClosed) {
        "APP CLOSED"
    } else {
        "Sensor " + String.format(Locale.ROOT, "%02d", camera.displayId)
    }

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** The one-time private-room explainer. iOS: `sensitiveRoomCard(_:)`. */
internal data class SensitiveRoomCopy(
    val title: String,
    val body: String,
)

/**
 * Copy adapts to whether the classifier's room call is known. Both sentences are
 * carried verbatim; the difference is "looks like it's in" versus "is in", which
 * is the difference between a guess and a fact and must not be flattened.
 */
internal fun sensitiveRoomCopy(camera: Camera): SensitiveRoomCopy = SensitiveRoomCopy(
    title = "${camera.room} is a private room",
    body = if (camera.isSensitiveRoom) {
        "This camera looks like it's in a private room. Privacy " +
            "is on by default: no live view, and sensitive moments " +
            "in clips are always censored."
    } else {
        "This camera is in a private room. Privacy is on: " +
            "no live view, and sensitive moments in clips " +
            "are always censored."
    },
)

/**
 * The card shown when sensors are paired but none is streaming. iOS:
 * `offlineCard`. Distinct from the no-sensors-at-all card.
 *
 * [detailMarkdown] carries `**bold**` exactly as the Swift `LocalizedStringKey`
 * does; render it through [liveMarkdownBold].
 */
internal data class OfflineCardCopy(
    val icon: String,
    val title: String,
    val detailMarkdown: String,
)

/**
 * iOS `offlineCard`. Never gates the live feed — purely a hint.
 *
 * The app-closed count matters twice: it picks the right title when monitoring
 * is running, and when monitoring is paused it warns that Reconnect-all will not
 * bring those sensors back, because nothing is running on those phones to
 * receive the intent.
 */
internal fun offlineCardCopy(cameras: List<Camera>, camerasPaused: Boolean): OfflineCardCopy {
    val closedCount = cameras.count { it.isAppClosed && !it.disabled }
    return when {
        camerasPaused -> {
            val closedNote = when {
                closedCount == 1 ->
                    " 1 sensor has its app closed, so Reconnect won't bring it back " +
                        "— reopen Memoria Sensor on that phone."
                closedCount > 1 ->
                    " $closedCount sensors have their app closed, so Reconnect won't " +
                        "bring those back — reopen Memoria Sensor on those phones."
                else -> ""
            }
            OfflineCardCopy(
                icon = "pause.circle",
                title = "Monitoring paused",
                detailMarkdown = "Tap **Reconnect all sensors** below to bring your " +
                    "sensors back online.$closedNote",
            )
        }
        closedCount > 0 -> OfflineCardCopy(
            icon = "iphone.slash",
            title = "Memoria Sensor is closed",
            detailMarkdown =
                "All available Memoria Sensors are offline, not open, or disconnected.",
        )
        else -> OfflineCardCopy(
            icon = "antenna.radiowaves.left.and.right.slash",
            title = "All sensors are offline",
            detailMarkdown =
                "All available Memoria Sensors are offline, not open, or disconnected.",
        )
    }
}

/** The bulk lever below the sensor list. iOS: `disconnectAllButton`. */
internal data class BulkMonitoringButton(
    val title: String,
    val icon: String,
    val isPrimary: Boolean,
)

/**
 * "Reconnect all" is offered whenever nothing is live — whether that is because
 * monitoring is paused or because every sensor went offline or had its app
 * closed. "Disconnect all" only makes sense when there is an actually-live
 * sensor to turn off, so it never sits nonsensically under an all-offline card.
 */
internal fun bulkMonitoringButton(onlineCount: Int): BulkMonitoringButton =
    if (onlineCount == 0) {
        BulkMonitoringButton("Reconnect all sensors", "video.fill", isPrimary = true)
    } else {
        BulkMonitoringButton("Disconnect all sensors", "video.slash", isPrimary = false)
    }

// ---------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------

/**
 * Carousel height. iOS: `tileHeight` — 16:9 of the content width, plus headroom
 * for the page-dot indicator.
 *
 * iOS reads `UIScreen.main.bounds.width - 32`, which is the screen minus the
 * stack's own 16pt horizontal padding, i.e. the content width. Taking the
 * content width as the argument says the same thing without asking a composable
 * for the screen.
 */
internal fun liveTileHeight(contentWidth: Dp): Dp = contentWidth * (9f / 16f) + PageDotHeadroom

/** iOS: `+ 36` on the tile height, and `.padding(.bottom, 32)` on each page. */
internal val PageDotHeadroom: Dp = 36.dp

/** iOS: `.padding(.bottom, 32)` — headroom for the page-dot indicator. */
internal val PageDotPadding: Dp = 32.dp

// ---------------------------------------------------------------------------
// Markdown
// ---------------------------------------------------------------------------

/**
 * Renders the `**bold**` runs SwiftUI's `Text(LocalizedStringKey)` renders for
 * free. Two cards depend on it: the empty card bolds "Sensors tab" and the
 * paused offline card bolds "Reconnect all sensors" — in both cases the emphasis
 * is what points the caregiver at a control, so dropping it would drop the
 * instruction.
 *
 * Deliberately handles only `**` pairs. An unterminated `**` is emitted
 * literally, which is what SwiftUI does with malformed markdown, rather than
 * swallowing the rest of the sentence.
 */
internal fun liveMarkdownBold(source: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        val open = source.indexOf("**", index)
        if (open < 0) {
            append(source.substring(index))
            return@buildAnnotatedString
        }
        val close = source.indexOf("**", open + 2)
        if (close < 0) {
            append(source.substring(index))
            return@buildAnnotatedString
        }
        append(source.substring(index, open))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(source.substring(open + 2, close))
        }
        index = close + 2
    }
}
