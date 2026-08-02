package com.memoria.caregiver.ui.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ResetPasswordScreen behavior. Spec: `Features/Auth/ResetPasswordView.swift`.
 *
 * Two things carry the risk here and both are asserted below:
 *
 * 1. **The three-state machine.** Verifying / form / invalid are mutually
 *    exclusive, and the invalid state is the one a user actually hits (expired
 *    link, already-consumed token). Rendering the form over a dead session would
 *    let someone type a password that can never be saved.
 * 2. **Submit gating.** Length, equality with the confirmation, and busy. The
 *    confirmation check in particular is silent when broken: the form still
 *    works, it just stops catching typos in a password the user cannot see.
 */
@RunWith(RobolectricTestRunner::class)
class ResetPasswordScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder {
        var submits = 0
        var closes = 0
        val passwordEdits = mutableListOf<String>()
        val confirmEdits = mutableListOf<String>()
    }

    private fun setUp(
        state: ResetPasswordState,
        confirm: String = "",
        isLinkValid: Boolean = false,
    ): Recorder {
        val recorder = Recorder()
        compose.setContent {
            MemoriaTheme {
                ResetPasswordScreen(
                    state = state,
                    confirm = confirm,
                    isLinkValid = isLinkValid,
                    onPasswordChange = { recorder.passwordEdits += it },
                    onConfirmChange = { recorder.confirmEdits += it },
                    onSubmit = { recorder.submits++ },
                    onClose = { recorder.closes++ },
                )
            }
        }
        return recorder
    }

    /** A state that would submit if nothing else were wrong. */
    private fun readyState(
        isBusy: Boolean = false,
        errorMessage: String? = null,
        email: String? = null,
    ) = ResetPasswordState(
        email = email,
        password = "correcthorse",
        isExchangingCode = false,
        form = AuthFormState(isBusy = isBusy, errorMessage = errorMessage),
    )

    // ---- state machine ----

    @Test
    fun `while exchanging the code, only the verifying body renders`() {
        setUp(ResetPasswordState(isExchangingCode = true))

        compose.onNodeWithText("Verifying link…").assertIsDisplayed()
        compose.onNodeWithText("Reset password").assertIsDisplayed()
        compose.onNodeWithText("Set new password").assertDoesNotExist()
        compose.onNodeWithText("This reset link is missing, expired, or has already been used.")
            .assertDoesNotExist()
    }

    /**
     * `exchanging` must win over `linkValid`, or a flag left over from a previous
     * attempt would show an editable form during the exchange.
     */
    @Test
    fun `exchanging wins over a link-valid flag`() {
        setUp(ResetPasswordState(isExchangingCode = true), isLinkValid = true)

        compose.onNodeWithText("Verifying link…").assertIsDisplayed()
        compose.onNodeWithText("Set new password").assertDoesNotExist()
    }

    /** The headline is the state's own signal: it only promises a password once the link is good. */
    @Test
    fun `the headline changes once the link is known good`() {
        setUp(readyState(), confirm = "correcthorse", isLinkValid = true)

        compose.onNodeWithText("Set a new password.").assertIsDisplayed()
        compose.onNodeWithText("Reset password").assertDoesNotExist()
    }

    @Test
    fun `a failed exchange renders the invalid body, not the form`() {
        setUp(ResetPasswordState(isExchangingCode = false), isLinkValid = false)

        compose.onNodeWithText("This reset link is missing, expired, or has already been used.")
            .assertIsDisplayed()
        compose.onNodeWithText("Request a fresh link from the sign-in screen.").assertIsDisplayed()
        compose.onNodeWithText("Set new password").assertDoesNotExist()
    }

    // ---- submit gating ----

    @Test
    fun `a password shorter than eight characters does not submit`() {
        val recorder = setUp(
            ResetPasswordState(password = "short7!", isExchangingCode = false),
            confirm = "short7!",
            isLinkValid = true,
        )

        compose.onNodeWithText("Set new password").performClick()

        assertEquals(0, recorder.submits)
    }

    @Test
    fun `a confirmation that does not match does not submit`() {
        val recorder = setUp(readyState(), confirm = "correcthorsf", isLinkValid = true)

        compose.onNodeWithText("Set new password").performClick()

        assertEquals(0, recorder.submits)
    }

    @Test
    fun `an empty confirmation does not submit`() {
        val recorder = setUp(readyState(), confirm = "", isLinkValid = true)

        compose.onNodeWithText("Set new password").performClick()

        assertEquals(0, recorder.submits)
    }

    @Test
    fun `a long, matching password submits`() {
        val recorder = setUp(readyState(), confirm = "correcthorse", isLinkValid = true)

        compose.onNodeWithText("Set new password").performClick()

        assertEquals(1, recorder.submits)
    }

    /**
     * The double-submit guard. `updatePassword` also runs the provisioning path a
     * normal sign-in runs, so firing it twice is not a harmless duplicate call.
     */
    @Test
    fun `a busy form shows Updating and cannot be submitted again`() {
        val recorder = setUp(
            readyState(isBusy = true),
            confirm = "correcthorse",
            isLinkValid = true,
        )

        compose.onNodeWithText("Set new password").assertDoesNotExist()
        repeat(3) { compose.onNodeWithText("Updating…").performClick() }

        assertEquals(0, recorder.submits)
    }

    // ---- error copy, verbatim ----

    @Test
    fun `a submit error renders verbatim and leaves the form up`() {
        val message = "Your new password must be at least 8 characters."
        setUp(
            readyState(errorMessage = message),
            confirm = "correcthorse",
            isLinkValid = true,
        )

        compose.onNodeWithText(message).assertIsDisplayed()
        // iOS keeps the form on a submit failure; only an exchange failure is terminal.
        compose.onNodeWithText("Set new password").assertIsDisplayed()
    }

    @Test
    fun `an exchange error replaces the fallback sentence verbatim`() {
        val message = "That link has already been used."
        setUp(
            ResetPasswordState(isExchangingCode = false, form = AuthFormState(errorMessage = message)),
            isLinkValid = false,
        )

        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("This reset link is missing, expired, or has already been used.")
            .assertDoesNotExist()
    }

    // ---- the rest ----

    @Test
    fun `the confirmation line names the address being reset`() {
        setUp(
            readyState(email = "jane@example.com"),
            confirm = "correcthorse",
            isLinkValid = true,
        )

        compose.onNodeWithText("Resetting password for jane@example.com.").assertIsDisplayed()
    }

    /**
     * Both fields are secure, so nothing on screen distinguishes them once
     * they hold text. Crossing the two callbacks would still look correct.
     */
    @Test
    fun `edits route to the field they came from`() {
        val recorder = setUp(
            ResetPasswordState(isExchangingCode = false),
            confirm = "",
            isLinkValid = true,
        )

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performScrollTo().performTextInput("a")
        fields[1].performScrollTo().performTextInput("b")

        // Asserts ROUTING, not call counts. These are controlled fields whose
        // value comes from a state object the test holds frozen, so after an
        // edit the field reconciles back to the old value and emits a second
        // callback with it. That is an artefact of pinning the state, not
        // behaviour worth pinning — what matters is that "a" reached the
        // password callback and ONLY that one, and "b" the confirm callback.
        assertTrue("password edits: ${recorder.passwordEdits}", "a" in recorder.passwordEdits)
        assertTrue("confirm edits: ${recorder.confirmEdits}", "b" in recorder.confirmEdits)
        assertFalse("b leaked into the password field", "b" in recorder.passwordEdits)
        assertFalse("a leaked into the confirm field", "a" in recorder.confirmEdits)
    }

    @Test
    fun `the toolbar close dismisses`() {
        val recorder = setUp(ResetPasswordState(isExchangingCode = true))

        compose.onNodeWithText("Close").performClick()

        assertEquals(1, recorder.closes)
    }

    /** The invalid body's only way out is its own Close, so it must be wired. */
    @Test
    fun `the invalid body's close dismisses`() {
        val recorder = setUp(ResetPasswordState(isExchangingCode = false), isLinkValid = false)

        // Index 1 is the in-body button; index 0 is the toolbar's.
        compose.onAllNodes(hasText("Close"))[1].performClick()

        assertEquals(1, recorder.closes)
    }
}
