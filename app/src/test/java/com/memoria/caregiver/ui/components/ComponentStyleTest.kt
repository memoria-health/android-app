package com.memoria.caregiver.ui.components

import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.CardAlt
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft
import com.memoria.caregiver.ui.theme.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Card and chip tone mappings. Spec: NemaraCard.swift, NemaraChip.swift. */
class ComponentStyleTest {

    // ---- Card ----

    @Test fun `card tones map to their surfaces`() {
        assertEquals(Card, NemaraCardTone.Default.background)
        assertEquals(CardAlt, NemaraCardTone.Alt.background)
        assertEquals(TerraSoft, NemaraCardTone.Terra.background)
        assertEquals(SkySoft, NemaraCardTone.Sky.background)
        assertEquals(ActionSoft, NemaraCardTone.Success.background)
        assertEquals(ActionSoft, NemaraCardTone.Action.background)
        assertEquals(6, NemaraCardTone.entries.size)
    }

    /**
     * Success and Action deliberately paint the same surface, like the aliased
     * color tokens. Pinned so nobody collapses them — that would make a future
     * change to the success treatment repaint every action card.
     */
    @Test fun `success and action share a surface but stay distinct tones`() {
        assertEquals(NemaraCardTone.Success.background, NemaraCardTone.Action.background)
        assertNotEquals(NemaraCardTone.Success, NemaraCardTone.Action)
    }

    // ---- Chip ----

    @Test fun `chip tones map to background and foreground`() {
        assertEquals(PaperDim, NemaraChipTone.Neutral.background)
        assertEquals(Ink2, NemaraChipTone.Neutral.foreground)
        assertEquals(TerraSoft, NemaraChipTone.Terra.background)
        assertEquals(Terra, NemaraChipTone.Terra.foreground)
        assertEquals(SkySoft, NemaraChipTone.Sky.background)
        assertEquals(Sky, NemaraChipTone.Sky.foreground)
        assertEquals(Ink, NemaraChipTone.Dark.background)
        assertEquals(Paper, NemaraChipTone.Dark.foreground)
        assertEquals(6, NemaraChipTone.entries.size)
    }

    /**
     * The warning chip uses two literals that are NOT palette tokens, ported
     * from NemaraChip.swift. The foreground is a darkened amber chosen to clear
     * AA contrast on the soft amber fill; the palette's mid-tone `Warning`
     * fails as text on a light background.
     *
     * Asserted explicitly so that "why isn't this using Warning?" has an answer
     * that fails loudly if someone changes it.
     */
    @Test fun `warning chip uses its own amber pair, not the Warning token`() {
        assertEquals(ChipAmberSoft, NemaraChipTone.Warning.background)
        assertEquals(ChipAmberInk, NemaraChipTone.Warning.foreground)
        assertNotEquals(Warning, NemaraChipTone.Warning.foreground)
        assertNotEquals(Warning, NemaraChipTone.Warning.background)
    }
}

/**
 * Password rules. Spec: NemaraPasswordChecklist in NemaraField.swift.
 *
 * The ASCII cases are the point. iOS uses ASCII-only regexes, so a Unicode-aware
 * Kotlin implementation would disagree with it on exactly the inputs a caregiver
 * with a non-English name is likely to type.
 */
class PasswordRuleTest {

    private fun satisfied(value: String) =
        passwordRules(value).filter { it.isSatisfied }.map { it.label }

    @Test fun `empty password satisfies nothing`() {
        assertEquals(emptyList<String>(), satisfied(""))
    }

    @Test fun `a strong password satisfies all five`() {
        assertEquals(5, satisfied("Mar9aret!").size)
    }

    @Test fun `length rule is exactly eight`() {
        assertEquals(false, passwordRules("Abc1234!").first { it.label.contains("8") }.isSatisfied.not())
        assertEquals(false, passwordRules("Abc123!").first { it.label.contains("8") }.isSatisfied)
    }

    @Test fun `rules are reported in a stable order`() {
        assertEquals(
            listOf(
                "At least 8 characters",
                "One uppercase letter",
                "One lowercase letter",
                "One number",
                "One symbol",
            ),
            passwordRules("x").map { it.label },
        )
    }

    /**
     * The divergence this guards: Kotlin's Char.isLetterOrDigit() is
     * Unicode-aware, so an accented letter would count as a LETTER — meaning the
     * symbol rule would not fire. iOS's [^A-Za-z0-9] treats it as a symbol and
     * the rule DOES fire. Same password, different checklist per platform.
     */
    @Test fun `accented letters count as symbols, matching the iOS ASCII regex`() {
        val symbol = { p: String -> passwordRules(p).first { it.label == "One symbol" }.isSatisfied }
        assertEquals(true, symbol("Passwordé1"))
        assertEquals(true, symbol("Password日1"))
        assertEquals(false, symbol("Password1"))
    }

    /** Uppercase and lowercase are ASCII-only too. */
    @Test fun `non-ascii letters do not satisfy the case rules`() {
        val upper = { p: String -> passwordRules(p).first { it.label == "One uppercase letter" }.isSatisfied }
        assertEquals(false, upper("École"[0].toString() + "bcdefg1!"))
        assertEquals(true, upper("Ecole1!"))
    }

    @Test fun `symbol rule accepts common punctuation`() {
        listOf("!", "@", "#", "$", "%", "^", "&", "*", "_", "-", " ").forEach { s ->
            assertEquals(
                "expected '$s' to count as a symbol",
                true,
                passwordRules("Passw0rd$s").first { it.label == "One symbol" }.isSatisfied,
            )
        }
    }
}
