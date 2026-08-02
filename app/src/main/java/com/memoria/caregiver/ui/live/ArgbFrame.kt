package com.memoria.caregiver.ui.live

/**
 * One decoded video frame as packed `ARGB_8888` pixels, row-major, **top-left
 * origin**. Spec: `Features/Patient/Live/BlurredVideoView.swift`, PARITY.md §12.
 *
 * iOS carries the frame as a `CIImage`, which Core Image owns and whose origin
 * is bottom-left. There is no Core Image on Android, so the blur pipeline works
 * on a plain pixel array instead — which is also what makes the box maths, the
 * hold, and the blur composition testable on the JVM with no device.
 *
 * **The origin flip is a convention, not a behavior change.** Vision hands iOS
 * bottom-left normalized boxes and `CIImage` is bottom-left, so the two agree;
 * an Android person detector hands back top-left normalized boxes and a bitmap
 * is top-left, so those two agree too. The rectangle that ends up blurred is
 * the same rectangle over the same part of the picture.
 *
 * [pixels] is deliberately mutable and deliberately reusable: the whole point of
 * this type is that the renderer can allocate one buffer per frame *size* rather
 * than one per frame, because a 1280x720 frame is 3.5 MB and allocating that 30
 * times a second is a garbage-collection pause, not a pipeline.
 */
class ArgbFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray = IntArray(width * height),
) {
    init {
        require(width > 0 && height > 0) { "frame must have a positive size" }
        require(pixels.size >= width * height) {
            "pixel buffer holds ${pixels.size}, needs ${width * height}"
        }
    }

    /** True when [other] can be written into this buffer without reallocating. */
    fun hasSize(width: Int, height: Int): Boolean = this.width == width && this.height == height

    /**
     * The pixel at [x], [y]. The hot loops index [pixels] directly and hoist the
     * row offset; this is for the places where clarity is worth the multiply.
     */
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/**
 * A rectangle in frame pixel coordinates, held as edges rather than
 * origin-plus-size because every operation here is an intersection or a clamp.
 *
 * Fractional on purpose: iOS pads boxes by a fraction of their own size and
 * carries the result as a `CGRect` without rounding, so rounding here would
 * quietly shrink a padded box on some frames and not others.
 */
data class PixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isEmpty: Boolean get() = right <= left || bottom <= top

    /**
     * The overlap with [other], or `null` when they do not overlap. Mirrors the
     * `box.intersection(source.extent)` iOS does at composite time — the padded
     * box is allowed to hang off the frame and is only clipped when it is used.
     */
    fun intersect(other: PixelRect): PixelRect? {
        val result = PixelRect(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        )
        return if (result.isEmpty) null else result
    }

    companion object {
        /** The whole frame, for intersecting boxes against. */
        fun ofFrame(width: Int, height: Int): PixelRect =
            PixelRect(0f, 0f, width.toFloat(), height.toFloat())
    }
}
