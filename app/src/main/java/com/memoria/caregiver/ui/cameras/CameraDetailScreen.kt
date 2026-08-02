package com.memoria.caregiver.ui.cameras

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraChip
import com.memoria.caregiver.ui.components.NemaraChipTone
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * Per-camera detail. Spec: `Features/Patient/Cameras/CameraDetailView.swift`,
 * PARITY.md §21. Three control surfaces:
 *
 * 1. **Pause AI / Resume AI** — `ai_paused`. Instant; video keeps streaming,
 *    only the LLM tick is skipped. Lives under a collapsed "Advanced" header,
 *    because it is a niche cost lever that sat too prominently beside Disconnect.
 * 2. **Disconnect / Reconnect** — `desired_state`. The wall phone polls every
 *    second and leaves or rejoins LiveKit, so a reconnect lands in 1-2s.
 *    Disconnect is confirmed by dialog (the destructive direction); Reconnect is
 *    not.
 * 3. **Per-camera detector toggles** — `cameras_detectors`, one row per
 *    detector, optimistic with a per-row in-flight state.
 *
 * The old "Hide / Show" control (`disabled`) is NOT here. It was removed from
 * the UI to keep the caregiver's options simple; the field still exists and is
 * honored where read, and PARITY.md §21 says not to re-expose it.
 *
 * Pure state-in / callbacks-out, per AuthUiState.kt. The busy flags arrive in
 * [state] rather than being tracked here, and the caller must clear them on
 * success as well as on failure — iOS drops them in `defer`, not in `catch`,
 * because the row stays mounted across the state change and a success-only path
 * left the button permanently disabled in the web grid.
 *
 * What stays local: the collapsed state of Advanced and the three confirmation
 * dialogs, which are `@State` on the iOS view for the same reason — they are
 * about this screen's own presentation and never outlive it.
 *
 * @param onToggleDetector flip one detector's membership in this camera's set.
 *   iOS PUTs the full new set and adopts the echoed result as truth.
 * @param onOpenDoorPicker present `WanderingDoorPickerView` for this sensor.
 * @param onShowPairingCode present `PairingCodeSheet` in regenerate mode. The
 *   Swift attaches `.trackScreen("pairing_code")` to that sheet, so its screen
 *   event belongs to whoever presents it.
 */
@Composable
fun CameraDetailScreen(
    state: CameraDetailState,
    modifier: Modifier = Modifier,
    onToggleDetector: (Detector) -> Unit,
    onToggleAiPause: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
    onDelete: () -> Unit,
    onShowPairingCode: () -> Unit,
    onOpenDoorPicker: () -> Unit,
) {
    val analytics = LocalAnalytics.current
    LaunchedEffect(Unit) { analytics.logScreen("camera_detail") }

    var showAdvanced by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Confirmation?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(top = Spacing.sm, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        val camera = state.camera
        if (camera == null) {
            Text(
                text = "Sensor not found",
                style = NemaraType.bodySemibold,
                color = Ink3,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 80.dp),
            )
        } else {
            HeaderCard(camera, state.showsAiPausedChip)
            // Room-derived privacy. Locked server-side (no user toggle) — this
            // section only explains the state.
            if (camera.privacyEnabled) PrivateRoomSection(camera)
            if (state.showsPairingCode) PairingCodeSection(onShowPairingCode)
            StateControls(
                camera = camera,
                state = state,
                onDisconnectRequested = { confirming = Confirmation.DISCONNECT },
                onReconnect = onReconnect,
            )
            AdvancedSection(
                camera = camera,
                state = state,
                expanded = showAdvanced,
                onToggleExpanded = { showAdvanced = !showAdvanced },
                onToggleAiPause = onToggleAiPause,
            )
            DetectorsSection(state = state, onToggleDetector = onToggleDetector)
            // Exit doors are configured PER SENSOR — this is that sensor.
            if (state.showsExitDoors) {
                ExitDoorsSection(state = state, onOpenDoorPicker = onOpenDoorPicker)
            }
            // Unpair returns a paired phone camera to UNPAIRED (re-pairable)
            // without deleting it. Once unpaired, the pairing-code section above
            // takes over.
            if (state.showsUnpair) {
                UnpairButton(state.isUnpairing) { confirming = Confirmation.UNPAIR }
            }
            DeleteCameraButton(state.isDeleting) { confirming = Confirmation.DELETE }
        }
    }

    when (confirming) {
        null -> Unit
        Confirmation.DISCONNECT -> ConfirmDialog(
            title = "Disconnect this sensor?",
            message = "The wall phone will leave LiveKit entirely. No video, " +
                "no AI calls, no LiveKit costs. Reconnecting takes " +
                "about 1-2 seconds.",
            confirmTitle = "Disconnect",
            onDismiss = { confirming = null },
            onConfirm = { confirming = null; onDisconnect() },
        )
        Confirmation.UNPAIR -> ConfirmDialog(
            title = "Unpair this sensor?",
            message = "The paired phone returns to its pairing screen. This " +
                "camera's room, detectors, and history are kept — pair a " +
                "phone back to it any time with a fresh code.",
            confirmTitle = "Unpair",
            onDismiss = { confirming = null },
            onConfirm = { confirming = null; onUnpair() },
        )
        Confirmation.DELETE -> ConfirmDialog(
            title = "Delete this sensor?",
            message = "The wall phone will unpair on its next poll. " +
                "Its event history is preserved. You can pair " +
                "a new phone to this patient any time.",
            confirmTitle = "Delete",
            onDismiss = { confirming = null },
            onConfirm = { confirming = null; onDelete() },
        )
    }
}

