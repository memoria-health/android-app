package com.memoria.caregiver.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The squircle geometry of the brand mark. Spec:
 * DesignSystem/Components/NemaraMark.swift.
 *
 * Everything here guards one failure mode: the corner ratio is a tuning value
 * with four decimal places, and rounding it to 0.22 or 0.225 changes nothing a
 * reviewer can see and everything a screenshot diff can. So the constant is
 * pinned, and the radii are pinned at the sizes the app actually asks for.
 */
class NemaraMarkTest {

    /** Tolerance well under the ~0.006dp gap that a rounded ratio would create. */
    private val tolerance = 1e-4f

    @Test fun `corner ratio is the iOS app-icon squircle value, unrounded`() {
        assertEquals(0.2237f, NEMARA_MARK_CORNER_RATIO, 0f)
    }

    @Test fun `default size matches the Swift init default`() {
        assertEquals(44.dp, NemaraMarkDefaultSize)
    }

    /**
     * The five sizes the app requests: 20 (tutorial overlay), 42 (feedback),
     * 44 (the four auth screens), 56 (welcome), plus 180 from the Swift preview
     * as the large end of the range.
     */
    @Test fun `radius is size times the ratio at every call-site size`() {
        assertEquals(4.4740f, nemaraMarkCornerRadius(20.dp).value, tolerance)
        assertEquals(9.3954f, nemaraMarkCornerRadius(42.dp).value, tolerance)
        assertEquals(9.8428f, nemaraMarkCornerRadius(44.dp).value, tolerance)
        assertEquals(12.5272f, nemaraMarkCornerRadius(56.dp).value, tolerance)
        assertEquals(40.2660f, nemaraMarkCornerRadius(180.dp).value, tolerance)
    }

    /**
     * The radius is proportional, not a fixed token. This is what fails if
     * someone "tidies" the shape into `Radius.md` or `Radius.lg` — the mark would
     * still look rounded at 44 and wrong at 20 and 180.
     */
    @Test fun `radius scales linearly with size`() {
        assertEquals(
            2f * nemaraMarkCornerRadius(44.dp).value,
            nemaraMarkCornerRadius(88.dp).value,
            tolerance,
        )
        assertEquals(0f, nemaraMarkCornerRadius(0.dp).value, 0f)
    }

    /**
     * A squircle, not a circle: the ratio is well below 0.5, so no size can
     * round the mark into a disc. Fails if the shape is ever swapped for a
     * percent-based or circular one.
     */
    @Test fun `radius stays below half the side at every size`() {
        listOf(1f, 20f, 44f, 180f, 1024f).forEach { side ->
            assertTrue(
                "radius must stay under half of $side",
                nemaraMarkCornerRadius(side.dp).value < side / 2f,
            )
        }
    }
}
