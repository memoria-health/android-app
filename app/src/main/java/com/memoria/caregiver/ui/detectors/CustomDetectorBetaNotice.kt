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
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.NemaraType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.memoria.caregiver.ui.theme.TerraInk
import com.memoria.caregiver.ui.theme.Terra

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
 */
@Composable
fun CustomDetectorBetaNotice(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Terra.copy(alpha = 0.12f))
            .padding(12.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NemaraGlyph(
            symbol = "exclamationmark.triangle.fill",
            size = 13.dp,
            color = Terra,
            // Triangle, not the circle its sibling uses, so the two notices
            // are distinguishable when a detector is both custom and
            // fine-detail.
            hole = AmberWashBeta,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(text = CustomBetaCopy, style = NemaraType.caption, color = Ink2)
    }
}

/**
 * Larger BETA badge for sticking onto a button. [NemaraChip] is 9sp mono,
 * which disappears at that size against a full-width control, so this is its
 * own thing rather than a chip with a bigger font. iOS: `BetaBadge`.
 */
@Composable
fun BetaBadge(modifier: Modifier = Modifier) {
    Text(
        text = "BETA",
        style = NemaraType.caption.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
        ),
        color = TerraInk,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Terra)
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics { contentDescription = "Beta feature" },
    )
}

/**
 * `terra` at 12% flattened onto `Card`. The glyph's bang is knocked out of the
 * triangle rather than drawn on it, so it has to be painted in the notice's own
 * background: a translucent colour there would let the triangle show through.
 */
private val AmberWashBeta = Terra.copy(alpha = 0.12f).compositeOver(Card)

internal const val CustomBetaCopy: String =
    "Custom detectors are in beta. Built from your description, so accuracy can vary."
