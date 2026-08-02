package com.memoria.caregiver.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.ui.cameras.SensorSymbol
import com.memoria.caregiver.ui.cameras.plainClickable
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCamFeedPlaceholder
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.NemaraPulse
import com.memoria.caregiver.ui.components.NemaraWaveform
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.CardAlt
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import com.memoria.caregiver.ui.theme.Warning
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

/**
 * The "Live" sub-tab. Spec: `Features/Patient/Live/PatientLiveView.swift`,
 * PARITY.md §18 and §12.
 *
 * **The phone-call metaphor.** The caregiver swipes through a carousel of camera
 * tiles and taps a call button overlaid on the currently-displayed tile to open
 * full-duplex audio with *that* camera. Swiping during an active call transfers
 * the call to the newly-displayed camera.
 *
 * Pure state-in / callbacks-out, per `ui/auth/AuthUiState.kt`. Everything with a
 * lifetime belongs to the host, and the reasons are worth restating because
 * getting any of them wrong is a real regression:
 *
 * - **The room is connected on patient OPEN and disconnected on patient CLOSE**,
 *   never here. Sub-tab switches inside the patient keep the room alive so video
 *   resumes instantly; the teardown lives on the patient shell's disposal. Doing
 *   it on this screen's disposal would reconnect the room on every tab tap.
 * - **Connect with broadcaster grants up front**, even for plain viewing, so
 *   starting and ending a call is a mic toggle with no room reconnect and the
 *   video tiles never blink.
 * - **Leaving the Live screen during an active call is still "hang up"** in the
 *   phone metaphor, so the host ends the call on disposal — but does not
 *   disconnect the room, and deliberately preserves the carousel selection so
 *   returning lands on the same tile.
 * - The desync reconciler ([DesyncReconciler]) and the reconnect-all loop
 *   ([ReconnectAllLoop]) live in this package as pure logic; the host drives them
 *   from the one-second tick and from the `cameras_paused` true-to-false edge.
 *
 * There is deliberately **no screen analytics event**: `PatientLiveView` carries
 * no `.trackScreen`, and the bulk button gets its event from `NemaraButton`,
 * which is the app's single instrumentation point.
 *
 * @param onSelectCamera the carousel's target changed — either the caregiver
 *   swiped, or the live set changed and the selection was re-resolved. While a
 *   call is active the host hands this to `TalkController.onSelectedCameraChanged`,
 *   which transfers the call, or hangs up when the new target cannot take one —
 *   a gateway camera has no speaker and a private room refuses talk, so
 *   transferring there would strand a call with no way to end it. Nullable because
 *   an emptied live set clears the selection, exactly as iOS's `ids.first` does.
 * @param onToggleAudio flip one tile's listen choice, by LiveKit identity.
 *   Independent of Talk: a caregiver can listen passively without starting a
 *   call, and the active call target is audible regardless of this toggle.
 * @param onOpenCameraDetail push the per-camera detail screen. iOS:
 *   `NavigationLink(value: cam.id)`.
 * @param onExpandCamera present [LiveFullScreenView] for this camera.
 * @param videoTile the seam for the people-blur renderer. iOS switches between
 *   `BlurredVideoView` and `LiveKitVideoView` on the device-local preference, and
 *   both exist here — but `BlurredVideoView` needs a `PersonDetector`, which has
 *   no implementation in the repository yet and belongs to the app root rather
 *   than to this screen (the same reasoning as `DIFFERENCES.md` D8's injected
 *   avatar loader). So the default renders the plain path for both values of
 *   [LiveVideoState.peopleBlurEnabled], and the host overrides with a lambda that
 *   routes `blurred` to `BlurredVideoView(track, detector, modifier)` once it has
 *   a detector to supply.
 */
