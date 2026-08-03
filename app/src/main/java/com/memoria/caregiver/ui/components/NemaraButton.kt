package com.memoria.caregiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.InterFamily
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import androidx.compose.ui.text.font.FontWeight

/**
 * Five variants. Background, foreground and border per
 * DesignSystem/Components/NemaraButton.swift.
 *
 * Danger renders identically to Terra. They stay distinct because the call
 * site's intent differs — danger is reserved for destructive confirmations —
 * so a future change to the destructive treatment does not repaint every brand
 * CTA. Same reasoning as the aliased color tokens in PARITY.md §13.
 */
enum class NemaraButtonVariant(
    val background: Color,
    val foreground: Color,
    val border: Color,
) {
    Primary(background = Action, foreground = ActionInk, border = Action),
    Terra(background = com.memoria.caregiver.ui.theme.Terra, foreground = TerraInk, border = com.memoria.caregiver.ui.theme.Terra),
    Secondary(background = Card, foreground = Ink, border = Line),
    Ghost(background = Color.Transparent, foreground = Ink, border = Color.Transparent),
    Danger(background = com.memoria.caregiver.ui.theme.Terra, foreground = TerraInk, border = com.memoria.caregiver.ui.theme.Terra),
}

/** Three sizes. The icon is always the label size plus 2. */
enum class NemaraButtonSize(
    val fontSize: TextUnit,
    val paddingVertical: Dp,
    val paddingHorizontal: Dp,
) {
    Sm(fontSize = 13.sp, paddingVertical = 8.dp, paddingHorizontal = 12.dp),
    Md(fontSize = 14.sp, paddingVertical = 12.dp, paddingHorizontal = 16.dp),
    Lg(fontSize = 16.sp, paddingVertical = 16.dp, paddingHorizontal = 20.dp);

    val iconSize: TextUnit get() = (fontSize.value + 2f).sp
}

/**
 * The Memoria button. Spec: PARITY.md §13 and NemaraButton.swift.
 *
 * **Analytics fires from inside this component, before the action.** That is the
 * single instrumentation point for the whole app (PARITY.md §13): keeping it
 * here means analytics coverage cannot rot as screens are added, and renaming a
 * button's copy cannot silently rename its event. Logging is a synchronous,
 * non-throwing buffer append, so it cannot alter or delay [onClick].
 *
 * Logging happens *before* [onClick] because actions routinely navigate away or
 * tear down the composition, and logging afterwards would drop exactly the taps
 * a funnel cares about.
 *
 * Deliberately built from `Modifier.clickable` rather than material3's `Button`:
 * Button brings its own container colors, elevation, ripple shape and — the part
 * that actually matters — **dims its content when disabled**. iOS does not.
 * NemaraButton.swift never reads the environment's isEnabled, so a disabled
 * button there is visually identical and simply inert, and 45 call sites rely on
 * that. Dimming here would be a visible divergence on every form in the app.
 */
@Composable
fun NemaraButton(
    title: String,
    modifier: Modifier = Modifier,
    variant: NemaraButtonVariant = NemaraButtonVariant.Primary,
    size: NemaraButtonSize = NemaraButtonSize.Md,
    block: Boolean = false,
    icon: ImageVector? = null,
    track: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val analytics = LocalAnalytics.current
    val shape = RoundedCornerShape(Radius.md)
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(variant.background)
            .border(1.dp, variant.border, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                analytics.logButton(track ?: title)
                onClick()
            }
            .padding(
                vertical = size.paddingVertical,
                horizontal = size.paddingHorizontal,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = variant.foreground,
                modifier = Modifier.size(size.iconSize.value.dp),
            )
        }
        Text(
            text = title,
            color = variant.foreground,
            fontFamily = InterFamily,
            fontSize = size.fontSize,
            fontWeight = FontWeight.SemiBold,
            // iOS applies .tracking(-0.1) on the label. Per DECISIONS.md D-008
            // kerning is a per-call-site concern, and this is that call site.
            letterSpacing = (-0.1).sp,
            textAlign = TextAlign.Center,
        )
    }
}
