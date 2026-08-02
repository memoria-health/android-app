package com.memoria.caregiver.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType

/**
 * Mono-uppercase eyebrow label — the design system's "ts-eye" pattern.
 * Spec: DesignSystem/Components/NemaraEyebrow.swift.
 *
 * JetBrains Mono 11, SemiBold, 0.6 tracking, uppercase. Default color is `Ink3`
 * (muted); section eyebrows above hero headlines pass `Terra`.
 *
 * The text is uppercased at render, matching SwiftUI's `.textCase(.uppercase)` —
 * so call sites pass normal-case strings and the component owns the casing.
 * Uppercasing at the call site instead would make the analytics and the
 * accessibility label shout too.
 */
@Composable
fun NemaraEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink3,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = NemaraType.eyebrow,
        // Per-call-site kerning, per DECISIONS.md D-008. iOS: .tracking(0.6)
        letterSpacing = 0.6.sp,
        color = color,
    )
}
