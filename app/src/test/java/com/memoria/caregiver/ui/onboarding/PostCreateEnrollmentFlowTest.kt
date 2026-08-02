package com.memoria.caregiver.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What happens after a patient is created. Spec: `NewPatientView.swift`'s
 * `PostCreateEnrollmentFlow` and `FaceDetectionConsentView`.
 *
 * The consent screen is the one place in the app that asks to put a face in the
 * re-ID gallery, so all three answers are pinned: two end the flow, one goes on
 * to capture, and only one of them records a preference. Getting "Don't ask me
 * again" wrong means asking a caregiver, repeatedly, for consent they declined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PostCreateEnrollmentFlowTest {

    @get:Rule val compose = createComposeRule()

    private val detectorsMarker = "DETECTOR LIBRARY SLOT"
    private val enrollmentMarker = "FACE ENROLLMENT SLOT"

    private var finished = 0
    private var dismissedForever = 0

    private fun setUp() {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    PostCreateEnrollmentFlow(
                        onFinish = { finished++ },
                        onDontAskAgain = { dismissedForever++ },
                        // The two screens the flow does not own. Tapping the
                        // marker stands in for their Continue and their finish.
                        detectorLibrary = { onContinue ->
                            Text(detectorsMarker, Modifier.clickable(onClick = onContinue))
                        },
                        faceEnrollment = { onComplete ->
                            Text(enrollmentMarker, Modifier.clickable(onClick = onComplete))
                        },
                    )
                }
            }
        }
    }

    /** Detectors first: the recommended set is pre-checked before anything else. */
    @Test
    fun `the flow opens on the detector library`() {
        setUp()

        compose.onNodeWithText(detectorsMarker).assertIsDisplayed()
    }

    @Test
    fun `continuing from detectors asks for facial-detection consent`() {
        setUp()

        compose.onNodeWithText(detectorsMarker).performClick()

        compose.onNodeWithText("Would you like to add facial detection?").assertIsDisplayed()
    }

    @Test
    fun `Proceed goes on to capture`() {
        setUp()
        compose.onNodeWithText(detectorsMarker).performClick()

        compose.onNodeWithText("Proceed").performClick()

        compose.onNodeWithText(enrollmentMarker).assertIsDisplayed()
        assertEquals(0, finished)
    }

    @Test
    fun `finishing capture ends the flow`() {
        setUp()
        compose.onNodeWithText(detectorsMarker).performClick()
        compose.onNodeWithText("Proceed").performClick()

        compose.onNodeWithText(enrollmentMarker).performClick()

        assertEquals(1, finished)
    }

    @Test
    fun `Maybe later ends the flow without recording anything`() {
        setUp()
        compose.onNodeWithText(detectorsMarker).performClick()

        compose.onNodeWithText("Maybe later").performClick()

        assertEquals(1, finished)
        assertEquals(0, dismissedForever)
    }

    /** Records the choice AND ends the flow — both, not one or the other. */
    @Test
    fun `the permanent dismissal records the choice and ends the flow`() {
        setUp()
        compose.onNodeWithText(detectorsMarker).performClick()

        compose.onNodeWithText("Don't ask me again").performClick()

        assertEquals(1, dismissedForever)
        assertEquals(1, finished)
    }

    /** The reason for asking, in the caregiver's words. Verbatim copy. */
    @Test
    fun `the consent screen explains why`() {
        setUp()
        compose.onNodeWithText(detectorsMarker).performClick()

        compose.onNodeWithText(
            "Adding facial detection lets Memoria be more accurate, " +
                "so alerts are more timely and consistent.",
        ).assertIsDisplayed()
    }
}
