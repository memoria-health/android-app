package com.memoria.caregiver.ui.live

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blur itself.
 *
 * DIFFERENCES.md D7 accepts that Android reaches the blur by a different
 * primitive from Core Image, and states the acceptance criterion: *equivalent
 * blur strength*. These tests pin the parts of that which are arithmetic — the
 * kernel width the sigma produces, the fact that shrinking averages rather than
 * samples, and the fact that nothing outside a person box is touched. The final
 * visual match is the Phase 4 screenshot pass, which no unit test replaces.
 */
class BlurCompositorTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(pixel: Int) = (pixel shr 16) and 0xFF
    private fun green(pixel: Int) = (pixel shr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF

    private fun solid(width: Int, height: Int, color: Int) =
        ArgbFrame(width, height, IntArray(width * height) { color })

    /** Alternating black and white pixels — maximum high-frequency detail. */
    private fun checkerboard(width: Int, height: Int) = ArgbFrame(
        width,
        height,
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if ((x + y) % 2 == 0) argb(255, 255, 255) else argb(0, 0, 0)
        },
    )

    // ---- constants ----

    @Test fun `sigma is thirty six, web BLUR_PX`() {
        assertEquals(36.0, BlurCompositor.SIGMA, 0.0)
    }

    @Test fun `three box passes approximate the gaussian`() {
        assertEquals(3, BlurCompositor.BOX_PASSES)
    }

    // ---- kernel sizing ----

    @Test fun `every box pass has an odd width, so it has a true centre`() {
        for (sigma in listOf(1.0, 4.5, 12.0, 36.0)) {
            for (width in BlurCompositor.boxSizesForGaussian(sigma)) {
                assertTrue("even box width $width for sigma $sigma", width % 2 == 1)
            }
        }
    }

    /**
     * The variance of n successive box blurs of width w is n(w² − 1)/12. If the
     * chosen widths do not reconstruct the requested sigma, the blur is simply
     * the wrong strength — which is the one thing D7 promised to preserve.
     */
    @Test fun `the chosen widths reconstruct the requested sigma`() {
        for (sigma in listOf(2.0, 4.5, 9.0, 36.0)) {
            val widths = BlurCompositor.boxSizesForGaussian(sigma)
            val variance = widths.sumOf { (it * it - 1).toDouble() / 12.0 }
            val effective = Math.sqrt(variance)
            assertTrue(
                "sigma $sigma reconstructed as $effective from ${widths.toList()}",
                abs(effective - sigma) < 0.25,
            )
        }
    }

    @Test fun `a non-positive sigma is a no-op rather than a crash on the video thread`() {
        assertTrue(BlurCompositor.boxSizesForGaussian(0.0).all { it == 1 })
        assertTrue(BlurCompositor.boxSizesForGaussian(-4.0).all { it == 1 })
    }

    // ---- downscale ----

    @Test fun `downscaled extent rounds up so no source pixel is dropped`() {
        assertEquals(8, BlurCompositor.downscaledExtent(64, 8))
        assertEquals(9, BlurCompositor.downscaledExtent(65, 8))
        assertEquals(1, BlurCompositor.downscaledExtent(3, 8))
    }

    @Test fun `downscaling averages the block rather than sampling one pixel of it`() {
        val source = checkerboard(4, 4)
        val dest = ArgbFrame(2, 2)

        BlurCompositor.downscale(source, factor = 2, dest = dest)

        // A 2x2 block of a checkerboard is two black and two white pixels.
        for (pixel in dest.pixels) {
            assertEquals(127, red(pixel))
            assertEquals(127, green(pixel))
            assertEquals(127, blue(pixel))
        }
    }

    @Test fun `a partial block at the edge averages only the pixels it actually covers`() {
        // 3 wide, factor 2: the second column covers one source pixel, not two.
        val source = ArgbFrame(
            3,
            1,
            intArrayOf(argb(0, 0, 0), argb(100, 100, 100), argb(200, 200, 200)),
        )
        val dest = ArgbFrame(2, 1)

        BlurCompositor.downscale(source, factor = 2, dest = dest)

        assertEquals(50, red(dest.pixels[0]))
        assertEquals(200, red(dest.pixels[1]))
    }

    @Test fun `downscaled pixels stay opaque`() {
        val dest = ArgbFrame(1, 1)

        BlurCompositor.downscale(solid(2, 2, argb(10, 20, 30)), factor = 2, dest = dest)

        assertEquals(0xFF, (dest.pixels[0] ushr 24) and 0xFF)
    }

    // ---- blur ----

    /**
     * Clamp-to-edge, which is iOS's `clampedToExtent()` before the Gaussian.
     * Without it the border samples nothing and the blurred region darkens
     * towards the frame edge — a vignette around anyone standing at the side of
     * the room.
     */
    @Test fun `blurring a flat image changes nothing, including at the edges`() {
        val image = solid(24, 24, argb(120, 130, 140))

        BlurCompositor.blurInPlace(image, sigma = 4.0, scratch = IntArray(24 * 24))

        for ((index, pixel) in image.pixels.withIndex()) {
            assertEquals("red at $index", 120, red(pixel))
            assertEquals("green at $index", 130, green(pixel))
            assertEquals("blue at $index", 140, blue(pixel))
        }
    }

    @Test fun `blurring turns a hard edge into a monotonic ramp`() {
        // White on the left, black on the right, seam between columns 14 and 15.
        val image = ArgbFrame(
            31,
            31,
            IntArray(31 * 31) { i -> if (i % 31 < 15) argb(255, 255, 255) else argb(0, 0, 0) },
        )

        BlurCompositor.blurInPlace(image, sigma = 3.0, scratch = IntArray(31 * 31))

        assertEquals("far from the seam the white is untouched", 255, red(image[0, 15]))
        assertEquals("far from the seam the black is untouched", 0, red(image[30, 15]))
        assertTrue("the seam did not soften", red(image[14, 15]) in 1..254)
        for (x in 8 until 23) {
            assertTrue(
                "not monotonic at $x: ${red(image[x, 15])} then ${red(image[x + 1, 15])}",
                red(image[x, 15]) >= red(image[x + 1, 15]),
            )
        }
        assertTrue("the ramp is flat", red(image[8, 15]) > red(image[22, 15]))
    }

    @Test fun `blurring flattens high-frequency detail towards the mean`() {
        val image = checkerboard(33, 33)

        BlurCompositor.blurInPlace(image, sigma = 4.5, scratch = IntArray(33 * 33))

        // Away from the edges the checkerboard should be indistinguishable grey.
        for (y in 8..24) {
            for (x in 8..24) {
                assertTrue("pixel $x,$y still has detail: ${red(image[x, y])}", abs(red(image[x, y]) - 127) <= 4)
            }
        }
    }

    // ---- composite ----

    @Test fun `nothing outside a box is touched`() {
        val frame = checkerboard(64, 64)
        val before = frame.pixels.copyOf()
        val boxes = listOf(PixelRect(16f, 16f, 32f, 32f))

        BlurPipeline().blurBoxesInPlace(frame, boxes)

        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val inside = x in 16 until 32 && y in 16 until 32
                if (!inside) {
                    assertEquals(
                        "pixel $x,$y outside the box changed",
                        before[y * 64 + x],
                        frame[x, y],
                    )
                }
            }
        }
    }

    @Test fun `detail inside a box is destroyed`() {
        val frame = checkerboard(64, 64)
        val boxes = listOf(PixelRect(16f, 16f, 48f, 48f))

        BlurPipeline().blurBoxesInPlace(frame, boxes)

        for (y in 20..44) {
            for (x in 20..44) {
                assertTrue(
                    "pixel $x,$y kept its detail: ${red(frame[x, y])}",
                    abs(red(frame[x, y]) - 127) <= 20,
                )
            }
        }
    }

    @Test fun `an empty box list leaves the frame bit-identical`() {
        val frame = checkerboard(32, 32)
        val before = frame.pixels.copyOf()

        BlurPipeline().blurBoxesInPlace(frame, emptyList())

        assertTrue(before.contentEquals(frame.pixels))
    }

    @Test fun `a box hanging off the frame blurs the part that is on it`() {
        val frame = checkerboard(48, 48)
        val before = frame.pixels.copyOf()

        // Padding routinely pushes a box past the edge; it must clip, not crash.
        BlurPipeline().blurBoxesInPlace(frame, listOf(PixelRect(-40f, -40f, 12f, 12f)))

        assertTrue("the on-frame corner was not blurred", before[0] != frame.pixels[0])
        assertEquals(before[47 * 48 + 47], frame[47, 47])
    }

    @Test fun `a box entirely off the frame changes nothing`() {
        val frame = checkerboard(32, 32)
        val before = frame.pixels.copyOf()

        BlurPipeline().blurBoxesInPlace(frame, listOf(PixelRect(-90f, -90f, -10f, -10f)))

        assertTrue(before.contentEquals(frame.pixels))
    }

    @Test fun `two boxes both get blurred and the gap between them does not`() {
        val frame = checkerboard(96, 32)
        val before = frame.pixels.copyOf()

        BlurPipeline().blurBoxesInPlace(
            frame,
            listOf(PixelRect(0f, 0f, 24f, 32f), PixelRect(72f, 0f, 96f, 32f)),
        )

        assertTrue("the first box was not blurred", before[10] != frame[10, 0])
        assertTrue("the second box was not blurred", before[80] != frame[80, 0])
        assertEquals(before[48], frame[48, 0])
    }

    // ---- resampling ----

    /**
     * Bilinear, not nearest neighbour. At a downscale of 8, nearest neighbour
     * would paint visible 8x8 blocks over a face while still passing every
     * "detail is gone" assertion above.
     */
    @Test fun `upsampling interpolates between neighbouring shrunken pixels`() {
        val small = ArgbFrame(2, 1, intArrayOf(argb(0, 0, 0), argb(200, 200, 200)))

        val midpoint = BlurCompositor.sampleBilinear(small, x = 0.5f, y = 0f)

        assertEquals(100, red(midpoint))
    }

    @Test fun `sampling outside the shrunken image clamps to its edge`() {
        val small = ArgbFrame(2, 1, intArrayOf(argb(10, 10, 10), argb(200, 200, 200)))

        assertEquals(10, red(BlurCompositor.sampleBilinear(small, x = -5f, y = -5f)))
        assertEquals(200, red(BlurCompositor.sampleBilinear(small, x = 9f, y = 9f)))
    }

    // ---- buffer reuse ----

    /**
     * The pipeline exists to keep allocation out of a callback that runs thirty
     * times a second, so it has to survive being handed frame after frame, and
     * a resolution change mid-call.
     */
    @Test fun `one pipeline handles repeated frames and a resolution change`() {
        val pipeline = BlurPipeline()
        val boxes = listOf(PixelRect(4f, 4f, 20f, 20f))

        repeat(3) { pipeline.blurBoxesInPlace(checkerboard(32, 32), boxes) }
        pipeline.blurBoxesInPlace(checkerboard(64, 48), boxes)
        val small = checkerboard(9, 9)

        pipeline.blurBoxesInPlace(small, boxes)

        // Was a hard white checkerboard pixel before the blur.
        assertTrue("the pixel was left sharp", red(small[5, 5]) != 255)
    }
}
