package com.memoria.caregiver.ui.enrollment

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aspect-fill mapping that places the rear-camera tracking ring over the
 * tracked face. Spec: `EnrollmentGeometry` in `EnrollmentVisionCamera.swift`.
 *
 * Getting this wrong puts the ring near the face rather than on it, which reads
 * as "the tracking is bad" and is untestable by screenshot on a still image.
 */
class EnrollmentGeometryTest {

    private val box = Rect(0.10f, 0.20f, 0.40f, 0.60f)

    private fun map(size: Size, mirrored: Boolean = false, rect: Rect = box) =
        EnrollmentGeometry.viewRect(rect, size, mirrored)

    @Test
    fun `source aspect is the upright 720 by 1280 buffer`() {
        assertEquals(720f / 1280f, EnrollmentGeometry.SOURCE_ASPECT, 0f)
    }

    @Test
    fun `a view of the source aspect maps one to one`() {
        val r = map(Size(720f, 1280f))
        assertEquals(72f, r.left, 1e-3f)
        assertEquals(256f, r.top, 1e-3f)
        assertEquals(288f, r.right, 1e-3f)
        assertEquals(768f, r.bottom, 1e-3f)
    }

    @Test
    fun `a centred box lands in the centre of any view`() {
        val centred = Rect(0.40f, 0.40f, 0.60f, 0.60f)
        for (size in listOf(Size(720f, 1280f), Size(1280f, 1280f), Size(400f, 1000f))) {
            val r = map(size, rect = centred)
            assertEquals("cx for $size", size.width / 2f, (r.left + r.right) / 2f, 1e-2f)
            assertEquals("cy for $size", size.height / 2f, (r.top + r.bottom) / 2f, 1e-2f)
        }
    }

    @Test
    fun `mirroring reflects the box about the centre line and keeps its size`() {
        val size = Size(720f, 1280f)
        val plain = map(size)
        val flipped = map(size, mirrored = true)
        assertEquals(size.width - (plain.left + plain.right) / 2f, (flipped.left + flipped.right) / 2f, 1e-3f)
        assertEquals(plain.width, flipped.width, 1e-3f)
        assertEquals(plain.height, flipped.height, 1e-3f)
        // Vertical placement is untouched by mirroring.
        assertEquals(plain.top, flipped.top, 1e-3f)
    }

    @Test
    fun `a view wider than the source fills the width and overflows the height`() {
        // Aspect FILL, not fit: the portrait source is scaled until it covers
        // the square view, so it runs off the top and bottom.
        val size = Size(1280f, 1280f)
        val full = EnrollmentGeometry.viewRect(Rect(0f, 0f, 1f, 1f), size, mirrored = false)
        assertEquals(0f, full.left, 1e-2f)
        assertEquals(size.width, full.width, 1e-2f)
        assertTrue(full.height > size.height)
        assertTrue(full.top < 0f)
        // Still centred.
        assertEquals(-full.top, full.bottom - size.height, 1e-2f)
    }

    @Test
    fun `a view taller than the source fills the height and overflows the width`() {
        val size = Size(400f, 1600f)
        val full = EnrollmentGeometry.viewRect(Rect(0f, 0f, 1f, 1f), size, mirrored = false)
        assertEquals(0f, full.top, 1e-2f)
        assertEquals(size.height, full.height, 1e-2f)
        assertTrue(full.width > size.width)
        assertEquals(-full.left, full.right - size.width, 1e-2f)
    }

    @Test
    fun `a box off the top of the frame maps above the view`() {
        // The camera does not clamp its boxes, so the ring is allowed to sit
        // partly off screen rather than snapping to an edge.
        val r = map(Size(720f, 1280f), rect = Rect(0.10f, -0.10f, 0.40f, 0.30f))
        assertTrue(r.top < 0f)
    }

    @Test
    fun `a zero-sized view produces an empty rect rather than NaN`() {
        val r = map(Size.Zero)
        assertEquals(0f, r.left, 0f)
        assertEquals(0f, r.top, 0f)
        assertEquals(0f, r.width, 0f)
        assertEquals(0f, r.height, 0f)
    }
}
