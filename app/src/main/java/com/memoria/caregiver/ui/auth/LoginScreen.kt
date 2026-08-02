package com.memoria.caregiver.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.components.NemaraMark
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra

/**
 * Login form: email + password + "Keep me signed in" + Forgot password.
 * Spec: Features/Auth/LoginView.swift, PARITY.md §16.
 *
 * Pure state-in / callbacks-out, per AuthUiState.kt: no ViewModel, no I/O, no
 * knowledge of Supabase. The only ambient dependency is the analytics sink,
 * which is a synchronous non-throwing buffer append and is the app's single
 * instrumentation seam (see Analytics.kt).
 *
 * Two things here look like they belong to a navigation host and deliberately do
 * not, because on iOS they live inside `LoginView` itself:
 *
 * 1. **The back chevron.** iOS hides the system back button
 *    (`navigationBarBackButtonHidden`) and supplies its own 18pt semibold
 *    `chevron.left` in the top-leading toolbar slot. It is drawn here and
 *    reported through [onBack]. If a Scaffold with a top bar is added later,
 *    this is the piece to lift out.
 * 2. **Screen tracking.** iOS attaches `.trackScreen("auth.login")` to the body.
 */
@Composable
fun LoginScreen(
    state: LoginState,
    modifier: Modifier = Modifier,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onKeepSignedInChange: (Boolean) -> Unit,
    onShowPasswordChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    onBack: () -> Unit,
) {
    val analytics = LocalAnalytics.current
    LaunchedEffect(Unit) { analytics.logScreen("auth.login") }

    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        BackChevronButton(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                // iOS: .padding(.bottom, 28) — no spacing token is 28.
                .padding(bottom = 28.dp),
            // iOS: VStack(alignment: .leading, spacing: 18). 18 is between
            // Spacing.md (16) and Spacing.lg (20), so it stays a literal.
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                NemaraMark(size = 44.dp)
                Text(
                    text = "Welcome back.",
                    style = NemaraType.largeTitle,
                    // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-1)
                    letterSpacing = (-1).sp,
                    color = Ink,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                NemaraField(
                    label = "Email",
                    value = state.email,
                    onValueChange = onEmailChange,
                    placeholder = "you@home.com",
                    keyboardType = KeyboardType.Email,
                    capitalization = KeyboardCapitalization.None,
                )
                NemaraField(
                    label = "Password",
                    value = state.password,
                    onValueChange = onPasswordChange,
                    placeholder = "Enter your password",
                    isSecure = !state.showPassword,
                    // iOS renders the secure state with SecureField, which is a
                    // UITextField with isSecureTextEntry — UIKit forces
                    // autocapitalization off there. Held constant across the
                    // Show/Hide flip rather than following iOS's default
                    // .sentences on the revealed TextField, which would
                    // capitalize the first letter of a password mid-edit.
                    keyboardType = KeyboardType.Password,
                    capitalization = KeyboardCapitalization.None,
                    trailing = {
                        Text(
                            text = if (state.showPassword) "Hide" else "Show",
                            style = NemaraType.captionStrong,
                            color = Terra,
                            modifier = Modifier.plainClickable {
                                onShowPasswordChange(!state.showPassword)
                            },
                        )
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                KeepSignedInCheckbox(
                    isChecked = state.keepSignedIn,
                    onToggle = { onKeepSignedInChange(!state.keepSignedIn) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Forgot password?",
                    // iOS: NemaraFont.bodySmall.weight(.semibold) — 13/semibold.
                    style = NemaraType.bodySmallStrong,
                    color = Terra,
                    modifier = Modifier.plainClickable(onForgotPassword),
                )
            }

            // Rendered verbatim from state. Never reworded — see AuthFormState.
            state.form.errorMessage?.let { message ->
                Text(text = message, style = NemaraType.bodySmall, color = Terra)
            }

            NemaraButton(
                title = if (state.form.isBusy) "Signing in…" else "Log in",
                variant = NemaraButtonVariant.Primary,
                size = NemaraButtonSize.Lg,
                block = true,
                enabled = state.canSubmit,
                onClick = onSubmit,
                // NemaraButton itself never dims when disabled, matching
                // NemaraButton.swift. This 0.5 is the *call site's* own
                // `.opacity(canSubmit ? 1 : 0.5)`, which LoginView.swift applies
                // alongside `.disabled(!canSubmit)` and PARITY.md §16 states for
                // both auth CTAs.
                modifier = Modifier.alpha(if (state.canSubmit) 1f else 0.5f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // iOS adds .padding(.top, 4) on top of the 18 stack spacing.
                    .padding(top = Spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(
                    Spacing.xxs,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "New here?", style = NemaraType.bodySmall, color = Ink2)
                Text(
                    text = "Create an account",
                    style = NemaraType.bodySmallStrong,
                    color = Terra,
                    modifier = Modifier.plainClickable(onCreateAccount),
                )
            }
        }
    }
}

/**
 * The hand-rolled checkbox from LoginView.swift — 18x18, radius 5, filled with
 * `action` when on and outlined in `line` at 1.5 when off.
 *
 * Not NemaraToggle: that component is the 40x24 switch pill, and the Swift here
 * draws a checkbox rather than using it. It is also not a NemaraButton, so no
 * analytics event fires on the flip — matching iOS, where this is a plain
 * `Button` with `.buttonStyle(.plain)`.
 */
@Composable
private fun KeepSignedInCheckbox(
    isChecked: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier = Modifier.plainClickable(onToggle),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(shape)
                .background(if (isChecked) Action else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (isChecked) Color.Transparent else Line,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isChecked) CheckmarkGlyph(size = 10.dp, color = ActionInk)
        }
        Text(text = "Keep me signed in", style = NemaraType.bodySmall, color = Ink2)
    }
}

/**
 * The toolbar back button. iOS sets `navigationBarBackButtonHidden(true)` and
 * puts an 18pt semibold `chevron.left` in `.topBarLeading`, so this sits above
 * the scroll area rather than inside it — a navigation bar does not scroll.
 *
 * The 44dp box is the touch target, not the glyph; centering an 18dp glyph in it
 * puts the chevron on the same 24dp gutter as the content below.
 */
@Composable
private fun BackChevronButton(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .size(44.dp)
            .testTag(LoginBackTestTag)
            .plainClickable(onBack),
        contentAlignment = Alignment.Center,
    ) {
        ChevronLeftGlyph(size = 18.dp, color = Ink)
    }
}

/** Test handle for the back chevron, which carries no text to select by. */
const val LoginBackTestTag: String = "login.back"

/**
 * Tap with no ripple and no indication — SwiftUI's `.buttonStyle(.plain)`, which
 * every tappable label on this screen uses.
 */
@Composable
private fun Modifier.plainClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

// ---------------------------------------------------------------------------
// Glyphs
// ---------------------------------------------------------------------------
//
// Two SF Symbols are named in the Swift: `chevron.left` at 18pt semibold and
// `checkmark` at 10pt black. There is no material-icons dependency in the
// version catalog and the porting rules forbid adding one, so both are drawn
// with Canvas — the same approach CountryPicker.kt takes for its three glyphs.
//
// Stroke weight is a fraction of the glyph box: 0.145 for semibold (matching
// CountryPicker), 0.19 for black, which is the next weight step up.

private const val SEMIBOLD_STROKE_RATIO = 0.145f
private const val BLACK_STROKE_RATIO = 0.19f

@Composable
private fun ChevronLeftGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        val inset = stroke / 2f
        drawPolyline(
            points = listOf(
                Offset(w * 0.68f, inset),
                Offset(w * 0.30f, w / 2f),
                Offset(w * 0.68f, w - inset),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun CheckmarkGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * BLACK_STROKE_RATIO
        drawPolyline(
            points = listOf(
                Offset(w * 0.12f, w * 0.55f),
                Offset(w * 0.38f, w * 0.82f),
                Offset(w * 0.88f, w * 0.20f),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/** Round-capped segments through [points]. See CountryPicker.kt for the rationale. */
private fun DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    for (i in 0 until points.size - 1) {
        drawLine(
            color = color,
            start = points[i],
            end = points[i + 1],
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
