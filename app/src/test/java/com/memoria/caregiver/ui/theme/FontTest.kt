package com.memoria.caregiver.ui.theme

import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Font families, spec at PARITY.md §13 and NemaraFont.swift.
 *
 * The load-bearing fact: **Fraunces ships no Bold cut.** iOS handles this with
 * an explicit resolver that maps .bold/.heavy/.black to the SemiBold file.
 * Compose's font matcher does the same thing implicitly (CSS font-matching:
 * for a requested weight >= 600 with no heavier face available, it walks down
 * to the heaviest available), so the correct Android port is to declare only
 * the three real cuts and let the matcher round.
 *
 * The failure this guards against is someone "fixing" the missing Bold by
 * registering the SemiBold file *as* Bold. That would make W600 and W700
 * indistinguishable and silently lose the SemiBold/Bold distinction that
 * eventTitle and monoMicro rely on.
 */
class FontTest {

    private val fraunces get() = FrauncesFamily as FontListFontFamily
    private val inter get() = InterFamily as FontListFontFamily
    private val mono get() = JetBrainsMonoFamily as FontListFontFamily

    @Test fun `fraunces ships exactly three cuts`() {
        assertEquals(3, fraunces.fonts.size)
    }

    @Test fun `fraunces has regular medium semibold`() {
        assertEquals(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold),
            fraunces.fonts.map { it.weight }.sortedBy { it.weight },
        )
    }

    @Test fun `fraunces declares no bold face`() {
        assertFalse(fraunces.fonts.any { it.weight.weight >= FontWeight.Bold.weight })
    }

    @Test fun `inter ships four cuts including bold`() {
        assertEquals(4, inter.fonts.size)
        assertTrue(inter.fonts.any { it.weight == FontWeight.Bold })
    }

    @Test fun `jetbrains mono ships four cuts including bold`() {
        assertEquals(4, mono.fonts.size)
        assertTrue(mono.fonts.any { it.weight == FontWeight.Bold })
    }

    /** No family may register the same weight twice — the matcher would pick arbitrarily. */
    @Test fun `no family has duplicate weights`() {
        listOf(fraunces, inter, mono).forEach { family ->
            val weights = family.fonts.map { it.weight }
            assertEquals(weights.size, weights.toSet().size)
        }
    }
}
