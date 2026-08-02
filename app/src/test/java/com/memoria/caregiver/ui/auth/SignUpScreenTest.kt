package com.memoria.caregiver.ui.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SignUpScreen behavior. Spec: Features/Auth/SignUpView.swift.
 *
 * What is worth pinning on the tallest form in the app is the *gate*: seven
 * conditions have to hold before `Create account` does anything, and a port that
 * drops one of them renders identically and lets a caregiver submit a form the
 * server will reject. So the tests walk the gate open one condition at a time
 * and assert nothing happens until the last one closes.
 *
 * Layout is deliberately not asserted. The one structural assumption made here
 * is that the form has exactly five text fields in source order, which
 * `the form has five text fields, in source order` pins directly so the other
 * tests' index-based typing cannot silently start typing into the wrong box.
 */
@RunWith(RobolectricTestRunner::class)
class SignUpScreenTest {

    @get:Rule val compose = createComposeRule()

    // ---- fixtures -----------------------------------------------------------

    /** Everything `canSubmit` needs except the confirmation, which is typed in. */
    private fun readyToConfirm() = SignUpState(
        displayName = "Jane Patel",
        email = "jane@home.com",
        phone = "5551234567",
        password = "Margar3t!",
        acceptedTerms = true,
    )

    /**
     * Hosts the screen over mutable state, the way a real caller would.
     *
     * `onReviewTerms` flips `acceptedTerms` because that is the host's job on
     * both platforms: iOS presents `TermsConsentView` as a sheet whose
     * completion sets the flag, Android routes to the terms gate. The screen
     * never sets it itself, and a harness that did not model the round trip
     * could not test the gate at all.
     */
    private fun setUp(
        initial: SignUpState = SignUpState(),
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onSubmit: () -> Unit = {},
        onReviewTerms: () -> Unit = {},
        onNavigateToLogin: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    SignUpScreen(
                        state = state,
                        onDisplayNameChange = { state = state.copy(displayName = it) },
                        onEmailChange = { state = state.copy(email = it) },
                        onPhoneChange = { state = state.copy(phone = it) },
                        onCountryChange = { state = state.copy(countryCode = it) },
                        onPasswordChange = { state = state.copy(password = it) },
                        onReviewTerms = {
                            state = state.copy(acceptedTerms = true)
                            onReviewTerms()
                        },
                        onSubmit = onSubmit,
                        onNavigateToLogin = onNavigateToLogin,
                        onBack = onBack,
                    )
                }
            }
        }
    }

    private fun field(index: Int): SemanticsNodeInteraction =
        compose.onAllNodes(hasSetTextAction())[index]

    private fun tapSubmit() =
        compose.onNodeWithText("Create account").performScrollTo().performClick()

    // ---- the submit gate ----------------------------------------------------

    /**
     * The whole point of the screen's gating. Each step below satisfies one more
     * `canSubmit` term and must still do nothing.
     *
     * This also pins the field ordering the other tests type against: the five
     * values only open the gate if each landed in its own box — a name/email
     * swap fails `email.contains("@")`, an email/phone swap fails the digit
     * count — and the count assertion catches a sixth field being added.
     */
    @Test
    fun `submit does nothing until every condition is satisfied`() {
        var submits = 0
        setUp(onSubmit = { submits++ })

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(5)

        tapSubmit()
        assertEquals("empty form", 0, submits)

        field(0).performScrollTo().performTextInput("Jane Patel")
        field(1).performScrollTo().performTextInput("jane@home.com")
        field(2).performScrollTo().performTextInput("5551234567")
        field(3).performScrollTo().performTextInput("Margar3t!")
        tapSubmit()
        assertEquals("no confirmation, no consent", 0, submits)

        field(4).performScrollTo().performTextInput("Margar3t!")
        tapSubmit()
        assertEquals("consent still missing", 0, submits)

        compose.onNodeWithText("Review and agree to the Terms and Privacy Notice to continue.")
            .performScrollTo()
            .performClick()
        tapSubmit()
        assertEquals(1, submits)
    }

    @Test
    fun `a confirmation that does not match blocks submit and says so`() {
        var submits = 0
        setUp(initial = readyToConfirm(), onSubmit = { submits++ })

        field(4).performScrollTo().performTextInput("Margar3t")
        compose.onNodeWithText("Passwords don't match.").assertExists()
        tapSubmit()
        assertEquals(0, submits)

        field(4).performScrollTo().performTextInput("!")
        compose.onNodeWithText("Passwords don't match.").assertDoesNotExist()
        tapSubmit()
        assertEquals(1, submits)
    }

    /**
     * Double submission. A sign-up already in flight must not be able to fire a
     * second one, which is what would create two accounts from one impatient
     * double tap.
     */
    @Test
    fun `a busy form announces itself and cannot be submitted again`() {
        var submits = 0
        setUp(
            initial = readyToConfirm().copy(form = AuthFormState(isBusy = true)),
            onSubmit = { submits++ },
        )
        field(4).performScrollTo().performTextInput("Margar3t!")

        compose.onNodeWithText("Creating account…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create account").assertDoesNotExist()

        compose.onNodeWithText("Creating account…").performClick()
        assertEquals(0, submits)
    }

    // ---- copy and callbacks -------------------------------------------------

    /**
     * The error sentence comes from `ApiError.errorDescription` and is rendered
     * verbatim. This fails if anyone ever "friendlies" it up.
     */
    @Test
    fun `the error message renders verbatim`() {
        val message = "That email is already registered. Try logging in instead."
        setUp(initial = SignUpState(form = AuthFormState(errorMessage = message)))

        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the terms row reports the tap and restates consent once given`() {
        var reviews = 0
        setUp(onReviewTerms = { reviews++ })

        compose.onNodeWithText("Review and agree to the Terms and Privacy Notice to continue.")
            .performScrollTo()
            .performClick()

        assertEquals(1, reviews)
        compose.onNodeWithText("You've agreed to the Terms and Privacy Notice. Tap to review again.")
            .assertIsDisplayed()
    }

    @Test
    fun `the log in link navigates without submitting`() {
        var logins = 0
        var submits = 0
        setUp(onNavigateToLogin = { logins++ }, onSubmit = { submits++ })

        compose.onNodeWithText("Log in").performScrollTo().performClick()

        assertEquals(1, logins)
        assertEquals(0, submits)
    }

    /** iOS hides the system back button and draws its own, so the screen owns it. */
    @Test
    fun `the back chevron reports back`() {
        var backs = 0
        setUp(onBack = { backs++ })

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    /**
     * `.trackScreen("auth.signup")` fires from onAppear on iOS. Typing recomposes
     * this form on every keystroke, so an effect keyed on anything but `Unit`
     * would log the screen dozens of times and wreck the screen-flow funnel.
     */
    @Test
    fun `the screen is tracked on appear, once, however much is typed`() {
        val analytics = RecordingAnalytics()
        setUp(analytics = analytics)

        field(0).performScrollTo().performTextInput("Jane Patel")
        field(1).performScrollTo().performTextInput("jane@home.com")

        assertEquals(listOf("auth.signup"), analytics.names("screen"))
    }

    // ---- the gate as pure logic --------------------------------------------

    @Test
    fun `every canSubmit term is load-bearing`() {
        val ok = readyToConfirm()
        val confirmation = "Margar3t!"
        assertTrue(signUpCanSubmit(ok, confirmation))

        assertFalse("name", signUpCanSubmit(ok.copy(displayName = "   "), confirmation))
        assertFalse("email", signUpCanSubmit(ok.copy(email = "jane.home.com"), confirmation))
        assertFalse("phone", signUpCanSubmit(ok.copy(phone = "555123456"), confirmation))
        assertFalse("password", signUpCanSubmit(ok.copy(password = "margar3t!"), confirmation))
        assertFalse("confirmation", signUpCanSubmit(ok, ""))
        assertFalse("mismatch", signUpCanSubmit(ok, "Margar3t"))
        assertFalse("consent", signUpCanSubmit(ok.copy(acceptedTerms = false), confirmation))
        assertFalse(
            "busy",
            signUpCanSubmit(ok.copy(form = AuthFormState(isBusy = true)), confirmation),
        )
    }

    /**
     * iOS checks the email with `email.contains("@")` and nothing more. Tightening
     * that here would reject addresses the iOS app accepts and the server takes.
     */
    @Test
    fun `the email check is the iOS one and not a real validator`() {
        val ok = readyToConfirm()
        assertTrue(signUpCanSubmit(ok.copy(email = "@"), "Margar3t!"))
    }

    /** "at least 10 digits once non-digit chars are stripped" — formatting is free. */
    @Test
    fun `phone counts digits and ignores everything else`() {
        assertTrue(isSignUpPhoneValid("(555) 123-4567"))
        assertTrue(isSignUpPhoneValid("+1 555 123 4567"))
        assertFalse(isSignUpPhoneValid("(555) 123-456"))
        assertFalse(isSignUpPhoneValid(""))
        // Swift's Character.isNumber is Unicode-aware, so Kotlin's must be too.
        assertTrue(isSignUpPhoneValid("९८७६५४३२१०"))
    }

    @Test
    fun `the consent sentences are the Swift copy, bold markers aside`() {
        assertEquals(
            "Review and agree to the Terms and Privacy Notice to continue.",
            signUpTermsSentence(accepted = false).text,
        )
        assertEquals(
            "You've agreed to the Terms and Privacy Notice. Tap to review again.",
            signUpTermsSentence(accepted = true).text,
        )
    }
}
