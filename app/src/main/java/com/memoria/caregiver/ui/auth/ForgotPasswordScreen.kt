package com.memoria.caregiver.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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
 * Step 1 of the forgot-password flow. Spec: `Features/Auth/ForgotPasswordView.swift`.
 *
 * Collects the caregiver's email and asks for a Supabase recovery mail. Supabase
 * returns success whether or not the address is registered, and this screen
 * mirrors that: the confirmation copy is identical either way, so the UI never
 * reveals whether an account exists.
 *
 * Pure over [ForgotPasswordState] plus callbacks — no ViewModel, no I/O. iOS
 * holds the same four values in `@State` and reaches Supabase through
 * `@Environment`; here the state is hoisted and the submit is a callback.
 *
 * The tag on the back control is a test hook, not accessibility copy: the Swift
 * puts a bare `Image(systemName: "chevron.left")` in the toolbar with no label,
 * and inventing one would be user-facing text iOS does not have.
 */
@Composable
fun ForgotPasswordScreen(
    state: ForgotPasswordState,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        BackBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = SCROLL_BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
            // The Swift nests a VStack of the same spacing here. Kept as its own
            // Column so the mark and headline stay one block if the outer
            // spacing is ever retuned.
            Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
                NemaraMark(size = 44.dp)
                Text(
                    text = if (state.sent) "Check your email." else "Reset your password.",
                    style = NemaraType.largeTitle,
                    color = Ink,
                    // iOS: .kerning(-1). Per DECISIONS.md D-008 the type tokens
                    // carry no letter spacing, so it lands at the call site.
                    letterSpacing = (-1).sp,
                )
            }

            // `@ViewBuilder formBody` / `sentBody` return loose views into the
            // enclosing VStack, so their children take the outer 18pt spacing.
            // ColumnScope receivers reproduce that; wrapping either in a Column
            // of its own would silently change the rhythm.
            if (state.sent) {
                SentBody(email = state.email, onBack = onBack)
            } else {
                FormBody(
                    state = state,
                    onEmailChange = onEmailChange,
                    onSubmit = onSubmit,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FormBody(
    state: ForgotPasswordState,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = "Enter the email you signed up with and we'll send you a link to set a new password.",
        style = NemaraType.bodySmall,
        color = Ink2,
    )

    NemaraField(
        label = "Email",
        value = state.email,
        onValueChange = onEmailChange,
        placeholder = "you@home.com",
        keyboardType = KeyboardType.Email,
        capitalization = KeyboardCapitalization.None,
    )

    // Rendered verbatim. The sentence is `ApiError.errorDescription` and is
    // product copy; never reword it here.
    state.form.errorMessage?.let { message ->
        Text(text = message, style = NemaraType.bodySmall, color = Terra)
    }

    val canSubmit = state.canSubmit
    NemaraButton(
        title = if (state.form.isBusy) "Sending…" else "Send reset link",
        // NemaraButton deliberately does not dim on `enabled = false`, because
        // NemaraButton.swift does not. This call site is the exception: the
        // Swift adds `.opacity(canSubmit ? 1 : 0.5)` itself, right after
        // `.disabled(!canSubmit)`. Dropping it would leave a full-strength
        // black CTA that does nothing when tapped.
        modifier = Modifier.alpha(if (canSubmit) 1f else DISABLED_OPACITY),
        variant = NemaraButtonVariant.Primary,
        size = NemaraButtonSize.Lg,
        block = true,
        enabled = canSubmit,
        onClick = onSubmit,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Remembered it?", style = NemaraType.bodySmall, color = Ink2)
        val interactionSource = remember { MutableInteractionSource() }
        Text(
            text = "Sign in",
            style = NemaraType.bodySmallStrong,
            color = Terra,
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                // `.buttonStyle(.plain)` on iOS — no ripple, no highlight.
                indication = null,
                onClick = onBack,
            ),
        )
    }
}

@Composable
private fun ColumnScope.SentBody(email: String, onBack: () -> Unit) {
    Text(
        // The Swift string is a LocalizedStringKey, so `**\(email)**` renders
        // the address bold inside the sentence.
        text = buildAnnotatedString {
            append("If ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(email) }
            append(" is registered, a reset link is on its way. Check your inbox (and spam folder).")
        },
        style = NemaraType.bodySmall,
        color = Ink2,
    )

    Text(text = "The link expires in 1 hour.", style = NemaraType.caption, color = Ink3)

    NemaraButton(
        title = "Back to sign in",
        modifier = Modifier.padding(top = Spacing.xxs),
        variant = NemaraButtonVariant.Primary,
        size = NemaraButtonSize.Lg,
        block = true,
        onClick = onBack,
    )
}

/**
 * The inline navigation bar: empty title, back button hidden, one leading
 * chevron that dismisses.
 *
 * Compose has no navigation bar to configure, so the bar height, the leading
 * inset and the touch target are **judgement, not ported values** — the same
 * category as the country-picker chrome in `DIFFERENCES.md` D9. They need the
 * screenshot pass.
 */
@Composable
private fun BackBar(onBack: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(start = Spacing.xs)
                .size(NAV_BAR_HEIGHT)
                .testTag(FORGOT_PASSWORD_BACK_TAG)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // SF Symbol "chevron.left" at system size 18, semibold. Drawn rather
            // than taken from an icon set, matching how CountryPicker.kt draws
            // "chevron.down" and "checkmark".
            ChevronLeftGlyph(size = 18.dp, color = Ink)
        }
    }
}

@Composable
private fun ChevronLeftGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        val inset = stroke / 2f
        val points = listOf(
            Offset(w * 0.70f, inset),
            Offset(w * 0.30f, w / 2f),
            Offset(w * 0.70f, w - inset),
        )
        for (i in 0 until points.size - 1) {
            drawLine(
                color = color,
                start = points[i],
                end = points[i + 1],
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * iOS gates the button on the address merely containing "@" — not on a real
 * address — and on no request being in flight. The busy half is what stops a
 * second tap firing a second reset mail.
 *
 * It lives here rather than on [ForgotPasswordState] because that file is shared
 * across the auth screens and is not this screen's to edit.
 */
internal val ForgotPasswordState.canSubmit: Boolean
    get() = email.contains("@") && !form.isBusy

/** Test hook for the toolbar back control, which carries no label on iOS. */
internal const val FORGOT_PASSWORD_BACK_TAG = "forgotPassword.back"

/** `VStack(spacing: 18)`. Not on the spacing scale; carried verbatim. */
private val SECTION_SPACING = 18.dp

/** `.padding(.bottom, 28)`. Not on the spacing scale; carried verbatim. */
private val SCROLL_BOTTOM_PADDING = 28.dp

/** `.opacity(canSubmit ? 1 : 0.5)` on the submit button. */
private const val DISABLED_OPACITY = 0.5f

/** Stroke weight of a semibold SF Symbol glyph, as in CountryPicker.kt. */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

/** UIKit's inline navigation bar height. */
private val NAV_BAR_HEIGHT = 44.dp
