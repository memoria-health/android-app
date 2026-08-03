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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.components.CountryPicker
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.components.NemaraMark
import com.memoria.caregiver.ui.components.PasswordRule
import com.memoria.caregiver.ui.components.passwordRules
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Terra

// ---------------------------------------------------------------------------
// Spacing that has no token
// ---------------------------------------------------------------------------

/**
 * The sign-up form's section rhythm. `SignUpView.swift` uses a literal 18, which
 * sits between `Spacing.md` (16) and `Spacing.lg` (20), so there is no token to
 * reach for and rounding it either way would shift every section boundary on the
 * tallest form in the app.
 */
private val SectionSpacing = 18.dp

/** Bottom inset of the scroll content. `.padding(.bottom, 28)`, also off-scale. */
private val ScrollBottomInset = 28.dp

/** Gap between the terms checkbox and its sentence. `HStack(spacing: 10)`. */
private val TermsRowGap = 10.dp

/** Gap between the two checklist columns. `GridItem(.flexible(), spacing: 14)`. */
private val ChecklistColumnGap = 14.dp

/** Eyebrow-to-control gap, shared by NemaraField and the country block. */
private val LabelGap = 6.dp

/** Inline navigation bar height — the iOS `.inline` title display mode. */
private val NavBarHeight = 44.dp

// ---------------------------------------------------------------------------
// Submit gating — pure, so it is testable without composing anything
// ---------------------------------------------------------------------------

/**
 * `SignUpView.canSubmit`, carried across rule for rule.
 *
 * [confirmPassword] is passed in rather than read off [SignUpState] because the
 * state type has no field for it — see the note on `SignUpScreen` below.
 *
 * Two places where the two languages' standard libraries had to be matched
 * deliberately rather than by the obvious translation:
 *
 * - `email.contains("@")` is the whole email check on iOS. It is not a real
 *   validation and must not be turned into one here; the server is the
 *   authority, and a stricter client rule would reject addresses iOS accepts.
 * - The password rules come from [passwordRules], which already pins the ASCII
 *   character classes iOS uses. Re-deriving them here would give two definitions
 *   of "valid password" that could drift apart.
 */
internal fun signUpCanSubmit(state: SignUpState, confirmPassword: String): Boolean =
    state.displayName.trim().isNotEmpty() &&
        state.email.contains("@") &&
        isSignUpPhoneValid(state.phone) &&
        passwordRules(state.password).all { it.isSatisfied } &&
        confirmPassword.isNotEmpty() &&
        confirmPassword == state.password &&
        state.acceptedTerms &&
        !state.form.isBusy

/**
 * "Phone is required so we can text the caregiver back from support. Light
 * validation: at least 10 digits once non-digit chars are stripped."
 *
 * `Char.isDigit()` is the Kotlin counterpart of Swift's `Character.isNumber`:
 * both are Unicode-aware, so a caregiver typing Devanagari digits passes on both
 * platforms. `it in '0'..'9'` would have been ASCII-only and stricter than iOS.
 */
internal fun isSignUpPhoneValid(phone: String): Boolean =
    phone.count { it.isDigit() } >= 10

/**
 * The terms sentence, with the two document names bold.
 *
 * The Swift hands SwiftUI a markdown string (`**Terms**`, `**Privacy Notice**`),
 * which SwiftUI renders as bold inline. Compose's `Text` does not parse markdown,
 * so the same sentence is assembled as an `AnnotatedString`. The words and
 * punctuation are verbatim; only the emphasis mechanism differs.
 *
 * Internal so a test can pin the rendered sentence without re-stating the spans.
 */
internal fun signUpTermsSentence(accepted: Boolean): AnnotatedString = buildAnnotatedString {
    append(if (accepted) "You've agreed to the " else "Review and agree to the ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Terms") }
    append(" and ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Privacy Notice") }
    append(if (accepted) ". Tap to review again." else " to continue.")
}

// ---------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------

