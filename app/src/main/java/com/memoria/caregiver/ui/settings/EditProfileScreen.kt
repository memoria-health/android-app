package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing

/**
 * The sheet for editing the signed-in caregiver's name, email and phone.
 * Spec: `Features/AppSettings/EditProfileView.swift`, PARITY.md §28.
 *
 * Presented from the Roster's top-right avatar and from the Settings tab's
 * caregiver card.
 *
 * ## The displayed email lags an email change, on purpose
 *
 * Name and phone are written via `PATCH /v1/me` and the returned row replaces
 * `currentCaregiver` immediately. Email goes through Supabase Auth's secure
 * email-change flow: the new address becomes live only after the caregiver
 * clicks the link Supabase mails them, at which point a DB trigger mirrors it
 * into `caregivers.email`. Until then this sheet, and every other surface, keeps
 * showing the OLD address. That is correct behavior, not a stale-cache bug, and
 * the hint under the field is what tells the caregiver so.
 *
 * ## Hydration
 *
 * iOS fills the fields from `currentCaregiver` in `.onAppear`, once. A pure
 * screen may not rewrite the state it is handed, so the appear is reported
 * through [onAppear] and the host applies `EditProfileState.hydrated()`, which
 * carries both of the Swift's guards.
 *
 * Everything the Save button is gated on lives in `SettingsUiState.kt` as pure
 * properties, so the gate is testable without composing anything.
 */
@Composable
fun EditProfileScreen(
    state: EditProfileState,
    modifier: Modifier = Modifier,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onAppear: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    // iOS: `.trackScreen("edit_profile")` on the sheet, applied by the presenter.
    // Screen tracking is NOT here. `ModalOverlay(screen = "edit_profile")` logs it at
    // the point this is presented, the way iOS's `.trackScreen()` does, so a
    // screen cannot ship untracked and no view is counted twice. See
    // ModalOverlay and HARDENING.md H-10.

    // `.onAppear(perform: hydrate)`. Keyed on Unit so it fires once per
    // presentation, which is what onAppear does for a sheet.
    LaunchedEffect(Unit) { onAppear() }

    val canSave = state.canSave

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        EditProfileNavBar(
            canSave = canSave,
            isSaving = state.isSaving,
            onCancel = onCancel,
            onSave = onSave,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            NemaraField(
                // The Swift passes "NAME"/"EMAIL"/"PHONE" already shouting;
                // NemaraEyebrow uppercases inside the component, so these render
                // identically and follow the Android convention of handing the
                // component normal-case strings (see NemaraEyebrow's doc).
                label = "Name",
                value = state.name,
                onValueChange = onNameChange,
                placeholder = "Your name",
                capitalization = KeyboardCapitalization.Words,
            )

            NemaraField(
                label = "Email",
                value = state.email,
                onValueChange = onEmailChange,
                placeholder = "you@home.com",
                keyboardType = KeyboardType.Email,
                capitalization = KeyboardCapitalization.None,
                // Appears the moment the address differs from the stored one,
                // and it is the only warning that the change is not immediate.
                hint = if (state.emailChanged) EMAIL_CHANGE_HINT else null,
            )

            NemaraField(
                label = "Phone",
                value = state.phone,
                onValueChange = onPhoneChange,
                placeholder = "(555) 123-4567",
                keyboardType = KeyboardType.Phone,
                capitalization = KeyboardCapitalization.None,
            )

            // Rendered verbatim — the sentence is the thrown error's own.
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth(),
                    style = NemaraType.caption,
                    color = Danger,
                )
            }
        }
    }
}

/**
 * The inline navigation bar: Cancel leading, centred title, Save trailing.
 *
 * Compose has no navigation bar, so it is drawn the same way `TermsGateScreen`
 * and `CountryPickerSheet` draw theirs (DIFFERENCES.md D9) — and with the same
 * metrics, so a caregiver moving between those sheets does not see the title
 * shift. The bar height, insets and title face are judgement, not ported values.
 *
 * Save is inert whenever [canSave] is false, which includes the whole time a
 * save is in flight — so a double tap cannot send two `PATCH /v1/me` calls or
 * two email-change requests.
 */
@Composable
private fun EditProfileNavBar(
    canSave: Boolean,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Edit profile",
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest: Inter 16 semibold. Same choice as TermsGate.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )

        Text(
            text = "Cancel",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .plainClickable(onClick = onCancel),
            style = NemaraType.bodyLarge,
            // iOS sets this one explicitly: .foregroundStyle(NemaraColor.ink2).
            color = Ink2,
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .testTag(EDIT_PROFILE_SAVE_TAG)
                .plainClickable(enabled = canSave, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) {
            if (isSaving) {
                // `ProgressView().controlSize(.small)`.
                CircularProgressIndicator(
                    Modifier.size(SaveSpinnerSize),
                    strokeWidth = SaveSpinnerStroke,
                )
            } else {
                Text(
                    text = "Save",
                    style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    // `.foregroundStyle(canSave ? NemaraColor.ink : NemaraColor.ink3)`.
                    color = if (canSave) Ink else Ink3,
                )
            }
        }
    }
}

/**
 * Test handle for the Save control, which has no stable label: it is the word
 * "Save" most of the time and a spinner while saving.
 */
internal const val EDIT_PROFILE_SAVE_TAG = "editProfile.save"

/** The email field's hint, shown only once the address differs. Verbatim. */
internal const val EMAIL_CHANGE_HINT =
    "We'll send a confirmation link to your new email. " +
        "Your address updates once you click it."

/** `ProgressView().controlSize(.small)` ≈ 16pt. Judgement, like the bar metrics. */
private val SaveSpinnerSize = 16.dp
private val SaveSpinnerStroke = 2.dp
