package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing

/**
 * Line height for the 14sp Inter body copy that carries SwiftUI's
 * `.lineSpacing(3)`.
 *
 * Same derivation as [CaptionLineHeight]: Inter declares ascent 1984, descent
 * -494 and zero line gap over 2048 units per em, a natural 1.2100 x the point
 * size, so 14 x 1.2100 = 16.94, plus 3 = 19.94. The same knowingly-accepted
 * difference applies — SwiftUI's line spacing only opens the gaps *between*
 * lines, while a Compose `lineHeight` also sizes the first line's box.
 */
private val ConsentBodyLineHeight = 19.94.sp

/**
 * The sentence a caregiver is agreeing to, with the part that says what is
 * actually stored in bold.
 *
 * The Swift hands SwiftUI one markdown string
 * (`**securely store video and audio clips of \(patientName)**`) and warns that
 * it must stay a single literal or SwiftUI stops rendering the bold. Compose's
 * `Text` does not parse markdown at all, so the same sentence is assembled as an
 * `AnnotatedString` and that warning does not carry over — the `+` below joins
 * two literals of plain prose, not markdown.
 *
 * **The words and punctuation are the iOS copy, character for character.** This
 * is the record of what consent was given for, so it is not paraphrased,
 * shortened or re-toned, and the emphasis is carried because the emphasised span
 * is the disclosure itself.
 *
 * Internal so a test can pin the rendered sentence without re-stating the spans.
 */
internal fun dataCollectionConsentBody(patientName: String): AnnotatedString =
    buildAnnotatedString {
        append("With your permission, Memoria will ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("securely store video and audio clips of $patientName")
        }
        append(
            " to help improve our understanding of elderly health and disease.\n\n" +
                "This is optional, never required, and you can turn it off anytime in " +
                "Settings.",
        )
    }

/**
 * First-open consent sheet asking the caregiver to opt this patient into
 * Memoria's research data-collection program. Spec:
 * `Features/Patient/Settings/DataCollectionConsentSheet.swift`, PARITY.md §24,
 * FINDINGS.md F-14 / I-5.
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. **It does not own the
 * once-only latch**: `PatientSettingsViewModel.showDataCollectionConsent` decides
 * when this is presented (raised once, while `dataCollectionDecidedAt == null`),
 * exactly as `PatientShell`'s `.onAppear` does on iOS.
 *
 * ## The two choices, and the third way out
 *
 * "Share recordings" sends `true`, "Not now" sends `false`. **Both are a
 * decision**: the backend stamps `data_collection_decided_at` on either value, so
 * either one stops the prompt coming back. They are the only two controls on this
 * sheet — there is no "Close", no "Skip", and nothing here records anything on
 * its own.
 *
 * iOS presents this with a plain `.sheet(isPresented:)` and
 * `.presentationDetents([.medium, .large])`, and deliberately **without**
 * `.interactiveDismissDisabled()` — so a swipe-down does dismiss it. A swipe-down
 * sends no request, so nothing is decided: `decided_at` stays null and
 * `data_collection_enabled` stays at the server default, which is **off**. That
 * is the property that matters here and it must be reproduced exactly:
 * *dismissing is not consent, it is the absence of an answer.*
 *
 * **What the host owes this sheet**, therefore:
 *
 * - present it in a dismissible `ModalBottomSheet` whose `onDismissRequest` is
 *   `PatientSettingsViewModel.dismissDataCollectionConsent()`, which sends
 *   nothing — never `onDecide(true)`, and not `onDecide(false)` either, because
 *   recording "no" for someone who only swiped is still recording a decision they
 *   did not make;
 * - present it with `dragHandle = null`, as `CountryPicker` and `SupportButton`
 *   already do — the grabber below is drawn here, matching the Swift's own
 *   `Capsule()`. See `AddCameraScreen`'s `SheetGrabber`.
 *
 * ## Layout, and the one place it does not copy the Swift
 *
 * iOS puts a `Spacer(minLength: 8)` either side of the body copy; those expand
 * only because the detent fixes the sheet's height. An Android bottom sheet hugs
 * its content instead, so they are laid out at the 8 the Swift declares as the
 * minimum, and the column scrolls if the copy ever outgrows the screen (a large
 * system font scale, a long name). The alternative — a full-height column with
 * weighted spacers — pushes the two buttons off the bottom in exactly that case,
 * and a consent prompt whose only remaining exit is a swipe is the failure this
 * whole screen exists to avoid.
 *
 * ## Logging
 *
 * Nothing on this path logs anything about the patient. The two `track` labels
 * are the fixed iOS strings, which is what `NemaraButton` reports instead of the
 * button title; neither carries the patient's name, and the decision itself is
 * recorded by the PATCH, not by analytics.
 *
 * @param patientName the patient's preferred name, interpolated into the body
 *   copy — iOS passes `patient.preferredName`; `patientDisplayName` is the
 *   caller-side fallback.
 * @param mutation the in-flight PATCH. `busy` makes both buttons inert, matching
 *   `.disabled(busy)`, so a double tap cannot queue two conflicting decisions;
 *   `errorText` is the failure, shown *with the sheet still up* rather than
 *   letting a failed request pass for a recorded answer.
 * @param onDecide `true` for "Share recordings", `false` for "Not now". One
 *   callback rather than two because there is one PATCH path —
 *   `PatientSettingsViewModel::setDataCollectionEnabled`, shared with
 *   [DataCollectionCard] — and it lowers the latch only when the write succeeds.
 */
@Composable
fun DataCollectionConsentSheet(
    patientName: String,
    mutation: CardMutationState,
    onDecide: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Paper)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(top = Spacing.xs)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Line),
        )

        Column(
            modifier = Modifier
                .padding(top = Spacing.xs)
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NemaraEyebrow("DATA COLLECTION")
            Text(
                text = "Help improve care for everyone.",
                style = NemaraType.title3,
                color = Ink,
                textAlign = TextAlign.Center,
            )
        }

        // iOS: Spacer(minLength: 8). See the layout note above.
        Spacer(Modifier.height(Spacing.xs))

        Text(
            text = dataCollectionConsentBody(patientName),
            modifier = Modifier.padding(horizontal = Spacing.xl),
            style = NemaraType.body,
            color = Ink2,
            lineHeight = ConsentBodyLineHeight,
            textAlign = TextAlign.Center,
        )

        // The failure sits under the copy and above the buttons, so the choice
        // the caregiver came here to make is still in front of them.
        mutation.errorText?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = Spacing.xl),
                style = NemaraType.caption,
                color = Danger,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(Spacing.xs))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NemaraButton(
                title = "Share recordings",
                variant = NemaraButtonVariant.Primary,
                size = NemaraButtonSize.Lg,
                block = true,
                track = "data_collection.consent_share",
                enabled = !mutation.busy,
                onClick = { onDecide(true) },
            )

            NemaraButton(
                title = "Not now",
                variant = NemaraButtonVariant.Secondary,
                size = NemaraButtonSize.Lg,
                block = true,
                track = "data_collection.consent_decline",
                enabled = !mutation.busy,
                onClick = { onDecide(false) },
            )
        }
    }
}
