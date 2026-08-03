package com.memoria.caregiver.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * The two `plainClickable` siblings, for controls that are a **choice** rather
 * than an action.
 *
 * `plainClickable` announces `Role.Button`, which is right for "do this now" —
 * Enroll, Send test email, Cancel. It is wrong for a segment of a segmented
 * control or a row with a checkmark, because a button carries no state: a
 * screen reader would say "Wi-Fi, button" and never say which of the two
 * segments is currently chosen. The caregiver would have to see the screen to
 * know what they had picked, which is precisely what a screen reader is for.
 *
 * These live in one file rather than one copy per package, for the reason
 * `DetectorGlyphs.kt` gives for its own single copy: several private copies is
 * how one of them quietly acquires an indication.
 *
 * Both suppress the ripple. iOS uses `.buttonStyle(.plain)` throughout, and
 * these controls draw their own selected state with a fill.
 */
@Composable
internal fun Modifier.plainSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    /**
     * `Role.Tab` for the segmented controls that port a SwiftUI
     * `Picker(...).pickerStyle(.segmented)`, `Role.RadioButton` for a vertical
     * list of exclusive options. TalkBack phrases the two differently and the
     * distinction is the one a sighted user sees.
     */
    role: Role = Role.RadioButton,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.selectable(
        selected = selected,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/**
 * A row that turns something on and off, where the on state is drawn as a
 * checkmark rather than as a switch. Announces the state, which is the whole
 * point — see [plainSelectable].
 *
 * Takes a `() -> Unit` because every caller already has one: these rows flip a
 * value the caller holds, so the new value is never in question.
 */
@Composable
internal fun Modifier.plainToggleable(
    checked: Boolean,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    onToggle: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.toggleable(
        value = checked,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onValueChange = { onToggle() },
    )
}
