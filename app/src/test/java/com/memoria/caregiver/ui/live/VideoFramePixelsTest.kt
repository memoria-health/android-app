package com.memoria.caregiver.ui.live

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colour conversion and the rotation mapping.
 *
 * PARITY.md §12 names the rotation mapping as one of the Android specifics, and
 * the failure mode as "sideways or upside-down video". Colour is the quieter of
 * the two: a limited-range frame decoded as full range still looks like video,
 * just washed out, so nothing catches it except an assertion on the numbers.
 */
class VideoFramePixelsTest {

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF
    private fun green(pixel: Int) = (pixel shr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF
    private fun alpha(pixel: Int) = (pixel ushr 24) and 0xFF

    /** A frame of one flat YUV colour, with tightly packed planes. */
    private fun flat(width: Int, height: Int, y: Int, u: Int, v: Int): ArgbFrame {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val dest = ArgbFrame(width, height)
        VideoFramePixels.i420ToArgb(
            dataY = ByteBuffer.wrap(ByteArray(width * height) { y.toByte() }),
            strideY = width,
            dataU = ByteBuffer.wrap(ByteArray(chromaWidth * chromaHeight) { u.toByte() }),
            strideU = chromaWidth,
            dataV = ByteBuffer.wrap(ByteArray(chromaWidth * chromaHeight) { v.toByte() }),
            strideV = chromaWidth,
            dest = dest,
        )
        return dest
    }

    // ---- colour ----

    @Test fun `limited-range black decodes to true black, not to sixteen`() {
        val frame = flat(4, 4, y = 16, u = 128, v = 128)

        for (pixel in frame.pixels) {
            assertEquals(0, red(pixel))
            assertEquals(0, green(pixel))
            assertEquals(0, blue(pixel))
        }
    }

    @Test fun `limited-range white decodes to full white`() {
        val frame = flat(4, 4, y = 235, u = 128, v = 128)

        for (pixel in frame.pixels) {
            assertTrue("red was ${red(pixel)}", red(pixel) >= 253)
            assertTrue("green was ${green(pixel)}", green(pixel) >= 253)
            assertTrue("blue was ${blue(pixel)}", blue(pixel) >= 253)
        }
    }

    @Test fun `a luma above the limited-range peak clamps instead of wrapping`() {
        val frame = flat(2, 2, y = 255, u = 128, v = 128)

        for (pixel in frame.pixels) {
            assertEquals(255, red(pixel))
            assertEquals(255, green(pixel))
            assertEquals(255, blue(pixel))
        }
    }

    @Test fun `chroma is applied, so a red frame is red and not grey`() {
        val frame = flat(4, 4, y = 81, u = 90, v = 240)

        for (pixel in frame.pixels) {
            assertTrue("red was ${red(pixel)}", red(pixel) >= 250)
            assertEquals(0, green(pixel))
            assertEquals(0, blue(pixel))
        }
    }

    @Test fun `every decoded pixel is opaque`() {
        val frame = flat(2, 2, y = 120, u = 128, v = 128)

        for (pixel in frame.pixels) {
            assertEquals(255, alpha(pixel))
        }
    }

    /**
     * A decoder is free to pad rows. Reading with `stride == width` shears the
     * picture progressively down the frame, which looks like a corrupt stream
     * rather than like a bug.
     */
    @Test fun `plane strides are honoured rather than assumed equal to the width`() {
        val strideY = 5
        val luma = ByteArray(strideY * 2) { 128.toByte() } // padding is mid-grey
        // Row 0: black, white. Row 1: white, black.
        luma[0] = 16
        luma[1] = 235.toByte()
        luma[strideY] = 235.toByte()
        luma[strideY + 1] = 16

        val dest = ArgbFrame(2, 2)
        VideoFramePixels.i420ToArgb(
            dataY = ByteBuffer.wrap(luma),
            strideY = strideY,
            dataU = ByteBuffer.wrap(ByteArray(3) { 128.toByte() }),
            strideU = 3,
            dataV = ByteBuffer.wrap(ByteArray(3) { 128.toByte() }),
            strideV = 3,
            dest = dest,
        )

        assertEquals(0, red(dest[0, 0]))
        assertTrue(red(dest[1, 0]) >= 253)
        assertTrue(red(dest[0, 1]) >= 253)
        assertEquals(0, red(dest[1, 1]))
    }

    // ---- rotation ----

    /** 3 wide, 2 tall, every pixel distinct so a transposition cannot hide. */
    private fun numbered() = ArgbFrame(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))

