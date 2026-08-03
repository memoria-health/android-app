package com.memoria.caregiver.ui.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/**
 * What the sign-in screen announces. Spec: FINDINGS.md F-31.
 *
 * This is the first screen a caregiver meets and the one they meet again every
 * time a session lapses, so it is the screen where an unlabelled control costs
 * the most. Three things were missing: the back chevron had no name, the two
 * tappable sentences announced as text, and "Keep me signed in" announced as a
 * button — naming the option and never saying whether it was on.
 */
@RunWith(RobolectricTestRunner::class)
class LoginAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private var backs = 0

    private fun setUp(state: LoginState = LoginState()) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    LoginScreen(
                        state = state,
                        onEmailChange = {},
                        onPasswordChange = {},
                        onKeepSignedInChange = {},
                        onShowPasswordChange = {},
                        onSubmit = {},
                        onForgotPassword = {},
                        onCreateAccount = {},
                        onBack = { backs++ },
                    )
                }
            }
        }
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    // ---- the back chevron --------------------------------------------------

    @Test
    fun `the back chevron is named`() {
        setUp()
        compose.onNodeWithTag(LoginBackTestTag).assert(hasContentDescription("Back"))
    }

    @Test
    fun `the back chevron announces as a button`() {
        setUp()
        compose.onNodeWithTag(LoginBackTestTag).assert(isButton())
    }

    @Test
    fun `the back chevron still goes back`() {
        setUp()
        compose.onNodeWithTag(LoginBackTestTag).performClick()
        assertEquals(1, backs)
    }

    // ---- the tappable sentences --------------------------------------------

    /**
     * iOS builds both as `Button`, so VoiceOver says "button" after the words.
     * On Android they were `Text` with a tap and nothing else, which reads as a
     * sentence a caregiver has no reason to touch.
     */
    @Test
    fun `forgot password announces as a button`() {
        setUp()
        compose.onNodeWithText("Forgot password?").assert(isButton())
    }

    @Test
    fun `create an account announces as a button`() {
        setUp()
        compose.onNodeWithText("Create an account").assert(isButton())
    }

    // ---- keep me signed in -------------------------------------------------

    @Test
    fun `keep me signed in announces as a checkbox`() {
        setUp()
        compose.onNodeWithText("Keep me signed in")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }

    /**
     * The state, which is the only thing worth knowing about a sign-in
     * preference and the only thing the drawn checkbox carried.
     */
    @Test
    fun `keep me signed in reads off when it is off`() {
        setUp(LoginState(keepSignedIn = false))
        compose.onNodeWithText("Keep me signed in").assertIsOff()
    }

    @Test
    fun `keep me signed in reads on when it is on`() {
        setUp(LoginState(keepSignedIn = true))
        compose.onNodeWithText("Keep me signed in").assertIsOn()
    }
}
