package com.memoria.caregiver.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing

/**
 * A destination that is genuinely not built, saying so on screen.
 *
 * **This exists so that "unbuilt" and "broken" cannot be confused.** The
 * alternative — leaving the control that reaches here wired to `{}` — renders an
 * ordinary tappable row that silently does nothing, which a caregiver reads as a
 * bug in a shipped product rather than as a gap. That was FINDINGS.md F-32:
 * twelve inert callbacks, eleven of which looked like broken buttons.
 *
 * Reaching a screen that admits it is missing is strictly better than reaching
 * nothing, so every destination that cannot be built today ends up here with
 * [reason] naming what it is waiting on.
 *
 * Carries its own [BackHandler] because it is presented as a conditional branch
 * rather than through a navigation graph. Without one, Back — and the edge
 * swipe, which is how most people leave a screen — falls through to the Activity
 * and quits the app. That was FINDINGS.md F-25, a Critical.
 */
const val NOT_BUILT_TAG: String = "not_built"

@Composable
fun NotBuiltScreen(
    title: String,
    reason: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .padding(Spacing.xl)
            .testTag(NOT_BUILT_TAG),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NemaraEyebrow(title)
        Text(
            text = "Not built yet",
            style = NemaraType.body,
            color = Ink,
        )
        Text(
            text = reason,
            modifier = Modifier.fillMaxWidth(),
            style = NemaraType.caption,
            color = Ink3,
            textAlign = TextAlign.Center,
        )
        NemaraButton(
            title = "Close",
            variant = NemaraButtonVariant.Secondary,
            onClick = onBack,
        )
    }
}
