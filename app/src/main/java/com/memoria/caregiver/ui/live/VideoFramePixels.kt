package com.memoria.caregiver.ui.live

import java.nio.ByteBuffer

/**
 * Getting a decoded WebRTC frame into the [ArgbFrame] the blur works on:
 * colour conversion, then rotation to upright. Spec:
 * `Features/Patient/Live/BlurredVideoView.swift`, PARITY.md §12.
 *
 * iOS gets both for free — `frame.toCVPixelBuffer()` hands Core Image something
 * it already understands, and `.oriented(_:)` is one call. Android decodes to
 * I420 planes and there is no equivalent, so the conversion is written out here.
 * It uses nothing but `java.nio`, which keeps it testable on the JVM — and
 * colour conversion is worth testing, because getting the range wrong produces
 * video that looks fine until you notice the blacks are grey.
 */
object VideoFramePixels {

    /**
     * Convert an I420 (planar YUV 4:2:0) frame to packed ARGB in [dest].
     *
     * Limited-range BT.601, which is what WebRTC delivers, in fixed point. The
     * `- 16` and the 1192 multiplier are the range expansion: skipping them
     * leaves blacks at 16/255 and whites at 235/255, i.e. washed out.
     *
     * Strides are honoured separately for every plane. A decoder is free to pad
     * rows, and assuming stride equals width produces a picture that shears
     * progressively down the frame.
     */
    fun i420ToArgb(
        dataY: ByteBuffer,
        strideY: Int,
        dataU: ByteBuffer,
        strideU: Int,
        dataV: ByteBuffer,
        strideV: Int,
        dest: ArgbFrame,
    ) {
        val width = dest.width
        val height = dest.height
        val out = dest.pixels
        for (y in 0 until height) {
            val rowY = y * strideY
            val rowChroma = y shr 1
            val rowU = rowChroma * strideU
            val rowV = rowChroma * strideV
            var index = y * width
            for (x in 0 until width) {
                val luma = ((dataY.get(rowY + x).toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                val chromaX = x shr 1
                val u = (dataU.get(rowU + chromaX).toInt() and 0xFF) - 128
                val v = (dataV.get(rowV + chromaX).toInt() and 0xFF) - 128

                val r = ((luma + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((luma - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((luma + 2066 * u) shr 10).coerceIn(0, 255)

                out[index++] = ALPHA_OPAQUE or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * Degrees of clockwise rotation, normalized to one of 0, 90, 180, 270.
     *
     * Anything else becomes 0. iOS does the same thing for the same reason —
     * `@unknown default: return .up` — because a frame drawn unrotated is
     * recoverable and a crash on the video thread is not.
     */
    fun normalizeRotation(degrees: Int): Int {
        val wrapped = ((degrees % 360) + 360) % 360
        return if (wrapped % 90 == 0) wrapped else 0
    }

    /** Frame width after rotating by [degrees]; 90 and 270 swap the axes. */
    fun rotatedWidth(width: Int, height: Int, degrees: Int): Int =
        if (normalizeRotation(degrees) % 180 == 90) height else width

    /** Frame height after rotating by [degrees]. */
    fun rotatedHeight(width: Int, height: Int, degrees: Int): Int =
        if (normalizeRotation(degrees) % 180 == 90) width else height

    /**
     * Rotate [source] clockwise by [degrees] into [dest].
     *
     * LiveKit reports the rotation that must be **applied** to show the frame
     * upright, on both platforms — iOS maps `._90` to `.right`, which is a 90°
     * clockwise display rotation, so clockwise is the direction here too.
     * PARITY.md §12 flags this as the mapping that yields sideways or
     * upside-down video when it is guessed rather than checked.
     *
     * Rotation happens **before** detection, matching iOS, which orients the
     * `CIImage` and then runs Vision on the oriented image. A detector handed a
     * sideways person finds no person, and the blur silently stops.
     */
    fun rotateClockwise(source: ArgbFrame, degrees: Int, dest: ArgbFrame) {
        val rotation = normalizeRotation(degrees)
        require(dest.width == rotatedWidth(source.width, source.height, rotation)) {
            "dest width mismatch for a $rotation degree rotation"
        }
        require(dest.height == rotatedHeight(source.width, source.height, rotation)) {
            "dest height mismatch for a $rotation degree rotation"
        }

        val src = source.pixels
        val out = dest.pixels
        val srcWidth = source.width
        val srcHeight = source.height

        when (rotation) {
            0 -> src.copyInto(out, 0, 0, srcWidth * srcHeight)

            90 -> {
                var index = 0
                for (y in 0 until dest.height) {
                    for (x in 0 until dest.width) {
                        out[index++] = src[(srcHeight - 1 - x) * srcWidth + y]
                    }
                }
            }

            180 -> {
                var index = 0
                for (y in 0 until dest.height) {
                    for (x in 0 until dest.width) {
                        out[index++] = src[(srcHeight - 1 - y) * srcWidth + (srcWidth - 1 - x)]
                    }
                }
            }

            else -> { // 270
                var index = 0
                for (y in 0 until dest.height) {
                    for (x in 0 until dest.width) {
                        out[index++] = src[x * srcWidth + (srcWidth - 1 - y)]
                    }
                }
            }
        }
    }

    private const val ALPHA_OPAQUE = 0xFF shl 24
}
