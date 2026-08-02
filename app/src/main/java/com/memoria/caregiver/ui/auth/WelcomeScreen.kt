package com.memoria.caregiver.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraMark
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing

/**
 * The screen name reported to analytics, matching `.trackScreen("auth.welcome")`
 * on the SwiftUI view.
 */
private const val ScreenName = "auth.welcome"

/**
 * The four edge insets. iOS writes these as literals (28 / 24 / 28) rather than
 * NemaraSpacing tokens, and only the top one happens to land on a token value,
 * so they are carried across as literals too. Inventing a "28" token to make
 * this look tokenised would put a value in the scale that the design system does
 * not have.
 */
private val HorizontalPadding = 28.dp
private val TopPadding = Spacing.xl // 24 — the one inset that is on the scale
private val BottomPadding = 28.dp

/** Side of the brand mark. iOS: `NemaraMark(size: 56)`. */
private val MarkSize = 56.dp

/** Gap between the mark and the headline block. iOS: `VStack(spacing: 22)`. */
private val MarkToCopySpacing = 22.dp

/** Gap between headline and subhead. iOS: `VStack(spacing: 18)`. */
private val HeadlineToSubheadSpacing = 18.dp

/** Gap between the two CTAs. iOS: `VStack(spacing: 10)`. */
private val CtaSpacing = 10.dp

/**
 * Kerning on the hero headline. iOS: `.kerning(-1.5)` on the display style.
 * Per DECISIONS.md D-008 the type tokens carry no letter spacing, so it is
 * applied here, at the call site, exactly as SwiftUI does.
 */
private val HeadlineKerning = (-1.5).sp

/**
 * Headline line height, 52.25sp.
 *
 * SwiftUI's `.lineSpacing(-2)` shortens the gap between lines by 2 points from
 * the font's own default. Compose has no relative equivalent — `lineHeight` is
 * absolute — so the default is computed from the shipped font and 2 subtracted.
 * Fraunces 144-soft declares ascent 1956, descent -510 and zero line gap over
 * 2000 units per em, so its natural line height is 1.2330 x the point size:
 * 44 x 1.2330 = 54.25, minus 2 = 52.25.
 *
 * Note this is deliberately NOT the 44/1.02 pairing recorded in the comment on
 * `NemaraFont.display`. That figure comes from the design-system HTML; the
 * shipped SwiftUI view does not use it, and the port follows the shipped view.
 *
 * One knowingly-accepted difference: SwiftUI's line spacing only affects the
 * gaps *between* lines, while a Compose `lineHeight` also sizes the first line's
 * own box. Since this value is smaller than the natural line height, that adds
 * no space above the headline — it can only trim, which is the intended
 * direction anyway.
 */
private val HeadlineLineHeight = 52.25.sp

/**
 * Subhead line height, 23.36sp. Same derivation as [HeadlineLineHeight], from
 * SwiftUI's `.lineSpacing(4)`: Inter 18pt declares ascent 1984, descent -494 and
 * zero line gap over 2048 units per em, a natural 1.2100 x the point size, so
 * 16 x 1.2100 = 19.36, plus 4 = 23.36.
 *
 * Here the value is *larger* than natural, so Compose distributes roughly 2sp of
 * it above the first line and below the last, which SwiftUI would not. Two
 * scaled pixels on a block that sits between two equal flexible spacers is not
 * worth an experimental line-height-trim API to remove.
 */
private val SubheadLineHeight = 23.36.sp

/**
 * First screen on a fresh launch. Spec: `Features/Auth/WelcomeView.swift`.
 *
 * The hero serif headline ("The other 99.9% of care.") plus the warm-cream paper
 * canvas; two CTAs at the bottom — Sign up (primary, large) and Log in (ghost).
 *
 * Static: it has no state object at all, only the two navigations out. iOS
 * expresses those as `NavigationLink(value: AuthRoute.signUp / .login)` inside
 * the view; here they are callbacks, because the navigation graph is not this
 * file's to own.
 *
 * @param onSignUp tapping "Create an account". iOS: `AuthRoute.signUp`.
 * @param onLogin tapping "I already have one". iOS: `AuthRoute.login`.
 */
@Composable
fun WelcomeScreen(
    onSignUp: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val analytics = LocalAnalytics.current
    // iOS attaches this with `.trackScreen("auth.welcome")`, which is an
    // `onAppear`. Logging is a synchronous, non-throwing buffer append
    // (PARITY.md §14), so this stays a side-effect-only call with nothing
    // awaiting it.
    LaunchedEffect(Unit) { analytics.logScreen(ScreenName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Background sits under the insets, as it does on iOS where
            // `.background` is applied after the padding and the frame.
            .background(Paper)
            .padding(horizontal = HorizontalPadding)
            .padding(top = TopPadding, bottom = BottomPadding),
    ) {
        // The two `Spacer(minLength: 0)` around the hero. Equal weights centre
        // the block and let it grow past centre on a short screen.
        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MarkToCopySpacing),
        ) {
            NemaraMark(size = MarkSize)

            Column(verticalArrangement = Arrangement.spacedBy(HeadlineToSubheadSpacing)) {
                Text(
                    text = "The other 99.9% of care.",
                    style = NemaraType.display,
                    color = Ink,
                    letterSpacing = HeadlineKerning,
                    lineHeight = HeadlineLineHeight,
                )
                Text(
                    text = "Stay close to the people you love, even when you can't be in the room.",
                    style = NemaraType.bodyLarge,
                    color = Ink2,
                    lineHeight = SubheadLineHeight,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(CtaSpacing)) {
            // iOS builds these two from private label helpers rather than
            // NemaraButton, because a NavigationLink needs a label view. The
            // helpers are, by their own comment, "full-block versions of
            // NemaraButton" and reproduce it exactly: primary is 16pt semibold /
            // 16pt vertical padding / action on actionInk / radius md, which is
            // Primary + Lg + block; ghost is 14pt semibold / 12pt vertical
            // padding / ink on nothing, which is Ghost + Md + block. Both apply
            // tracking -0.1, which NemaraButton already carries.
            NemaraButton(
                title = "Create an account",
                variant = NemaraButtonVariant.Primary,
                size = NemaraButtonSize.Lg,
                block = true,
                onClick = onSignUp,
            )
            NemaraButton(
                title = "I already have one",
                variant = NemaraButtonVariant.Ghost,
                size = NemaraButtonSize.Md,
                block = true,
                onClick = onLogin,
            )
        }
    }
}