/** The three destructive confirmations. Only one can be up at a time. */
private enum class Confirmation { DISCONNECT, UNPAIR, DELETE }

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun HeaderCard(camera: Camera, showsAiPausedChip: Boolean) {
    SectionCard(padding = 14.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LargeSymbolTile(iconName = camera.iconName, background = PaperDim, color = Ink2)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = camera.room, style = NemaraType.bodySemibold, color = Ink)
                Text(
                    text = cameraDetailLine(camera),
                    style = NemaraType.monoSmall,
                    color = Ink3,
                )
            }
            if (showsAiPausedChip) NemaraChip("AI PAUSED", tone = NemaraChipTone.Neutral)
        }
    }
}

/**
 * "Private room" explainer. Read-only by design: privacy is derived from the AI
 * room classification (bathroom/bedroom) and locked server-side, so there is
 * deliberately no toggle here.
 */
@Composable
private fun PrivateRoomSection(camera: Camera) {
    SectionCard(padding = 14.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            LargeSymbolTile(
                iconName = "lock.shield.fill",
                background = ActionSoft,
                color = Action,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text = "Private room", style = NemaraType.bodySemibold, color = Ink)
                Text(
                    text = "Live view off, clips protected. Set automatically " +
                        "because this looks like a ${roomClassLabel(camera)}.",
                    style = NemaraType.caption,
                    color = Ink3,
                    lineHeight = CaptionLineHeight,
                )
            }
        }
    }
}

/**
 * "Awaiting pairing", shown when the camera row exists but no wall phone has
 * completed `/v1/cameras/pair` yet. The sheet mints a fresh 6-digit code each
 * time it appears.
 */
@Composable
private fun PairingCodeSection(onShowPairingCode: () -> Unit) {
    SectionCard(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            NemaraEyebrow("AWAITING PAIRING")
            Text(
                text = "No wall phone has claimed this sensor yet.",
                style = NemaraType.bodySmall,
                color = Ink2,
            )
            Text(
                text = "Tap below for a 6-digit code, then enter it on the " +
                    "MemoriaSensor wall-phone app to complete pairing.",
                style = NemaraType.caption,
                color = Ink3,
                lineHeight = CaptionLineHeight,
                modifier = Modifier.padding(bottom = Spacing.xxs),
            )
            NemaraButton(
                title = "Show pairing code",
                variant = NemaraButtonVariant.Primary,
                block = true,
                icon = QrCodeVector,
                onClick = onShowPairingCode,
            )
        }
    }
}