/**
 * Sign-up form: name + email + phone + country + password (with live checklist)
 * + confirmation + Terms acceptance + primary CTA.
 * Spec: Features/Auth/SignUpView.swift.
 *
 * Pure over [SignUpState]: no ViewModel, no I/O, no knowledge of Supabase.
 *
 * **One piece of state is held locally, and it is a gap in `SignUpState`, not a
 * design choice.** iOS keeps `confirmPassword` in `@State` on the view, and
 * `SignUpState` has no field for it, so it lives in a plain `remember` here —
 * deliberately not `rememberSaveable`, which would persist the plaintext.
 * That is survivable — the confirmation is never submitted, it only gates the
 * button — but it means the caller cannot observe or pre-fill it, and the
 * mismatch message cannot be driven from a test fixture. The field belongs on
 * `SignUpState`; the orchestrator owns that file, so it is reported rather than
 * added.
 *
 * @param onReviewTerms the terms row was tapped. iOS presents `TermsConsentView`
 *   as a sheet whose completion sets `agreedToTerms = true`; here the host
 *   routes to the terms gate and flips `acceptedTerms` on the way back. The
 *   screen never sets it itself, which is what keeps consent a decision the
 *   caller records rather than one the form assumes.
 */
@Composable
fun SignUpScreen(
    state: SignUpState,
    onDisplayNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onCountryChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onReviewTerms: () -> Unit,
    onSubmit: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val analytics = LocalAnalytics.current
    // `.trackScreen("auth.signup")` — iOS logs this from onAppear. Keyed on Unit
    // so it fires once per entry, not on every recomposition of a typed-in form.
    LaunchedEffect(Unit) { analytics.logScreen("auth.signup") }

    // `remember`, NOT `rememberSaveable`. rememberSaveable writes its value into
    // the saved-instance-state Bundle, which the system persists to disk and can
    // include in a crash record — so the plaintext password would outlive the
    // process. iOS's @State is in-memory only. Losing the confirmation on a
    // configuration change is the correct trade: the user retypes one field, and
    // the password never touches storage.
    var confirmPassword by remember { mutableStateOf("") }

    val passwordsMismatch = confirmPassword.isNotEmpty() && confirmPassword != state.password
    val canSubmit = signUpCanSubmit(state, confirmPassword)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        SignUpNavigationBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = ScrollBottomInset),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {

            // ---- Mark + headline ----
            // The Swift nests a group here with the same 18 spacing as its
            // parent, so the two render identically today and flattening it
            // would change nothing visible. Kept as one group anyway: it is the
            // unit the rest of the form is spaced against, and a later change to
            // either spacing should not silently move the other.
            Column(verticalArrangement = Arrangement.spacedBy(SectionSpacing)) {
                NemaraMark(size = 44.dp)
                Text(
                    text = "Make a Memoria account.",
                    style = NemaraType.largeTitle,
                    color = Ink,
                    // iOS `.kerning(-1)`. Per DECISIONS.md D-008 the type tokens
                    // carry none, so it is applied here at the call site.
                    letterSpacing = (-1).sp,
                )
            }

            // ---- Fields ----
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                NemaraField(
                    label = "Your name",
                    value = state.displayName,
                    onValueChange = onDisplayNameChange,
                    placeholder = "Jane Patel",
                    capitalization = KeyboardCapitalization.Words,
                )
                NemaraField(
                    label = "Email",
                    value = state.email,
                    onValueChange = onEmailChange,
                    placeholder = "you@home.com",
                    keyboardType = KeyboardType.Email,
                    capitalization = KeyboardCapitalization.None,
                )
                NemaraField(
                    label = "Phone",
                    value = state.phone,
                    onValueChange = onPhoneChange,
                    placeholder = "(555) 123-4567",
                    keyboardType = KeyboardType.Phone,
                )

                Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
                    NemaraEyebrow("COUNTRY")
                    CountryPicker(code = state.countryCode, onCodeChange = onCountryChange)
                }

                NemaraField(
                    label = "Password",
                    value = state.password,
                    onValueChange = onPasswordChange,
                    placeholder = "8+ chars, mixed case, number, symbol",
                    isSecure = true,
                    // The Swift passes no capitalization here, but its Group
                    // applies capitalization only on the non-secure branch, and
                    // UIKit forces it off for secure entry regardless. Compose
                    // has no such rule, so leaving the component default
                    // (Sentences) would capitalize the first character of every
                    // password on Android and not on iOS.
                    capitalization = KeyboardCapitalization.None,
                    // NewPassword, not Password: this is the password being
                    // chosen, so the manager should offer to generate and save
                    // one rather than offering an existing one back.
                    contentType = ContentType.NewPassword,
                )

                NemaraPasswordChecklist(value = state.password)

                NemaraField(
                    label = "Confirm password",
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    placeholder = "Re-enter password",
                    isSecure = true,
                    capitalization = KeyboardCapitalization.None,
                    // The confirmation of the same new password, so it takes the
                    // same hint and the manager fills both in one pass.
                    contentType = ContentType.NewPassword,
                )

                if (passwordsMismatch) {
                    Text(
                        text = "Passwords don't match.",
                        style = NemaraType.caption,
                        color = Terra,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- Consent ----
            SignUpTermsRow(accepted = state.acceptedTerms, onReview = onReviewTerms)

            // ---- Error ----
            // Rendered verbatim from state. Never reworded: the sentence is the
            // product's copy and is pinned by test.
            state.form.errorMessage?.let { message ->
                Text(text = message, style = NemaraType.bodySmall, color = Terra)
            }

            // ---- CTA ----
            NemaraButton(
                title = if (state.form.isBusy) "Creating account…" else "Create account",
                // `.opacity(canSubmit ? 1 : 0.5)` — the dimming is at the iOS
                // CALL SITE, not in NemaraButton, which deliberately renders a
                // disabled button identically. Same per-call-site pattern as
                // kerning: the component stays neutral, the form asks for it.
                modifier = Modifier.alpha(if (canSubmit) 1f else 0.5f),
                variant = NemaraButtonVariant.Primary,
                size = NemaraButtonSize.Lg,
                block = true,
                // Also what stops a double submission: `isBusy` is one of the
                // canSubmit terms, so the in-flight form cannot fire again.
                enabled = canSubmit,
                onClick = onSubmit,
            )

            // ---- Footer ----
            SignUpLoginLink(onNavigateToLogin = onNavigateToLogin)
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

/**
 * The inline navigation bar. iOS hides the system back button
 * (`navigationBarBackButtonHidden`) and draws its own chevron as a leading
 * toolbar item, so the affordance belongs to this screen rather than to whatever
 * hosts it.
 */
@Composable
private fun SignUpNavigationBar(onBack: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NavBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                // Puts the chevron's centre on the same 24dp gutter the content
                // below sits on. Matches LoginScreen.kt's back chevron.
                .padding(start = 2.dp)
                .size(NavBarHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onBack,
                )
                // The Canvas glyph carries no text, so TalkBack would announce
                // an unlabeled button. iOS gets a label from the SF Symbol for
                // free; this is that label, not new visible copy.
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            ChevronLeftGlyph(size = 18.dp, color = Ink)
        }
    }
}

