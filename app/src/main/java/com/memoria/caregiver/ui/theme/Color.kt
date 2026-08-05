package com.memoria.caregiver.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Memoria color palette — 22 tokens. Spec: PARITY.md §13.
 *
 * On iOS the source of truth is the asset catalog rather than Swift, because
 * iOS launch screens can only reference catalog colors. Android has no such
 * constraint, so Kotlin is the single source of truth here and only the one or
 * two tokens the platform needs before Compose starts are mirrored into
 * res/values/colors.xml.
 *
 * Light mode only. There are no dark variants on iOS and there must be none
 * here (PARITY.md §9 R8).
 *
 * "Warm cream paper carries the interface. Terra rust is the brand primary.
 * Warm near-black is the action color. Sky blue is the dedicated routine-event
 * palette."
 *
 * Five tokens share a value with another token (Action==Ink, ActionSoft==PaperDim,
 * TerraInk==ActionInk==Card, Danger==Terra, Info==Sky). They stay separate names
 * because call sites use the semantic name — collapsing them would couple, for
 * example, every error state to the brand color. ColorTest pins this.
 */

// ---- Surfaces ----
val Paper = Color(0xFFEFE6DA)
val PaperDim = Color(0xFFE6DDC8)
val Card = Color(0xFFFBF8F0)
val CardAlt = Color(0xFFF0E8D6)

// ---- Ink (text) ----
val Ink = Color(0xFF1C1A14)
val Ink2 = Color(0xFF2E2A20)
val Ink3 = Color(0xFF4A4539)
val Ink4 = Color(0xFF8A8373)
val Line = Color(0xFFC9C0AB)

// ---- Brand: terra rust (primary) ----
val Terra = Color(0xFFA04331)
val TerraSoft = Color(0xFFF2DDD3)
val TerraDeep = Color(0xFF7A2F1F)
val TerraInk = Color(0xFFFBF8F0)

// ---- Action: warm near-black (primary CTA, active states) ----
val Action = Color(0xFF1C1A14)
val ActionSoft = Color(0xFFE6DDC8)
val ActionInk = Color(0xFFFBF8F0)

// ---- Routine: sky blue (dedicated for routine events) ----
val Sky = Color(0xFF6B8AA0)
val SkySoft = Color(0xFFDDE6EE)

// Behaviour · plum.  Care · sage.
// Muted to the same register as Terra and Sky: four category hues must read
// as one family, not one shouting colour and three quiet ones. Identical
// hexes to the iOS asset catalog and the web tokens so a category looks the
// same on every surface.
val Plum = Color(0xFF8A6E96)
val PlumSoft = Color(0xFFE8E0EC)
val Sage = Color(0xFF6E9070)
val SageSoft = Color(0xFFDFEADF)

// ---- Semantic ----
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFF59E0B)
val Danger = Color(0xFFA04331)
val Info = Color(0xFF6B8AA0)
