package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.components.NemaraAvatar
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraCardTone
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.PatientAvatar
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * Line height for the 11sp Inter body copy that carries SwiftUI's
 * `.lineSpacing(2)` — used by all four cards and by the privacy footnote.
 *
 * Same derivation as `WelcomeScreen.SubheadLineHeight`: Inter declares ascent
 * 1984, descent -494 and zero line gap over 2048 units per em, a natural 1.2100
 * x the point size, so 11 x 1.2100 = 13.31, plus 2 = 15.31.
 *
 * The knowingly-accepted difference is the same one recorded there: SwiftUI's
 * line spacing only affects the gaps *between* lines, while a Compose
 * `lineHeight` also sizes the first line's box, adding about one scaled pixel
 * above and below a wrapped block.
 */
internal val CaptionLineHeight = 15.31.sp

/**
 * The patient "Settings" sub-tab. Spec:
 * `Features/Patient/Settings/PatientSettingsView.swift`, PARITY.md §24.
 *
 * Profile, face-enrollment status, email alerts, privacy, data collection,
 * feedback entry, remove patient. Monitoring (pause/resume all cameras) is
 * **not** here — it is owned solely by the Live tab's "Disconnect all cameras"
 * button, because both drove the same patient-level pause flag and the duplicate
 * Settings toggle was removed. The detectors and cameras lists moved to their
 * own tabs for the same reason.
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. Everything asynchronous —
 * the photo picker, the PATCHes, the enrollment poll ([pollFaceEnrollmentSummary]),
 * the delete — belongs to whatever hosts this.
 *
 * **Screen tracking is deliberately absent.** iOS attaches
 * `.trackScreen("patient.settings")` in `PatientShell`, not in this view, unlike
 * the auth screens which track themselves. The host owns it.
 */