/**
 * Checkbox plus consent sentence, the whole row tappable.
 *
 * The checkbox is drawn, not a material3 `Checkbox`: that control brings its own
 * size, ripple, colors and animation, none of which are the 18pt rounded square
 * the Swift draws.
 */
@Composable
private fun SignUpTermsRow(accepted: Boolean, onReview: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val boxShape = RoundedCornerShape(5.dp)

    Row(
        // A button, NOT a checkbox, even though it draws one: the tap opens
        // the terms sheet rather than flipping the box, and the box only ever
        // changes once the sheet is accepted. Announcing it as a checkbox would
        // promise a toggle that tapping does not perform.
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            // `.buttonStyle(.plain)` — no ripple, no tint change.
            indication = null,
            role = Role.Button,
            onClick = onReview,
        ),
        horizontalArrangement = Arrangement.spacedBy(TermsRowGap),
        // `HStack(alignment: .top)` — the checkbox stays level with the first
        // line when the sentence wraps.
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(boxShape)
                .background(if (accepted) Action else Color.Transparent)
                .then(
                    // The Swift clears the border when filled rather than
                    // drawing a border the fill would hide.
                    if (accepted) Modifier else Modifier.border(1.5.dp, Line, boxShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (accepted) {
                CheckmarkGlyph(size = 10.dp, color = ActionInk, strokeRatio = BlackStrokeRatio)
            }
        }

        Text(
            text = signUpTermsSentence(accepted),
            style = NemaraType.caption,
            color = Ink2,
            textAlign = TextAlign.Start,
        )
    }
}

