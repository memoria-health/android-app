package com.memoria.caregiver.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraChip
import com.memoria.caregiver.ui.components.NemaraChipTone
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Spacing

/**
 * The "Cameras" sub-tab: every paired sensor under one patient, its live status,
 * and the way into the per-camera detail screen and the pair-a-new-phone flow.
 * Spec: `Features/Patient/Cameras/PatientCamerasView.swift`.
 *
 * Pure state-in / callbacks-out, per AuthUiState.kt. Two things iOS does inline
 * that are the host's here, because the navigation graph is not this file's to
 * own: pushing the detail screen ([onSelectCamera] carries the camera id, which
 * is what iOS's `NavigationLink(value:)` does), and presenting the add-sensor
 * sheet. The Swift attaches `.trackScreen("add_camera")` to that sheet, so
 * whoever presents it owns that event; there is deliberately no screen event
 * here, because `PatientCamerasView` itself has none.
 *
 * @param onAddSensor "Add a sensor". iOS: `showingAddCamera = true`.
 */
@Composable
fun CameraListScreen(
    state: CameraListState,
    modifier: Modifier = Modifier,
    onSelectCamera: (String) -> Unit,
    onAddSensor: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(top = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        if (state.cameras.isEmpty()) {
            EmptyCard()
        } else {
            CameraList(cameras = state.cameras, onSelectCamera = onSelectCamera)
        }
        NemaraButton(
            title = "Add a sensor",
            variant = NemaraButtonVariant.Secondary,
            block = true,
            icon = PlusVector,
            onClick = onAddSensor,
            modifier = Modifier
                .padding(horizontal = Spacing.md)
                // Clears the floating tab bar so the last row is not hidden
                // behind it. iOS: .padding(.bottom, 120).
                .padding(bottom = TabBarClearance),
        )
    }
}

/**
 * iOS: `VStack(spacing: 14)`. Between Spacing.sm (12) and Spacing.md (16), so it
 * stays a literal.
 */
internal val CardGap = 14.dp

/** iOS: `.padding(.bottom, 120)` — room for the floating tab bar. */
internal val TabBarClearance = 120.dp

@Composable
private fun EmptyCard() {
    NemaraCard(
        // SwiftUI proposes the container's width to the card's content, and the
        // caption inside carries `.fixedSize(horizontal: false, vertical: true)`
        // — it takes the full width and wraps. NemaraCard sizes to its content
        // on both platforms, so the width has to be asked for here.
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        padding = 18.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "No sensors yet", style = NemaraType.bodySemibold, color = Ink)
            Text(
                // SwiftUI renders `**Add a sensor**` in a Text as markdown, so
                // the button's name is bold in the sentence. Carried, because
                // that emphasis is what points the caregiver at the control
                // directly below.
                text = buildAnnotatedString {
                    append("Pair a Memoria Sensor from ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Add a sensor") }
                    append(" to start monitoring.")
                },
                style = NemaraType.caption,
                color = Ink3,
                lineHeight = CaptionLineHeight,
            )
        }
    }
}

/**
 * Caption line height, 15.31sp.
 *
 * SwiftUI's `.lineSpacing(2)` adds 2 points to the font's own default gap;
 * Compose's `lineHeight` is absolute, so the default is computed from the
 * shipped font and 2 added. Inter 18pt declares ascent 1984, descent -494 and
 * zero line gap over 2048 units per em, a natural 1.21 x the point size:
 * 11 x 1.21 = 13.31, plus 2 = 15.31. Same derivation as WelcomeScreen.kt's.
 */
internal val CaptionLineHeight = 15.31.sp

@Composable
private fun CameraList(cameras: List<Camera>, onSelectCamera: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraEyebrow(
            text = "SENSORS · ${cameras.size} PAIRED",
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        NemaraCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            padding = 0.dp,
        ) {
            cameras.forEachIndexed { index, camera ->
                CameraRow(camera = camera, onClick = { onSelectCamera(camera.id) })
                if (index < cameras.size - 1) HairLine()
            }
        }
    }
}

/** iOS: `NemaraColor.line.frame(height: 1)` between rows. */
@Composable
internal fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/**
 * One row. Live status comes from the canonical `online` flag, which is reliable
 * for paired rows — see Camera.swift for why it is not the visibility filter on
 * the Live tab.
 */
@Composable
private fun CameraRow(camera: Camera, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .plainClickable(onClick = onClick)
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolTile(iconName = camera.iconName)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = camera.room, style = NemaraType.bodySmallStrong, color = Ink)
            Text(
                text = cameraStatusLine(camera),
                style = NemaraType.monoSmall,
                color = Ink3,
            )
        }
        RowBadges(camera)
        SensorSymbol(name = "chevron.right", size = 13.dp, color = Ink3)
    }
}

/** The 32dp rounded tile behind a row's icon. iOS: radius 9, `paperDim`. */
@Composable
internal fun SymbolTile(iconName: String) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(PaperDim),
        contentAlignment = Alignment.Center,
    ) {
        SensorSymbol(name = iconName, size = 14.dp, color = Ink2, knockout = PaperDim)
    }
}

@Composable
private fun RowBadges(camera: Camera) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cameraRowBadges(camera).forEach { badge ->
            when (badge) {
                CameraRowBadge.UNPAIRED -> NemaraChip("UNPAIRED", tone = NemaraChipTone.Terra)
                CameraRowBadge.APP_CLOSED -> NemaraChip("APP CLOSED", tone = NemaraChipTone.Warning)
                CameraRowBadge.DISCONNECTED_DOT -> Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Danger)
                        .semantics { contentDescription = "Disconnected" },
                )
                CameraRowBadge.HIDDEN -> NemaraChip("HIDDEN", tone = NemaraChipTone.Warning)
                CameraRowBadge.AI_PAUSED -> NemaraChip("AI PAUSED", tone = NemaraChipTone.Neutral)
            }
        }
    }
}

/**
 * Tap with no ripple and no indication — SwiftUI's `.buttonStyle(.plain)`, which
 * every row on both camera screens uses.
 */
@Composable
internal fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        // Every one of these is a SwiftUI `Button` on iOS, and a button tells a
        // screen reader that it is a button. Without a role TalkBack reads the
        // label as ordinary text: the caregiver hears the words and is never
        // told they can be tapped. Set inside the helper rather than at each
        // call site so a new caller cannot be added without it. Controls that
        // are a CHOICE rather than an action use `plainSelectable` or
        // `plainToggleable`, which carry the role their state needs.
        role = Role.Button,
        onClick = onClick,
    )
}
