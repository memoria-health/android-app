package com.memoria.caregiver.ui.patient

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.components.PatientAvatar
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim

/**
 * The pinned header on every patient tab. Port of
 * `Features/Patient/PHeader.swift`.
 *
 * Avatar, preferred name, relation, an optional Feedback pill and a Home
 * button. **No back chevron** — the patient bundle is entered as a whole and
 * left through Home, rather than unwound one screen at a time, so a chevron
 * here would offer a second and inconsistent way out.
 *
 * It is pinned rather than scrolled: the caregiver can be several screens deep
 * in a patient's events and still needs to know whose events they are.
 *
 * @param showsIdentity false on the Settings tab, where the profile card
 *   directly below already shows the avatar and name — repeating them in the
 *   bar reads as a rendering bug. Only Feedback and Home remain.
 * @param onFeedback when null the pill is absent. iOS makes it optional the
 *   same way rather than always showing it.
 */
@Composable
fun PatientHeader(
    patient: Patient,
    /**
     * Resolves the patient's photo to a signed URL. Threaded through rather
     * than reached for, exactly as `PatientAvatar` requires — see REQ-22 for
     * why no photo renders yet regardless.
     */
    resolvePhotoUrl: suspend (String) -> String?,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    showsIdentity: Boolean = true,
    onFeedback: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showsIdentity) {
            PatientAvatar(
                patientId = patient.id,
                avatarLetter = patient.avatarLetter,
                photoStorageKey = patient.photoStorageKey,
                resolvePhotoUrl = resolvePhotoUrl,
                size = 36.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = patient.preferredName,
                    // 19sp serif with -0.3 tracking. Not a token: it is used
                    // only here, and DECISIONS.md D-008 keeps tracking out of
                    // the tokens and applies it at the call site.
                    style = NemaraType.title3.copy(
                        fontSize = 19.sp,
                        letterSpacing = (-0.3).sp,
                    ),
                    color = Ink,
                )
                Text(
                    text = patient.relation.displayName,
                    style = NemaraType.caption,
                    color = Ink3,
                )
            }
        }

        // Pushes the buttons to the trailing edge, and is what keeps them there
        // when the identity block is hidden.
        Column(modifier = Modifier.weight(1f)) {}

        if (onFeedback != null) {
            Row(
                modifier = Modifier
                    // 12 here plus the Row's own 12 gives the 24dp gap iOS
                    // leaves, so Feedback and Home cannot be hit together.
                    .padding(end = 12.dp)
                    // Same reasoning as Home: the pill draws at iOS's ~30dp but
                    // must not be a sub-44dp target.
                    .defaultMinSize(minHeight = 44.dp)
                    .background(PaperDim, CircleShape)
                    .border(1.dp, Line, CircleShape)
                    .clickable(onClick = onFeedback)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .semantics { contentDescription = "Give feedback" },
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = FeedbackBubble,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(11.dp),
                )
                // 13sp semibold Inter — the existing bodySmallStrong token.
                Text(text = "Feedback", style = NemaraType.bodySmallStrong, color = Ink)
            }
        }

        // The glyph is 18dp to match iOS, but the TARGET is 44dp.
        //
        // `Modifier.clickable` on a bare Icon gets no minimum-size expansion, so
        // this was an 18x18dp hit rect — and it is the only way out of the
        // patient bundle, because the design deliberately has no back chevron.
        // iOS's `Button { Image(...) }` gets UIKit's 44pt minimum for free.
        // 44dp rather than Android's 48dp guideline so the header keeps iOS's
        // height; the guideline's purpose is served either way.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onHome)
                .semantics { contentDescription = "Home" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HomeHouse,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
