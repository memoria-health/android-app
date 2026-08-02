package com.memoria.caregiver.ui.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.enrollment.EnrollmentFacing
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The enrollment shell. Spec: PARITY.md §26-27.
 *
 * The gates are what matter here. Done must stay inert until coverage is
 * genuinely complete, because a partial gallery is worse than none — it enrolls
 * a face the wall cameras will then fail to match from the angles nobody
 * captured. The camera picker must lock once complete, and Skip must always
 * work, because enrollment is optional on both platforms.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FaceEnrollmentScreenTest {

    @get:Rule val compose = createComposeRule()

    /** Stands in for the camera preview this unit does not own. */
    private val previewMarker = "PREVIEW SLOT"

    private val complete = FaceEnrollmentState(
        cameraAuthorized = true,
        cameraConfigured = true,
        coverage = CoverageDialState(
            covered = (0 until COVERAGE_SEGMENT_COUNT).toSet(),
            hasFrontal = true,
            faceVisible = true,
            wellFramed = true,
        ),
        frontalCount = 2,
        isComplete = true,
    )

    private fun setUp(
        state: FaceEnrollmentState,
        onSkip: () -> Unit = {},
        onFacingChange: (EnrollmentFacing) -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onSubmit: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    FaceEnrollmentScreen(
                        state = state,
                        preview = { Text(previewMarker) },
                        onSkip = onSkip,
                        onFacingChange = onFacingChange,
                        onOpenSettings = onOpenSettings,
                        onSubmit = onSubmit,
                    )
                }
            }
        }
    }

    // ---- the Done gate ----

    @Test
    fun `Done is inert until coverage is complete`() {
        var submitted = 0
        setUp(
            FaceEnrollmentState(cameraAuthorized = true, cameraConfigured = true),
            onSubmit = { submitted++ },
        )

        compose.onNodeWithText("Done").performClick()

        assertEquals(0, submitted)
    }

    @Test
    fun `Done submits once coverage is complete`() {
        var submitted = 0
        setUp(complete, onSubmit = { submitted++ })

        compose.onNodeWithText("Done").performClick()

        assertEquals(1, submitted)
    }

    @Test
    fun `an in-flight submit cannot be repeated`() {
        var submitted = 0
        setUp(complete.copy(isSubmitting = true), onSubmit = { submitted++ })

        compose.onNodeWithText("Saving…").performClick()

        assertEquals(0, submitted)
    }

    @Test
    fun `a submit failure is shown as written`() {
        setUp(complete.copy(submitError = "Submit failed: the request timed out."))

        compose.onNodeWithText("Submit failed: the request timed out.").assertIsDisplayed()
    }

    // ---- skip ----

    /** Always available, at every stage — enrollment is optional. */
    @Test
    fun `Skip is available even when nothing has been captured`() {
        var skipped = 0
        setUp(FaceEnrollmentState(), onSkip = { skipped++ })

        compose.onNodeWithTag(FaceEnrollmentSkipTestTag).performClick()

        assertEquals(1, skipped)
    }

    // ---- the camera picker ----

    /** Rear is the default: a nurse pointing the phone at the resident. */
    @Test
    fun `switching camera is reported`() {
        var picked: EnrollmentFacing? = null
        setUp(FaceEnrollmentState(), onFacingChange = { picked = it })

        compose.onNodeWithText("Front camera").performClick()

        assertEquals(EnrollmentFacing.Front, picked)
    }

    /** Locked once complete: the captures are in and switching can only confuse. */
    @Test
    fun `the camera picker locks once coverage is complete`() {
        var picked: EnrollmentFacing? = null
        setUp(complete, onFacingChange = { picked = it })

        compose.onNodeWithText("Front camera").performClick()

        assertEquals(null, picked)
    }

    // ---- the two dials ----

    @Test
    fun `the rear dial talks to the person holding the phone`() {
        setUp(FaceEnrollmentState(cameraAuthorized = true, cameraConfigured = true))

        compose.onNodeWithText("Slowly move around to every side of them").assertIsDisplayed()
        compose.onNodeWithText(previewMarker).assertIsDisplayed()
    }

    @Test
    fun `the front dial talks to the person enrolling themselves`() {
        setUp(
            FaceEnrollmentState(
                facing = EnrollmentFacing.Front,
                cameraAuthorized = true,
                cameraConfigured = true,
            ),
        )

        compose.onNodeWithText("Slowly turn your head in every direction").assertIsDisplayed()
    }

    /** The caption is the model's settled guidance, shown as given. */
    @Test
    fun `the guidance caption is shown when the model supplies one`() {
        setUp(
            FaceEnrollmentState(
                cameraAuthorized = true,
                cameraConfigured = true,
                guidanceCaption = "Move a little closer to them",
            ),
        )

        compose.onNodeWithText("Move a little closer to them").assertIsDisplayed()
    }

    /** Completion replaces the live view, so the finished state reads as locked. */
    @Test
    fun `completion replaces the dial with a locked confirmation`() {
        setUp(complete)

        compose.onNodeWithText("Enrollment complete").assertIsDisplayed()
        compose.onNodeWithText(previewMarker).assertDoesNotExist()
        compose.onNodeWithText("Slowly move around to every side of them").assertDoesNotExist()
    }

    // ---- no camera ----

    /**
     * The recovery path: without it a denied permission is a dead end inside a
     * flow the caregiver has already committed to.
     */
    @Test
    fun `a denied camera offers a way into system settings`() {
        var opened = 0
        setUp(FaceEnrollmentState(cameraAuthorized = false), onOpenSettings = { opened++ })

        compose.onNodeWithText("Camera access needed").assertIsDisplayed()
        compose.onNodeWithText("Open Settings").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a camera setup failure is shown once access is granted`() {
        setUp(
            FaceEnrollmentState(
                cameraAuthorized = true,
                cameraConfigured = false,
                setupError = "No camera available.",
            ),
        )

        compose.onNodeWithText("No camera available.").assertIsDisplayed()
    }

    // ---- the upload cap ----

    @Test
    fun `the upload cap matches the backend`() {
        assertEquals(40, FACE_ENROLLMENT_MAX_UPLOAD)
    }
}
