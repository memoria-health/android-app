package com.memoria.caregiver.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The 22 color tokens, spec at PARITY.md §13.
 *
 * Hex values are written out again here on purpose rather than imported. A test
 * that reads the constant it is testing proves nothing; these literals were
 * transcribed from the iOS asset catalog independently, so a typo in Color.kt
 * fails here.
 */
class ColorTest {

    // ---- Surfaces ----

    @Test fun `paper is warm cream`() = assertEquals(Color(0xFFEFE6DA), Paper)
    @Test fun `paperDim is the recessed surface`() = assertEquals(Color(0xFFE6DDC8), PaperDim)
    @Test fun `card is the raised surface`() = assertEquals(Color(0xFFFBF8F0), Card)
    @Test fun `cardAlt is the alternate card surface`() = assertEquals(Color(0xFFF0E8D6), CardAlt)

    // ---- Ink ----

    @Test fun `ink is primary text`() = assertEquals(Color(0xFF1C1A14), Ink)
    @Test fun `ink2 is secondary text`() = assertEquals(Color(0xFF2E2A20), Ink2)
    @Test fun `ink3 is tertiary text`() = assertEquals(Color(0xFF4A4539), Ink3)
    @Test fun `ink4 is quaternary text`() = assertEquals(Color(0xFF8A8373), Ink4)
    @Test fun `line is the hairline border`() = assertEquals(Color(0xFFC9C0AB), Line)

    // ---- Brand: terra rust ----

    @Test fun `terra is brand primary`() = assertEquals(Color(0xFFA04331), Terra)
    @Test fun `terraSoft is the safety tint`() = assertEquals(Color(0xFFF2DDD3), TerraSoft)
    @Test fun `terraDeep is the pressed brand state`() = assertEquals(Color(0xFF7A2F1F), TerraDeep)
    @Test fun `terraInk is text on terra`() = assertEquals(Color(0xFFFBF8F0), TerraInk)

    // ---- Action: warm near-black ----

    @Test fun `action is the primary CTA`() = assertEquals(Color(0xFF1C1A14), Action)
    @Test fun `actionSoft is the soft action surface`() = assertEquals(Color(0xFFE6DDC8), ActionSoft)
    @Test fun `actionInk is text on action`() = assertEquals(Color(0xFFFBF8F0), ActionInk)

    // ---- Routine: sky blue ----

    @Test fun `sky is the routine-event palette`() = assertEquals(Color(0xFF6B8AA0), Sky)
    @Test fun `skySoft is the routine tint`() = assertEquals(Color(0xFFDDE6EE), SkySoft)

    // ---- Semantic ----

    @Test fun `success is green`() = assertEquals(Color(0xFF22C55E), Success)
    @Test fun `warning is amber`() = assertEquals(Color(0xFFF59E0B), Warning)
    @Test fun `danger is terra`() = assertEquals(Color(0xFFA04331), Danger)
    @Test fun `info is sky`() = assertEquals(Color(0xFF6B8AA0), Info)

    /**
     * Five tokens share a value with another token. PARITY.md §13 is explicit
     * that they stay separate names because call sites use the semantic name.
     *
     * These assertions exist to stop a future reader "cleaning up the
     * duplicates" — collapsing them would silently couple, for example, the
     * error color to the brand color, so a rebrand would change every error
     * state in the app.
     */
    @Test fun `deliberate aliases hold`() {
        assertEquals(Ink, Action)
        assertEquals(PaperDim, ActionSoft)
        assertEquals(Card, TerraInk)
        assertEquals(Card, ActionInk)
        assertEquals(Terra, Danger)
        assertEquals(Sky, Info)
    }

    /** Guards against a copy-paste that collapses a token onto its neighbour. */
    @Test fun `ink ramp is strictly four distinct values`() {
        val ramp = listOf(Ink, Ink2, Ink3, Ink4)
        assertEquals(4, ramp.toSet().size)
    }

    @Test fun `paper and card are not the same surface`() = assertNotEquals(Paper, Card)
}
