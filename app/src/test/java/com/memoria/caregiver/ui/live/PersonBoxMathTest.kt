package com.memoria.caregiver.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two cross-platform constants and the padding arithmetic they feed.
 *
 * PARITY.md §12 records both numbers as shared with the web dashboard rather
 * than Apple tuning, so a value rounded here is a privacy difference between two
 * clients of the same product — invisible in a screenshot and invisible in a
 * build.
 */
class PersonBoxMathTest {

    private val tolerance = 1e-4f

    // ---- the constants themselves ----

    @Test fun `padding is eighteen percent, web PAD`() {
        assertEquals(0.18f, PersonBoxMath.PAD, 0f)
    }

    @Test fun `minimum confidence is a quarter`() {
        assertEquals(0.25f, PersonBoxMath.MIN_CONFIDENCE, 0f)
    }

    // ---- padding ----

    @Test fun `padding expands each edge by eighteen percent of the box's own size`() {
        val box = NormalizedBox(x = 0.25f, y = 0.5f, width = 0.5f, height = 0.25f)

        val rect = PersonBoxMath.pixelRect(box, frameWidth = 400, frameHeight = 400)

        // Raw box is 100..300 across and 200..300 down. Pads are 0.18 * 200 = 36
        // and 0.18 * 100 = 18.
        assertEquals(100f - 36f, rect.left, tolerance)
        assertEquals(300f + 36f, rect.right, tolerance)
        assertEquals(200f - 18f, rect.top, tolerance)
        assertEquals(300f + 18f, rect.bottom, tolerance)
    }

    @Test fun `a padded box is one point three six times the raw box in both axes`() {
        val box = NormalizedBox(x = 0.1f, y = 0.1f, width = 0.3f, height = 0.4f)

        val rect = PersonBoxMath.pixelRect(box, frameWidth = 1000, frameHeight = 1000)

        assertEquals(300f * 1.36f, rect.width, 1e-2f)
        assertEquals(400f * 1.36f, rect.height, 1e-2f)
    }

    /**
     * Both insets read the size BEFORE padding, which is what `CGRect.insetBy`
     * does. Applying the vertical pad to an already-widened box would stretch a
     * standing person's box sideways and is an easy transcription slip.
     */
    @Test fun `the vertical pad is not derived from the padded width`() {
        val box = NormalizedBox(x = 0f, y = 0f, width = 1f, height = 0.1f)

        val rect = PersonBoxMath.pixelRect(box, frameWidth = 100, frameHeight = 100)

        assertEquals(10f * 1.36f, rect.height, tolerance)
    }

    /**
     * PARITY.md §12: the padded rect is allowed off the frame and is clipped
     * only when composited. Clamping here would leave a strip of sharp picture
     * against the border, around a person standing at the edge.
     */
    @Test fun `a box at the frame edge pads outside the frame and is not clamped`() {
        val box = NormalizedBox(x = 0f, y = 0f, width = 0.2f, height = 0.2f)

        val rect = PersonBoxMath.pixelRect(box, frameWidth = 100, frameHeight = 100)

        assertTrue("left should be negative, was ${rect.left}", rect.left < 0f)
        assertTrue("top should be negative, was ${rect.top}", rect.top < 0f)
    }

    // ---- confidence gate ----

    @Test fun `a detection exactly at the threshold is blurred`() {
        val people = listOf(DetectedPerson(NormalizedBox(0f, 0f, 0.1f, 0.1f), confidence = 0.25f))

        assertEquals(1, PersonBoxMath.boxes(people, 100, 100).size)
    }

    @Test fun `a detection just under the threshold is dropped`() {
        val people = listOf(DetectedPerson(NormalizedBox(0f, 0f, 0.1f, 0.1f), confidence = 0.2499f))

        assertTrue(PersonBoxMath.boxes(people, 100, 100).isEmpty())
    }

    @Test fun `the confident survivors keep their order and the rest are dropped`() {
        val people = listOf(
            DetectedPerson(NormalizedBox(0.0f, 0f, 0.1f, 0.1f), confidence = 0.9f),
            DetectedPerson(NormalizedBox(0.3f, 0f, 0.1f, 0.1f), confidence = 0.1f),
            DetectedPerson(NormalizedBox(0.6f, 0f, 0.1f, 0.1f), confidence = 0.3f),
        )

        val boxes = PersonBoxMath.boxes(people, 100, 100)

        assertEquals(2, boxes.size)
        assertTrue(boxes[0].left < boxes[1].left)
        assertEquals(60f - 1.8f, boxes[1].left, tolerance)
    }

    @Test fun `no detections is no boxes`() {
        assertTrue(PersonBoxMath.boxes(emptyList(), 640, 480).isEmpty())
    }

    // ---- PixelRect ----

    @Test fun `intersecting with the frame clips a box that hangs off it`() {
        val box = PixelRect(-20f, -30f, 50f, 60f)

        val clipped = box.intersect(PixelRect.ofFrame(100, 100))

        assertEquals(PixelRect(0f, 0f, 50f, 60f), clipped)
    }

    @Test fun `a box entirely off frame intersects to nothing`() {
        val box = PixelRect(-40f, -40f, -10f, -10f)

        assertEquals(null, box.intersect(PixelRect.ofFrame(100, 100)))
    }
}
