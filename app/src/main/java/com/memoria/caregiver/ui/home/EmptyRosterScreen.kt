package com.memoria.caregiver.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * Body-copy line height, 18.73sp.
 *
 * Inter's natural line height is 1.2100 x the point size (ascent 1984, descent
 * -494, zero line gap, 2048 units per em — the derivation WelcomeScreen.kt
 * documents). At the bodySmall token's 13sp that is 15.73, so SwiftUI's
 * `.lineSpacing(3)` is 18.73. Compose's `lineHeight` is absolute where
 * SwiftUI's `lineSpacing` only affects the gaps BETWEEN lines, so Compose also
 * distributes roughly 1.5sp above the first line and below the last; the copy
 * sits inside a padded card where that is not visible.
 */
private val BodySmallLineHeightPlus3 = 18.73.sp

/** iOS: `.frame(width: 128, height: 128)` on the concentric rings. */
private val RingsSize = 128.dp

/** iOS: `.padding(CGFloat(i) * 14)` — each ring is inset 14 further than the last. */
private val RingStep = 14.dp

/** iOS: `Circle().fill(NemaraColor.terraSoft).padding(42)` — a 44pt filled disc. */
private val RingFillInset = 42.dp

/** iOS: `.opacity(i == 2 ? 1 : 0.65)` — the two outer rings are ghosted. */
private const val OuterRingAlpha = 0.65f

/** iOS: `Image(systemName: "plus").font(.system(size: 30, weight: .semibold))`. */
private val RingsPlusSize = 30.dp

/** iOS: `.frame(maxWidth: 280)` on the body copy, so it wraps to three lines. */
private val EmptyBodyMaxWidth = 280.dp

/**
 * What a brand-new caregiver sees on first sign-in. Replaces the roster's
 * patient list with a centered "Add your first patient" card so the next action
 * is the only thing on the screen.
 *
 * Not a top-level route: this is a state of the Home tab, rendered inline from
 * [RosterScreen] when `state.patients` is empty.
 *
 * iOS binds `showingNewPatient` and presents the new-patient sheet from
 * `RosterView`; here the tap is reported through [onAddPatient] and the host
 * owns the sheet, the same seam every auth screen uses for navigation.
 */
@Composable
fun EmptyRosterScreen(
    onAddPatient: () -> Unit,
    modifier: Modifier = Modifier,
    caregiverDisplayName: String? = null,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {
        RosterLargeTitle(
            title = "Today",
            sub = "Welcome, ${rosterFirstName(caregiverDisplayName)}",
            // iOS adds .padding(.horizontal, 4) on top of the title's own 20,
            // so the empty state's header sits on a 24pt gutter while the
            // populated roster's sits on 20. Not a mistake to normalise.
            modifier = Modifier.padding(horizontal = Spacing.xxs),
        )

        // Spacer(minLength: 0) above and below: the card is centred in whatever
        // space is left under the title, and collapses to no gap on a short
        // screen rather than pushing the card off the bottom.
        Spacer(Modifier.weight(1f))

        NemaraCard(
            modifier = Modifier.padding(horizontal = Spacing.md),
            padding = Spacing.lg,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ConcentricRings()

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Add your first patient",
                        style = NemaraType.title1,
                        // Per-call-site kerning, per DECISIONS.md D-008.
                        // iOS: .kerning(-0.7)
                        letterSpacing = (-0.7).sp,
                        color = Ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Add their profile and you'll see everything " +
                            "you're verified to care for, all in one place.",
                        modifier = Modifier.widthIn(max = EmptyBodyMaxWidth),
                        style = NemaraType.bodySmall,
                        color = Ink2,
                        textAlign = TextAlign.Center,
                        // iOS: .lineSpacing(3)
                        lineHeight = BodySmallLineHeightPlus3,
                    )
                }

                NemaraButton(
                    title = "Add a patient",
                    // iOS: .padding(.top, 4) on top of the 16 stack spacing.
                    modifier = Modifier.padding(top = Spacing.xxs),
                    variant = NemaraButtonVariant.Primary,
                    size = NemaraButtonSize.Lg,
                    icon = PlusGlyph,
                    onClick = onAddPatient,
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

/**
 * Three concentric hairline rings around a filled disc with a plus in the
 * middle — the empty state's only ornament.
 *
 * SwiftUI's `.padding(n)` on a `Circle` inside a fixed frame insets it on all
 * sides, so ring `i` is `128 - 2 * 14i` across. `Modifier.matchParentSize()`
 * plus the same padding reproduces that exactly, and `Modifier.border` insets
 * its stroke inward like `strokeBorder`, so no ring overflows the 128 box.
 *
 * The alpha sits between the padding and the border on purpose: it must dim the
 * ring that is drawn, and SwiftUI applies `.opacity` to each leaf.
 */
@Composable
private fun ConcentricRings() {
    Box(
        modifier = Modifier.size(RingsSize),
        contentAlignment = Alignment.Center,
    ) {
        for (i in 0 until 3) {
            val isInnermost = i == 2
            Box(
                Modifier
                    .matchParentSize()
                    .padding(RingStep * i)
                    .alpha(if (isInnermost) 1f else OuterRingAlpha)
                    .border(
                        width = 1.dp,
                        color = if (isInnermost) Terra else TerraSoft,
                        shape = CircleShape,
                    ),
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .padding(RingFillInset)
                .background(TerraSoft, CircleShape),
        )

        Icon(
            imageVector = PlusGlyph,
            contentDescription = null,
            tint = Terra,
            modifier = Modifier.size(RingsPlusSize),
        )
    }
}

/**
 * Shared large-title layout used by [RosterScreen], [EmptyRosterScreen] and the
 * app-settings screen.
 *
 * Lives here so both roster screens can share the same eye/serif treatment —
 * the same reason `roster_largeTitle` lives in `EmptyRosterView.swift` on iOS,
 * where `AppSettingsView.swift:19` also calls it as
 * `roster_largeTitle(title: "Settings")`. That third call site passes neither
 * `sub` nor `trailing`, which collapses this to a plain full-width title, and
 * `ui/settings/AppSettingsScreen.kt` draws that degenerate case directly rather
 * than depending on the roster unit. Public here so it does not have to.
 *
 * iOS aligns the row on `.lastTextBaseline`, so the trailing controls sit on the
 * title's baseline rather than on the bottom of its line box — about 8dp apart
 * at 32pt. [trailing] is given a `RowScope`, so it opts in with
 * `Modifier.alignBy(LastBaseline)`; `Alignment.Bottom` is the fallback for
 * content that has no baseline at all, which is also what SwiftUI does.
 */
@Composable
fun RosterLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xxs, bottom = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            // weight(1f) is SwiftUI's Spacer(): the title column takes the slack
            // and the trailing controls are pushed to the trailing edge.
            modifier = Modifier.weight(1f).alignBy(LastBaseline),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (sub != null) {
                NemaraEyebrow(text = sub)
            }
            Text(
                text = title,
                style = NemaraType.largeTitle,
                // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-1)
                letterSpacing = (-1).sp,
                color = Ink,
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}
