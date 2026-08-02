package com.memoria.caregiver.ui.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The submit and send gates, and the Swift string trimming both rest on.
 *
 * Pure logic, so no Robolectric: these are the assertions that decide whether a
 * caregiver's message can leave the device at all, and they should not need a
 * composition to check.
 */
class SupportUiStateTest {

    // ---- Trimming ----------------------------------------------------------

    /**
     * The characters `CharacterSet.whitespacesAndNewlines` covers and Kotlin's
     * own `isWhitespace()` does not agree on. U+0085 is the one that actually
     * differs: Java classifies NEXT LINE as a control character, Foundation as
     * a newline, so a message of nothing but U+0085 is empty to iOS and sendable
     * to a naive port.
     */
    @Test
    fun `blank-only text is empty after Swift trimming`() {
        val blanks = listOf(
            " ",
            "\u0009",          // horizontal tab
            "\u000A",          // line feed
            "\u000D\u000A",    // carriage return + line feed
            "\u000B",          // vertical tab
            "\u000C",          // form feed
            "\u0085",          // NEXT LINE — the one Kotlin's isWhitespace misses
            "\u00A0",          // no-break space
            "\u2028",          // line separator
            "\u2029",          // paragraph separator
            "\u3000",          // ideographic space
            " \u0009\u000A ",
        )
        for (s in blanks) {
            assertEquals("expected \"${escape(s)}\" to trim away", "", s.trimmingWhitespaceAndNewlines())
        }
    }

    @Test
    fun `trimming keeps interior whitespace and only strips the ends`() {
        assertEquals("a  b", "  a  b \n".trimmingWhitespaceAndNewlines())
    }

    // ---- Feedback submit gate ---------------------------------------------

    /**
     * `FeedbackView.swift:31-33`. Everything except "what's not working" is
     * optional, and a field holding only spaces is not filled in.
     */
    @Test
    fun `feedback needs the frustration field and nothing else`() {
        assertFalse(FeedbackState().canSubmit)
        assertFalse(FeedbackState(whatsNotWorking = "   \n ").canSubmit)
        assertFalse(FeedbackState(rating = 5, whatsLoved = "everything").canSubmit)
        assertTrue(FeedbackState(whatsNotWorking = "the camera drops").canSubmit)
    }

    /**
     * `canSubmit` deliberately excludes the in-flight flag — the Swift gates the
     * button on `isSubmitting || !canSubmit` and re-checks `canSubmit` alone
     * inside `submit()`. Folding the two together here would silently change
     * what that second check means.
     */
    @Test
    fun `canSubmit ignores the in-flight flag`() {
        assertTrue(FeedbackState(whatsNotWorking = "x", isSubmitting = true).canSubmit)
    }

    // ---- Spinner tiles -----------------------------------------------------

    /**
     * `FeedbackView.swift:381-383`. At least one tile always shows so the pick
     * visibly registers, and never more than the cap even if the picker somehow
     * returned more.
     */
    @Test
    fun `placeholder tiles are floored at one and capped at the limit`() {
        assertEquals(1, FeedbackState(pendingPickCount = 0).placeholderCount)
        assertEquals(3, FeedbackState(pendingPickCount = 3).placeholderCount)
        assertEquals(
            FEEDBACK_MAX_ATTACHMENTS,
            FeedbackState(pendingPickCount = 99).placeholderCount,
        )
    }

    // ---- Support send gate -------------------------------------------------

    /**
     * `SupportButton.swift:46`. The `didSend` half is what makes this a
     * one-message sheet: without it a second tap on the arrow posts the same
     * text again, and the founders get two copies of the same request.
     */
    @Test
    fun `support send needs text and refuses once in flight or already sent`() {
        assertFalse(HelpChatState().canSend)
        assertFalse(HelpChatState(message = "  \n ").canSend)
        assertTrue(HelpChatState(message = "the app won't load").canSend)
        assertFalse(HelpChatState(message = "hello", isSending = true).canSend)
        assertFalse(HelpChatState(message = "hello", didSend = true).canSend)
    }

    @Test
    fun `the posted message is trimmed`() {
        assertEquals("hello", HelpChatState(message = "  hello \n").trimmedMessage)
    }

    // ---- Copy --------------------------------------------------------------

    /**
     * `FeedbackView.swift:56`. The platform's own sentence is appended, not
     * replaced — a caregiver who cannot send needs the reason, and support
     * needs it repeated back to them.
     */
    @Test
    fun `the submit failure sentence carries the underlying reason`() {
        assertEquals(
            "Couldn't send feedback. The Internet connection appears to be offline.",
            feedbackSubmitErrorMessage("The Internet connection appears to be offline."),
        )
    }

    private fun escape(s: String): String =
        s.map { c -> if (c.code in 33..126) c.toString() else "\\u%04X".format(c.code) }
            .joinToString("")
}
