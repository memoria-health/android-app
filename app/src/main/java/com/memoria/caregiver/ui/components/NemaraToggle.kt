package com.memoria.caregiver.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Line

/**
 * Warm-black on/off pill. Spec: DesignSystem/Components/NemaraToggle.swift.
 *
 * 40 × 24 with a 20dp knob — deliberately tighter than the platform switch, and
 * using the Memoria action color rather than the system green. Built from
 * primitives rather than material3's `Switch` for the same reason NemaraButton
 * avoids `Button`: `Switch` brings its own dimensions, thumb sizing and color
 * roles, none of which match.
 *
 * **Logs the NEW value before flipping**, matching iOS. A toggle logged with its
 * old value reads backwards in the funnel, and logging after the flip loses the
 * event if the state change tears down the composition.
 *
 * [track] exists because toggles have no visible title, so there is nothing to
 * fall back to. iOS defaults to the literal string "toggle" when unset, which is
 * useless in aggregate but is the documented behavior; set it wherever the flip
 * is worth tracking.
 */
@Composable
fun NemaraToggle(
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    track: String? = null,
    enabled: Boolean = true,
) {
    val analytics = LocalAnalytics.current
    val interactionSource = remember { MutableInteractionSource() }

    // iOS: withAnimation(.spring(response: 0.28, dampingFraction: 0.78)).
    // Compose expresses springs as dampingRatio + stiffness rather than
    // response; StiffnessMediumLow settles in ~0.3s, which is the closest
    // standard value to a 0.28 response.
    val knobOffset by animateDpAsState(
        targetValue = if (isOn) 8.dp else (-8).dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "knobOffset",
    )

    Box(
        modifier = modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(CircleShape)
            .background(if (isOn) Action else Line)
            // Named in the semantics tree by the same string that names it in
            // analytics. Toggles carry no visible title, so a test asserting on
            // one otherwise has to select "the switch" — which works until a
            // screen grows a second switch, and then silently targets the wrong
            // control. One id, two uses, no third concept.
            .then(if (track != null) Modifier.testTag(track) else Modifier)
            // `toggleable`, not `clickable`. Both give the tap; only `toggleable`
            // publishes a ToggleableState, which is what a screen reader reads
            // to say "on" or "off". With `clickable(role = Role.Switch)` the
            // control announced itself as a switch and then could not say which
            // way it was set — across every toggle in the app, including the
            // privacy ones. Caregivers are exactly the population most likely to
            // be using a screen reader.
            .toggleable(
                value = isOn,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) { next ->
                analytics.logToggle(track ?: "toggle", next)
                onToggle(next)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(Card),
        )
    }
}
