package com.memoria.caregiver.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The patient-photo downscale rule. Spec: `NewPatientView.swift`'s
 * `downsampledJPEG(from:maxDimension:quality:)`.
 *
 * The backend caps a patient photo at 1 MiB. A modern phone camera produces
 * 4000px JPEGs several megabytes large, so a rule that stops shrinking fails the
 * upload — silently, because the Swift treats a photo failure as best-effort and
 * keeps the patient it just created.
 */
class NewPatientPhotoTest {

    @Test
    fun `the tuning constants match iOS`() {
        assertEquals(1024, PATIENT_PHOTO_MAX_DIMENSION)
        // iOS asks for 0.8 on a 0-1 scale; Android's compress() is 0-100.
        assertEquals(80, PATIENT_PHOTO_JPEG_QUALITY)
        assertEquals("image/jpeg", PATIENT_PHOTO_CONTENT_TYPE)
    }

    /** The Swift's scale factor is 1.0 for anything already inside the limit. */
    @Test
    fun `an image already within the limit is left alone`() {
        assertEquals(800 to 600, patientPhotoScaledSize(800, 600))
        assertEquals(1024 to 768, patientPhotoScaledSize(1024, 768))
    }

    @Test
    fun `the longest edge is scaled to the limit either way round`() {
        assertEquals(1024 to 576, patientPhotoScaledSize(4000, 2250))
        assertEquals(576 to 1024, patientPhotoScaledSize(2250, 4000))
    }

    /** A 12MP phone photo, which is the case this exists for. */
    @Test
    fun `a phone camera photo comes down to the limit`() {
        val (width, height) = patientPhotoScaledSize(4032, 3024)
        assertEquals(1024, width)
        assertEquals(768, height)
    }

    /** An extreme aspect ratio must not round the short edge to zero. */
    @Test
    fun `the short edge never rounds to zero`() {
        val (width, height) = patientPhotoScaledSize(4000, 1)
        assertEquals(1024, width)
        assertEquals(1, height)
    }
}
