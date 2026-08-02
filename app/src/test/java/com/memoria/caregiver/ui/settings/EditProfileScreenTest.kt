package com.memoria.caregiver.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `EditProfileScreen` behavior. Spec: `Features/AppSettings/EditProfileView.swift`.
 *
 * The gates themselves are pure and live in `SettingsUiStateTest`; what is
 * checked here is that the screen is actually wired to them — that Save is inert
 * when the gate says so, that each field reports to its own callback, and that
 * the email-change warning appears exactly when the address differs. The last
 * one matters because without it a caregiver changes their email, sees the old
 * one afterwards, and reasonably concludes the app lost their edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class EditProfileScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Callbacks {
        val names = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val phones = mutableListOf<String>()
        var appears = 0
        var cancels = 0
        var saves = 0
    }

    private fun setUp(state: EditProfileState): Callbacks {
        val callbacks = Callbacks()
        compose.setContent {
            MemoriaTheme {
                EditProfileScreen(
                    state = state,
                    onNameChange = { callbacks.names += it },
                    onEmailChange = { callbacks.emails += it },
                    onPhoneChange = { callbacks.phones += it },
                    onAppear = { callbacks.appears++ },
                    onCancel = { callbacks.cancels++ },
                    onSave = { callbacks.saves++ },
                )
            }
        }
        return callbacks
    }

    private fun hydrated(): EditProfileState =
        EditProfileState(caregiver = caregiver()).hydrated()

    // -----------------------------------------------------------------------
    // The Save control
    // -----------------------------------------------------------------------

    @Test
    fun `save is inert until something changes`() {
        val callbacks = setUp(hydrated())

        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()

        assertEquals(0, callbacks.saves)
    }

    @Test
    fun `save fires once the form is changed and valid`() {
        val callbacks = setUp(hydrated().copy(name = "Ary"))

        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()

        assertEquals(1, callbacks.saves)
    }

    /**
     * Phone is required here just as it is at signup, so an edit that guts it
     * cannot be saved even though something genuinely changed.
     */
    @Test
    fun `a changed but invalid form cannot be saved`() {
        val callbacks = setUp(hydrated().copy(phone = "555"))

        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()

        assertEquals(0, callbacks.saves)
    }

    /**
     * The double-submission guard. While the save is in flight the control shows
     * a spinner instead of the word, and a second tap must not send a second
     * `PATCH /v1/me` — or, worse, a second email-change request.
     */
    @Test
    fun `a save in flight refuses a second tap`() {
        // The spinner that replaces the word animates forever; with the clock
        // auto-advancing, every finder's `waitForIdle` would wait for a frame
        // that never stops coming. See AppSettingsScreenTest for the same note.
        compose.mainClock.autoAdvance = false

        val callbacks = setUp(hydrated().copy(name = "Ary", isSaving = true))

        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()
        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()

        assertEquals(0, callbacks.saves)
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `cancel dismisses without saving`() {
        val callbacks = setUp(hydrated().copy(name = "Ary"))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, callbacks.cancels)
        assertEquals(0, callbacks.saves)
    }

    // -----------------------------------------------------------------------
    // The email-change warning
    // -----------------------------------------------------------------------

    /**
     * The hint is the only thing that explains why the caregiver's address does
     * not change straight away. It must not be showing when nothing changed, and
     * it must not appear for a difference in case alone, which is the same
     * address and does not trigger the Supabase flow.
     */
    @Test
    fun `the email hint appears only once the address really differs`() {
        setUp(hydrated())

        compose.onNodeWithText(EMAIL_CHANGE_HINT).assertDoesNotExist()
    }

    @Test
    fun `a case-only email edit shows no hint`() {
        setUp(hydrated().copy(email = "Aryaman@Memoria.Care"))

        compose.onNodeWithText(EMAIL_CHANGE_HINT).assertDoesNotExist()
    }

    @Test
    fun `a new address shows the confirmation-link hint`() {
        setUp(hydrated().copy(email = "ary@memoria.care"))

        compose.onNodeWithText(EMAIL_CHANGE_HINT).performScrollTo().assertIsDisplayed()
    }

    // -----------------------------------------------------------------------
    // Fields and lifecycle
    // -----------------------------------------------------------------------

    /**
     * Three fields of the same shape, in one column, each with its own callback.
     * A swapped pair here would write the caregiver's phone number into their
     * name and mail a confirmation link to a phone number.
     */
    @Test
    fun `each field reports to its own callback`() {
        val callbacks = setUp(EditProfileState(caregiver = caregiver()))
        val fields = compose.onAllNodes(hasSetTextAction())

        fields[0].performScrollTo().performTextInput("n")
        fields[1].performScrollTo().performTextInput("e")
        fields[2].performScrollTo().performTextInput("p")

        // Routing, not call counts. These are controlled fields over a state the
        // test holds frozen, so after each edit the field reconciles back to the
        // old value and emits a second callback carrying it. That is an artefact
        // of pinning the state. What matters is that each character reached its
        // OWN callback and leaked into no other — the defect this guards is two
        // crossed callbacks, which would mail a confirmation link to a phone
        // number.
        assertTrue("names = ${callbacks.names}", "n" in callbacks.names)
        assertTrue("emails = ${callbacks.emails}", "e" in callbacks.emails)
        assertTrue("phones = ${callbacks.phones}", "p" in callbacks.phones)
        assertFalse("e leaked into names", "e" in callbacks.names)
        assertFalse("n leaked into emails", "n" in callbacks.emails)
        assertFalse("p leaked into emails", "p" in callbacks.emails)
    }

    /** `.onAppear(perform: hydrate)` — reported once, so the host can hydrate. */
    @Test
    fun `the sheet reports its appearance once`() {
        val callbacks = setUp(EditProfileState(caregiver = caregiver()))

        assertEquals(1, callbacks.appears)
    }

    /** The failure sentence is the thrown error's own; rendered verbatim. */
    @Test
    fun `the error renders verbatim`() {
        setUp(hydrated().copy(errorMessage = "That email is already in use."))

        compose.onNodeWithText("That email is already in use.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