@Composable
fun LiveVideoScreen(
    state: LiveVideoState,
    modifier: Modifier = Modifier,
    onSelectCamera: (String?) -> Unit,
    onToggleAudio: (String) -> Unit,
    onOpenCameraDetail: (String) -> Unit,
    onExpandCamera: (String) -> Unit,
    onAcknowledgeSensitiveRoom: (String) -> Unit,
    onCallButtonTap: () -> Unit,
    onCaptureClip: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onReconnectAll: () -> Unit,
    videoTile: @Composable (LiveVideoTile, Modifier) -> Unit = { tile, tileModifier ->
        DefaultLiveVideoTile(tile, tileModifier)
    },
) {
    val online = state.onlineCameras

    // iOS: onChange(of: onlineCameras.map(\.id)). Resolve the selection the
    // moment the live set changes, so the Call button appears the instant a
    // sensor goes live. Without it, a bootstrap that ran against an empty roster
    // leaves no selection, no tile is the call target, and the button stays
    // hidden until the caregiver tabs away and back. A still-valid selection is
    // kept, so nobody is yanked off their tile mid-swipe or mid-call.
    val onlineIds = online.map { it.id }
    LaunchedEffect(onlineIds) {
        val resolved = resolveSelection(state.selectedCameraId, onlineIds)
        if (resolved != state.selectedCameraId) onSelectCamera(resolved)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            // iOS: .padding(.horizontal, 16) / .padding(.top, 14) / .padding(.bottom, 16)
            .padding(horizontal = ContentInset)
            .padding(top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        // One-time explainer for cameras with per-camera privacy on. One at a
        // time — dismissing reveals the next, if any.
        state.unacknowledgedSensitiveCameras.firstOrNull()?.let { camera ->
            SensitiveRoomCard(camera = camera, onDismiss = { onAcknowledgeSensitiveRoom(camera.id) })
        }

        // The grid follows the cameras' ACTUAL live state, never the
        // patient-level pause flag. The bulk buttons are just desired_state
        // toggles: they must not own or gate what is shown, so a camera that
        // comes back online — from the sensor, from the web, or from here —
        // always appears. Reaching the offline branch means every camera is
        // honestly offline; the desync reconciler force-resubscribes if a gap
        // persists, so this is never shown while video could be streaming.
        if (online.isEmpty()) {
            if (state.cameras.isEmpty()) {
                EmptyCard()
            } else {
                OfflineCard(cameras = state.cameras, camerasPaused = state.camerasPaused)
            }
        } else {
            CameraCarousel(
                state = state,
                online = online,
                onSelectCamera = onSelectCamera,
                onToggleAudio = onToggleAudio,
                onExpandCamera = onExpandCamera,
                onCallButtonTap = onCallButtonTap,
                onCaptureClip = onCaptureClip,
                videoTile = videoTile,
            )
        }

        CamerasCard(
            cameras = state.cameras,
            onlineCount = online.size,
            onOpenCameraDetail = onOpenCameraDetail,
        )

        if (state.cameras.isNotEmpty()) {
            BulkMonitoringLever(
                onlineCount = online.size,
                isBusy = state.isTogglingMonitoring,
                onDisconnectAll = onDisconnectAll,
                onReconnectAll = onReconnectAll,
            )
        }
    }
}

/**
 * What the tile needs to render a feed. Split out so the blur renderer can be
 * plugged in without this screen knowing anything about it.
 */
data class LiveVideoTile(
    val room: Room?,
    val track: VideoTrack,
    val identity: String?,
    /** The device-local "Blur people" preference for this patient. */
    val blurred: Boolean,
)

/** The plain renderer. Ignores [LiveVideoTile.blurred]; see [LiveVideoScreen]. */
@Composable
fun DefaultLiveVideoTile(tile: LiveVideoTile, modifier: Modifier = Modifier) {
    LiveVideoRenderer(
        room = tile.room,
        track = tile.track,
        identity = tile.identity,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Carousel
// ---------------------------------------------------------------------------

@Composable
private fun CameraCarousel(
    state: LiveVideoState,
    online: List<Camera>,
    onSelectCamera: (String?) -> Unit,
    onToggleAudio: (String) -> Unit,
    onExpandCamera: (String) -> Unit,
    onCallButtonTap: () -> Unit,
    onCaptureClip: (String) -> Unit,
    videoTile: @Composable (LiveVideoTile, Modifier) -> Unit,
) {
    val selectedIndex = online.indexOfFirst { it.id == state.selectedCameraId }
        .let { if (it < 0) 0 else it }
    val pagerState = rememberPagerState(initialPage = selectedIndex) { online.size }

    // Host-driven selection changes move the pager; swipes report back. Both
    // sides are guarded on inequality, so the two converge instead of chasing
    // each other, and neither fires on the frame where they already agree.
    LaunchedEffect(state.selectedCameraId, online.size) {
        val target = online.indexOfFirst { it.id == state.selectedCameraId }
        if (target >= 0 && target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }
    val currentPage = pagerState.currentPage
    LaunchedEffect(currentPage, onlineIdsOf(online)) {
        val camera = online.getOrNull(currentPage) ?: return@LaunchedEffect
        if (camera.id != state.selectedCameraId) onSelectCamera(camera.id)
    }

    // iOS reads UIScreen width minus the stack's own 16pt padding, i.e. exactly
    // the content width this box is handed.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = liveTileHeight(maxWidth)
        Box(Modifier.fillMaxWidth().height(height)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val camera = online[page]
                CameraTile(
                    state = state,
                    camera = camera,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Headroom for the page-dot indicator, so the dots never
                        // sit on the video.
                        .padding(bottom = PageDotPadding),
                    onToggleAudio = onToggleAudio,
                    onExpandCamera = onExpandCamera,
                    onCallButtonTap = onCallButtonTap,
                    onCaptureClip = onCaptureClip,
                    videoTile = videoTile,
                )
            }
            // iOS: .tabViewStyle(.page(indexDisplayMode: count > 1 ? .always : .never)).
            if (online.size > 1) {
                PageDots(
                    count = online.size,
                    current = pagerState.currentPage,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (index == current) TerraInk else TerraInk.copy(alpha = 0.45f)),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// One tile
// ---------------------------------------------------------------------------

@Composable
private fun CameraTile(
    state: LiveVideoState,
    camera: Camera,
    modifier: Modifier,
    onToggleAudio: (String) -> Unit,
    onExpandCamera: (String) -> Unit,
    onCallButtonTap: () -> Unit,
    onCaptureClip: (String) -> Unit,
    videoTile: @Composable (LiveVideoTile, Modifier) -> Unit,
) {
    val identity = camera.livekitIdentity
    val track = identity?.let { state.videoTracksByIdentity[it] }
    val levels = talkWaveformLevels(state.audioLevels, identity)
    val isCallTarget = camera.id == state.selectedCameraId
    val connectivity = liveConnectivity(camera, state.trackIdentities, state.roomConnected)
    val shape = RoundedCornerShape(Radius.lg)

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(TileAspectRatio)
                .clip(shape)
                .background(Ink),
        ) {
            // ---- Feed layer -------------------------------------------------
            if (camera.privacyEnabled) {
                // Private room: the feed for THIS camera is severed server-side,
                // so never render video even if a stale track lingers. Event
                // insights still flow everywhere else in the app, and every other
                // camera streams normally.
                PrivacyPlacard(Modifier.fillMaxSize())
            } else if (track != null) {
                videoTile(
                    LiveVideoTile(
                        room = state.room,
                        track = track,
                        identity = identity,
                        blurred = state.peopleBlurEnabled,
                    ),
                    Modifier.fillMaxSize(),
                )
            } else {
                // Expected-online, waiting on the subscribe.
                NemaraCamFeedPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    hue = 30f,
                    loading = true,
                )
            }

            // Bottom scrim drawn INTO the feed, so it bleeds to the tile's
            // clipped bottom edge and reads as a natural video fade rather than a
            // dark box floating inside the view. It seats the white text below.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(ScrimHeight)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.6f))),
                    ),
            )

            // ---- Chrome -----------------------------------------------------
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatePill(
                        pill = statePill(
                            camera = camera,
                            connectivity = connectivity,
                            callActive = state.talk.callState == TalkCallState.ACTIVE,
                        ),
                    )
                    // Beside the LIVE pill, only for cameras whose own AI is
                    // paused. Per-camera, and it updates live over the cameras
                    // realtime channel, so a pause from any node shows here.
                    if (camera.aiPaused) AiPausedPill()
                    Spacer(Modifier.weight(1f))
                    // Hidden for a privacy-enabled camera: there is no audio to
                    // listen to, the backend never sends it.
                    if (!camera.privacyEnabled && identity != null) {
                        val isAudible = identity in state.audioEnabledIdentities
                        TileIconButton(
                            glyph = if (isAudible) "speaker.wave.2.fill" else "speaker.slash.fill",
                            label = if (isAudible) {
                                "Mute ${camera.room} audio"
                            } else {
                                "Unmute ${camera.room} audio"
                            },
                            onClick = { onToggleAudio(identity) },
                        )
                    }
                    // Dev-only data-collection trigger. Tells THIS camera to
                    // record the next 15s and ship it to the laptop receiver.
                    // Hidden entirely unless the flag is on, and the flag is
                    // hard-false in release.
                    if (state.clipCaptureEnabled) {
                        val capturing = state.capturingCameraId == camera.id
                        TileIconButton(
                            glyph = if (capturing) "record.circle.fill" else "record.circle",
                            label = "Capture 15-second clip from ${camera.room}",
                            tint = if (capturing) SystemRed else TerraInk,
                            enabled = state.capturingCameraId == null,
                            onClick = { onCaptureClip(camera.id) },
                        )
                    }
                    TileIconButton(
                        glyph = "arrow.up.left.and.arrow.down.right",
                        label = "Expand ${camera.room}",
                        onClick = { onExpandCamera(camera.id) },
                    )
                }

                Spacer(Modifier.weight(1f))

                if (!camera.privacyEnabled && isCallTarget && state.lkError != null) {
                    TileToast(state.lkError, Modifier.align(Alignment.CenterHorizontally))
                }
                state.captureNote?.takeIf { it.cameraId == camera.id }?.let { note ->
                    TileToast(note.text, Modifier.align(Alignment.CenterHorizontally))
                }

                BottomOverlay(camera = camera, state = state, levels = levels)
            }

            // No Talk on a privacy-enabled camera (private room, refused on both
            // sides) and none on a gateway/Pi camera, which has no speaker — the
            // caregiver's voice plays through the wall phone, so calling an RTSP
            // camera dials into a room where nothing can answer.
            if (shouldShowCallButton(camera = camera, isThisCallTarget = isCallTarget)) {
                CallButton(
                    state = state.talk.callState,
                    onTap = onCallButtonTap,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-10).dp),
                )
            }
        }

        // The hairline sits above the feed, so the punch-through of the video
        // surface cannot eat it.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(TileAspectRatio)
                .border1(shape),
        )
    }
}

