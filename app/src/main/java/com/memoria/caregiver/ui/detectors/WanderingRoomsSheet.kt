package com.memoria.caregiver.ui.detectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra

/**
 * "Which cameras should watch for wandering?" — asked once, right after the
 * wandering detector is added.
 *
 * Port of `WanderingRoomsSheet` in `DetectorLibraryView.swift`.
 *
 * ## Why this exists at all
 *
 * Wandering is the one preset that is per-camera: it watches doors that lead
 * outside, and which room those are in is something only the caregiver knows.
 * The answer sets `where_rooms`, which then decides where the exit-door setup
 * section appears (`wanderingWatchesRoom`).
 *
 * Asking here is the point of it. Afterwards this is a settings screen the
 * caregiver has no reason to open, so the scope would stay at "everywhere" and
 * the doors section would appear on every camera — which is exactly the noise
 * the feature was added to remove.
 *
 * Rendered as a full-screen step rather than iOS's `List`-in-a-sheet, matching
 * how every other modal in this app is presented (DIFFERENCES.md D9).
 */
@Composable
fun WanderingRoomsSheet(
    rooms: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Wandering cameras",
                style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "Which cameras should watch for wandering?",
                style = NemaraType.bodyLarge,
                color = Ink,
                modifier = Modifier.padding(top = Spacing.sm),
            )

            rooms.forEach { room ->
                val isSelected = room in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // toggleable, not clickable: TalkBack has to announce
                        // the checked state, not just that a row exists.
                        // Same rule as NemaraToggle (FINDINGS.md F-18).
                        .toggleable(
                            value = isSelected,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(room) },
                        )
                        .padding(vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = room, style = NemaraType.bodyLarge, color = Ink)
                    if (isSelected) {
                        Text(
                            text = "✓",
                            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Terra,
                        )
                    }
                }
            }

            Text(
                text = "Exit-door setup will only appear on these cameras. " +
                    "Leave empty for all cameras.",
                style = NemaraType.bodySmall,
                color = Ink3,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        }

        Box(modifier = Modifier.padding(Spacing.md)) {
            // Always enabled. An empty selection is a valid answer — it means
            // every camera, which is what the footer says and what a null
            // `where_rooms` already encodes.
            NemaraButton(title = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
        }
    }
}
