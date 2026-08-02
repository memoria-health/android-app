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
import com.memoria.caregiver.ui.theme.Warning

/**
 * Expectations notice for fine-detail detectors — small motion like a seizure or
 * choking, small objects like medication or a stove.
 * Spec: Features/Patient/Detectors/AccuracyCaveatNotice.swift.
 *
 * Shown at the enroll moment and in the detail sheet so a caregiver knows
 * accuracy varies with distance and lighting BEFORE they rely on it. Driven by
 * the server-computed `showsAccuracyCaveat` on both `Detector` and
 * `DetectorLibraryEntry`.
 *
 * Amber "heads up" styling so it stands out, but it only sets expectations — it
 * is not an alarm and not an error.
 *
 * `compact` is the one-line caption for dense lists (the library rows and the
 * detector rows); the full notice is the default.
 */
@Composable
fun AccuracyCaveatNotice(
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
                        // iOS: NemaraColor.warning.opacity(0.12). Composited
                        // against the parent rather than applied as a layer
                        // alpha, so the text on top stays fully opaque.
                        .background(Warning.copy(alpha = 0.12f))
                        .padding(12.dp)
                }
            )
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NemaraGlyph(
            symbol = "exclamationmark.circle.fill",
            size = 13.dp,
            color = Warning,
            // The bang is knocked out of the disc, so it has to be painted in
            // whatever sits behind: the amber wash in the full notice, and the
            // card surface the compact line always sits on.
            hole = if (compact) Card else AmberWash,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = if (compact) AccuracyCaveatShort else AccuracyCaveatFull,
            style = NemaraType.caption,
            color = if (compact) Warning else Ink2,
        )
    }
}

/**
 * `warning` at 12% flattened onto `Card`. The glyph's bang is knocked out of the
 * disc rather than drawn on it, so it has to be painted in the notice's own
 * background — a translucent colour there would let the disc show through.
 */
private val AmberWash = Warning.copy(alpha = 0.12f).compositeOver(Card)

internal const val AccuracyCaveatFull: String =
    "This detector reads small, fine details, so how reliably it works " +
        "depends on how far away the camera is, the lighting, and the angle. " +
        "It can miss events or misread them. Treat any alert as a reason to " +
        "check in, not a guarantee that something did or did not happen."

internal const val AccuracyCaveatShort: String =
    "Reads fine detail. Accuracy depends on camera distance, lighting, and angle."