@Composable
private fun BottomOverlay(
    camera: Camera,
    state: LiveVideoState,
    levels: List<Float>,
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = camera.room, style = NemaraType.bodySemibold, color = TerraInk)
            Text(
                text = tileStatusLine(
                    camera = camera,
                    talk = state.talk,
                    selectedCameraId = state.selectedCameraId,
                    nowMillis = state.nowMillis,
                ),
                // iOS: NemaraFont.mono(size: 9, weight: .semibold) — one step
                // lighter than the pills' monoMicro, which is bold at the same
                // size. Per DECISIONS.md D-008 the tracking is applied here.
                style = MonoNineSemibold,
                letterSpacing = 0.5.sp,
                color = TerraInk.copy(alpha = 0.78f),
            )
        }
        Spacer(Modifier.weight(1f))
        // The waveform is only meaningful when audio is flowing, so it shows for
        // the active call target only.
        if (shouldShowTalkWaveform(state.talk.callState, camera.id, state.selectedCameraId)) {
            NemaraWaveform(
                levels = levels,
                modifier = Modifier.width(TALK_WAVEFORM_WIDTH).height(TALK_WAVEFORM_HEIGHT),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Pills and small tile chrome
// ---------------------------------------------------------------------------

@Composable
private fun StatePill(pill: LiveStatePill) {
    when (pill) {
        is LiveStatePill.Live -> Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (pill.onAir) SystemRed else Terra)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraPulse(color = TerraInk, size = 5.dp)
            Text(
                text = if (pill.onAir) "ON AIR" else "LIVE",
                style = NemaraType.monoMicro,
                letterSpacing = 0.8.sp,
                color = TerraInk,
            )
        }
        LiveStatePill.AppClosed -> MutedPill("APP CLOSED")
        LiveStatePill.None -> Unit
    }
}

