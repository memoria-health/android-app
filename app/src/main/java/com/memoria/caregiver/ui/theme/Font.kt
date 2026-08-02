package com.memoria.caregiver.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.memoria.caregiver.R

/**
 * The three font families. Spec: PARITY.md §13 and NemaraFont.swift.
 *
 * Fraunces ships as a pre-baked SOFT-75 / opsz-144 static cut, which satisfies
 * the design system's `font-variation-settings: "SOFT" 75, "opsz" 144` without
 * pulling in the multi-megabyte variable font.
 *
 * **Fraunces has no Bold cut.** iOS resolves this explicitly, mapping
 * .bold/.heavy/.black onto the SemiBold file. Compose reaches the same result
 * implicitly: its font matcher follows CSS font-matching, so a request for
 * W700 with nothing heavier than W600 available walks down to W600. Declaring
 * only the three real cuts is therefore the correct port.
 *
 * Do NOT "fix" the missing Bold by registering the SemiBold file as
 * FontWeight.Bold. That collapses W600 and W700 into the same face and destroys
 * the SemiBold/Bold distinction that eventTitle and monoMicro depend on.
 */

val FrauncesFamily = FontFamily(
    Font(R.font.fraunces_144_soft_regular, FontWeight.Normal),
    Font(R.font.fraunces_144_soft_medium, FontWeight.Medium),
    Font(R.font.fraunces_144_soft_semibold, FontWeight.SemiBold),
)

val InterFamily = FontFamily(
    Font(R.font.inter_18pt_regular, FontWeight.Normal),
    Font(R.font.inter_18pt_medium, FontWeight.Medium),
    Font(R.font.inter_18pt_semibold, FontWeight.SemiBold),
    Font(R.font.inter_18pt_bold, FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)
