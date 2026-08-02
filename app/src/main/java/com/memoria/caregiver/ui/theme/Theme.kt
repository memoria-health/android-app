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
    // primary is Terra, NOT Action. Material3 collapses two roles iOS keeps
    // separate: the primary CTA fill and the app-wide control accent. iOS sets
    // the accent globally — AccentColor.colorset is #A04331 and
    // App/NemaraCaregiverApp.swift:71 applies .tint(NemaraColor.terra) to the
    // root — and everything that tints from it (caret, focused field indicator,
    // switches, sliders, selection handles) is rust.
    //
    // The CTA fill is unaffected by this choice because NemaraButton does not
    // read colorScheme at all; it resolves its colors from the variant enum. So
    // pointing primary at Terra costs nothing on the CTA axis and fixes the
    // accent axis. NemaraField.swift:60 tints its caret terra explicitly,
    // which is the same requirement.
    primary = Terra,
    onPrimary = TerraInk,
    primaryContainer = TerraSoft,
    onPrimaryContainer = TerraDeep,

    secondary = Action,
    onSecondary = ActionInk,
    secondaryContainer = ActionSoft,
    onSecondaryContainer = Ink,

    tertiary = Sky,
    onTertiary = TerraInk,
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

    // Material3 defines 36 roles. Any left unset keeps the Material baseline,
    // which is lavender — and the roles most likely to be hit are exactly the
    // ones stray components read: DropdownMenu and ModalBottomSheet take
    // surfaceContainer / surfaceContainerLow, Snackbar takes inverseSurface,
    // the date and time pickers take surfaceContainerHigh. Phase 5 is full of
    // sheets, so leaving these would put lavender-tinted neutrals inside a
    // cream app. Every role is pinned to the Memoria palette; ThemeTest asserts
    // no baseline color survives.
    surfaceContainerLowest = Card,
    surfaceContainerLow = Card,
    surfaceContainer = Card,
    surfaceContainerHigh = CardAlt,
    surfaceContainerHighest = CardAlt,
    surfaceBright = Card,
    surfaceDim = PaperDim,
    inverseSurface = Ink,
    inverseOnSurface = Card,
    inversePrimary = TerraSoft,
    scrim = Ink,

    // The "fixed" roles hold a colour that is identical in light and dark
    // themes. This app is light-only, so they simply take the matching
    // container tokens. They exist here because ThemeTest's reflection walk
    // caught them the moment the Compose BOM that introduced them landed —
    // twelve more lavenders that would otherwise have arrived silently.
    primaryFixed = TerraSoft,
    primaryFixedDim = TerraSoft,
    onPrimaryFixed = TerraDeep,
    onPrimaryFixedVariant = TerraDeep,
    secondaryFixed = ActionSoft,
    secondaryFixedDim = ActionSoft,
    onSecondaryFixed = Ink,
    onSecondaryFixedVariant = Ink2,
    tertiaryFixed = SkySoft,
    tertiaryFixedDim = SkySoft,
    onTertiaryFixed = Ink,
    onTertiaryFixedVariant = Ink2,
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