/** "Already have an account? Log in" — centered under the CTA. */
@Composable
private fun SignUpLoginLink(onNavigateToLogin: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Already have an account?", style = NemaraType.bodySmall, color = Ink2)
        Text(
            text = "Log in",
            // `NemaraFont.bodySmall.weight(.semibold)` is exactly this token.
            style = NemaraType.bodySmallStrong,
            color = Terra,
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onNavigateToLogin,
            ),
        )
    }
}

/**
 * Live password checklist — five rules in two leading-aligned columns, each row
 * darkening to `Ink` once its rule passes.
 * Spec: `NemaraPasswordChecklist` in DesignSystem/Components/NemaraField.swift.
 *
 * **This belongs in `ui/components` beside `passwordRules`, which already lives
 * there.** It is private here only because this port task is scoped to two
 * files; sign-up is its single call site today, so nothing is duplicated yet.
 *
 * Built from `Column` + `Row` rather than `LazyVerticalGrid`: the grid is inside
 * a `verticalScroll` parent, where a lazy list of unbounded height throws. Five
 * rows is not a lazy-layout problem in the first place — SwiftUI's `LazyVGrid`
 * here is just the idiomatic way to write two columns, not a performance choice.
 */
@Composable
private fun NemaraPasswordChecklist(value: String) {
    val rules = passwordRules(value)

    Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
        rules.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChecklistColumnGap),
            ) {
                pair.forEach { rule ->
                    PasswordRuleCell(rule = rule, modifier = Modifier.weight(1f))
                }
                // The fifth rule is alone on the last row. Without this the cell
                // would stretch across both columns and its label would no
                // longer line up with the column above it.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PasswordRuleCell(rule: PasswordRule, modifier: Modifier = Modifier) {
    val boxShape = RoundedCornerShape(4.dp)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LabelGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(boxShape)
                .background(if (rule.isSatisfied) Success else Color.Transparent)
                .then(
                    if (rule.isSatisfied) Modifier else Modifier.border(1.5.dp, Line, boxShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (rule.isSatisfied) {
                CheckmarkGlyph(size = 8.dp, color = Paper, strokeRatio = BlackStrokeRatio)
            }
        }
        Text(
            text = rule.label,
            style = NemaraType.caption,
            color = if (rule.isSatisfied) Ink else Ink3,
        )
    }
}

// ---------------------------------------------------------------------------
// SF Symbol glyphs
// ---------------------------------------------------------------------------
//
// Drawn rather than imported, matching how CountryPicker.kt handles the same
// problem: material icons are a different drawing with different terminals and
// different optical weight, and mixing the two makes one screen's chevron
// visibly unlike the next one's. Geometry and the semibold ratio are carried
// from CountryPicker's private glyphs, which are file-private there.

/** Stroke weight as a fraction of the glyph box, for SF Symbol `.semibold`. */
private const val SemiboldStrokeRatio = 0.145f

/**
 * Same, for `.black` — the heaviest SF Symbol weight, used by both checkmarks
 * here. One weight step up from semibold, and the same value LoginScreen.kt
 * picked for its `checkmark`, so the two screens' checkmarks match.
 */
private const val BlackStrokeRatio = 0.19f

@Composable
private fun CheckmarkGlyph(size: Dp, color: Color, strokeRatio: Float) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * strokeRatio
        drawGlyphLine(color, stroke, Offset(w * 0.12f, w * 0.55f), Offset(w * 0.38f, w * 0.82f))
        drawGlyphLine(color, stroke, Offset(w * 0.38f, w * 0.82f), Offset(w * 0.88f, w * 0.20f))
    }
}

@Composable
private fun ChevronLeftGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SemiboldStrokeRatio
        // Inset by half the stroke so the round caps stay inside the box.
        val inset = stroke / 2f
        drawGlyphLine(color, stroke, Offset(w * 0.70f, inset), Offset(w * 0.30f, w / 2f))
        drawGlyphLine(color, stroke, Offset(w * 0.30f, w / 2f), Offset(w * 0.70f, w - inset))
    }
}

private fun DrawScope.drawGlyphLine(
    color: Color,
    strokeWidth: Float,
    start: Offset,
    end: Offset,
) {
    // Round caps at a shared point render the same as a round join, which is
    // what SF Symbol terminals look like.
    drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
}
