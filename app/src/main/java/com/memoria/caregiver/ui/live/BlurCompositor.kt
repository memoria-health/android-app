package com.memoria.caregiver.ui.live

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The blur itself, as pure maths over ARGB pixels. Spec:
 * `Features/Patient/Live/BlurredVideoView.swift`, PARITY.md §12,
 * DIFFERENCES.md D7.
 *
 * iOS blurs the **whole** frame once with a sigma-36 Gaussian and then
 * composites the blurred content back inside each person box. The order matters:
 * blurring the frame first means a box edge samples real neighbouring picture
 * rather than the box's own clipped content, so the blur has no seam at the
 * boundary. This does the same thing in the same order.
 *
 * **DIFFERENCES.md D7 fixed the primitive: downscale, blur, upscale, at every
 * API level, not `RenderEffect`.** That decision is settled and this file
 * implements it; see D7 for the device-tail reasoning.
 *
 * Everything here is `IntArray` in and `IntArray` out with no Android types, so
 * the arithmetic that decides how blurred a person's face actually is can be
 * tested on the JVM.
 */
object BlurCompositor {

    /**
     * Gaussian blur strength in **frame** pixels. iOS
     * `blurSigma: Double = 36`, mirroring web `BLUR_PX = 36` (a CSS blur radius
     * is approximately a Gaussian sigma).
     */
    const val SIGMA: Double = 36.0

    /**
     * How far the frame is shrunk before it is blurred. Android-only; there is
     * no iOS counterpart because Core Image blurs at full size.
     *
     * A sigma-36 blur at full resolution is a very wide kernel. Shrinking by 8
     * first turns it into a sigma-4.5 blur over 1/64th of the pixels, and the
     * shrink is itself a low-pass, so the result matches while costing a
     * fraction of the work. 8 is chosen because the blur radius **after**
     * shrinking is still ~4.5 px: much coarser and the box-blur passes stop
     * having enough pixels to look Gaussian.
     */
    const val DOWNSCALE: Int = 8

    /**
     * Three box-blur passes approximate a Gaussian closely enough that the
     * difference is invisible, which is the standard result. Two passes leave a
     * visible cross-shaped kernel; four costs more for no visible gain.
     */
    const val BOX_PASSES: Int = 3

    /**
     * Box widths whose successive application approximates a Gaussian of
     * [sigma]. The usual construction: pick the largest odd width that does not
     * overshoot, then promote as many passes as needed to the next odd width up.
     *
     * Returns all-1 widths (a no-op blur) for a non-positive sigma rather than
     * throwing, because a caller that ends up there should render a sharp frame,
     * not crash the video thread.
     */
    fun boxSizesForGaussian(sigma: Double, passes: Int = BOX_PASSES): IntArray {
        require(passes > 0) { "a blur needs at least one pass" }
        if (sigma <= 0.0) return IntArray(passes) { 1 }

        val n = passes.toDouble()
        val variance = sigma * sigma
        val idealWidth = sqrt(12.0 * variance / n + 1.0)
        var lower = floor(idealWidth).toInt()
        if (lower % 2 == 0) lower -= 1
        if (lower < 1) lower = 1
        val upper = lower + 2

        // How many passes should use the LOWER width; the rest use the upper one.
        val idealCount =
            (12.0 * variance - n * lower * lower - 4.0 * n * lower - 3.0 * n) /
                (-4.0 * lower - 4.0)
        val lowerCount = idealCount.roundToInt().coerceIn(0, passes)

        return IntArray(passes) { i -> if (i < lowerCount) lower else upper }
    }

    /** Width or height of the shrunk image, rounded up so no source pixel is lost. */
    fun downscaledExtent(extent: Int, factor: Int): Int =
        max(1, (extent + factor - 1) / factor)

