package com.memoria.caregiver.ui.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LoginScreen behavior. Spec: Features/Auth/LoginView.swift, PARITY.md §16.
 *
 * These assert the things a caregiver would notice and a refactor could quietly
 * break: that an incomplete or in-flight form cannot be submitted, that the
 * server's error sentence reaches the screen unedited, and that each tappable
 * label reports the right thing. Layout is not asserted — parity is judged by
 * screenshot (DECISIONS.md D-004).
 */
@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {

    @get:Rule val compose = createComposeRule()

    private val filled = LoginState(email = "jane@home.com", password = "hunter2")

    private fun setUp(
        state: LoginState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onEmailChange: (String) -> Unit = {},
        onPasswordChange: (String) -> Unit = {},
        onKeepSignedInChange: (Boolean) -> Unit = {},
        onShowPasswordChange: (Boolean) -> Unit = {},
        onSubmit: () -> Unit = {},
        onForgotPassword: () -> Unit = {},
        onCreateAccount: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    LoginScreen(
                        state = state,
                        onEmailChange = onEmailChange,
                        onPasswordChange = onPasswordChange,
                        onKeepSignedInChange = onKeepSignedInChange,
                        onShowPasswordChange = onShowPasswordChange,
                        onSubmit = onSubmit,
                        onForgotPassword = onForgotPassword,
                        onCreateAccount = onCreateAccount,
                        onBack = onBack,
                    )
                }
            }
        }
    }

    // ---- Submit gating -----------------------------------------------------

    /** iOS: `.disabled(!canSubmit)`. An empty form must be inert, not merely ugly. */
    @Test
    fun `empty form cannot be submitted`() {
        var submits = 0
        setUp(LoginState(), onSubmit = { submits++ })

        compose.onNodeWithText("Log in").performClick()

        assertEquals(0, submits)
    }

    @Test
    fun `password alone cannot be submitted`() {
        var submits = 0
        setUp(LoginState(password = "hunter2"), onSubmit = { submits++ })

        compose.onNodeWithText("Log in").performClick()

        assertEquals(0, submits)
    }

    @Test
    fun `a complete form submits once per tap`() {
        var submits = 0
        setUp(filled, onSubmit = { submits++ })

        compose.onNodeWithText("Log in").performClick()

        assertEquals(1, submits)
    }

    /**
     * The double-submission guard. A sign-in already in flight leaves the form
     * fully populated, so the only thing standing between an impatient second
     * tap and a second network call is `isBusy` reaching the button.
     */
    @Test
    fun `a busy form swaps the label and refuses further taps`() {
        var submits = 0
        setUp(
            filled.copy(form = AuthFormState(isBusy = true)),
            onSubmit = { submits++ },
        )

        compose.onNodeWithText("Signing in…").assertIsDisplayed()
        compose.onNodeWithText("Signing in…").performClick()

        assertEquals(0, submits)
    }

    // ---- Errors ------------------------------------------------------------

    /**
     * Pinned verbatim. The copy belongs to the product, and a screen that
     * rewrites it — even into something friendlier — is a defect.
     */
    @Test
    fun `renders the error sentence exactly as given`() {
        setUp(
            filled.copy(
                form = AuthFormState(
                    errorMessage = "That email or password is incorrect. Please try again.",
                ),
            ),
        )

        compose
            .onNodeWithText("That email or password is incorrect. Please try again.")
            .assertIsDisplayed()
    }

    // ---- Password visibility ----------------------------------------------

    @Test
    fun `Show reveals the password and the label becomes Hide`() {
        var requested: Boolean? = null
        setUp(filled, onShowPasswordChange = { requested = it })

        compose.onNodeWithText("Show").performClick()

        assertEquals(true, requested)
    }

    @Test
    fun `Hide is offered once the password is visible`() {
        var requested: Boolean? = null
        setUp(
            filled.copy(showPassword = true),
            onShowPasswordChange = { requested = it },
        )

        compose.onNodeWithText("Hide").performClick()

        assertEquals(false, requested)
    }

    // ---- Keep me signed in -------------------------------------------------

    /**
     * The box starts checked (AuthUiState.kt: the forgiving default for someone
     * checking on a parent at 3am), so the first tap must report `false`.
     */
    @Test
    fun `tapping Keep me signed in reports the flipped value`() {
        var kept: Boolean? = null
        setUp(filled, onKeepSignedInChange = { kept = it })

        compose.onNodeWithText("Keep me signed in").performClick()

        assertEquals(false, kept)
    }

    // ---- Navigation --------------------------------------------------------

    @Test
    fun `Forgot password leaves the screen`() {
        var forgot = 0
        setUp(filled, onForgotPassword = { forgot++ })

        compose.onNodeWithText("Forgot password?").performClick()

        assertEquals(1, forgot)
    }

    @Test
    fun `Create an account leaves the screen`() {
        var create = 0
        setUp(LoginState(), onCreateAccount = { create++ })

        compose.onNodeWithText("Create an account").performScrollTo().performClick()

        assertEquals(1, create)
    }

    /** iOS hides the system back button and supplies its own chevron. */
    @Test
    fun `the back chevron dismisses`() {
        var backs = 0
        setUp(LoginState(), onBack = { backs++ })

        compose.onNodeWithTag(LoginBackTestTag).performClick()

        assertEquals(1, backs)
    }

    // ---- Analytics ---------------------------------------------------------

    /** iOS: `.trackScreen("auth.login")`, and the name is part of the funnel. */
    @Test
    fun `records the screen once on appearance`() {
        val analytics = RecordingAnalytics()
        setUp(LoginState(), analytics = analytics)
        compose.waitForIdle()

        assertEquals(listOf("auth.login"), analytics.names("screen"))
    }
}