/**
 * The Disconnect / Reconnect row. State-aware label and color: destructive
 * going FROM connected, harmless going back.
 */
@Composable
private fun StateControls(
    camera: Camera,
    state: CameraDetailState,
    onDisconnectRequested: () -> Unit,
    onReconnect: () -> Unit,
) {
    val disconnected = camera.desiredState == Camera.DesiredState.DISCONNECTED
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraEyebrow("CONTROLS", modifier = Modifier.padding(horizontal = Spacing.md))
        SectionCard(padding = 0.dp) {
            ControlRow(
                iconName = if (disconnected) {
                    "antenna.radiowaves.left.and.right"
                } else {
                    "wifi.slash"
                },
                title = when {
                    state.isTogglingDisconnect && disconnected -> "Reconnecting…"
                    state.isTogglingDisconnect -> "Disconnecting…"
                    disconnected -> "Reconnect"
                    else -> "Disconnect (privacy)"
                },
                subtitle = if (disconnected) {
                    "Phone rejoins LiveKit (~1-2s)."
                } else {
                    "Phone leaves LiveKit entirely."
                },
                destructive = !disconnected,
                enabled = !state.controlsBusy,
                onClick = { if (disconnected) onReconnect() else onDisconnectRequested() },
            )
        }
        state.actionError?.let { message ->
            Text(
                text = message,
                style = NemaraType.caption,
                color = Terra,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
        }
    }
}

/**
 * Collapsed "Advanced" section holding Pause AI. Collapsed by default: pausing
 * the AI tick is a niche cost lever and sat too prominently beside Disconnect.
 */
@Composable
private fun AdvancedSection(
    camera: Camera,
    state: CameraDetailState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleAiPause: () -> Unit,
) {
    // iOS: withAnimation(.easeInOut(duration: 0.18)) around the flip, whose only
    // animatable effect is the chevron's rotation.
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "advancedChevron",
    )
    SectionCard(padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .plainClickable(onClick = onToggleExpanded)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolTile(iconName = "gearshape")
            Text(
                text = "Advanced",
                style = NemaraType.bodySmallStrong,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            SensorSymbol(
                name = "chevron.down",
                size = 13.dp,
                color = Ink3,
                modifier = Modifier.rotate(rotation),
            )
        }
        if (expanded) {
            HairLine()
            // Pause AI / Resume AI — instant, no confirmation. Lighter than
            // Disconnect: video keeps streaming, only the LLM tick is skipped.
            ControlRow(
                iconName = if (camera.aiPaused) "waveform" else "waveform.slash",
                title = when {
                    state.isTogglingAiPause && camera.aiPaused -> "Resuming AI…"
                    state.isTogglingAiPause -> "Pausing AI…"
                    camera.aiPaused -> "Resume AI"
                    else -> "Pause AI"
                },
                subtitle = if (camera.aiPaused) {
                    "Resume AI tick. Video keeps streaming either way."
                } else {
                    "Pause AI tick. Video keeps streaming."
                },
                destructive = false,
                enabled = !state.controlsBusy,
                onClick = onToggleAiPause,
            )
        }
    }
}

@Composable
private fun ControlRow(
    iconName: String,
    title: String,
    subtitle: String,
    destructive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (destructive) TerraSoft else PaperDim),
            contentAlignment = Alignment.Center,
        ) {
            SensorSymbol(
                name = iconName,
                size = 14.dp,
                color = if (destructive) Terra else Ink2,
                knockout = if (destructive) TerraSoft else PaperDim,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = NemaraType.bodySmallStrong,
                color = if (destructive) Terra else Ink,
            )
            Text(text = subtitle, style = NemaraType.caption, color = Ink3)
        }
        SensorSymbol(name = "chevron.right", size = 13.dp, color = Ink3)
    }
}

/**
 * Per-camera detector toggles. The list is the patient's full detector set; the
 * checkmark means "assigned in `cameras_detectors`". Tapping flips membership
 * for THIS camera only.
 */
