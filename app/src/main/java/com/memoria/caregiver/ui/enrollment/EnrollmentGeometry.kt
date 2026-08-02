package com.memoria.caregiver.ui.enrollment

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max

/**
 * Maps a normalised face rect into a view rect for a preview shown aspect-fill
 * (`PreviewView.ScaleType.FILL_CENTER`, iOS `.resizeAspectFill`).
 *
 * Deterministic and pure — no camera and no layout — so the rear-camera
 * tracking ring can be positioned from Compose alone.
 *
 * Spec: `EnrollmentVisionCamera.swift` `EnrollmentGeometry`, `PARITY.md` §26.
 * The one change from iOS is the origin: this takes a **top-left** normalised
 * rect, so iOS's `1 - normVision.maxY` flip is already done by the time a rect
 * gets here.
 */
object EnrollmentGeometry {

    /** Portrait source aspect (w/h): 720×1280 once the buffer is upright. */
    const val SOURCE_ASPECT: Float = 720f / 1280f

    /**
     * @param normalized face box in normalised top-left coordinates.
     * @param size the preview's size in pixels or dp — whatever unit the caller
     *   wants back.
     * @param mirrored true when the preview is mirrored (front self-enroll), so
     *   the ring lands over the face the user sees rather than its reflection.
     */
    fun viewRect(normalized: Rect, size: Size, mirrored: Boolean): Rect {
        val nx = if (mirrored) 1f - normalized.right else normalized.left
        val ny = normalized.top
        val nw = normalized.width
        val nh = normalized.height

        // aspect-fill the portrait source into `size`
        val scale = max(size.width / SOURCE_ASPECT, size.height / 1f)
        val dispW = SOURCE_ASPECT * scale
        val dispH = 1f * scale
        val originX = (size.width - dispW) / 2f
        val originY = (size.height - dispH) / 2f

        val left = originX + nx * dispW
        val top = originY + ny * dispH
        return Rect(left = left, top = top, right = left + nw * dispW, bottom = top + nh * dispH)
    }
}
