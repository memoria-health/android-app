package com.memoria.caregiver.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing and radius scales. Spec: PARITY.md §13, NemaraSpacing.swift and
 * NemaraRadius.swift, both tracing to nemara_design_system.html §04 Geometry.
 *
 * Use the named tokens at call sites rather than raw numbers — that is what
 * keeps vertical rhythm consistent across screens.
 */

object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val huge = 56.dp
}

object Radius {
    /** 6 — chips, small inset surfaces. */
    val sm = 6.dp

    /** 10 — text fields, buttons. */
    val md = 10.dp

    /** 14 — primary card. */
    val lg = 14.dp

    /** 20 — extra-large surfaces. */
    val xl = 20.dp

    /**
     * Pill. iOS uses 999 rather than a true capsule so the SwiftUI renderer
     * settles; Compose has RoundedCornerShape(percent = 50) and no such quirk,
     * but the value is kept so shared components measured against the iOS
     * screenshots cannot drift. Any value >= height/2 renders identically.
     */
    val pill = 999.dp
}
