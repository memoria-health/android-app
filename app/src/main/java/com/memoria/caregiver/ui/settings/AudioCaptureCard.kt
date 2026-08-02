package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraToggle
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Terra

/**
 * Patient-wide audio capture toggle — **the facilities audio kill**. Spec:
 * `Features/Patient/Settings/AudioCaptureCard.swift`, PARITY.md §24.
 *
 * Off means the camera publishers never publish an audio track: severed at the
 * source, so no live sound, no audio in clips, no speech alerts, and listening
 * detectors fall back to visual-only. The flip reaches gateway cameras within
 * seconds via the roster long-poll. Care facilities where audio recording is not
 * permitted keep this OFF.
 *
 * Two things separate it from the other toggle cards and neither may be
 * generalised away (PARITY.md §24):
 *
 * 1. **A failure replaces the body copy, in terra.** There is no separate error
 *    line and no banner — the sentence under the title becomes the error.
 * 2. **[audioCaptureEnabled] defaults to `true` when absent.** A patient row
 *    that predates the column, or a partial decode, must read as "audio on",
 *    because that is the server-side default and showing the toggle off would
 *    tell a caregiver sound is severed when it is not.
 */
@Composable
fun AudioCaptureCard(
    audioCaptureEnabled: Boolean?,
    mutation: CardMutationState,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = audioCaptureEnabled ?: true
    val errorText = mutation.errorText

    NemaraCard(
        modifier = modifier.padding(horizontal = 16.dp),
        padding = 14.dp,
    ) {
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
                if (enabled) {
                    SpeakerWaveGlyph(size = 16.dp, color = Action)
                } else {
                    SpeakerSlashGlyph(size = 16.dp, color = Ink3)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text = "Audio capture", style = NemaraType.bodySemibold, color = Ink)
                Text(
                    text = errorText ?: if (enabled) {
                        "Cameras capture sound for live audio, clips, and speech alerts."
                    } else {
                        "No sound is captured, recorded, or analyzed. Required for care " +
                            "facilities."
                    },
                    style = NemaraType.caption,
                    color = if (errorText == null) Ink3 else Terra,
                    lineHeight = CaptionLineHeight,
                )
            }

            NemaraToggle(
                isOn = enabled,
                onToggle = onEnabledChange,
                track = "audio_capture.toggle",
                enabled = !mutation.busy,
            )
        }
    }
}

/**
 * The sentence the host puts in [CardMutationState.errorText] when the PATCH
 * fails. iOS hard-codes it in `flip(to:)` rather than surfacing
 * `error.localizedDescription`, so it lives with the card and not with whatever
 * makes the call.
 */
const val AUDIO_CAPTURE_ERROR: String = "Couldn't update audio setting. Try again."