@Composable
private fun DetectorsSection(
    state: CameraDetailState,
    onToggleDetector: (Detector) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraEyebrow(
            text = "DETECTORS ON THIS SENSOR",
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        state.assignmentsError?.let { message ->
            Text(
                text = message,
                style = NemaraType.caption,
                color = Terra,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
        }
        val detectors = state.togglableDetectors
        SectionCard(padding = 0.dp) {
            detectors.forEachIndexed { index, detector ->
                DetectorAssignmentRow(
                    detector = detector,
                    isAssigned = state.assignedDetectorIds.contains(detector.id),
                    isBusy = state.togglingDetectorId == detector.id,
                    onToggle = { onToggleDetector(detector) },
                )
                if (index < detectors.size - 1) HairLine()
            }
        }
        Text(
            text = "Toggling here only affects this sensor. Other sensors " +
                "under the same patient are independent. The global " +
                "master switch lives on the detector itself.",
            style = NemaraType.caption,
            color = Ink3,
            modifier = Modifier
                .padding(horizontal = Spacing.md)
                .padding(top = Spacing.xxs),
        )
    }
}

/**
 * One detector row. Busy disables it; so does the detector being off globally,
 * because a per-camera assignment on a globally-off detector does nothing.
 *
 * The 0.55 dim is applied to each drawn element rather than to the row as a
 * layer. SwiftUI's `.opacity` without `.compositingGroup()` applies per leaf, so
 * the tile fill and the glyph on top of it are each faded against the card —
 * compositing the row first and fading the result would come out a different
 * shade.
 */
@Composable
private fun DetectorAssignmentRow(
    detector: Detector,
    isAssigned: Boolean,
    isBusy: Boolean,
    onToggle: () -> Unit,
) {
    val on = detector.isOn
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .plainClickable(enabled = detectorRowEnabled(detector, isBusy), onClick = onToggle)
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(PaperDim.dimmed(on)),
            contentAlignment = Alignment.Center,
        ) {
            SensorSymbol(
                name = detector.iconName,
                size = 14.dp,
                color = Ink2.dimmed(on),
                // What is actually behind the glyph once the tile itself is
                // faded: the tile's fill blended onto the card.
                knockout = if (on) PaperDim else lerp(Card, PaperDim, DIM_ALPHA),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = detector.name,
                    style = NemaraType.bodySmallStrong,
                    color = Ink.dimmed(on),
                )
                // The chips are inside the faded row too. A chip is a fill plus
                // text, and NemaraChip owns both, so this one is faded as a
                // group rather than leaf by leaf — the only place on this screen
                // where that distinction is not reproducible without reaching
                // inside a shared component.
                val chipDim = if (on) Modifier else Modifier.alpha(DIM_ALPHA)
                if (!on) {
                    NemaraChip("OFF GLOBALLY", tone = NemaraChipTone.Neutral, modifier = chipDim)
                }
                // A freshly created custom detector has no identifier until the
                // backend compile lands, about 3-5s.
                if (detector.identifier == null) {
                    NemaraChip("COMPILING…", tone = NemaraChipTone.Warning, modifier = chipDim)
                }
            }
            Text(
                text = detector.description,
                style = NemaraType.caption,
                color = Ink3.dimmed(on),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Terra,
                strokeWidth = 2.dp,
            )
        } else {
            SensorSymbol(
                name = if (isAssigned) "checkmark.circle.fill" else "circle",
                size = 22.dp,
                color = (if (isAssigned) Action else Ink3).dimmed(on),
                knockout = Card,
            )
        }
    }
}

/** iOS: `.opacity(detector.isOn ? 1.0 : 0.55)`. */
private const val DIM_ALPHA = 0.55f

private fun Color.dimmed(enabled: Boolean): Color =
    if (enabled) this else copy(alpha = alpha * DIM_ALPHA)

