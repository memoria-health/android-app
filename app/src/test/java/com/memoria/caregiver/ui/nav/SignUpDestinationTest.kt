package com.memoria.caregiver.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.auth.SignUpState
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [SignUpDestination] — the sign-up form and the consent gate over it.
 *
 * What this file defends is the thing FINDINGS.md F-27 says was missing: that
 * there is a way to reach the form, a way to reach the gate from it, and **no
 * way past the gate that skips consent**. The gate's own scroll rule and the
 * form's own submit rule are tested next door in `TermsGateScreenTest` and
 * `SignUpScreenTest`; what is only testable here is the round trip between them,
 * including the three ways out of the gate — Cancel, Back, and agreeing — which
 * must not be interchangeable.
 *
 * Pages come from a stub rather than the shipped PDFs so the reviewed / unread
 * split is exact. `PdfPageLoaderTest` is what proves the real documents load.
 */
// Rasterised pages need real Skia, same as TermsGateScreenTest.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SignUpDestinationTest {

    // An Android rule, not the plain one: the gate installs a BackHandler, and
    // pressing Back for real means going through the activity's dispatcher.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var accepted = 0
    private var submits = 0

    private val termsRow = "Review and agree to the Terms and Privacy Notice to continue."
    private val gateTitle = "Terms & Privacy"
    private val scrollPrompt = "Scroll to the end of each document to continue."

    /** One short page — its end sentinel is on screen, so the document reads as reviewed. */
    private fun shortPages() = listOf(ImageBitmap(400, 20))

    /** Far taller than the viewport, so nothing is reviewed without a real scroll. */
    private fun tallPages() = listOf(ImageBitmap(40, 1000))

    /**
     * Short pages the first time a document is asked for, tall ones after that.
     *
     * A gate that reset nothing on re-open would look identical to one that does
     * if both viewings served the same pages: with short pages both viewings
     * unlock immediately, and with tall pages neither ever does. Serving a long
     * document the second time makes the two behaviours differ.
     */
    private fun pagesThatGrowOnReopen(): suspend (String) -> List<ImageBitmap> {
        val seen = mutableSetOf<String>()
        return { resource -> if (seen.add(resource)) shortPages() else tallPages() }
    }

    private fun setUp(
        state: SignUpState = SignUpState(),
        pageSource: suspend (String) -> List<ImageBitmap> = { shortPages() },
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    SignUpDestination(
                        state = state,
                        onDisplayNameChange = {},
                        onEmailChange = {},
                        onPhoneChange = {},
                        onCountryChange = {},
                        onPasswordChange = {},
                        onAcceptTerms = { accepted++ },
                        onSubmit = { submits++ },
                        onNavigateToLogin = {},
                        onBack = {},
                        pageSource = pageSource,
                    )
                }
            }
        }
    }

    private fun openGate() {
        compose.onNodeWithText(termsRow).performScrollTo().performClick()
        compose.onNodeWithText(gateTitle).assertExists()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    // -----------------------------------------------------------------------
    // Reaching the gate
    // -----------------------------------------------------------------------

    @Test
    fun `the destination shows the sign-up form`() {
        setUp()

        compose.onNodeWithText("Make a Memoria account.").assertExists()
    }

    @Test
    fun `the terms row opens the consent gate`() {
        setUp()

        compose.onNodeWithText(termsRow).performScrollTo().performClick()

        compose.onNodeWithText(gateTitle).assertExists()
    }

    /**
     * iOS presents the gate as a sheet, so the form is still there underneath
     * and comes back with what was typed in it. A separate route would tear the
     * form's composition down and lose the confirm-password field, which is
     * held in a plain `remember` and typed immediately above the terms row.
     */
    @Test
    fun `the form stays mounted underneath the gate`() {
        setUp()

        openGate()

        compose.onNodeWithText("Make a Memoria account.").assertExists()
    }

    // -----------------------------------------------------------------------
    // The three ways out — only one of them is consent
    // -----------------------------------------------------------------------

    @Test
    fun `cancelling returns to the form without agreeing`() {
        setUp()
        openGate()

        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText(gateTitle).assertDoesNotExist()
        assertEquals("cancel is not consent", 0, accepted)
    }

    /**
     * Back — and the edge swipe, which is how most Android users close a screen
     * — must be Cancel. Before this handler existed the press fell through to
     * the navigation host and popped the whole sign-up route, so a caregiver
     * reading the terms lost the form they had filled in (FINDINGS.md F-25).
     */
    @Test
    fun `back closes the gate rather than the form, and is not consent`() {
        setUp()
        openGate()

        pressBack()

        compose.onNodeWithText(gateTitle).assertDoesNotExist()
        compose.onNodeWithText("Make a Memoria account.").assertExists()
        assertEquals("back is not consent", 0, accepted)
    }

    @Test
    fun `agreeing reports consent once and closes the gate`() {
        setUp()
        openGate()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(scrollPrompt).fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithText("I agree").performClick()

        assertEquals(1, accepted)
        compose.onNodeWithText(gateTitle).assertDoesNotExist()
    }

    /**
     * The gate blocks. With documents too long to have been read, "I agree" is
     * inert — not merely dimmed — so there is no path to an account that skips
     * the reading.
     */
    @Test
    fun `agreeing does nothing while the documents are unread`() {
        setUp(pageSource = { tallPages() })
        openGate()

        compose.onNodeWithText("I agree").performClick()

        assertEquals(0, accepted)
        compose.onNodeWithText(gateTitle).assertExists()
    }

    /**
     * Re-opening to "review again" starts from an unreviewed gate, as iOS does —
     * its sheet is built fresh each presentation, so the reviewed set is empty
     * and the button is disabled again. The form's own agreement is untouched.
     */
    @Test
    fun `re-opening the gate asks for the documents to be read again`() {
        setUp(pageSource = pagesThatGrowOnReopen())
        openGate()
        // The first viewing did unlock, so the second one starting locked can
        // only be the reset.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(scrollPrompt).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText(termsRow).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText(scrollPrompt).assertCountEquals(1)
    }

    // -----------------------------------------------------------------------
    // The form's own action still reaches the host
    // -----------------------------------------------------------------------

    @Test
    fun `submitting an agreed form reaches the host`() {
        setUp(
            state = SignUpState(
                displayName = "Jane Patel",
                email = "jane@home.com",
                phone = "5551234567",
                password = "Margar3t!",
                acceptedTerms = true,
            ),
        )

        // The confirmation is the screen's own field and nothing outside it can
        // pre-fill it, so it is typed here the way a caregiver would. It is the
        // fifth and last text box on the form; the ordering is pinned by
        // SignUpScreenTest.
        compose.onAllNodes(hasSetTextAction())[4].performScrollTo().performTextInput("Margar3t!")
        compose.onNodeWithText("Create account").performScrollTo().performClick()

        assertEquals(1, submits)
    }
}