/**
 * Per-camera "AI PAUSED" badge. The video keeps streaming — this only signals
 * that analysis is off — so it is styled like the LIVE pill but in a muted tone
 * rather than the bright terra.
 */
@Composable
private fun AiPausedPill() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.62f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveGlyph(
            name = "pause.fill",
            size = 8.dp,
            tint = TerraInk,
            strokeRatio = LiveGlyphBoldStroke,
        )
        Text(
            text = "AI PAUSED",
            style = NemaraType.monoMicro,
            letterSpacing = 0.8.sp,
            color = TerraInk,
        )
    }
}

@Composable
private fun MutedPill(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.55f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        style = NemaraType.monoMicro,
        letterSpacing = 0.8.sp,
        color = TerraInk,
    )
}

@Composable
private fun TileIconButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    tint: Color = TerraInk,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Ink.copy(alpha = 0.55f))
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .semantics { contentDescription = label },
    ) {
        LiveGlyph(name = glyph, size = 12.dp, tint = tint)
    }
}

/** The dark rounded caption both the error line and the capture note use. */
@Composable
private fun TileToast(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Ink.copy(alpha = 0.65f))
            .padding(8.dp),
        style = NemaraType.caption,
        color = TerraInk,
        textAlign = TextAlign.Center,
    )
}

