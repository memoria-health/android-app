package com.memoria.caregiver.ui.support

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SupportContactScreen] behavior. Spec:
 * `Features/AppSettings/SupportContactView.swift`, PARITY.md §28, FINDINGS.md
 * F-14.
 *
 * What a caregiver would notice and a refactor could quietly break: that a blank
 * message cannot be sent and a blank subject can, that a send in flight cannot
 * be fired twice, and that both the failure sentence and the backend's
 * confirmation reach the screen unedited. Layout is judged by screenshot
 * (DECISIONS.md D-004).
 */
@RunWith(RobolectricTestRunner::class)
class SupportContactScreenTest {

    @get:Rule val compose = createComposeRule()

    private val subjects = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private var sends = 0
    private var cancels = 0

    private fun setUp(
        state: SupportContactState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    SupportContactScreen(
                        state = state,
                        onSubjectChange = { subjects += it },
                        onMessageChange = { messages += it },
                        onSend = { sends++ },
                        onCancel = { cancels++ },
                    )
                }
            }
        }
    }

    // The send control lives in the hand-drawn navigation bar, which sits
    // outside the scroll container, so there is nothing to scroll it into view.
    private fun tapSend() =
        compose.onNodeWithTag(SUPPORT_CONTACT_SEND_TAG).performClick()

    // ---- Send gating -------------------------------------------------------

    /** `.disabled(!canSend)`. An empty form must be inert, not merely grey. */
    @Test
    fun `an empty message cannot be sent`() {
        setUp(SupportContactState())

        tapSend()

        assertEquals(0, sends)
    }

    /**
     * The message is trimmed of whitespace AND newlines before it counts, so a
     * caregiver who tapped return a few times and gave up has not written
     * anything to send.
     */
    @Test
    fun `a whitespace-only message cannot be sent`() {
        setUp(SupportContactState(message = "  \n\t "))

        tapSend()

        assertEquals(0, sends)
    }

    /** The subject is optional and says so; the message alone is a full form. */
    @Test
    fun `a message with no subject sends`() {
        setUp(SupportContactState(message = "the kitchen camera keeps dropping"))

        tapSend()

        assertEquals(1, sends)
    }

    /**
     * `canSend` is false while `isSending`, and the control is a spinner rather
     * than a button. A second tap here would email the founders twice.
     */
    @Test
    fun `a send in flight shows a spinner instead of the button`() {
        setUp(SupportContactState(message = "hello", isSending = true))

        compose.onNodeWithTag(SUPPORT_CONTACT_SEND_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Sending").assertIsDisplayed()
    }

    // ---- Fields ------------------------------------------------------------

    /** The subject's own affordance for "you may leave this blank". */
    @Test
    fun `the subject is labelled optional through its placeholder`() {
        setUp(SupportContactState())

        compose.onNodeWithText("SUBJECT").assertIsDisplayed()
        compose.onNodeWithText("Optional").assertIsDisplayed()
    }

    @Test
    fun `an empty message shows the prompt`() {
        setUp(SupportContactState())

        compose.onNodeWithText("MESSAGE").assertIsDisplayed()
        compose.onNodeWithText("Tell us what's on your mind.").assertIsDisplayed()
    }

    @Test
    fun `a filled message hides the prompt`() {
        setUp(SupportContactState(message = "the kitchen camera keeps dropping"))

        compose.onNodeWithText("Tell us what's on your mind.").assertDoesNotExist()
        compose.onNodeWithText("the kitchen camera keeps dropping").assertIsDisplayed()
    }

    /**
     * Crossed wires would send the founders an email whose subject is the body,
     * which reads as spam. Split into two tests because a single one cannot tell
     * "the message field wrote to the subject" apart from Compose re-syncing the
     * subject field after its value came back unchanged — this screen is pure,
     * so nothing it is handed ever does change.
     */
    @Test
    fun `typing a subject reports only a subject`() {
        setUp(SupportContactState())

        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("camera")

        assertEquals(listOf("camera"), subjects)
        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `typing a message reports only a message`() {
        setUp(SupportContactState())

        compose.onNodeWithTag(SUPPORT_CONTACT_MESSAGE_TAG)
            .performScrollTo().performTextInput("it drops")

        assertEquals(listOf("it drops"), messages)
        assertEquals(emptyList<String>(), subjects)
    }

    // ---- Outcomes ----------------------------------------------------------

    /**
     * The failure sentence is the platform's own, rendered with no prefix at
     * all — `SupportContactView.swift:118` assigns `localizedDescription`
     * directly. Paraphrasing it here would be a silent product change.
     */
    @Test
    fun `a failure shows the API sentence verbatim`() {
        setUp(SupportContactState(errorMessage = ApiError.Forbidden.errorDescription))

        compose.onNodeWithText("You don't have access to this.")
            .performScrollTo().assertIsDisplayed()
    }

    /** The confirmation is the backend's own `status`, not a sentence of ours. */
    @Test
    fun `a success shows the backend status verbatim`() {
        setUp(SupportContactState(successMessage = "Sent. We'll reply to your account email."))

        compose.onNodeWithText("Sent. We'll reply to your account email.")
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * iOS renders these as `if let error … else if let success`. A stale
     * confirmation sitting under a fresh failure would tell the caregiver their
     * message got through when it did not.
     */
    @Test
    fun `a failure hides any earlier confirmation`() {
        setUp(
            SupportContactState(
                errorMessage = "That isn't here anymore.",
                successMessage = "Sent.",
            ),
        )

        compose.onNodeWithText("That isn't here anymore.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sent.").assertDoesNotExist()
    }

    // ---- Chrome ------------------------------------------------------------

    @Test
    fun `cancel leaves without sending`() {
        setUp(SupportContactState(message = "hello"))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancels)
        assertEquals(0, sends)
    }

    @Test
    fun `the sheet names itself`() {
        setUp(SupportContactState())

        compose.onNodeWithText("Contact support").assertIsDisplayed()
    }

    /**
     * iOS logs this from `AppSettingsView`, where the sheet is presented. Logged
     * by the screen here so a second caller cannot lose it.
     */
    @Test
    fun `the screen logs itself once`() {
        val analytics = RecordingAnalytics()
        setUp(SupportContactState(), analytics = analytics)

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("support_contact"), analytics.names("screen"))
    }
}
