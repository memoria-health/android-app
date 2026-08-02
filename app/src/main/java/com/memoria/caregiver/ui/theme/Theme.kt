package com.memoria.caregiver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The app theme. Spec: PARITY.md §9 R8 — iOS is light-mode only.
 *
 * Three things are deliberately absent and must stay absent:
 *
 * 1. **No isSystemInDarkTheme().** The scheme is a constant, so the app renders
 *    identically whatever the system setting is. iOS has no dark variant of any
 *    token, so honouring dark mode here would mean inventing a palette the
 *    designers never made.
 * 2. **No dynamicColor / Material You.** It would repaint the brand from the
 *    user's wallpaper. Terra rust is the brand.
 * 3. **No dark colorScheme defined at all.** Defining one "for later" is how it
 *    ends up wired up by accident.
 *
 * Material3's scheme is populated because material3 components (Surface, Text,
 * TextField) read their defaults from it. Feature code should still reach for
 * the named tokens directly — MaterialTheme.colorScheme is the floor, not the
 * palette.
 */
private val MemoriaColorScheme = lightColorScheme(
    primary = Action,
    onPrimary = ActionInk,
    primaryContainer = ActionSoft,
    onPrimaryContainer = Ink,

    secondary = Terra,
    onSecondary = TerraInk,
    secondaryContainer = TerraSoft,
    onSecondaryContainer = TerraDeep,

    tertiary = Sky,
    onTertiary = Card,
    tertiaryContainer = SkySoft,
    onTertiaryContainer = Ink,

    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = CardAlt,
    onSurfaceVariant = Ink3,

    error = Danger,
    onError = TerraInk,
    errorContainer = TerraSoft,
    onErrorContainer = TerraDeep,

    outline = Line,
    outlineVariant = Line,
)

/**
 * Material3 typography mapped onto the Memoria styles, so stray material3
 * components do not fall back to Roboto. Feature code uses NemaraType directly.
 */
private val MemoriaTypography = Typography(
    displayLarge = NemaraType.display,
    displayMedium = NemaraType.largeTitle,
    displaySmall = NemaraType.title1,
    headlineLarge = NemaraType.title1,
    headlineMedium = NemaraType.title2,
    headlineSmall = NemaraType.title3,
    titleLarge = NemaraType.title2,
    titleMedium = NemaraType.title3,
    titleSmall = NemaraType.eventTitle,
    bodyLarge = NemaraType.bodyLarge,
    bodyMedium = NemaraType.body,
    bodySmall = NemaraType.bodySmall,
    labelLarge = NemaraType.labelStrong,
    labelMedium = NemaraType.label,
    labelSmall = NemaraType.caption,
)

@Composable
fun MemoriaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MemoriaColorScheme,
        typography = MemoriaTypography,
        content = content,
    )
}
