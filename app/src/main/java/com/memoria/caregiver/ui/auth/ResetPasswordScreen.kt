package com.memoria.caregiver.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.components.NemaraMark
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra

/**
 * Steps 2 and 3 of the forgot-password flow. iOS: `Features/Auth/ResetPasswordView.swift`.
 *
 * Reached from the `nemara://reset-password?code=…` link in the recovery email.
 * The code is exchanged for a recovery session before anything is editable, so
 * the screen is a **three-state machine** and only ever renders one of them:
 *
 * | state | condition | body |
 * |---|---|---|
 * | verifying | [ResetPasswordState.isExchangingCode] | "Verifying link…" |
 * | form | exchange succeeded ([isLinkValid]) | password + confirm + submit |
 * | invalid | exchange failed | why, and how to get a fresh link |
 *
 * The headline changes with the state too — "Reset password" while verifying or
 * invalid, "Set a new password." once the link is known good.
 *
 * Pure over its state, per `AuthUiState.kt`: no ViewModel, no I/O. The caller
 * owns exchanging the code, calling `updatePassword`, and dismissing on success
 * (iOS dismisses the cover itself because `updatePassword` runs the same
 * `onAuthenticated` provisioning as a normal sign-in, so the app lands on home).
 *
 * ## Two values that are not on `ResetPasswordState` yet
 *
 * [confirm] and [isLinkValid] are parameters rather than state fields because
 * `AuthUiState.kt` is owned by the orchestrator and this screen may not edit it.
 * Both are required for parity and **neither can be derived** from what the
 * state carries today:
 *
 * - iOS gates submit on `password == confirm`, so dropping the second field
 *   would drop a real validation rule, not just a text box.
 * - "link valid" is not `email != null` — `exchangeRecoveryCode` returns
 *   `String?`, so a valid link can come back without an address. Nor is it
 *   `errorMessage == null`: a *submit* failure also sets `errorMessage`, and iOS
 *   keeps showing the form in that case rather than flipping to the invalid
 *   state. It is genuinely a separate flag.
 *
 * When those fields land on `ResetPasswordState`, delete the two parameters and
 * read them off `state`; nothing else in this file changes.
 *
 * @param confirm the "Confirm password" field. See above.
 * @param isLinkValid the recovery code was exchanged successfully. See above.
 * @param onSubmit set the new password. Cannot fire while [ResetPasswordState]'s
 *   form is busy, so a double tap cannot send two updates.
 * @param onClose dismiss the screen. Fires from the toolbar and from the
 *   invalid-link body's button, which on iOS are both `dismiss()`.
 */
@Composable
fun ResetPasswordScreen(
    state: ResetPasswordState,
    confirm: String,
    isLinkValid: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // iOS: `passwordValid && password == confirm && !submitting && !exchanging && linkValid`.
    val canSubmit = state.password.length >= MIN_PASSWORD_LENGTH &&
        state.password == confirm &&
        !state.form.isBusy &&
        !state.isExchangingCode &&
        isLinkValid

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        CloseToolbar(onClose = onClose)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The scroll region takes whatever the toolbar leaves.
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                // iOS: .padding(.bottom, 28). Not a NemaraSpacing token there
                // either — 28 sits between xl (24) and xxl (32).
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
                NemaraMark(size = 44.dp)
                Text(
                    text = if (isLinkValid) "Set a new password." else "Reset password",
                    style = NemaraType.largeTitle,
                    color = Ink,
                    // iOS: .kerning(-1). Per DECISIONS.md D-008 the type tokens
                    // carry no letter spacing, so it is applied here.
                    letterSpacing = (-1).sp,
                )
            }

            // Exactly one body renders. The order matters: `exchanging` wins
            // over `linkValid` so a stale flag cannot show the form mid-exchange.
            if (state.isExchangingCode) {
                VerifyingBody()
            } else if (isLinkValid) {
                FormBody(
                    state = state,
                    confirm = confirm,
                    canSubmit = canSubmit,
                    onPasswordChange = onPasswordChange,
                    onConfirmChange = onConfirmChange,
                    onSubmit = onSubmit,
                )
            } else {
                InvalidBody(errorMessage = state.form.errorMessage, onClose = onClose)
            }
        }
    }
}

