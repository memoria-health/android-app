package com.memoria.caregiver.ui.support

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The floating help button and its chat sheet.
 * Spec: `Features/Support/SupportButton.swift`, PARITY.md §28.
 *
 * The sheet content is exercised directly rather than through
 * `SupportButtonOverlay`'s `ModalBottomSheet`, so these assert the sheet's own
 * behavior instead of a Material presentation animation.
 */
@RunWith(RobolectricTestRunner::class)
class SupportButtonTest {

    @get:Rule val compose = createComposeRule()

    private fun setUpSheet(
        state: HelpChatState,
        onMessageChange: (String) -> Unit = {},
        onSend: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            MemoriaTheme {
                HelpChatSheet(
                    state = state,
                    onMessageChange = onMessageChange,
                    onSend = onSend,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    // ---- The button --------------------------------------------------------

    /**
     * The button carries no visible text, so its accessibility label is the only
     * thing naming it. iOS labels it "Get help".
     */
    @Test
    fun `the floating button is labelled and reports its tap`() {
        var opens = 0
        compose.setContent { MemoriaTheme { SupportButton(onClick = { opens++ }) } }

        compose.onNodeWithContentDescription("Get help").assertIsDisplayed()
        compose.onNodeWithTag(SUPPORT_BUTTON_TAG).performClick()

        assertEquals(1, opens)
    }

    // ---- Send gating -------------------------------------------------------

    /** An empty composer must be inert; there is nothing to email the founders. */
    @Test
    fun `an empty composer cannot send`() {
        var sends = 0
        setUpSheet(HelpChatState(isPresented = true), onSend = { sends++ })

        compose.onNodeWithTag(SUPPORT_SEND_TAG).performClick()

        assertEquals(0, sends)
    }

    /** Whitespace is not a message. `trimmed.isEmpty` gates the arrow. */
    @Test
    fun `a whitespace-only composer cannot send`() {
        var sends = 0
        setUpSheet(HelpChatState(isPresented = true, message = "   \n "), onSend = { sends++ })

        compose.onNodeWithTag(SUPPORT_SEND_TAG).performClick()

        assertEquals(0, sends)
    }

    @Test
    fun `a written message sends once per tap`() {
        var sends = 0
        setUpSheet(
            HelpChatState(isPresented = true, message = "the app won't load"),
            onSend = { sends++ },
        )

        compose.onNodeWithTag(SUPPORT_SEND_TAG).performClick()

        assertEquals(1, sends)
    }

    /**
     * This sheet takes ONE message. Without the `didSend` half of the gate a
     * second tap on the arrow posts the same text again and the founders get a
     * duplicate.
     */
    @Test
    fun `a sheet that already sent refuses another tap`() {
        var sends = 0
        setUpSheet(
            HelpChatState(isPresented = true, message = "hello", didSend = true, sentText = "hello"),
            onSend = { sends++ },
        )

        compose.onNodeWithTag(SUPPORT_SEND_TAG).performClick()

        assertEquals(0, sends)
    }

    /** Mid-flight the arrow is replaced by a spinner, so there is nothing to tap. */
    @Test
    fun `an in-flight send shows a spinner instead of the arrow`() {
        setUpSheet(HelpChatState(isPresented = true, message = "hello", isSending = true))

        compose.onNodeWithTag(SUPPORT_SEND_TAG).assertDoesNotExist()
    }

    // ---- Transcript --------------------------------------------------------

    /** The two greeting bubbles are the sheet's whole framing and are fixed copy. */
    @Test
    fun `the greeting bubbles are always shown`() {
        setUpSheet(HelpChatState(isPresented = true))

        compose.onNodeWithText("What do you need help with?").assertIsDisplayed()
        compose.onNodeWithText("Send us a note and we'll text you back instantly.")
            .assertIsDisplayed()
    }

    /**
     * After sending, the caregiver's own words come back as an outgoing bubble
     * followed by the acknowledgement — the confirmation that the right text
     * left the device.
     */
    @Test
    fun `a sent message is echoed and acknowledged`() {
        setUpSheet(
            HelpChatState(isPresented = true, didSend = true, sentText = "the app won't load"),
        )

        compose.onNodeWithText("the app won't load").assertIsDisplayed()
        compose.onNodeWithText("Got it — we'll text you back shortly.").assertIsDisplayed()
    }

    /** The failure reason is the platform's own sentence and is shown unedited. */
    @Test
    fun `the failure reason is rendered verbatim`() {
        val message = "The Internet connection appears to be offline."
        setUpSheet(HelpChatState(isPresented = true, message = "hello", errorMessage = message))

        compose.onNodeWithText(message).assertIsDisplayed()
    }

    // ---- Chrome ------------------------------------------------------------

    /**
     * The leading toolbar button relabels once the message has landed: "Cancel"
     * would read as "throw away what I just sent".
     */
    @Test
    fun `the leading button reads Cancel before sending`() {
        setUpSheet(HelpChatState(isPresented = true))

        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test
    fun `the leading button reads Done after sending`() {
        setUpSheet(HelpChatState(isPresented = true, didSend = true, sentText = "hello"))

        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `the leading button dismisses`() {
        var dismissals = 0
        setUpSheet(HelpChatState(isPresented = true), onDismiss = { dismissals++ })

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissals)
    }

    @Test
    fun `typing reports the new text`() {
        val typed = mutableListOf<String>()
        setUpSheet(HelpChatState(isPresented = true), onMessageChange = { typed += it })

        compose.onNodeWithTag(SUPPORT_COMPOSER_TAG).performTextInput("hi")

        assertEquals("hi", typed.lastOrNull())
    }

    /**
     * `.disabled(didSend)` on the field. Editing after sending would leave text
     * on screen that was never posted.
     */
    @Test
    fun `the composer is locked once the message has been sent`() {
        setUpSheet(HelpChatState(isPresented = true, didSend = true, sentText = "hello"))

        compose.onNodeWithTag(SUPPORT_COMPOSER_TAG).assertIsNotEnabled()
    }
}