/**
 * Fills a tile whose camera has per-camera privacy on. Dignity-first: no feed, no
 * spinner — just a clear statement that the live view is intentionally off for
 * this room while insights keep arriving elsewhere.
 */
@Composable
internal fun PrivacyPlacard(modifier: Modifier = Modifier) {
    Box(modifier.background(Ink), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LiveGlyph(name = "eye.slash.fill", size = 26.dp, tint = TerraInk.copy(alpha = 0.85f))
            Text(text = "Private room", style = NemaraType.bodySemibold, color = TerraInk)
            Text(
                // A literal newline in the Swift, not a wrap — the two sentences
                // are meant to sit on their own lines.
                text = "Live view is off for this room.\nInsights still arrive on your phone.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = NemaraType.caption,
                color = TerraInk.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
private fun SensitiveRoomCard(camera: Camera, onDismiss: () -> Unit) {
    val copy = sensitiveRoomCopy(camera)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(ActionSoft)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LiveGlyph(
            name = "lock.shield.fill",
            size = 14.dp,
            tint = Action,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(text = copy.title, style = NemaraType.bodySemibold, color = Ink)
            Text(
                text = copy.body,
                style = NemaraType.caption,
                color = Ink3,
                lineHeight = CaptionLineHeight,
            )
        }
        Box(
            modifier = Modifier
                .plainClickable(onClick = onDismiss)
                .padding(6.dp)
                .semantics { contentDescription = "Dismiss private-room notice for ${camera.room}" },
        ) {
            LiveGlyph(name = "xmark", size = 11.dp, tint = Ink3)
        }
    }
}

/** No sensors paired at all. iOS: `emptyCard`. */
@Composable
private fun EmptyCard() {
    StatusCard(
        icon = "antenna.radiowaves.left.and.right.slash",
        title = "No sensors paired",
        detailMarkdown = "Pair a Memoria Sensor from the **Sensors tab** to begin the live feed.",
        detailHorizontalPadding = 28.dp,
    )
}

/**
 * Sensors ARE paired but none is streaming. iOS: `offlineCard`. Distinct from
 * [EmptyCard], and it never gates the live feed — purely a hint.
 */
@Composable
private fun OfflineCard(cameras: List<Camera>, camerasPaused: Boolean) {
    val copy = offlineCardCopy(cameras, camerasPaused)
    StatusCard(
        icon = copy.icon,
        title = copy.title,
        detailMarkdown = copy.detailMarkdown,
        detailHorizontalPadding = 28.dp,
    )
}

@Composable
private fun StatusCard(
    icon: String,
    title: String,
    detailMarkdown: String,
    detailHorizontalPadding: Dp,
) {
    val shape = RoundedCornerShape(Radius.lg)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(TileAspectRatio)
            .clip(shape)
            .background(CardAlt)
            .border1(shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiveGlyph(
                name = icon,
                size = 26.dp,
                tint = Ink3,
                strokeRatio = LiveGlyphPictogramStroke,
            )
            Text(text = title, style = NemaraType.title3, color = Ink)
            Text(
                text = liveMarkdownBold(detailMarkdown),
                modifier = Modifier.padding(horizontal = detailHorizontalPadding),
                style = NemaraType.bodySmall,
                color = Ink2,
                textAlign = TextAlign.Center,
                lineHeight = BodySmallLineHeight,
            )
        }
    }
}

/**
 * The sensor list. The eyebrow always reflects the real online count and never
 * reads "PAUSED": `cameras_paused` is only the bulk lever, it does not gate the
 * feed, so a sensor can be live while it is true and labelling the whole list
 * paused would contradict what the caregiver is watching.
 */
@Composable
private fun CamerasCard(
    cameras: List<Camera>,
    onlineCount: Int,
    onOpenCameraDetail: (String) -> Unit,
) {
    NemaraCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveGlyph(name = "wifi", size = 12.dp, tint = Action)
                NemaraEyebrow(text = "SENSORS · $onlineCount ONLINE")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cameras.forEach { camera ->
                    SensorRow(camera = camera, onClick = { onOpenCameraDetail(camera.id) })
                }
            }
        }
    }
}

