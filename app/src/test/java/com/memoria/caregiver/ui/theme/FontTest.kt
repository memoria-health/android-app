package com.memoria.caregiver.ui.theme

import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.test.junit4.createComposeRule
import com.memoria.caregiver.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    /**
     * Which FILE is bound to which weight — the assertion the rest of this class
     * was missing.
     *
     * Counting cuts and listing weights cannot catch the realistic failure:
     * swapping `fraunces_144_soft_medium` and `fraunces_144_soft_semibold`, a
     * one-token typo between two near-identically-named resources. Every other
     * test here still passes, every screen renders Medium where SemiBold belongs,
     * and it surfaces only in a screenshot diff — if anyone happens to be
     * comparing that glyph weight.
     */
    @Test
    fun `each weight is bound to its own font file`() {
        fun bindings(family: FontListFontFamily) =
            family.fonts.associate { it.weight to (it as ResourceFont).resId }

        assertEquals(
            mapOf(
                FontWeight.Normal to R.font.fraunces_144_soft_regular,
                FontWeight.Medium to R.font.fraunces_144_soft_medium,
                FontWeight.SemiBold to R.font.fraunces_144_soft_semibold,
            ),
            bindings(fraunces),
        )

        assertEquals(
            mapOf(
                FontWeight.Normal to R.font.inter_18pt_regular,
                FontWeight.Medium to R.font.inter_18pt_medium,
                FontWeight.SemiBold to R.font.inter_18pt_semibold,
                FontWeight.Bold to R.font.inter_18pt_bold,
            ),
            bindings(inter),
        )

        assertEquals(
            mapOf(
                FontWeight.Normal to R.font.jetbrains_mono_regular,
                FontWeight.Medium to R.font.jetbrains_mono_medium,
                FontWeight.SemiBold to R.font.jetbrains_mono_semibold,
                FontWeight.Bold to R.font.jetbrains_mono_bold,
            ),
            bindings(mono),
        )
    }

    /** No file may be reused across two weights — that is the "register SemiBold as Bold" anti-fix. */
    @Test
    fun `no font file is bound to two weights`() {
        listOf(fraunces, inter, mono).forEach { family ->
            val ids = family.fonts.map { (it as ResourceFont).resId }
            assertEquals(ids.size, ids.toSet().size)
        }
    }
}

/**
 * The behavior the class doc and PARITY.md §13 actually claim: "bold requests
 * round down to SemiBold". Declaring three cuts is only correct if Compose's
 * matcher really does resolve W700 to the W600 face — nothing above resolves a
 * font, so nothing above proves it.
 *
 * Robolectric because font resolution needs a Context.
 */
@RunWith(RobolectricTestRunner::class)
class FontResolutionTest {

    @get:Rule val compose = createComposeRule()

    /**
     * Every face resolved in ONE composition. `setContent` may be called only
     * once per test, so a per-lookup helper would throw "Cannot call setContent
     * twice per test".
     */
    private fun resolveAll(vararg requests: Pair<FontFamily, FontWeight>): List<Any?> {
        var resolved: List<Any?> = emptyList()
        compose.setContent {
            val resolver = LocalFontFamilyResolver.current
            resolved = requests.map { (family, weight) ->
                resolver.resolve(family, weight, FontStyle.Normal).value
            }
        }
        compose.waitForIdle()
        assertEquals(requests.size, resolved.size)
        return resolved
    }

    @Test
    fun `fraunces bold resolves to the same face as semibold`() {
        val (semibold, bold) = resolveAll(
            FrauncesFamily to FontWeight.SemiBold,
            FrauncesFamily to FontWeight.Bold,
        )
        assertEquals(semibold, bold)
    }

    /** Sanity check on the method: weights that DO differ must not collapse. */
    @Test
    fun `fraunces regular and semibold resolve to different faces`() {
        val (regular, semibold) = resolveAll(
            FrauncesFamily to FontWeight.Normal,
            FrauncesFamily to FontWeight.SemiBold,
        )
        assertNotEquals(regular, semibold)
    }

    /** Inter has a real Bold cut, so bold must NOT collapse onto semibold there. */
    @Test
    fun `inter bold resolves to its own face`() {
        val (semibold, bold) = resolveAll(
            InterFamily to FontWeight.SemiBold,
            InterFamily to FontWeight.Bold,
        )
        assertNotEquals(semibold, bold)
    }
}
