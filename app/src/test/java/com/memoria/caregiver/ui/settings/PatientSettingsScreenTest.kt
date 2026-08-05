package com.memoria.caregiver.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.FaceEnrollmentSummary
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `PatientSettingsScreen` behavior. Spec:
 * `Features/Patient/Settings/PatientSettingsView.swift`, PARITY.md §24.
 *
 * Asserts the things a caregiver would notice and a refactor could quietly
 * break: that removing a patient cannot happen on one tap, that a failed audio
 * PATCH replaces the body copy instead of appearing beside it, and that an
 * absent `audio_capture_enabled` reads as ON. Layout is not asserted — parity is
 * judged by screenshot (DECISIONS.md D-004).
 */
@RunWith(RobolectricTestRunner::class)
class PatientSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val peggy = patient()

    private fun setUp(
        state: PatientSettingsState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onRemovePatient: () -> Unit = {},
        onOpenFeedback: () -> Unit = {},
        onEnroll: () -> Unit = {},
        onRemovePhoto: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    PatientSettingsScreen(
                        state = state,
                        resolvePhotoUrl = { null },
                        onPickPhoto = {},
                        onRemovePhoto = onRemovePhoto,
                        onEnroll = onEnroll,
                        onEmailAlertsEnabledChange = {},
                        onEmailDraftChange = {},
                        onEmailRecipientsChange = {},
                        onEmailValidationError = {},
                        onSendTestEmail = {},
                        onPeopleBlurChange = {},
                        onAudioCaptureChange = {},
                        onDataCollectionChange = {},
                        onOpenFeedback = onOpenFeedback,
                        onRemovePatient = onRemovePatient,
                    )
                }
            }
        }
    }

    // ---- removing a patient is two-step ----

    @Test
    fun `tapping remove asks first and does not delete`() {
        var removed = 0
        setUp(PatientSettingsState(patient = peggy), onRemovePatient = { removed++ })

        compose.onNodeWithText("Remove this patient").performScrollTo().performClick()

        assertEquals(0, removed)
        compose.onNodeWithText("Remove patient?").assertIsDisplayed()
        // The message names the patient, so a caregiver managing two parents
        // cannot confirm the wrong one.
        compose.onNodeWithText(
            "Peggy's cameras, detectors, and event history will be permanently " +
                "removed. This cannot be undone.",
        ).assertIsDisplayed()

        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, removed)
    }

    @Test
    fun `cancelling the confirmation deletes nothing`() {
        var removed = 0
        setUp(PatientSettingsState(patient = peggy), onRemovePatient = { removed++ })

        compose.onNodeWithText("Remove this patient").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, removed)
        compose.onNodeWithText("Remove this patient").assertIsDisplayed()
    }

    /**
     * A delete already in flight must not be re-triggerable, or a caregiver
     * tapping twice fires two deletes at a patient that is already gone.
     */
    @Test
    fun `a delete in flight cannot be started again`() {
        var removed = 0
        setUp(
            PatientSettingsState(patient = peggy, isDeleting = true),
            onRemovePatient = { removed++ },
        )

        compose.onNodeWithText("Removing…").performScrollTo().performClick()

        assertEquals(0, removed)
        compose.onNodeWithText("Remove patient?").assertDoesNotExist()
    }

    // ---- audio capture ----

    /**
     * PARITY.md §24: on failure the card renders the error text **in place of**
     * the body copy, in terra — not in a separate banner.
     */
    @Test
    fun `an audio failure replaces the body copy rather than joining it`() {
        setUp(
            PatientSettingsState(
                patient = peggy.copy(audioCaptureEnabled = true),
                audioCapture = CardMutationState(errorText = AUDIO_CAPTURE_ERROR),
            ),
        )

        compose.onNodeWithText(AUDIO_CAPTURE_ERROR).assertExists()
        compose.onNodeWithText(
            "Cameras capture sound for live audio, clips, and speech alerts.",
        ).assertDoesNotExist()
    }

    /**
     * `audio_capture_enabled` is optional on the wire. Absent must read as ON —
     * showing it off would tell a caregiver sound is severed when it is not.
     */
    @Test
    fun `absent audio capture reads as on`() {
        setUp(PatientSettingsState(patient = peggy.copy(audioCaptureEnabled = null)))

        // Asserted on the CONTROL, not on the caption beside it.
        //
        // This used to look for the "cameras capture sound…" sentence, which
        // the July declutter moved behind an ⓘ. Reading state off prose was
        // always the weaker test — the toggle is what the caregiver acts on,
        // and it is what has to be right.
        // Selected by the toggle's own id. There are four switches on this
        // screen and none carries a visible title, so "the switch" would work
        // until the fifth one arrived and then target the wrong control.
        compose.onNodeWithTag("audio_capture.toggle").performScrollTo().assertIsOn()
    }

    // ---- identity card ----

    @Test
    fun `before the summary loads the card is neutral and offers Enroll`() {
        setUp(PatientSettingsState(patient = peggy))

        compose.onNodeWithText("Patient identity").assertExists()
        compose.onNodeWithText("Enroll").assertExists()
    }

    @Test
    fun `an enrolled patient is offered re-enrollment`() {
        var enrolls = 0
        setUp(
            PatientSettingsState(
                patient = peggy,
                enrollmentSummary = FaceEnrollmentSummary(
                    selfieCount = 5,
                    harvestedCount = 0,
                    processingCount = 0,
                    failedCount = 0,
                ),
            ),
            onEnroll = { enrolls++ },
        )

        compose.onNodeWithText("Peggy's face enrollment is complete").assertExists()
        compose.onNodeWithText("Re-enroll").performScrollTo().performClick()
        assertEquals(1, enrolls)
    }

    // ---- profile photo ----

    /** The remove affordance only exists once a photo has actually been uploaded. */
    @Test
    fun `remove photo appears only when a photo is stored`() {
        setUp(PatientSettingsState(patient = peggy))
        compose.onNodeWithText("Remove photo").assertDoesNotExist()
    }

    @Test
    fun `remove photo reports the tap when a photo is stored`() {
        var removed = 0
        setUp(
            PatientSettingsState(patient = peggy.copy(photoStorageKey = "p-1.jpg")),
            onRemovePhoto = { removed++ },
        )

        compose.onNodeWithText("Remove photo").performScrollTo().performClick()
        assertEquals(1, removed)
    }

    // ---- feedback ----

    /**
     * iOS puts `.contentShape(Rectangle())` on the padded row, so the whole row
     * is the tap target — including the gap between the copy and the chevron.
     */
    @Test
    fun `the whole feedback row opens feedback`() {
        var opened = 0
        setUp(PatientSettingsState(patient = peggy), onOpenFeedback = { opened++ })

        compose.onNodeWithText("Feedback & support").performScrollTo().performClick()
        assertEquals(1, opened)
    }
}