    /**
     * Box-average [source] down by [factor] into [dest].
     *
     * Every source pixel contributes to exactly one destination pixel, including
     * the partial block at a right or bottom edge whose size is not a multiple
     * of the factor. Averaging rather than point-sampling matters: point-sampling
     * a face at 1/8 scale aliases high-contrast detail straight through the
     * blur, which is precisely the detail this feature exists to remove.
     */
    fun downscale(source: ArgbFrame, factor: Int, dest: ArgbFrame) {
        require(factor >= 1) { "downscale factor must be at least 1" }
        require(dest.width == downscaledExtent(source.width, factor)) { "dest width mismatch" }
        require(dest.height == downscaledExtent(source.height, factor)) { "dest height mismatch" }

        val src = source.pixels
        val out = dest.pixels
        for (dy in 0 until dest.height) {
            val y0 = dy * factor
            val y1 = min(y0 + factor, source.height)
            for (dx in 0 until dest.width) {
                val x0 = dx * factor
                val x1 = min(x0 + factor, source.width)
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (y in y0 until y1) {
                    var index = y * source.width + x0
                    for (x in x0 until x1) {
                        val pixel = src[index++]
                        r += (pixel shr 16) and 0xFF
                        g += (pixel shr 8) and 0xFF
                        b += pixel and 0xFF
                        count++
                    }
                }
                out[dy * dest.width + dx] =
                    ALPHA_OPAQUE or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
    }

    /**
     * Blur [image] in place to approximately [sigma], using [scratch] as the
     * ping-pong buffer. [scratch] must be at least as large as the image, and is
     * left holding intermediate pixels.
     *
     * Edges clamp to the edge pixel, which is what iOS's `clampedToExtent()`
     * before the Gaussian does — without it a blurred region at the frame border
     * darkens towards transparent and reads as a vignette.
     */
    fun blurInPlace(image: ArgbFrame, sigma: Double, scratch: IntArray) {
        require(scratch.size >= image.width * image.height) { "scratch buffer too small" }
        for (boxWidth in boxSizesForGaussian(sigma)) {
            val radius = (boxWidth - 1) / 2
            if (radius < 1) continue
            horizontalBoxBlur(image.pixels, scratch, image.width, image.height, radius)
            verticalBoxBlur(scratch, image.pixels, image.width, image.height, radius)
        }
    }

    /**
     * Paint the blurred content back inside each box, and nowhere else.
     *
     * [blurred] is the shrunk, blurred copy of [sharp]; each destination pixel
     * is sampled from it bilinearly, which fuses the upscale into the composite
     * so only the pixels that actually get blurred are ever resampled. Boxes are
     * intersected with the frame here, exactly where iOS intersects them.
     *
     * Mutates [sharp]. Pixels outside every box are left bit-identical, so the
     * room stays sharp — the copy on the settings card promises exactly that.
     */
    fun compositeBlurredBoxes(
        sharp: ArgbFrame,
        blurred: ArgbFrame,
        factor: Int,
        boxes: List<PixelRect>,
    ) {
        if (boxes.isEmpty()) return
        val frame = PixelRect.ofFrame(sharp.width, sharp.height)
        for (box in boxes) {
            val visible = box.intersect(frame) ?: continue
            // Outward rounding. A half-covered edge pixel is blurred rather than
            // left sharp: erring towards more blur is the safe direction.
            val x0 = floor(visible.left).toInt().coerceIn(0, sharp.width - 1)
            val y0 = floor(visible.top).toInt().coerceIn(0, sharp.height - 1)
            val x1 = ceilToInt(visible.right).coerceIn(x0 + 1, sharp.width)
            val y1 = ceilToInt(visible.bottom).coerceIn(y0 + 1, sharp.height)

            for (y in y0 until y1) {
                val sourceY = (y + 0.5f) / factor - 0.5f
                var index = y * sharp.width + x0
                for (x in x0 until x1) {
                    val sourceX = (x + 0.5f) / factor - 0.5f
                    sharp.pixels[index++] = sampleBilinear(blurred, sourceX, sourceY)
                }
            }
        }
    }

    /**
     * Bilinear sample of [image] at a fractional position, clamping outside.
     * Internal rather than private so the resampling can be pinned directly —
     * a nearest-neighbour "optimisation" here would put visible 8x8 blocks over
     * someone's face and still pass every other test in the suite.
     */
    internal fun sampleBilinear(image: ArgbFrame, x: Float, y: Float): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = x - x0
        val ty = y - y0
        val cx0 = x0.coerceIn(0, image.width - 1)
        val cx1 = (x0 + 1).coerceIn(0, image.width - 1)
        val cy0 = y0.coerceIn(0, image.height - 1)
        val cy1 = (y0 + 1).coerceIn(0, image.height - 1)

        val p00 = image.pixels[cy0 * image.width + cx0]
        val p10 = image.pixels[cy0 * image.width + cx1]
        val p01 = image.pixels[cy1 * image.width + cx0]
        val p11 = image.pixels[cy1 * image.width + cx1]

        // Written out per channel rather than looped over shifts: the loop needs
        // an array of shifts, and allocating one per blurred pixel is thousands
        // of allocations a frame.
        val r = mix(p00 shr 16, p10 shr 16, p01 shr 16, p11 shr 16, tx, ty)
        val g = mix(p00 shr 8, p10 shr 8, p01 shr 8, p11 shr 8, tx, ty)
        val b = mix(p00, p10, p01, p11, tx, ty)
        return ALPHA_OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private fun mix(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
        val topLeft = (c00 and 0xFF).toFloat()
        val topRight = (c10 and 0xFF).toFloat()
        val bottomLeft = (c01 and 0xFF).toFloat()
        val bottomRight = (c11 and 0xFF).toFloat()
        val top = topLeft + (topRight - topLeft) * tx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * tx
        return (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
    }

    // ---- box blur passes -------------------------------------------------
    //
    // Separable sliding-window averages: each output pixel costs one add and one
    // subtract regardless of radius, which is what makes a wide blur affordable
    // in a frame callback. Alpha is not accumulated — video frames are opaque
    // and every output is written fully opaque, so carrying it would be three
    // more operations per pixel for a constant.

    private fun horizontalBoxBlur(src: IntArray, dest: IntArray, width: Int, height: Int, radius: Int) {
        val window = 2 * radius + 1
        for (y in 0 until height) {
            val row = y * width
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val pixel = src[row + i.coerceIn(0, width - 1)]
                sumR += (pixel shr 16) and 0xFF
                sumG += (pixel shr 8) and 0xFF
                sumB += pixel and 0xFF
            }
            for (x in 0 until width) {
                dest[row + x] =
                    ALPHA_OPAQUE or ((sumR / window) shl 16) or
                        ((sumG / window) shl 8) or (sumB / window)
                val leaving = src[row + (x - radius).coerceIn(0, width - 1)]
                val entering = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                sumR += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                sumG += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                sumB += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    private fun verticalBoxBlur(src: IntArray, dest: IntArray, width: Int, height: Int, radius: Int) {
        val window = 2 * radius + 1
        for (x in 0 until width) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val pixel = src[i.coerceIn(0, height - 1) * width + x]
                sumR += (pixel shr 16) and 0xFF
                sumG += (pixel shr 8) and 0xFF
                sumB += pixel and 0xFF
            }
            for (y in 0 until height) {
                dest[y * width + x] =
                    ALPHA_OPAQUE or ((sumR / window) shl 16) or
                        ((sumG / window) shl 8) or (sumB / window)
                val leaving = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sumR += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                sumG += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                sumB += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    private fun ceilToInt(value: Float): Int {
        val floored = floor(value).toInt()
        return if (value > floored) floored + 1 else floored
    }

    private const val ALPHA_OPAQUE = 0xFF shl 24
}

/**
 * Holds the scratch buffers the blur needs, so the frame callback allocates
 * nothing after the first frame of a given size.
 *
 * Not thread-safe on purpose — one instance per renderer, confined to the video
 * decode thread, mirroring the way iOS keeps all of this on the SDK's serial
 * video thread with no locking.
 */
class BlurPipeline(
    private val factor: Int = BlurCompositor.DOWNSCALE,
    private val sigma: Double = BlurCompositor.SIGMA,
) {
    private var small: ArgbFrame? = null
    private var scratch: IntArray = IntArray(0)

    /**
     * Blur [frame] inside [boxes], in place.
     *
     * With no boxes the frame is returned untouched and **no blur work happens
     * at all** — iOS builds the blurred image only when there is something to
     * composite it into, and an empty room is the common case.
     */
    fun blurBoxesInPlace(frame: ArgbFrame, boxes: List<PixelRect>) {
        if (boxes.isEmpty()) return

        val smallWidth = BlurCompositor.downscaledExtent(frame.width, factor)
        val smallHeight = BlurCompositor.downscaledExtent(frame.height, factor)
        val cached = small
        val target = if (cached != null && cached.hasSize(smallWidth, smallHeight)) {
            cached
        } else {
            ArgbFrame(smallWidth, smallHeight).also { small = it }
        }
        if (scratch.size < smallWidth * smallHeight) {
            scratch = IntArray(smallWidth * smallHeight)
        }

        BlurCompositor.downscale(frame, factor, target)
        // The frame shrank by `factor`, so the blur radius shrinks with it.
        BlurCompositor.blurInPlace(target, sigma / factor, scratch)
        BlurCompositor.compositeBlurredBoxes(frame, target, factor, boxes)
    }
}
