package com.memoria.caregiver.ui.support

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The downscale rule behind every screenshot upload.
 *
 * This is where a silent regression is most expensive: the backend caps each
 * image at roughly 3 MB and the whole request is base64, so a rule that stops
 * shrinking sends a 12 MB screenshot as 16 MB of JSON and the submission fails
 * with an error the caregiver cannot act on.
 */
class FeedbackAttachmentsTest {

    /** The values `FeedbackView.swift` is written against. */
    @Test
    fun `the tuning constants match iOS`() {
        assertEquals(4, FEEDBACK_MAX_ATTACHMENTS)
        assertEquals(1600, FEEDBACK_MAX_IMAGE_DIMENSION)
        // iOS asks for 0.6 on a 0-1 scale; Android's compress() is 0-100.
        assertEquals(60, FEEDBACK_JPEG_QUALITY)
    }

    /**
     * `compressedJPEG` only ever shrinks. Upscaling a small crop to 1600 would
     * grow the payload while adding no detail.
     */
    @Test
    fun `an image already within the limit is left alone`() {
        assertEquals(800 to 600, feedbackScaledSize(800, 600))
        assertEquals(1600 to 1200, feedbackScaledSize(1600, 1200))
    }

    /** The LONGEST edge is what meets the limit, whichever way round it is. */
    @Test
    fun `the longest edge is scaled to the limit and aspect ratio survives`() {
        assertEquals(1600 to 900, feedbackScaledSize(3200, 1800))
        assertEquals(900 to 1600, feedbackScaledSize(1800, 3200))
    }

    /** A phone screenshot — the case this exists for. */
    @Test
    fun `a portrait phone screenshot comes down to the limit`() {
        val (w, h) = feedbackScaledSize(1290, 2796)
        assertEquals(1600, h)
        assertEquals(738, w)
    }

    /**
     * An extreme aspect ratio must not round the short edge to zero:
     * `Bitmap.createScaledBitmap` throws on a zero dimension, which would take
     * out the whole pick rather than one image.
     */
    @Test
    fun `a sliver keeps at least one pixel on its short edge`() {
        assertEquals(1600 to 1, feedbackScaledSize(160_000, 1))
    }
}
