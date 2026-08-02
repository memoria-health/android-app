package com.memoria.caregiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.CardAlt
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * Card tones. Spec: DesignSystem/Components/NemaraCard.swift.
 *
 * Success and Action both paint ActionSoft — the same deliberate aliasing as the
 * color tokens. Kept distinct so a later change to the success treatment does
 * not repaint every action card.
 */
enum class NemaraCardTone(val background: Color) {
    Default(background = Card),
    Alt(background = CardAlt),
    Terra(background = TerraSoft),
    Sky(background = SkySoft),
    Success(background = ActionSoft),
    Action(background = ActionSoft),
}

/**
 * Raised paper card. Radius `lg` (14), 1px border defaulting to `Line`.
 *
 * Use [tone] for hero cards — terra-soft for the safety hero, sky-soft for the
 * routine hero. **Not** for event cards in the events list, which compose a
 * zero-padding card with their own background.
 */
@Composable
fun NemaraCard(
    modifier: Modifier = Modifier,
    tone: NemaraCardTone = NemaraCardTone.Default,
    padding: Dp = Spacing.md,
    borderColor: Color = Line,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier = modifier
            .clip(shape)
            .background(tone.background)
            .border(1.dp, borderColor, shape)
            .padding(padding),
        content = content,
    )
}
