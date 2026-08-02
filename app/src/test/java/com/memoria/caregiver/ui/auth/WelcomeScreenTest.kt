package com.memoria.caregiver.ui.auth

import androidx.compose.runtime.CompositionLocalProvider
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

/**
 * WelcomeScreen behavior. Spec: Features/Auth/WelcomeView.swift, PARITY.md §28.
 *
 * The screen is static, so there is no submit gating and no busy state to pin.
 * What can actually break in a port is which CTA goes where — the two buttons sit
 * next to each other, take the same callback shape, and swapping them compiles,
 * renders identically, and sends every new caregiver to a login form for an
 * account they do not have yet.
 */
@RunWith(RobolectricTestRunner::class)
class WelcomeScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp(
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onSignUp: () -> Unit = {},
        onLogin: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    WelcomeScreen(onSignUp = onSignUp, onLogin = onLogin)
                }
            }
        }
    }

    /**
     * Both directions in one composition, so a wiring swap cannot pass by
     * satisfying one assertion at a time.
     */
    @Test
    fun `each cta fires only its own navigation`() {
        var signUp = 0
        var login = 0
        setUp(onSignUp = { signUp++ }, onLogin = { login++ })

        compose.onNodeWithText("Create an account").performClick()
        assertEquals(1, signUp)
        assertEquals(0, login)

        compose.onNodeWithText("I already have one").performClick()
        assertEquals(1, signUp)
        assertEquals(1, login)
    }

    /**
     * iOS attaches `.trackScreen("auth.welcome")`, an onAppear. This is the first
     * screen of the signed-out funnel, so losing the event silently removes the
     * denominator from every activation number.
     */
    @Test
    fun `logs the welcome screen event on appear`() {
        val analytics = RecordingAnalytics()
        setUp(analytics)
        compose.waitForIdle()

        assertEquals(listOf("auth.welcome"), analytics.names("screen"))
    }

    /**
     * The hero copy is the product's, pinned verbatim. Both strings are exact
     * matches, so a reflowed or "improved" sentence fails rather than passing on
     * a substring.
     */
    @Test
    fun `renders the hero copy verbatim`() {
        setUp()

        compose.onNodeWithText("The other 99.9% of care.").assertIsDisplayed()
        compose.onNodeWithText(
            "Stay close to the people you love, even when you can't be in the room."
        ).assertIsDisplayed()
        compose.onNodeWithText("Create an account").assertIsDisplayed()
        compose.onNodeWithText("I already have one").assertIsDisplayed()
    }
}
