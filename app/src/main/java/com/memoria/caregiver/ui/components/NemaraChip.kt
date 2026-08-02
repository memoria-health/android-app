package com.memoria.caregiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * The two amber values below are the ONLY colors in the component layer that are
 * not palette tokens. They come straight from NemaraChip.swift, where the
 * warning tone is built from literals rather than from `NemaraColor.warning`.
 *
 * That is deliberate on iOS and the comment says why: the foreground is a
 * darkened amber chosen to clear AA contrast against the soft amber fill. The
 * palette's `Warning` (#F59E0B) is a mid-tone that fails as text on a light
 * fill. Do not "clean this up" by pointing them at Warning.
 *
 * They are not promoted into the palette because no other component uses them,
 * and the 22 tokens are pinned against the iOS asset catalog by ColorTest.
 */
internal val ChipAmberSoft = Color(0xFFF4E3C7)
internal val ChipAmberInk = Color(0xFF8C5F1F)

/** Chip tones. Spec: DesignSystem/Components/NemaraChip.swift. */
enum class NemaraChipTone(val background: Color, val foreground: Color) {
    Neutral(background = PaperDim, foreground = Ink2),
    Terra(background = TerraSoft, foreground = com.memoria.caregiver.ui.theme.Terra),
    Sky(background = SkySoft, foreground = com.memoria.caregiver.ui.theme.Sky),
    Action(background = ActionSoft, foreground = com.memoria.caregiver.ui.theme.Action),
    Warning(background = ChipAmberSoft, foreground = ChipAmberInk),
    Dark(background = Ink, foreground = Paper),
}

/**
 * Pill chip. Status badges (ALL ONLINE / PARTIAL / OFFLINE), event category
 * labels, and neutral metadata pills.
 *
 * Always mono 9 Bold, 0.6 tracking, uppercase. The optional leading dot is 5dp
 * and takes its own color, because a status chip's dot and its text are tinted
 * independently on iOS.
 */
@Composable
fun NemaraChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: NemaraChipTone = NemaraChipTone.Neutral,
    icon: ImageVector? = null,
    dotColor: Color? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tone.background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(5.dp).clip(CircleShape).background(dotColor),
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone.foreground,
                modifier = Modifier.size(9.dp),
            )
        }
        Text(
            text = text.uppercase(),
            style = NemaraType.monoMicro,
            letterSpacing = 0.6.sp,
            color = tone.foreground,
        )
    }
}

/**
 * Solid-fill category pill — SAFETY / ROUTINE / CUSTOM on event cards.
 *
 * Separate from [NemaraChip] rather than a seventh tone because the background
 * is supplied by the caller: the category color comes from the event's own
 * category, not from a fixed set. The foreground is always TerraInk.
 */
@Composable
fun NemaraSolidPill(
    text: String,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = NemaraType.monoMicro,
        letterSpacing = 0.6.sp,
        color = TerraInk,
    )
}
