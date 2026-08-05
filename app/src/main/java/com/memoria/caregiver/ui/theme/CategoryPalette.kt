package com.memoria.caregiver.ui.theme

import androidx.compose.ui.graphics.Color
import com.memoria.caregiver.data.model.Detector

/**
 * The one place a detector/event category becomes a colour or a label.
 *
 * This replaced a dozen copies of `category == Category.SAFETY` scattered
 * across the roster row, detector row, detector detail, add-detector picker
 * and both event surfaces. With two categories a boolean was survivable; with
 * four it was a dozen places to forget, and a fifth would have meant hunting
 * them all again.
 *
 * Hexes match the iOS asset catalog and the web tokens exactly, so a category
 * looks the same on every surface.
 *
 * Pairing rule: colour NEVER carries the category alone. Four muted hues at
 * chip size is past what colour can reliably distinguish, and this audience
 * skews 50+. Every surface pairs the hue with the detector's icon or a text
 * label, so colour is reinforcement rather than information.
 */

/** Foreground: icon tint, emphasised text, rules. */
val Detector.Category.accent: Color
    get() = when (this) {
        Detector.Category.SAFETY -> Terra
        Detector.Category.ROUTINE -> Sky
        Detector.Category.BEHAVIOUR -> Plum
        Detector.Category.CARE -> Sage
        // A category this build predates. Neutral ink rather than a guessed
        // hue: rendering it in another category's colour would be an outright
        // lie about what happened.
        Detector.Category.UNKNOWN -> Ink3
    }

/** Background wash: chips, card tints, tile fills. */
val Detector.Category.soft: Color
    get() = when (this) {
        Detector.Category.SAFETY -> TerraSoft
        Detector.Category.ROUTINE -> SkySoft
        Detector.Category.BEHAVIOUR -> PlumSoft
        Detector.Category.CARE -> SageSoft
        Detector.Category.UNKNOWN -> CardAlt
    }

/** Text that must hold contrast on [soft]. */
val Detector.Category.deep: Color
    get() = when (this) {
        Detector.Category.SAFETY -> TerraDeep
        // The others have no separate deep token yet; the accent already
        // holds enough contrast on its own soft wash. Kept as one `when` so
        // the day a deep plum exists there is exactly one place to add it.
        else -> accent
    }

/** Caregiver-facing label. */
val Detector.Category.displayName: String
    get() = when (this) {
        Detector.Category.SAFETY -> "Safety"
        Detector.Category.ROUTINE -> "Routine"
        Detector.Category.BEHAVIOUR -> "Behaviour"
        Detector.Category.CARE -> "Care"
        Detector.Category.UNKNOWN -> "Other"
    }

/**
 * Whether this category escalates a patient card to the attention treatment.
 *
 * Only SAFETY does, deliberately: a red card that fires for everything trains
 * people to ignore it. Named rather than `== SAFETY` at the call site so it
 * states the intent (does this shout?) instead of the mechanism.
 */
val Detector.Category.escalates: Boolean
    get() = this == Detector.Category.SAFETY

/**
 * The categories a caregiver may choose, in the order they are offered.
 * Excludes UNKNOWN, which exists only to absorb a value from a newer server
 * and must never be selectable.
 */
val SELECTABLE_CATEGORIES: List<Detector.Category> = listOf(
    Detector.Category.SAFETY,
    Detector.Category.ROUTINE,
    Detector.Category.BEHAVIOUR,
    Detector.Category.CARE,
)
