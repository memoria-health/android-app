package com.memoria.caregiver.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The initial normalization and hue derivation from NemaraAvatar.swift.
 *
 * Both are pure, and both are exactly where a port silently drifts: Swift's
 * `prefix(1)` is a grapheme cluster and its `unicodeScalars.first` is a code
 * point, while the obvious Kotlin (`take(1)`, `this[0]`) is a UTF-16 unit. The
 * two disagree on every name that does not start with a plain ASCII letter.
 */
class NemaraAvatarInitialTest {

    @Test fun `takes the first character and uppercases it`() {
        assertEquals("M", avatarInitial("Margaret"))
        assertEquals("M", avatarInitial("margaret"))
        assertEquals("J", avatarInitial("j"))
    }

    /** Swift's `?? 0` fallback. PatientSettingsView passes a literal "?" for the same case. */
    @Test fun `an empty name yields an empty initial rather than throwing`() {
        assertEquals("", avatarInitial(""))
    }

    @Test fun `non-letter first characters pass through`() {
        assertEquals("?", avatarInitial("?"))
        assertEquals("7", avatarInitial("7th Street"))
    }

    /**
     * The surrogate-pair case. `raw.take(1)` would return the lone high
     * surrogate U+D83D, which renders as a replacement box and hashes to a
     * different hue than the character the user actually typed.
     */
    @Test fun `an astral character survives as a whole code point`() {
        assertEquals("😀", avatarInitial("😀 Grandma"))
    }

    /**
     * The combining-mark case. Swift's `prefix(1)` keeps `e` + U+0301 together
     * as one grapheme cluster, so the avatar shows "É". Splitting on Char would
     * show a bare "E" and drop the accent from a caregiver's name.
     */
    @Test fun `a decomposed accented letter keeps its combining mark`() {
        // Escapes, not literals: a precomposed U+00E9 in the source would let
        // this pass without ever exercising the grapheme-cluster path.
        assertEquals("E\u0301", avatarInitial("e\u0301lodie"))
    }

    /** Devanagari has no case mapping; the initial must survive unchanged. */
    @Test fun `a non-cased script is passed through unchanged`() {
        assertEquals("अ", avatarInitial("अनिल"))
    }
}

/** `(scalar * 47) % 360`, saturation 0.18, brightness 0.86 — NemaraAvatar.swift. */
class NemaraAvatarHueTest {

    @Test fun `hue is scalar times 47 modulo 360`() {
        assertEquals(19, avatarHueDegrees("M"))
        assertEquals(3, avatarHueDegrees("E"))
        assertEquals(144, avatarHueDegrees("H"))
        assertEquals(301, avatarHueDegrees("S"))
        assertEquals(238, avatarHueDegrees("J"))
    }

    @Test fun `an empty initial falls back to hue zero`() {
        assertEquals(0, avatarHueDegrees(""))
    }

    /** The hue comes off the *uppercased* initial, so case cannot change a patient's tone. */
    @Test fun `case is normalized before the hue is derived`() {
        assertEquals(avatarHueDegrees(avatarInitial("M")), avatarHueDegrees(avatarInitial("margaret")))
    }

    /**
     * The point of multiplying by 47: it is coprime with 360, so the 26 Latin
     * initials land on 26 distinct hues instead of a near-identical gradient.
     * A multiplier sharing a factor with 360 would collide.
     */
    @Test fun `every latin initial gets a distinct hue`() {
        val hues = ('A'..'Z').map { avatarHueDegrees(it.toString()) }
        assertEquals(26, hues.toSet().size)
    }

    @Test fun `hue stays inside the colour wheel for every code point`() {
        val samples = listOf("", "A", "z", "?", "अ", "中", "😀", "𝔐")
        samples.forEach { s ->
            val hue = avatarHueDegrees(s)
            assertTrue("hue $hue out of range for '$s'", hue in 0..359)
        }
    }

    /**
     * Derived from an astral code point, not from its leading surrogate. If
     * this used `initial[0].code` the hue would be 59 (from U+D83D) instead.
     */
    @Test fun `an astral initial hashes off the full code point`() {
        assertEquals(344, avatarHueDegrees("😀"))
        assertNotEquals(avatarHueDegrees("\uD83D"), avatarHueDegrees("😀"))
    }
}

/**
 * The HSB triple, checked against independently computed sRGB rather than
 * against another call to `Color.hsv`.
 *
 * Compose packs sRGB colours at 8 bits per channel, so the deltas below are
 * quantization headroom, not slack in the expectation.
 */
class NemaraAvatarColorTest {

    @Test fun `M resolves to the hand-computed pastel`() {
        // hue 19 deg, s 0.18, v 0.86 -> c = v*s = 0.1548, m = v-c = 0.7052,
        // x = c*(1 - |19/60 - 1|) = 0.04902; sector 0 -> (c+m, x+m, m).
        val color = avatarBackgroundColor("M")
        assertEquals(0.86f, color.red, 0.005f)
        assertEquals(0.75422f, color.green, 0.005f)
        assertEquals(0.7052f, color.blue, 0.005f)
    }

    /**
     * The two tuning constants, asserted through the HSB identities that hold
     * for any hue: brightness is the largest channel, and saturation is
     * (max - min) / max. This pins 0.86 and 0.18 across the whole alphabet, so
     * a "slightly punchier" tweak fails loudly instead of drifting.
     */
    @Test fun `every initial keeps saturation 0_18 and brightness 0_86`() {
        ('A'..'Z').forEach { c ->
            val color: Color = avatarBackgroundColor(c.toString())
            val maxChannel = max(color.red, max(color.green, color.blue))
            val minChannel = min(color.red, min(color.green, color.blue))
            assertEquals("brightness for $c", 0.86f, maxChannel, 0.01f)
            assertEquals("saturation for $c", 0.18f, (maxChannel - minChannel) / maxChannel, 0.01f)
        }
    }

    @Test fun `the background is fully opaque`() {
        assertEquals(1f, avatarBackgroundColor("M").alpha, 0.001f)
    }

    @Test fun `different initials produce different backgrounds`() {
        assertNotEquals(avatarBackgroundColor("M"), avatarBackgroundColor("E"))
    }
}
