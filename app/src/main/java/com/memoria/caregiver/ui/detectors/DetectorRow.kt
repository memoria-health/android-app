package com.memoria.caregiver.ui.detectors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.components.NemaraToggle
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraDeep
import com.memoria.caregiver.ui.theme.TerraSoft
import com.memoria.caregiver.ui.theme.Warning

/**
 * One row in the patient's detector list: icon tile, name, description, a
 * where·when pill, and the on/off toggle, with an overflow menu for Edit and
 * Delete. Spec: Features/Patient/Settings/DetectorRow.swift, PARITY.md §21.
 *
 * **Sensitivity is not here.** It moved into the detail sheet, matching the web
 * dashboard. What stayed behind is the wandering activation hint, because
 * wandering being "on" does not mean it is watching anything until that sensor's
 * exit doors are set.
 *
 * Two pieces of state are deliberately local rather than hoisted: whether the
 * overflow menu is open, and whether the delete confirmation is showing. Both
 * are transient presentation state that no caller can act on, and hoisting them
 * would make every list owner carry a per-row map of menu flags. The delete
 * itself, and the `isDeleting` that disables the menu while it runs, do come
 * from [state].
 *
 * @param onLoadDoorConfigs fired for the wandering detector while it is on, to
 *   populate [DetectorRowState.doorConfigs]. Keyed on
 *   [DetectorRowState.doorPrefetchKey] so flipping the toggle refetches, exactly
 *   like the iOS `.task(id:)`.
 */
@Composable
fun DetectorRow(
    state: DetectorRowState,
    modifier: Modifier = Modifier,
    onToggleOn: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLoadDoorConfigs: () -> Unit,
) {
    val detector = state.detector
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.doorPrefetchKey) {
        if (state.isWandering && detector.isOn) onLoadDoorConfigs()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // .top, so the icon tile stays level with the name when the
            // description wraps.
            verticalAlignment = Alignment.Top,
        ) {
            CategoryIconTile(detector)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(detector.name, style = NemaraType.bodySmallStrong, color = Ink)
                Text(
                    text = detector.description,
                    style = NemaraType.caption,
                    color = Ink2,
                    // iOS: .lineSpacing(3) on 11pt — the font's own line height
                    // (~1.21x) plus 3, expressed absolutely for Compose.
                    lineHeight = 16.3.sp,
                )
                WhereWhenPill(
                    rooms = detector.roomsLabel,
                    whenLabel = state.whenLabel,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (detector.showsAccuracyCaveat) {
                    AccuracyCaveatNotice(compact = true, modifier = Modifier.padding(top = 4.dp))
                }
            }

            NemaraToggle(
                isOn = detector.isOn,
                onToggle = onToggleOn,
                track = "detector.toggle",
            )

            OverflowMenu(
                expanded = menuOpen,
                enabled = !state.isDeleting,
                onExpandedChange = { menuOpen = it },
                onEdit = onEdit,
                onConfirmDelete = { confirmingDelete = true },
            )
        }

        if (state.showsWanderingHint) {
            WanderingActivationHint(
                text = state.wanderingHintText,
                isActive = state.activeSensorCount > 0,
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp),
            )
        }

        if (!state.isLast) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(state.deleteConfirmTitle, style = NemaraType.bodySemibold, color = Ink) },
            text = {
                Text(
                    text = "This removes the detector for this patient. Any in-flight clips " +
                        "that already fired stay in the events feed.",
                    style = NemaraType.bodySmall,
                    color = Ink2,
                )
            },
            // Destructive on the confirm side, matching both the iOS button
            // roles and the Android convention of confirm-on-the-right.
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text("Delete", style = NemaraType.bodySmallStrong, color = Terra)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("Cancel", style = NemaraType.bodySmall, color = Ink2)
                }
            },
            containerColor = Card,
        )
    }
}

/** Test handle for the overflow button, which carries no text to select by. */
const val DetectorRowMenuTestTag: String = "detector.row.menu"

/**
 * 32dp tile, radius 9. **The tint carries the category** — terra for safety, sky
 * for routine — which is why the row has no separate category pill.
 */
@Composable
private fun CategoryIconTile(detector: Detector) {
    val isSafety = detector.category == Detector.Category.SAFETY
    val fill = if (isSafety) TerraSoft else SkySoft
    Box(
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        NemaraGlyph(
            symbol = detector.iconName,
            size = 14.dp,
            color = if (isSafety) TerraDeep else Sky,
            hole = fill,
        )
    }
}

/**
 * One where·when pill. The trigger label was dropped to keep the row minimal; it
 * lives in the detail sheet.
 */
@Composable
private fun WhereWhenPill(rooms: String, whenLabel: String, modifier: Modifier = Modifier) {
    // iOS: NemaraFont.mono(size: 9.5, weight: .semibold), which is between the
    // monoSmall (10) and monoMicro (9) tokens, so the size is set at this call
    // site on the SemiBold mono style.
    val style = NemaraType.monoSmall.copy(fontSize = 9.5.sp)
    Row(
        modifier = modifier
            .border(1.dp, Line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(rooms, style = style, letterSpacing = 0.5.sp, color = Ink3)
        // The separator is Line, not Ink3 — it recedes behind the two values.
        Text("·", style = style, letterSpacing = 0.5.sp, color = Line)
        Text(whenLabel, style = style, letterSpacing = 0.5.sp, color = Ink3)
    }
}

@Composable
private fun OverflowMenu(
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    Box {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(DetectorRowMenuTestTag)
                .plainClickable(enabled = enabled) { onExpandedChange(true) },
            contentAlignment = Alignment.Center,
        ) {
            NemaraGlyph(symbol = "ellipsis", size = 14.dp, color = Ink3)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text("Edit detector", style = NemaraType.bodySmall, color = Ink) },
                leadingIcon = { NemaraGlyph("slider.horizontal.3", 16.dp, Ink) },
                onClick = { onExpandedChange(false); onEdit() },
            )
            DropdownMenuItem(
                // iOS marks this `.destructive`, which renders red. Terra is the
                // app's danger token.
                text = { Text("Delete detector", style = NemaraType.bodySmall, color = Terra) },
                leadingIcon = { NemaraGlyph("trash", 16.dp, Terra) },
                onClick = { onExpandedChange(false); onConfirmDelete() },
            )
        }
    }
}

/**
 * Wandering only runs on a sensor once that sensor's exit doors are set, so "on"
 * alone would imply it is watching when it is not. Green once any sensor is
 * armed, amber while none is.
 */
@Composable
private fun WanderingActivationHint(text: String, isActive: Boolean, modifier: Modifier = Modifier) {
    val tint = if (isActive) Success else Warning
    val wash = tint.copy(alpha = 0.10f).compositeOver(Card)
    Row(
        // The 44 start inset lines the hint up with the row's text column, and it
        // sits OUTSIDE the tinted box.
        modifier = modifier
            .padding(start = 44.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(wash)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // .top, so the icon lines up with the first line when the text wraps.
        verticalAlignment = Alignment.Top,
    ) {
        NemaraGlyph(
            symbol = if (isActive) "checkmark.circle.fill" else "exclamationmark.triangle.fill",
            size = 12.dp,
            color = tint,
            hole = wash,
        )
        Text(text, style = NemaraType.caption, color = Ink2)
    }
}

