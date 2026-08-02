package com.memoria.caregiver.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * NewPatientScreen behavior. Spec: `Features/Onboarding/NewPatientView.swift`.
 *
 * What is asserted is what a caregiver would notice and a refactor could quietly
 * break: that an incomplete form cannot create a patient, that the in-flight
 * state cannot double-submit, and that the failure sentence reaches the screen
 * unedited. Layout is judged by screenshot (DECISIONS.md D-004).
 */
// The photo preview is an ImageBitmap, and Robolectric's default LEGACY
// graphics returns a null Bitmap from createBitmap.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NewPatientScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 8, 2)

    /** Everything filled but the optional fields. */
    private val ready = NewPatientState(
        fullName = "Margaret O'Brien",
        dateOfBirth = LocalDate.of(1955, 3, 1),
        gender = "Female",
    )

    private fun setUp(
        state: NewPatientState,
        onClose: () -> Unit = {},
        onFullNameChange: (String) -> Unit = {},
        onDateOfBirthChange: (LocalDate) -> Unit = {},
        onGenderChange: (String) -> Unit = {},
        onRelationChange: (Patient.Relation) -> Unit = {},
        onCountryChange: (String) -> Unit = {},
        onPickPhoto: () -> Unit = {},
        onSubmit: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    NewPatientScreen(
                        state = state,
                        today = today,
                        onClose = onClose,
                        onFullNameChange = onFullNameChange,
                        onDateOfBirthChange = onDateOfBirthChange,
                        onGenderChange = onGenderChange,
                        onRelationChange = onRelationChange,
                        onCountryChange = onCountryChange,
                        onPickPhoto = onPickPhoto,
                        onSubmit = onSubmit,
                    )
                }
            }
        }
    }

    // ---- the submit gate ----

    /**
     * The button is inert, not hidden, exactly as on iOS — so the caregiver can
     * see what they are working toward.
     */
    @Test
    fun `an incomplete form cannot create a patient`() {
        var submitted = 0
        setUp(NewPatientState(fullName = "Margaret"), onSubmit = { submitted++ })

        compose.onNodeWithText("Add patient").performClick()

        assertEquals(0, submitted)
    }

    @Test
    fun `a complete form creates the patient`() {
        var submitted = 0
        setUp(ready, onSubmit = { submitted++ })

        compose.onNodeWithText("Add patient").performClick()

        assertEquals(1, submitted)
    }

    /** A second tap while the create is in flight would make two patients. */
    @Test
    fun `an in-flight submit cannot be repeated`() {
        var submitted = 0
        setUp(ready.copy(isSubmitting = true), onSubmit = { submitted++ })

        compose.onNodeWithText("Adding…").performClick()

        assertEquals(0, submitted)
    }

    // ---- failure ----

    /** The patient was NOT created, and the reason has to survive verbatim. */
    @Test
    fun `a create failure is shown as written`() {
        setUp(ready.copy(submitError = "The network connection was lost."))

        compose.onNodeWithText("The network connection was lost.").assertIsDisplayed()
    }

    // ---- fields ----

    @Test
    fun `typing a name reports it`() {
        var typed: String? = null
        setUp(NewPatientState(), onFullNameChange = { typed = it })

        compose.onNode(hasSetTextAction()).performTextInput("Margaret")

        assertEquals("Margaret", typed)
    }

    @Test
    fun `picking a gender reports it`() {
        var picked: String? = null
        // A date is already chosen, so "Select" is unambiguously the gender field.
        setUp(NewPatientState(dateOfBirth = LocalDate.of(1955, 3, 1)), onGenderChange = { picked = it })

        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Non-binary").performClick()

        assertEquals("Non-binary", picked)
    }

    @Test
    fun `picking a relationship reports it`() {
        var picked: Patient.Relation? = null
        setUp(ready, onRelationChange = { picked = it })

        compose.onNodeWithText("Grandparent").performScrollTo().performClick()

        assertEquals(Patient.Relation.GRANDPARENT, picked)
    }

    @Test
    fun `the close button reports a dismiss`() {
        var closed = 0
        setUp(ready, onClose = { closed++ })

        compose.onNodeWithTag(NewPatientCloseTestTag).performClick()

        assertEquals(1, closed)
    }

    // ---- photo ----

    @Test
    fun `the upload target invites a photo until one is picked`() {
        setUp(ready)

        compose.onNodeWithText("+ Upload photo").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a picked photo turns the target into a change action`() {
        setUp(ready.copy(photoPreview = ImageBitmap(2, 2)))

        compose.onNodeWithText("Change photo").performScrollTo().assertIsDisplayed()
    }

    /** Photo work must not race the create it is keyed to. */
    @Test
    fun `the photo cannot be changed while the patient is being created`() {
        var picks = 0
        setUp(ready.copy(isSubmitting = true), onPickPhoto = { picks++ })

        compose.onNodeWithText("+ Upload photo").performScrollTo().performClick()

        assertEquals(0, picks)
    }

    /** iOS: `String(fullName.first ?? "?")` — an empty name must not crash. */
    @Test
    fun `the avatar falls back to a question mark before a name is typed`() {
        setUp(NewPatientState())

        compose.onNodeWithText("?").performScrollTo().assertIsDisplayed()
    }
}
