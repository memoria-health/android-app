package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraToggle
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim

/**
 * "Evaluation mode" — the caregiver-facing face of the event gate. Spec:
 * `Features/Patient/Settings/EvaluationModeCard.swift`.
 *
 * When on, this patient's alerts are held for a few seconds and a Memoria
 * specialist sees a masked clip and can drop false alarms before the family
 * is told. Every flip PATCHes `event_gate_enabled`.
 *
 * Two details that look like slips and are not:
 *
 * - **Default is `true`**, unlike [DataCollectionCard]'s `false`. Evaluation
 *   mode is on by default, so an absent value must not render as off and tell
 *   the caregiver something untrue about their own account. Data collection is
 *   the opposite case: absent consent must never read as consent.
 * - **The info button opens a full sheet, not the usual `InfoDot` popover.**
 *   The explanation has to carry an image and enough text to inform a real
 *   decision, because for most households it is the only place they learn a
 *   person sees masked footage of their home.
 */
@Composable
fun EvaluationModeCard(
    patient: Patient?,
    mutation: CardMutationState,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = patient?.eventGateEnabled ?: true
    var showInfo by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Sits ABOVE the card so the row itself keeps the same shape as
        // every other settings toggle.
        Text(
            text = "RECOMMENDED",
            style = NemaraType.eyebrow,
            color = Action,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ActionSoft)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )

        NemaraCard(padding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (enabled) ActionSoft else PaperDim),
                        contentAlignment = Alignment.Center,
                    ) {
                        LockShieldGlyph(
                            size = 16.dp,
                            color = if (enabled) Action else Ink3,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Evaluation mode",
                                style = NemaraType.bodySemibold,
                                color = Ink,
                            )
                            Text(
                                text = "ⓘ",
                                style = NemaraType.bodySmall,
                                color = Ink4,
                                modifier = Modifier
                                    .clickable { showInfo = true }
                                    .semantics {
                                        contentDescription =
                                            "About evaluation mode"
                                    },
                            )
                        }
                    }

                    NemaraToggle(
                        isOn = enabled,
                        onToggle = onEnabledChange,
                        track = "evaluation_mode.toggle",
                        enabled = !mutation.busy,
                    )
                }

                mutation.errorText?.let { message ->
                    Text(text = message, style = NemaraType.caption, color = Danger)
                }
            }
        }
    }

    if (showInfo) {
        EvaluationModeInfoSheet(onDismiss = { showInfo = false })
    }
}
