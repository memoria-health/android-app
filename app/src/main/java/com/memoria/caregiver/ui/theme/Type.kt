package com.memoria.caregiver.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The 20 named type styles. Spec: PARITY.md §13 and NemaraFont.swift.
 *
 * **These carry family, size and weight only — no letter spacing, no line
 * height.** That mirrors iOS, where NemaraFont builds styles from those three
 * properties and 60 call sites apply .kerning()/.tracking() individually (9
 * distinct kerning values, 7 distinct tracking values).
 *
 * Compose's TextStyle can hold letterSpacing, so baking the documented values
 * in is tempting and would be more idiomatic. It is also wrong: it would apply
 * kerning at every call site including the ~40 that deliberately have none,
 * shifting text metrics app-wide against the iOS screenshots. Kerning stays a
 * per-call-site concern, ported screen by screen. See DECISIONS.md D-008.
 *
 * The iOS comments record intended line-height/kerning pairs for the largest
 * three serif styles (display 44/1.02 · -1.5, largeTitle 32/1.05 · -1,
 * title1 28/1.1 · -0.8); those are applied where used, not here.
 */
object NemaraType {

    // ---- Display & headlines (Fraunces / serif) ----
    val display = TextStyle(fontFamily = FrauncesFamily, fontSize = 44.sp, fontWeight = FontWeight.Normal)
    val largeTitle = TextStyle(fontFamily = FrauncesFamily, fontSize = 32.sp, fontWeight = FontWeight.Normal)
    val title1 = TextStyle(fontFamily = FrauncesFamily, fontSize = 28.sp, fontWeight = FontWeight.Normal)

    /** Card heading. */
    val title2 = TextStyle(fontFamily = FrauncesFamily, fontSize = 22.sp, fontWeight = FontWeight.Normal)

    /** Small serif. */
    val title3 = TextStyle(fontFamily = FrauncesFamily, fontSize = 18.sp, fontWeight = FontWeight.Normal)

    /** Event card title. */
    val eventTitle = TextStyle(fontFamily = FrauncesFamily, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

    // ---- Body (Inter / sans) ----
    val bodyLarge = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.Normal)
    val body = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val bodyMedium = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val bodySemibold = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val bodySmall = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val bodySmallStrong = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val labelStrong = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Normal)
    val captionStrong = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

    // ---- Mono (JetBrains Mono) ----
    val eyebrow = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val monoSmall = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val monoMicro = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    val monoValue = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}