    @Test fun `no rotation copies the frame through unchanged`() {
        val dest = ArgbFrame(3, 2)

        VideoFramePixels.rotateClockwise(numbered(), 0, dest)

        assertTrue(intArrayOf(1, 2, 3, 4, 5, 6).contentEquals(dest.pixels))
    }

    @Test fun `ninety degrees rotates clockwise and swaps the axes`() {
        val dest = ArgbFrame(2, 3)

        VideoFramePixels.rotateClockwise(numbered(), 90, dest)

        // 1 2 3 / 4 5 6  ->  4 1 / 5 2 / 6 3
        assertTrue(
            "was ${dest.pixels.toList()}",
            intArrayOf(4, 1, 5, 2, 6, 3).contentEquals(dest.pixels),
        )
    }

    @Test fun `one hundred and eighty degrees turns the frame end over end`() {
        val dest = ArgbFrame(3, 2)

        VideoFramePixels.rotateClockwise(numbered(), 180, dest)

        assertTrue(
            "was ${dest.pixels.toList()}",
            intArrayOf(6, 5, 4, 3, 2, 1).contentEquals(dest.pixels),
        )
    }

    @Test fun `two hundred and seventy degrees is the other quarter turn, not the same one`() {
        val dest = ArgbFrame(2, 3)

        VideoFramePixels.rotateClockwise(numbered(), 270, dest)

        // 1 2 3 / 4 5 6  ->  3 6 / 2 5 / 1 4
        assertTrue(
            "was ${dest.pixels.toList()}",
            intArrayOf(3, 6, 2, 5, 1, 4).contentEquals(dest.pixels),
        )
    }

    @Test fun `four quarter turns return the original frame`() {
        var frame = numbered()
        repeat(4) {
            val dest = ArgbFrame(frame.height, frame.width)
            VideoFramePixels.rotateClockwise(frame, 90, dest)
            frame = dest
        }

        assertTrue(intArrayOf(1, 2, 3, 4, 5, 6).contentEquals(frame.pixels))
    }

    // ---- rotation normalization ----

    @Test fun `the four quarter turns pass through`() {
        assertEquals(0, VideoFramePixels.normalizeRotation(0))
        assertEquals(90, VideoFramePixels.normalizeRotation(90))
        assertEquals(180, VideoFramePixels.normalizeRotation(180))
        assertEquals(270, VideoFramePixels.normalizeRotation(270))
    }

    @Test fun `a full turn and a negative turn both normalize`() {
        assertEquals(0, VideoFramePixels.normalizeRotation(360))
        assertEquals(270, VideoFramePixels.normalizeRotation(-90))
        assertEquals(90, VideoFramePixels.normalizeRotation(450))
    }

    /** iOS `@unknown default: return .up`. Unrotated beats a crash on the video thread. */
    @Test fun `an angle that is not a quarter turn falls back to upright`() {
        assertEquals(0, VideoFramePixels.normalizeRotation(45))
        assertEquals(0, VideoFramePixels.normalizeRotation(-1))
    }

    @Test fun `rotated dimensions swap only on the quarter turns`() {
        assertEquals(640, VideoFramePixels.rotatedWidth(640, 480, 0))
        assertEquals(480, VideoFramePixels.rotatedHeight(640, 480, 0))
        assertEquals(480, VideoFramePixels.rotatedWidth(640, 480, 90))
        assertEquals(640, VideoFramePixels.rotatedHeight(640, 480, 90))
        assertEquals(640, VideoFramePixels.rotatedWidth(640, 480, 180))
        assertEquals(480, VideoFramePixels.rotatedWidth(640, 480, 270))
    }
}
