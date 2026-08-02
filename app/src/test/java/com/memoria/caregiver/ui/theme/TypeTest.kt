package com.memoria.caregiver.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 20 named type styles, spec at PARITY.md §13 and
 * nemara-caregiver-app/.../Tokens/NemaraFont.swift.
 */
class TypeTest {

    private fun assertStyle(
        style: TextStyle,
        size: TextUnit,
        weight: FontWeight,
        family: androidx.compose.ui.text.font.FontFamily,
    ) {
        assertEquals(size, style.fontSize)
        assertEquals(weight, style.fontWeight)
        assertEquals(family, style.fontFamily)
    }

    // ---- Serif (Fraunces) — 6 styles ----

    @Test fun `display`() = assertStyle(NemaraType.display, 44.sp, FontWeight.Normal, FrauncesFamily)
    @Test fun `largeTitle`() = assertStyle(NemaraType.largeTitle, 32.sp, FontWeight.Normal, FrauncesFamily)
    @Test fun `title1`() = assertStyle(NemaraType.title1, 28.sp, FontWeight.Normal, FrauncesFamily)
    @Test fun `title2`() = assertStyle(NemaraType.title2, 22.sp, FontWeight.Normal, FrauncesFamily)
    @Test fun `title3`() = assertStyle(NemaraType.title3, 18.sp, FontWeight.Normal, FrauncesFamily)
    @Test fun `eventTitle`() = assertStyle(NemaraType.eventTitle, 17.sp, FontWeight.SemiBold, FrauncesFamily)

    // ---- Sans (Inter) — 10 styles ----

    @Test fun `bodyLarge`() = assertStyle(NemaraType.bodyLarge, 16.sp, FontWeight.Normal, InterFamily)
    @Test fun `body`() = assertStyle(NemaraType.body, 14.sp, FontWeight.Normal, InterFamily)
    @Test fun `bodyMedium`() = assertStyle(NemaraType.bodyMedium, 14.sp, FontWeight.Medium, InterFamily)
    @Test fun `bodySemibold`() = assertStyle(NemaraType.bodySemibold, 14.sp, FontWeight.SemiBold, InterFamily)
    @Test fun `bodySmall`() = assertStyle(NemaraType.bodySmall, 13.sp, FontWeight.Normal, InterFamily)
    @Test fun `bodySmallStrong`() = assertStyle(NemaraType.bodySmallStrong, 13.sp, FontWeight.SemiBold, InterFamily)
    @Test fun `labelStrong`() = assertStyle(NemaraType.labelStrong, 12.sp, FontWeight.SemiBold, InterFamily)
    @Test fun `label`() = assertStyle(NemaraType.label, 12.sp, FontWeight.Medium, InterFamily)
    @Test fun `caption`() = assertStyle(NemaraType.caption, 11.sp, FontWeight.Normal, InterFamily)
    @Test fun `captionStrong`() = assertStyle(NemaraType.captionStrong, 11.sp, FontWeight.SemiBold, InterFamily)

    // ---- Mono (JetBrains Mono) — 4 styles ----

    @Test fun `eyebrow`() = assertStyle(NemaraType.eyebrow, 11.sp, FontWeight.SemiBold, JetBrainsMonoFamily)
    @Test fun `monoSmall`() = assertStyle(NemaraType.monoSmall, 10.sp, FontWeight.SemiBold, JetBrainsMonoFamily)
    @Test fun `monoMicro`() = assertStyle(NemaraType.monoMicro, 9.sp, FontWeight.Bold, JetBrainsMonoFamily)
    @Test fun `monoValue`() = assertStyle(NemaraType.monoValue, 12.sp, FontWeight.SemiBold, JetBrainsMonoFamily)

    /**
     * iOS applies kerning and line height per call site, not on the token:
     * NemaraFont.swift builds styles from family/size/weight only, and 60 call
     * sites across the app apply .kerning() / .tracking() with 9 distinct
     * kerning and 7 distinct tracking values.
     *
     * Compose's TextStyle can carry letterSpacing, so it is tempting to bake the
     * documented values in. Doing so would apply kerning at every call site
     * including the ~40 that deliberately have none, changing rendering app-wide.
     * See DECISIONS.md D-008.
     */
    @Test fun `tokens carry no baked-in letter spacing`() {
        val all = listOf(
            NemaraType.display, NemaraType.largeTitle, NemaraType.title1, NemaraType.title2,
            NemaraType.title3, NemaraType.eventTitle, NemaraType.bodyLarge, NemaraType.body,
            NemaraType.bodyMedium, NemaraType.bodySemibold, NemaraType.bodySmall,
            NemaraType.bodySmallStrong, NemaraType.labelStrong, NemaraType.label,
            NemaraType.caption, NemaraType.captionStrong, NemaraType.eyebrow,
            NemaraType.monoSmall, NemaraType.monoMicro, NemaraType.monoValue,
        )
        assertEquals(20, all.size)
        all.forEach { assertTrue(it.letterSpacing == TextUnit.Unspecified) }
    }
}
