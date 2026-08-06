package com.memoria.caregiver.ui.detectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Warning

/**
 * Beta marker for CUSTOM detectors — the ones a caregiver wrote themselves, as
 * opposed to the presets we tuned and tested.
 * Spec: Features/Patient/Detectors/CustomDetectorBetaNotice.swift.
 *
 * Deliberately separate from [AccuracyCaveatNotice], which answers a different
 * question. That one is about the SUBJECT being hard to see (fine detail, small
 * motion) and is server-computed per detector. This one is about the DETECTOR
 * being newly generated from a sentence the caregiver typed, which we have
 * never evaluated. A detector can be both, so the two may appear together.
 *
 * `compact` is the one-line caption for dense lists; the full notice is the
 * default (create review + detail sheet).
 */
@Composable
fun CustomDetectorBetaNotice(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .then(
                if (compact) {
                    Modifier
                } else {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Warning.copy(alpha = 0.12f))
                        .padding(12.dp)
                }
            )
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NemaraGlyph(
            symbol = "exclamationmark.triangle.fill",
            size = 13.dp,
            color = Warning,
            // Triangle, not the circle its sibling uses, so the two
            // notices are distinguishable at a glance when a detector
            // is both custom and fine-detail.
            hole = if (compact) Card else AmberWashBeta,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = if (compact) CustomBetaShort else CustomBetaFull,
            style = NemaraType.caption,
            color = if (compact) Warning else Ink2,
        )
    }
}

/** The small BETA chip that sits beside a custom detector's name. */
@Composable
fun BetaChip(modifier: Modifier = Modifier) {
    Text(
        text = "BETA",
        style = NemaraType.caption.copy(fontSize = 9.sp),
        color = Warning,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Warning.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .semantics { contentDescription = "Beta detector" },
    )
}

/** `warning` at 12% flattened onto `Card`, matching [AccuracyCaveatNotice]. */
private val AmberWashBeta = Warning.copy(alpha = 0.12f).compositeOver(Card)

internal const val CustomBetaFull: String =
    "Custom detectors are in beta. This one was built from your own " +
        "description, so it has not been tested the way our built-in " +
        "detectors have and its accuracy can vary. Treat any alert as a " +
        "reason to check in rather than something to rely on."

internal const val CustomBetaShort: String =
    "Beta. Built from your description, so accuracy can vary."
