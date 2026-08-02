package com.memoria.caregiver.ui.enrollment

import androidx.compose.ui.geometry.Rect

/**
 * Stand-in for Vision's `faceCaptureQuality` — the one input of the enrollment
 * loop that has **no ML Kit equivalent**. See `QUESTIONS.md` Q2.
 *
 * It is an injected function so the rest of the camera can be built, reviewed
 * and tested while the metric itself is settled, and so that swapping the
 * metric later touches exactly one file.
 *
 * Whatever lands here, the coverage model's `0.4` frontal / `0.25` extreme
 * floors are calibrated to **Vision's** scale and must be re-derived against the
 * replacement. Carrying the iOS numbers onto a different metric would silently
 * reject or accept the wrong frames, which is invisible in review and only shows
 * up as a bad re-ID gallery weeks later.
 */
fun interface EnrollmentQualityScorer {

    /**
     * Score one detected face, 0…1, higher is better.
     *
     * @param frame upright pixel access to the whole frame, for a blur or
     *   exposure estimate.
     * @param faceBox the face in normalised top-left coordinates, so an
     *   implementation can restrict itself to the face crop.
     */
    fun score(frame: EnrollmentFrame, faceBox: Rect): Float
}

/**
 * **PLACEHOLDER — not a quality metric.** Returns a constant that clears every
 * floor, so the capture loop runs end to end and the dials can be exercised.
 *
 * Shipping this means the enrollment gallery accepts blurred and badly-lit
 * frames, which is exactly the failure the real metric exists to prevent. It
 * must be replaced before enrollment reaches a household — `QUESTIONS.md` Q2.
 */
object PlaceholderQualityScorer : EnrollmentQualityScorer {
    override fun score(frame: EnrollmentFrame, faceBox: Rect): Float = 1f
}
