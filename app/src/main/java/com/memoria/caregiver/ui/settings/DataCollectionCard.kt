package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraToggle
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim

/**
 * Per-patient research data-collection toggle. Spec:
 * `Features/Patient/Settings/DataCollectionCard.swift`, PARITY.md §24.
 *
 * How a caregiver changes their mind after the first-open consent sheet has
 * recorded a decision. Every flip PATCHes `data_collection_enabled`; the API
 * client writes the canonical row back, so [patient] re-derives and the toggle
 * snaps back on failure because it is bound to the (unchanged) server state.
 *
 * Two details that look like slips and are not:
 *
 * - **Default is `false`**, unlike `AudioCaptureCard`'s `true`. Contributing
 *   recordings to research is opt-in; an absent value must never read as
 *   consent.
 * - **The icon does not change with the state**, unlike the other two toggle
 *   cards — only its tint does. There is one symbol,
 *   `waveform.badge.magnifyingglass`.
 *
 * The error renders on its own line below the row, in `Danger` (the semantic
 * token, which happens to share Terra's value), not in place of the body copy.
 * That is the opposite of `AudioCaptureCard` and is why the two are not one
 * component.
 */
@Composable
fun DataCollectionCard(
    patient: Patient?,
    mutation: CardMutationState,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = patient?.dataCollectionEnabled ?: false
    val name = patient?.preferredName ?: "this patient"

    NemaraCard(
        modifier = modifier.padding(horizontal = 16.dp),
        padding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (enabled) ActionSoft else PaperDim),
                    contentAlignment = Alignment.Center,
                ) {
                    WaveformMagnifierGlyph(
                        size = 16.dp,
                        color = if (enabled) Action else Ink3,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "Contribute recordings to research",
                        style = NemaraType.bodySemibold,
                        color = Ink,
                    )
                    // Cameras in private rooms are excluded from research clips
                    // server-side (per-camera privacy), so the toggle is never
                    // locked by a patient-level privacy flag.
                    //
                    // The on/off copy differs by one word — "is securely
                    // storing" vs "will securely store". Present tense once it
                    // is actually happening.
                    Text(
                        text = if (enabled) {
                            "Memoria is securely storing video and audio clips of $name " +
                                "to help improve our understanding of elderly health and " +
                                "disease."
                        } else {
                            "Memoria will securely store video and audio clips of $name " +
                                "to help improve our understanding of elderly health and " +
                                "disease."
                        },
                        style = NemaraType.caption,
                        color = Ink3,
                        lineHeight = CaptionLineHeight,
                    )
                }

                NemaraToggle(
                    isOn = enabled,
                    onToggle = onEnabledChange,
                    track = "data_collection.toggle",
                    enabled = !mutation.busy,
                )
            }

            mutation.errorText?.let { message ->
                Text(
                    text = message,
                    style = NemaraType.caption,
                    color = Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