@Composable
fun PatientSettingsScreen(
    state: PatientSettingsState,
    resolvePhotoUrl: suspend (String) -> String?,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onEnroll: () -> Unit,
    onEmailAlertsEnabledChange: (Boolean) -> Unit,
    onEmailDraftChange: (String) -> Unit,
    onEmailRecipientsChange: (List<String>) -> Unit,
    onEmailValidationError: (String) -> Unit,
    onSendTestEmail: () -> Unit,
    onPeopleBlurChange: (Boolean) -> Unit,
    onAudioCaptureChange: (Boolean) -> Unit,
    onDataCollectionChange: (Boolean) -> Unit,
    onEvaluationModeChange: (Boolean) -> Unit,
    onOpenFeedback: () -> Unit,
    onRemovePatient: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two-step (confirm + execute) so a single mis-tap cannot cascade-delete a
    // patient's entire history. Local, like iOS's `@State`: it is transient UI
    // state with no I/O behind it.
    var showingDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        // SwiftUI's VStack centres by default; Compose's Column starts. Every
        // card fills the width so it makes no difference to them, but the
        // wrap-width "Remove this patient" button at the bottom would sit
        // hard-left without this.
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileCard(
            state = state,
            resolvePhotoUrl = resolvePhotoUrl,
            onPickPhoto = onPickPhoto,
            onRemovePhoto = onRemovePhoto,
        )

        IdentityCard(
            summary = state.enrollmentSummary,
            name = patientDisplayName(state.patient),
            onEnroll = onEnroll,
        )

        PatientEmailAlertsCard(
            patient = state.patient,
            accountEmail = state.accountEmail,
            state = state.emailAlerts,
            onEnabledChange = onEmailAlertsEnabledChange,
            onDraftChange = onEmailDraftChange,
            onRecipientsChange = onEmailRecipientsChange,
            onValidationError = onEmailValidationError,
            onSendTestEmail = onSendTestEmail,
        )

        PrivacySection(
            peopleBlurEnabled = state.peopleBlurEnabled,
            onPeopleBlurChange = onPeopleBlurChange,
            audioCaptureEnabled = state.patient?.audioCaptureEnabled,
            audioCapture = state.audioCapture,
            onAudioCaptureChange = onAudioCaptureChange,
        )

        EvaluationModeCard(
            patient = state.patient,
            mutation = state.evaluationMode,
            onEnabledChange = onEvaluationModeChange,
        )

        DataCollectionCard(
            patient = state.patient,
            mutation = state.dataCollection,
            onEnabledChange = onDataCollectionChange,
        )

        FeedbackEntry(onOpenFeedback = onOpenFeedback)

        NemaraButton(
            title = if (state.isDeleting) "Removing…" else "Remove this patient",
            variant = NemaraButtonVariant.Ghost,
            size = NemaraButtonSize.Sm,
            enabled = !state.isDeleting && state.patient != null,
            onClick = { showingDeleteConfirm = true },
            modifier = Modifier
                .padding(top = 8.dp)
                // Clears the floating tab bar. 40 sits comfortably above the bar
                // without leaving dead space.
                .padding(bottom = 40.dp),
        )
        // iOS also applies `.foregroundStyle(NemaraColor.terra)` to that button.
        // It is a no-op: NemaraButton.swift sets `.foregroundStyle(foreground)`
        // on its own label, and the innermost foreground style wins — so the
        // label renders in the ghost variant's `ink`, not terra. Not carried,
        // because carrying it would introduce a colour iOS does not show.
    }

    if (showingDeleteConfirm) {
        RemovePatientDialog(
            preferredName = state.patient?.preferredName,
            onDismiss = { showingDeleteConfirm = false },
            onConfirm = {
                showingDeleteConfirm = false
                onRemovePatient()
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

@Composable
private fun ProfileCard(
    state: PatientSettingsState,
    resolvePhotoUrl: suspend (String) -> String?,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    val patient = state.patient

    NemaraCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        padding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (patient != null) {
                    Box(Modifier.size(56.dp)) {
                        PatientAvatar(
                            patientId = patient.id,
                            avatarLetter = patient.avatarLetter,
                            photoStorageKey = patient.photoStorageKey,
                            resolvePhotoUrl = resolvePhotoUrl,
                            size = 56.dp,
                        )
                        ChangePhotoBadge(
                            enabled = !state.isUploadingPhoto,
                            onClick = onPickPhoto,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                } else {
                    NemaraAvatar(letter = "?", size = 56.dp)
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = patient?.fullName ?: "",
                        style = NemaraType.title2,
                        // Per-call-site kerning, per DECISIONS.md D-008.
                        // iOS: .kerning(-0.4)
                        letterSpacing = (-0.4).sp,
                        color = Ink,
                    )
                    if (patient != null) {
                        Text(
                            text = "${patient.relation.displayName} · Goes by " +
                                "${patient.preferredName} · ${patient.age} yrs",
                            style = NemaraType.caption,
                            color = Ink3,
                        )
                    }
                }

                // The Swift's action is empty — the button is drawn and does
                // nothing yet. Carried as-is, including the analytics event
                // NemaraButton fires on tap, because that is the behavior today.
                NemaraButton(
                    title = "Edit",
                    variant = NemaraButtonVariant.Ghost,
                    size = NemaraButtonSize.Sm,
                    icon = PencilIcon,
                    onClick = {},
                )
            }

            if (patient?.photoStorageKey != null) {
                NemaraButton(
                    title = "Remove photo",
                    variant = NemaraButtonVariant.Ghost,
                    size = NemaraButtonSize.Sm,
                    icon = TrashIcon,
                    onClick = onRemovePhoto,
                )
            }

            state.photoUploadError?.let { message ->
                Text(text = message, style = NemaraType.caption, color = Terra)
            }
        }
    }
}

/**
 * The 22dp camera badge sitting on the avatar's bottom-trailing corner. On iOS
 * this is the `PhotosPicker` trigger; here it reports the tap and the host opens
 * the system picker.
 *
 * The 2dp paper ring is `strokeBorder`, which insets inward — `Modifier.border`
 * does the same, so the badge stays 22dp overall on both platforms.
 */
@Composable
private fun ChangePhotoBadge(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Action)
            .border(2.dp, Paper, CircleShape)
            .plainClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Change photo" },
        contentAlignment = Alignment.Center,
    ) {
        CameraFillGlyph(size = 11.dp, color = ActionInk, knockout = Action)
    }
}

// ---------------------------------------------------------------------------
// Identity (face enrollment)
// ---------------------------------------------------------------------------

/**
 * Re-ID enrollment status. The wording renders three states matched
 * byte-for-byte with the web dashboard's `PatientIdentityStatus` card, so a
 * caregiver flipping between devices sees the same language.
 *
 * Not-enrolled is an action the caregiver needs to take, so the card pops with a
 * terra tint + border and an exclamation indicator instead of the neutral dashed
 * circle.
 *
 * **The 14dp horizontal inset is not a typo.** Every other card on this screen
 * sits at 16; `identityCard` alone uses `.padding(.horizontal, 14)`, so it is
 * 2dp wider than its neighbours. Carried verbatim.
 */