@Composable
private fun SensorRow(camera: Camera, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().plainClickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(PaperDim),
            contentAlignment = Alignment.Center,
        ) {
            SensorSymbol(name = camera.iconName, size = 13.dp, color = Ink2, knockout = PaperDim)
        }
        Text(text = camera.room, style = NemaraType.bodySmall, color = Ink)
        Spacer(Modifier.weight(1f))
        // The app-closed state is spelled out here, not left to the dot: this one
        // needs the app reopened, not a reconnect, and it shows even while paused
        // because Reconnect-all will not bring it back.
        Text(
            text = cameraRowTrailingLabel(camera),
            style = NemaraType.monoSmall,
            color = if (camera.isAppClosed) Warning else Ink3,
        )
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(cameraStatusDotColor(camera)),
        )
        SensorSymbol(name = "chevron.right", size = 11.dp, color = Ink3)
    }
}

@Composable
private fun BulkMonitoringLever(
    onlineCount: Int,
    isBusy: Boolean,
    onDisconnectAll: () -> Unit,
    onReconnectAll: () -> Unit,
) {
    val lever = bulkMonitoringButton(onlineCount)
    NemaraButton(
        title = lever.title,
        variant = if (lever.isPrimary) {
            NemaraButtonVariant.Primary
        } else {
            NemaraButtonVariant.Secondary
        },
        block = true,
        enabled = !isBusy,
        onClick = { if (lever.isPrimary) onReconnectAll() else onDisconnectAll() },
        modifier = Modifier.alphaWhenBusy(isBusy),
    )
}

// ---------------------------------------------------------------------------
// Shared literals
// ---------------------------------------------------------------------------

/**
 * A structurally-comparable key for "which cameras are live", so an effect keyed
 * on it restarts when the set changes and not on every recomposition.
 */
private fun onlineIdsOf(cameras: List<Camera>): List<String> = cameras.map { it.id }

/** iOS: `.padding(.horizontal, 16)` on the scroll content. */
private val ContentInset: Dp = 16.dp

/** iOS: `VStack(spacing: 14)`. Between Spacing.sm and Spacing.md, so a literal. */
private val SectionGap: Dp = 14.dp

/** iOS: `.aspectRatio(16/9, contentMode: .fit)` on every full-width tile. */
private const val TileAspectRatio: Float = 16f / 9f

/** iOS: the bottom fade drawn into the feed, `.frame(height: 92)`. */
private val ScrimHeight: Dp = 92.dp

/**
 * Caption line height, 15.31sp. SwiftUI's `.lineSpacing(2)` adds two points to
 * the font's own gap; Compose's `lineHeight` is absolute. Inter's natural ratio
 * is 1.21, so 11 x 1.21 = 13.31, plus 2. Same derivation as
 * `ui/cameras/CameraListScreen.kt`.
 */
private val CaptionLineHeight = 15.31.sp

/** The same derivation at `.lineSpacing(3)` on bodySmall: 13 x 1.21 = 15.73, plus 3. */
private val BodySmallLineHeight = 18.73.sp

/** iOS: `NemaraFont.mono(size: 9, weight: .semibold)` — not the bold monoMicro. */
private val MonoNineSemibold = NemaraType.monoMicro.copy(fontWeight = FontWeight.SemiBold)

/** iOS: `.strokeBorder(NemaraColor.line, lineWidth: 1)`. */
private fun Modifier.border1(shape: Shape): Modifier = this.border(1.dp, Line, shape)

/**
 * iOS: `.opacity(isTogglingMonitoring ? 0.6 : 1)` on the bulk button.
 *
 * The dimming is separate from the disabling on purpose. `NemaraButton` does not
 * dim a disabled button — 45 call sites depend on that — so a button that must
 * *look* busy has to say so here.
 */
private fun Modifier.alphaWhenBusy(isBusy: Boolean): Modifier =
    if (isBusy) this.alpha(0.6f) else this
