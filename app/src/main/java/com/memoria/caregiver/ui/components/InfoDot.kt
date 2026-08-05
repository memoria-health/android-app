package com.memoria.caregiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper

/**
 * The house pattern for explanatory text: an ⓘ that reveals a short
 * explanation instead of a permanent caption.
 *
 * Port of `DesignSystem/Components/InfoDot.swift`, introduced by the July
 * declutter. Screens stay readable and the explanation stays one tap away for
 * whoever wants it.
 *
 * ## Why a dialog rather than a popover
 *
 * iOS uses `.popover(arrowEdge: .top)`, which anchors a small panel to the dot
 * and adapts to a sheet on compact widths. Compose has no anchored popover with
 * that behaviour; `Popup` exists but positions itself manually and would need
 * edge-collision handling written by hand for a component used in a dozen
 * places. A small centred dialog carries the same short sentence, dismisses the
 * same way, and — unlike a hand-placed popup — cannot end up half off-screen on
 * a narrow phone. Recorded in DIFFERENCES.md.
 *
 * @param text one or two sentences. This is not a place for paragraphs: the
 *   whole point of the pattern is that the caption was too long to sit on the
 *   screen permanently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoDot(
    text: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Text(
        text = "ⓘ",
        style = NemaraType.bodyMedium,
        color = Ink4,
        modifier = modifier
            // Compose's own clickable with no indication and an explicit Button
            // role, rather than one of the eight `plainClickable` copies that
            // live private to other files. This is the design-system package —
            // reaching into a feature package for a modifier would point the
            // dependency the wrong way. Role.Button is what tells TalkBack the
            // glyph can be tapped; without it the dot is read as text.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { open = true },
            )
            // A bare glyph announces as nothing. Named for what it does, and
            // the explanation itself is NOT put here: a screen reader would
            // then read the whole sentence on every pass over the row, which is
            // the permanent caption this pattern removed.
            .semantics { contentDescription = "More information" },
    )

    if (open) {
        BasicAlertDialog(onDismissRequest = { open = false }) {
            Text(
                text = text,
                style = NemaraType.bodySmall,
                color = Ink2,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Paper)
                    .padding(14.dp),
            )
        }
    }
}
