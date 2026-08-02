package com.memoria.caregiver.ui.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ForgotPasswordScreen behavior. Spec: `Features/Auth/ForgotPasswordView.swift`.
 *
 * The two things worth pinning are the ones a plausible-looking port gets wrong:
 * the submit gate (iOS asks only for an "@", and refuses while a request is in
 * flight, which is what stops a double tap sending two recovery mails), and the
 * confirmation copy, which must never disclose whether the address is
 * registered.
 *
 * The device qualifier is a tall screen so the scrolling form is laid out in
 * one viewport; the clicks still scroll to their target, so the tests do not
 * depend on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ForgotPasswordScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Callbacks {
        val emails = mutableListOf<String>()
        var submits = 0
        var backs = 0
    }

    private fun setUp(state: ForgotPasswordState): Callbacks {
        val callbacks = Callbacks()
        compose.setContent {
            MemoriaTheme {
                ForgotPasswordScreen(
                    state = state,
                    onEmailChange = { callbacks.emails += it },
                    onSubmit = { callbacks.submits++ },
                    onBack = { callbacks.backs++ },
                )
            }
        }
        return callbacks
    }

    /**
     * `canSubmit` on iOS is `email.contains("@") && !sending`. A non-address
     * must not be submittable — but the button is still rendered and still
     * hit-tested, so the gate has to be the callback, not the pixels.
     */
    @Test
    fun `an address without an at sign cannot be submitted`() {
        val callbacks = setUp(ForgotPasswordState(email = "jane"))

        compose.onNodeWithText("Send reset link").performScrollTo().performClick()

        assertEquals(0, callbacks.submits)
    }

    @Test
    fun `an address containing an at sign submits`() {
        val callbacks = setUp(ForgotPasswordState(email = "jane@home.com"))

        compose.onNodeWithText("Send reset link").performScrollTo().performClick()

        assertEquals(1, callbacks.submits)
    }

    /**
     * The double-submission guard. While the reset call is in flight the label
     * changes and the button is inert; a second tap must not queue a second
     * recovery mail.
     */
    @Test
    fun `a busy form shows Sending and refuses a second tap`() {
        val callbacks = setUp(
            ForgotPasswordState(
                email = "jane@home.com",
                form = AuthFormState(isBusy = true),
            ),
        )

        compose.onNodeWithText("Sending…").performScrollTo().performClick()
        compose.onNodeWithText("Sending…").performClick()

        assertEquals(0, callbacks.submits)
    }

    /**
     * The sentence is `ApiError.errorDescription` and is product copy. Pinned
     * verbatim so a future "friendlier" rewrite in the UI layer fails loudly.
     */
    @Test
    fun `the error sentence renders verbatim`() {
        setUp(
            ForgotPasswordState(
                email = "jane@home.com",
                form = AuthFormState(errorMessage = "Too many requests. Try again in a minute."),
            ),
        )

        compose.onNodeWithText("Too many requests. Try again in a minute.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * Supabase returns success whether or not the address is registered, and
     * the confirmation must read identically either way — so the copy is
     * conditional ("If … is registered"), never affirmative. This also checks
     * the form is gone, because leaving the field on screen would invite a
     * second send.
     */
    @Test
    fun `the sent state replaces the form and does not confirm the account exists`() {
        setUp(ForgotPasswordState(email = "jane@home.com", sent = true))

        compose.onNodeWithText("Check your email.").assertIsDisplayed()
        compose.onNodeWithText(
            "If jane@home.com is registered, a reset link is on its way. " +
                "Check your inbox (and spam folder).",
        ).assertIsDisplayed()
        compose.onNodeWithText("The link expires in 1 hour.").assertIsDisplayed()

        compose.onNodeWithText("Send reset link").assertDoesNotExist()
        compose.onNodeWithText("Reset your password.").assertDoesNotExist()
        // NemaraEyebrow uppercases the field label.
        compose.onNodeWithText("EMAIL").assertDoesNotExist()
    }

    @Test
    fun `back to sign in dismisses from the sent state`() {
        val callbacks = setUp(ForgotPasswordState(email = "jane@home.com", sent = true))

        compose.onNodeWithText("Back to sign in").performScrollTo().performClick()

        assertEquals(1, callbacks.backs)
    }

    /**
     * Three affordances dismiss on iOS — the toolbar chevron, the "Sign in"
     * link and the sent-state button — and all three call the same `dismiss()`.
     */
    @Test
    fun `the sign in link and the toolbar chevron both dismiss`() {
        val callbacks = setUp(ForgotPasswordState(email = "jane@home.com"))

        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.onNodeWithTag(FORGOT_PASSWORD_BACK_TAG).performClick()

        assertEquals(2, callbacks.backs)
    }

    @Test
    fun `typing reports the email upward`() {
        val callbacks = setUp(ForgotPasswordState())

        compose.onNode(hasSetTextAction()).performTextInput("j")

        assertEquals(listOf("j"), callbacks.emails)
    }

    /**
     * The screen is stateless, so nothing it renders may depend on anything but
     * the state it was handed: an empty address still draws the form, and no
     * callback fires without a tap.
     */
    @Test
    fun `an untouched screen fires nothing`() {
        val callbacks = setUp(ForgotPasswordState())

        compose.onNodeWithText("Reset your password.").assertIsDisplayed()
        assertTrue(
            callbacks.emails.isEmpty() && callbacks.submits == 0 && callbacks.backs == 0,
        )
    }
}
