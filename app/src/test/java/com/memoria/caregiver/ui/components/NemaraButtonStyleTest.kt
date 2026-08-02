package com.memoria.caregiver.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The button's variant and size mappings, pulled out as pure functions so they
 * are Tier-1 testable. Spec: PARITY.md §13 and DesignSystem/Components/NemaraButton.swift.
 */
class NemaraButtonStyleTest {

    // ---- Variants: background / foreground / border ----

    @Test fun `primary is action on action`() {
        assertEquals(Action, NemaraButtonVariant.Primary.background)
        assertEquals(ActionInk, NemaraButtonVariant.Primary.foreground)
        assertEquals(Action, NemaraButtonVariant.Primary.border)
    }

    @Test fun `terra is the brand CTA`() {
        assertEquals(Terra, NemaraButtonVariant.Terra.background)
        assertEquals(TerraInk, NemaraButtonVariant.Terra.foreground)
        assertEquals(Terra, NemaraButtonVariant.Terra.border)
    }

    @Test fun `secondary is card on a hairline border`() {
        assertEquals(Card, NemaraButtonVariant.Secondary.background)
        assertEquals(Ink, NemaraButtonVariant.Secondary.foreground)
        assertEquals(Line, NemaraButtonVariant.Secondary.border)
    }

    @Test fun `ghost is bare text with no background or border`() {
        assertEquals(Color.Transparent, NemaraButtonVariant.Ghost.background)
        assertEquals(Ink, NemaraButtonVariant.Ghost.foreground)
        assertEquals(Color.Transparent, NemaraButtonVariant.Ghost.border)
    }

    /**
     * danger renders identically to terra on iOS. They stay separate variants
     * because the call site's intent differs — danger is reserved for
     * destructive confirmations. Collapsing them would mean a future change to
     * the destructive treatment silently repaints every brand CTA.
     */
    @Test fun `danger renders as terra but stays a distinct variant`() {
        assertEquals(Terra, NemaraButtonVariant.Danger.background)
        assertEquals(TerraInk, NemaraButtonVariant.Danger.foreground)
        assertEquals(Terra, NemaraButtonVariant.Danger.border)
        assertEquals(5, NemaraButtonVariant.entries.size)
    }

    // ---- Sizes: font, icon, padding ----

    @Test fun `small size metrics`() {
        assertEquals(13.sp, NemaraButtonSize.Sm.fontSize)
        assertEquals(15.sp, NemaraButtonSize.Sm.iconSize)
        assertEquals(8.dp, NemaraButtonSize.Sm.paddingVertical)
        assertEquals(12.dp, NemaraButtonSize.Sm.paddingHorizontal)
    }

    @Test fun `medium size metrics`() {
        assertEquals(14.sp, NemaraButtonSize.Md.fontSize)
        assertEquals(16.sp, NemaraButtonSize.Md.iconSize)
        assertEquals(12.dp, NemaraButtonSize.Md.paddingVertical)
        assertEquals(16.dp, NemaraButtonSize.Md.paddingHorizontal)
    }

    @Test fun `large size metrics`() {
        assertEquals(16.sp, NemaraButtonSize.Lg.fontSize)
        assertEquals(18.sp, NemaraButtonSize.Lg.iconSize)
        assertEquals(16.dp, NemaraButtonSize.Lg.paddingVertical)
        assertEquals(20.dp, NemaraButtonSize.Lg.paddingHorizontal)
    }

    /** iOS: iconSize is always fontSize + 2. */
    @Test fun `icon is always two points larger than the label`() {
        NemaraButtonSize.entries.forEach {
            assertEquals(it.fontSize.value + 2f, it.iconSize.value, 0.001f)
        }
    }
}