@Composable
private fun ExitDoorsSection(
    state: CameraDetailState,
    onOpenDoorPicker: () -> Unit,
) {
    val active = state.isWanderingActiveHere
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraEyebrow("WANDERING · EXIT DOORS")
            Spacer(Modifier.weight(1f))
            val tint = if (active) Success else Ink3
            Text(
                text = if (active) "ACTIVE" else "NOT ACTIVE",
                style = NemaraType.monoMicro,
                // iOS: .tracking(0.5). Not NemaraChip: that pill tracks 0.6 and
                // paints a palette tone, where this one tints from its own
                // foreground at 14%.
                letterSpacing = 0.5.sp,
                color = tint,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f))
                    .padding(horizontal = Spacing.xs, vertical = 3.dp),
            )
        }
        SectionCard(padding = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .plainClickable(onClick = onOpenDoorPicker)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    SensorSymbol(name = "door.left.hand.open", size = 15.dp, color = Terra)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Exit doors on this sensor",
                        style = NemaraType.bodySemibold,
                        color = Ink,
                    )
                    Text(
                        text = doorStatusText(state.doorConfig),
                        style = NemaraType.caption,
                        color = Ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (state.doorConfig != null) "Change" else "Set",
                    style = NemaraType.monoSmall,
                    letterSpacing = 0.5.sp,
                    color = Terra,
                )
                SensorSymbol(name = "chevron.right", size = 12.dp, color = Ink3)
            }
        }
        Text(
            text = if (active) {
                "Only someone leaving through these doors counts as wandering on this sensor."
            } else {
                "Wandering is OFF on this sensor until you set its exit doors — pick the " +
                    "door(s) someone would use to leave home in this view."
            },
            style = NemaraType.caption,
            color = Ink3,
            modifier = Modifier
                .padding(horizontal = Spacing.md)
                .padding(top = Spacing.xxs),
        )
    }
}

@Composable
private fun UnpairButton(isUnpairing: Boolean, onRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraEyebrow("PAIRING", modifier = Modifier.padding(horizontal = Spacing.md))
        WideActionRow(
            iconName = "iphone.slash",
            title = if (isUnpairing) "Unpairing…" else "Unpair sensor",
            foreground = Ink,
            background = PaperDim,
            enabled = !isUnpairing,
            onClick = onRequest,
        )
        Text(
            text = "Frees this sensor so a phone can be re-paired to it. " +
                "Keeps the room, detectors, and history.",
            style = NemaraType.caption,
            color = Ink3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        )
    }
}

@Composable
private fun DeleteCameraButton(isDeleting: Boolean, onRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraEyebrow("DANGER ZONE", modifier = Modifier.padding(horizontal = Spacing.md))
        WideActionRow(
            iconName = "trash",
            title = if (isDeleting) "Deleting…" else "Delete sensor",
            foreground = Terra,
            background = TerraSoft,
            enabled = !isDeleting,
            onClick = onRequest,
        )
    }
}

/** The full-width tinted row shared by Unpair and Delete. iOS: radius 11. */
@Composable
private fun WideActionRow(
    iconName: String,
    title: String,
    foreground: Color,
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SensorSymbol(name = iconName, size = 14.dp, color = foreground, knockout = background)
        Text(text = title, style = NemaraType.bodySmallStrong, color = foreground)
    }
}

/**
 * A confirmation with a destructive action. iOS renders the destructive button
 * in the system's red; the palette has no system red, and `Danger` is Terra, so
 * the two buttons are separated by weight and by ink instead — see the report
 * accompanying this unit.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = NemaraType.title3, color = Ink) },
        text = { Text(text = message, style = NemaraType.bodySmall, color = Ink2) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmTitle, style = NemaraType.bodySemibold, color = Terra)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", style = NemaraType.body, color = Ink2)
            }
        },
        containerColor = Card,
    )
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** Every section on this screen is a full-width card on a 16dp gutter. */
@Composable
private fun SectionCard(
    padding: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    NemaraCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        padding = padding,
        content = content,
    )
}

/** The 38dp tile in the header and private-room cards. iOS: radius 11, icon 16. */
@Composable
private fun LargeSymbolTile(iconName: String, background: Color, color: Color) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        SensorSymbol(name = iconName, size = 16.dp, color = color, knockout = background)
    }
}
