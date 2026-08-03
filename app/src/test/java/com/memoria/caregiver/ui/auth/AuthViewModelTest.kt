package com.memoria.caregiver.ui.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [AuthViewModel]'s sign-up half. Spec: `Features/Auth/SignUpView.swift`.
 *
 * Everything asserted here happens **before** any request leaves: whether the
 * view model agrees to call at all, and what it writes into `SignUpState` on the
 * way. `Dispatchers.Main` is parked on a [StandardTestDispatcher] that is never
 * advanced, so the `viewModelScope.launch` inside `submitSignUp` is queued and
 * its body — the only part that would touch Supabase — never runs. One
 * dispatcher, one scheduler, nothing to drain: the harness trap
 * `CameraDetailViewModelTest` documents is avoided by not starting anything.
 */
// Robolectric, not a plain JVM test: building a Supabase client pulls in Android
// framework classes even with an in-memory session manager.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: AuthViewModel

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        viewModel = offlineAuthViewModel(mainDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Everything `signUpCanSubmit` asks for, satisfied. */
    private fun completedForm() = SignUpState(
        displayName = "Jane Patel",
        email = "jane@home.com",
        phone = "5551234567",
        password = PASSWORD,
        acceptedTerms = true,
    )

    private companion object {
        const val PASSWORD = "Password1!"
        const val TOS = "2026-07-26-placeholder"
    }

    // -----------------------------------------------------------------------
    // The submit gate
    // -----------------------------------------------------------------------

    /**
     * The sign-up call carries a `tosVersion`, so letting a form through without
     * agreement would record consent that was never given. The screen's button
     * is disabled, and this is the second lock on the same door.
     */
    @Test
    fun `signing up is refused until the terms are accepted`() {
        viewModel.onSignUpChange(completedForm().copy(acceptedTerms = false))

        viewModel.submitSignUp(confirmPassword = PASSWORD, tosVersion = TOS)

        assertFalse("no call may start", viewModel.signUp.value.form.isBusy)
    }

    @Test
    fun `signing up is refused while the form is incomplete`() {
        // Nine digits — one short of what support needs to text back.
        viewModel.onSignUpChange(completedForm().copy(phone = "555123456"))

        viewModel.submitSignUp(confirmPassword = PASSWORD, tosVersion = TOS)

        assertFalse(viewModel.signUp.value.form.isBusy)
    }

    @Test
    fun `signing up is refused when the confirmation does not match`() {
        viewModel.onSignUpChange(completedForm())

        viewModel.submitSignUp(confirmPassword = "Password2!", tosVersion = TOS)

        assertFalse(viewModel.signUp.value.form.isBusy)
    }

    /**
     * A double tap must not fire two sign-ups. The second call sees `isBusy` and
     * stops, which is the same guard `submitLogin` carries.
     */
    @Test
    fun `a second submit while one is in flight is refused`() {
        viewModel.onSignUpChange(completedForm())

        viewModel.submitSignUp(confirmPassword = PASSWORD, tosVersion = TOS)
        val afterFirst = viewModel.signUp.value
        viewModel.submitSignUp(confirmPassword = PASSWORD, tosVersion = TOS)

        assertTrue("the first call is the one that runs", afterFirst.form.isBusy)
        assertEquals(afterFirst, viewModel.signUp.value)
    }

    /** A complete, agreed form does start the call, and clears any stale error. */
    @Test
    fun `a completed form starts the sign-up`() {
        viewModel.onSignUpChange(
            completedForm().copy(form = AuthFormState(errorMessage = "That email is already in use.")),
        )

        viewModel.submitSignUp(confirmPassword = PASSWORD, tosVersion = TOS)

        assertTrue(viewModel.signUp.value.form.isBusy)
        assertNull(viewModel.signUp.value.form.errorMessage)
    }

    // -----------------------------------------------------------------------
    // The field setters
    // -----------------------------------------------------------------------

    /**
     * Each setter reads the live value rather than one handed in, so two edits
     * landing back to back cannot lose the earlier one — the read-modify-write
     * hazard that the whole-state setter has by construction.
     */
    @Test
    fun `each field is edited without disturbing the others`() {
        viewModel.onSignUpDisplayNameChange("Jane Patel")
        viewModel.onSignUpEmailChange("jane@home.com")
        viewModel.onSignUpPhoneChange("5551234567")
        viewModel.onSignUpCountryChange("IN")
        viewModel.onSignUpPasswordChange(PASSWORD)

        val state = viewModel.signUp.value
        assertEquals("Jane Patel", state.displayName)
        assertEquals("jane@home.com", state.email)
        assertEquals("5551234567", state.phone)
        assertEquals("IN", state.countryCode)
        assertEquals(PASSWORD, state.password)
    }

    /** Typing clears a stale error, so a corrected field stops shouting. */
    @Test
    fun `editing a field clears a stale error`() {
        viewModel.onSignUpChange(
            SignUpState(form = AuthFormState(errorMessage = "That email is already in use.")),
        )

        viewModel.onSignUpEmailChange("someone-else@home.com")

        assertNull(viewModel.signUp.value.form.errorMessage)
    }

    /**
     * Agreement is set only from the consent gate. Nothing else in the view
     * model writes it, which is what keeps it a decision the caregiver made.
     */
    @Test
    fun `accepting the terms is what marks the form agreed`() {
        assertFalse(viewModel.signUp.value.acceptedTerms)

        viewModel.onSignUpTermsAccepted()

        assertTrue(viewModel.signUp.value.acceptedTerms)
    }
}