/** iOS gates on `password.count >= 8`. */
private const val MIN_PASSWORD_LENGTH = 8

/**
 * iOS uses `spacing: 18` on both the outer stack and the header stack. 18 is not
 * a NemaraSpacing value on iOS either, so it is carried as a raw measurement
 * rather than rounded to `Spacing.md` (16) or `Spacing.lg` (20).
 */
private val SECTION_SPACING = 18.dp

/** Standard iOS navigation-bar height, which is what the toolbar occupies. */
private val TOOLBAR_HEIGHT = 44.dp

/**
 * The bare trailing "Close" from iOS's `ToolbarItem(placement: .topBarTrailing)`.
 *
 * Deliberately not a [NemaraButton]: iOS uses a plain SwiftUI `Button` tinted
 * `ink2`, and every NemaraButton variant resolves its foreground to `Ink`,
 * `ActionInk` or `TerraInk`. Rendering it as a Ghost button would also give it
 * button padding and an analytics event that iOS does not emit here.
 */
@Composable
private fun CloseToolbar(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOOLBAR_HEIGHT)
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = "Close",
            style = NemaraType.bodyLarge,
            color = Ink2,
            modifier = Modifier.clickable(onClick = onClose),
        )
    }
}

/**
 * These three bodies emit their children straight into the caller's [Column], so
 * each one is spaced [SECTION_SPACING] from its neighbours — matching SwiftUI,
 * where `@ViewBuilder` bodies flatten into the enclosing `VStack`. Wrapping them
 * in their own Column would nest the spacing one level too deep.
 */
@Composable
private fun VerifyingBody() {
    Text(
        text = "Verifying link…",
        style = NemaraType.bodySmall,
        color = Ink3,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

@Composable
private fun FormBody(
    state: ResetPasswordState,
    confirm: String,
    canSubmit: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val email = state.email
    if (email != null) {
        Text(
            // iOS renders this through markdown: `**\(recoveringEmail)**`.
            text = buildAnnotatedString {
                append("Resetting password for ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(email) }
                append(".")
            },
            style = NemaraType.bodySmall,
            color = Ink2,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        NemaraField(
            label = "New password",
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = "At least 8 characters",
            isSecure = true,
        )
        NemaraField(
            label = "Confirm password",
            value = confirm,
            onValueChange = onConfirmChange,
            placeholder = "Re-enter it",
            isSecure = true,
        )
    }

    val errorMessage = state.form.errorMessage
    if (errorMessage != null) {
        // Verbatim from the state. Never reworded — the copy is the product's.
        Text(text = errorMessage, style = NemaraType.bodySmall, color = Terra)
    }

    NemaraButton(
        title = if (state.form.isBusy) "Updating…" else "Set new password",
        // NemaraButton does not dim when disabled, matching iOS's component.
        // This *call site* dims explicitly — `.opacity(canSubmit ? 1 : 0.5)` in
        // the Swift — so the alpha belongs here, not in the component.
        modifier = Modifier.alpha(if (canSubmit) 1f else 0.5f),
        variant = NemaraButtonVariant.Primary,
        size = NemaraButtonSize.Lg,
        block = true,
        enabled = canSubmit,
        onClick = onSubmit,
    )
}

@Composable
private fun InvalidBody(errorMessage: String?, onClose: () -> Unit) {
    Text(
        // The fallback names all three causes, because the user cannot tell them
        // apart and the recovery is the same for all of them.
        text = errorMessage ?: "This reset link is missing, expired, or has already been used.",
        style = NemaraType.bodySmall,
        color = Ink2,
    )

    Text(
        text = "Request a fresh link from the sign-in screen.",
        style = NemaraType.caption,
        color = Ink3,
    )

    NemaraButton(
        title = "Close",
        modifier = Modifier.padding(top = Spacing.xxs),
        variant = NemaraButtonVariant.Primary,
        size = NemaraButtonSize.Lg,
        block = true,
        onClick = onClose,
    )
}
