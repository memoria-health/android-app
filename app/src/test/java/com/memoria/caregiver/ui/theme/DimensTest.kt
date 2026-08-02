package com.memoria.caregiver.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spacing and radius scales, spec at PARITY.md §13. */
class DimensTest {

    @Test fun `spacing scale matches iOS`() {
        assertEquals(4.dp, Spacing.xxs)
        assertEquals(8.dp, Spacing.xs)
        assertEquals(12.dp, Spacing.sm)
        assertEquals(16.dp, Spacing.md)
        assertEquals(20.dp, Spacing.lg)
        assertEquals(24.dp, Spacing.xl)
        assertEquals(32.dp, Spacing.xxl)
        assertEquals(40.dp, Spacing.xxxl)
        assertEquals(56.dp, Spacing.huge)
    }

    @Test fun `spacing scale is strictly ascending`() {
        val scale = listOf(
            Spacing.xxs, Spacing.xs, Spacing.sm, Spacing.md, Spacing.lg,
            Spacing.xl, Spacing.xxl, Spacing.xxxl, Spacing.huge,
        )
        assertEquals(scale.sortedBy { it.value }, scale)
        assertEquals(scale.size, scale.toSet().size)
    }

    @Test fun `radius scale matches iOS`() {
        assertEquals(6.dp, Radius.sm)
        assertEquals(10.dp, Radius.md)
        assertEquals(14.dp, Radius.lg)
        assertEquals(20.dp, Radius.xl)
    }

    /**
     * iOS uses 999 rather than a true capsule so the SwiftUI renderer settles
     * (NemaraRadius.swift). Compose has RoundedCornerShape(percent = 50) and no
     * such quirk, but the value is kept at 999 so a shared component measured
     * against the iOS screenshot cannot drift. Any value >= height/2 renders
     * identically.
     */
    @Test fun `pill radius is large enough to read as a capsule at any realistic height`() {
        assertTrue(Radius.pill.value >= 999f)
    }
}