@Composable
private fun IdentityCard(
    summary: com.memoria.caregiver.data.model.FaceEnrollmentSummary?,
    name: String,
    onEnroll: () -> Unit,
) {
    val cardState = identityCardState(summary)
    val urgent = cardState == IdentityCardState.NotEnrolled

    NemaraCard(
        modifier = Modifier.padding(horizontal = 14.dp),
        tone = if (urgent) NemaraCardTone.Terra else NemaraCardTone.Default,
        padding = 14.dp,
        borderColor = if (urgent) Terra else Line,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when (cardState) {
                    IdentityCardState.Enrolled ->
                        CheckmarkCircleFillGlyph(size = 28.dp, color = Success, knockout = Card)

                    IdentityCardState.Processing ->
                        // SwiftUI's circular ProgressView is not resized by a
                        // .frame — it centres its own ~20pt indicator inside the
                        // 28pt box, so the size below is the indicator, not the
                        // box. Tint is terra on both (iOS sets it explicitly;
                        // Compose's default primary is also Terra).
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Terra,
                            strokeWidth = 2.dp,
                        )

                    IdentityCardState.NotEnrolled ->
                        ExclamationTriangleFillGlyph(
                            size = 28.dp,
                            color = Terra,
                            knockout = TerraSoft,
                        )

                    IdentityCardState.Loading ->
                        CircleDashedGlyph(size = 28.dp, color = Ink3)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = identityCardTitle(cardState, name),
                    style = NemaraType.bodySemibold,
                    color = Ink,
                )
            }

            Text(
                text = if (cardState == IdentityCardState.Enrolled) "Re-enroll" else "Enroll",
                style = NemaraType.bodySmallStrong,
                color = Terra,
                modifier = Modifier.plainClickable(onClick = onEnroll),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Privacy
// ---------------------------------------------------------------------------

/**
 * Device-local privacy preferences under one header.
 *
 * The old patient-wide "Privacy mode" toggle was removed: privacy is now PER
 * CAMERA, derived automatically from AI room classification (bathroom/bedroom →
 * private, locked, no toggle). What remains here is the on-device blur
 * preference and the audio kill.
 */
@Composable
private fun PrivacySection(
    peopleBlurEnabled: Boolean,
    onPeopleBlurChange: (Boolean) -> Unit,
    audioCaptureEnabled: Boolean?,
    audioCapture: CardMutationState,
    onAudioCaptureChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("PRIVACY", modifier = Modifier.padding(horizontal = 16.dp))

        PeopleBlurCard(enabled = peopleBlurEnabled, onEnabledChange = onPeopleBlurChange)

        AudioCaptureCard(
            audioCaptureEnabled = audioCaptureEnabled,
            mutation = audioCapture,
            onEnabledChange = onAudioCaptureChange,
        )

        Text(
            text = "Blur people applies on this device only — it changes what " +
                "you see, not what is recorded or what other caregivers " +
                "see. Bathroom and bedroom cameras are private " +
                "automatically: no live view, and sensitive moments in " +
                "clips are always censored.",
            style = NemaraType.caption,
            color = Ink3,
            lineHeight = CaptionLineHeight,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Feedback
// ---------------------------------------------------------------------------

@Composable
private fun FeedbackEntry(onOpenFeedback: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("HELP US IMPROVE", modifier = Modifier.padding(horizontal = 16.dp))

        NemaraCard(
            modifier = Modifier.padding(horizontal = 16.dp),
            padding = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // iOS: .contentShape(Rectangle()) on the padded row, so the
                    // whole row is the tap target and not just the glyphs.
                    .plainClickable(onClick = onOpenFeedback)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(TerraSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    SparklesGlyph(size = 14.dp, color = Terra)
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Feedback & support",
                        style = NemaraType.bodySmallStrong,
                        color = Ink,
                    )
                    Text(
                        text = "Send feedback, contact the team, or share Memoria",
                        style = NemaraType.caption,
                        color = Ink3,
                    )
                }

                ChevronRightGlyph(size = 13.dp, color = Ink3)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Remove patient
// ---------------------------------------------------------------------------

/**
 * iOS presents this with `.alert(...)`, which is the platform's own alert
 * chrome; material3's `AlertDialog` is the equivalent on Android, so the dialog
 * is not hand-rolled. Its surface, type and outline all resolve from
 * `MemoriaTheme`.
 *
 * The two roles iOS gets from `role: .cancel` / `role: .destructive` are drawn
 * explicitly here: Android has no button roles, so "Remove" takes the `Danger`
 * token and "Cancel" takes `Ink`.
 */
@Composable
private fun RemovePatientDialog(
    preferredName: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Remove patient?", style = NemaraType.title3, color = Ink) },
        text = {
            Text(
                text = if (preferredName != null) {
                    "$preferredName's cameras, detectors, and event history " +
                        "will be permanently removed. This cannot be undone."
                } else {
                    "This will permanently remove the patient and all data."
                },
                style = NemaraType.body,
                color = Ink2,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "Remove", style = NemaraType.bodySemibold, color = Terra)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", style = NemaraType.bodySemibold, color = Ink)
            }
        },
        containerColor = Card,
    )
}
