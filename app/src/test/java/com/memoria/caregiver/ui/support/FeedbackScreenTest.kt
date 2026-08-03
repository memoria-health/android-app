package com.memoria.caregiver.ui.support

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
import org.robolectric.annotation.GraphicsMode

/**
 * FeedbackScreen behavior. Spec: `Features/Feedback/FeedbackView.swift`,
 * PARITY.md §28.
 *
 * These assert what a caregiver would notice and a refactor could quietly
 * break: that an incomplete or in-flight form cannot be sent, that the failure
 * sentence reaches the screen unedited, that the attachment cap holds, and that
 * the sent state swaps the form's footer for a confirmation. Layout is not
 * asserted — parity is judged by screenshot (DECISIONS.md D-004).
 */
// This screen renders screenshot thumbnails, and Robolectric's default
// LEGACY graphics returns a null Bitmap from createBitmap — so Compose
// dies with an NPE rather than a meaningful failure. NATIVE runs real Skia.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class FeedbackScreenTest {

    @get:Rule val compose = createComposeRule()

    private val filled = FeedbackState(whatsNotWorking = "the camera keeps dropping")

    private fun attachment(id: String) =
        FeedbackAttachment(id = id, image = ImageBitmap(1, 1), jpegBase64 = "")

    private fun setUp(
        state: FeedbackState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onRatingChange: (Int) -> Unit = {},
        onWhatsLovedChange: (String) -> Unit = {},
        onWhatsNotWorkingChange: (String) -> Unit = {},
        onAddScreenshots: () -> Unit = {},
        onRemoveAttachment: (FeedbackAttachment) -> Unit = {},
        onSubmit: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    FeedbackScreen(
                        state = state,
                        onRatingChange = onRatingChange,
                        onWhatsLovedChange = onWhatsLovedChange,
                        onWhatsNotWorkingChange = onWhatsNotWorkingChange,
                        onAddScreenshots = onAddScreenshots,
                        onRemoveAttachment = onRemoveAttachment,
                        onSubmit = onSubmit,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }

    // ---- Submit gating -----------------------------------------------------

    /** `.disabled(!canSubmit)`. An empty form must be inert, not merely ugly. */
    @Test
    fun `an empty form cannot be submitted`() {
        var submits = 0
        setUp(FeedbackState(), onSubmit = { submits++ })

        compose.onNodeWithText("Send feedback").performScrollTo().performClick()

        assertEquals(0, submits)
    }

    /**
     * The required field is trimmed before it counts. A caregiver who typed a
     * couple of spaces and gave up has not written feedback, and the backend
     * rejects a request with no rating and no non-blank text.
     */
    @Test
    fun `a whitespace-only required field cannot be submitted`() {
        var submits = 0
        setUp(FeedbackState(whatsNotWorking = "   \n  "), onSubmit = { submits++ })

        compose.onNodeWithText("Send feedback").performScrollTo().performClick()

        assertEquals(0, submits)
    }

    /**
     * The rating and "what do you like" are optional — filling in only the
     * required field is a complete submission.
     */
    @Test
    fun `the required field alone submits`() {
        var submits = 0
        setUp(filled, onSubmit = { submits++ })

        compose.onNodeWithText("Send feedback").performScrollTo().performClick()

        assertEquals(1, submits)
    }

    /**
     * The double-submission guard. An in-flight POST leaves the form fully
     * populated, so `isSubmitting` reaching the button is the only thing between
     * an impatient second tap and a duplicate email to the founders.
     */
    @Test
    fun `an in-flight form swaps the label and refuses further taps`() {
        var submits = 0
        setUp(filled.copy(isSubmitting = true), onSubmit = { submits++ })

        compose.onNodeWithText("Sending…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sending…").performClick()

        assertEquals(0, submits)
    }

    // ---- Errors ------------------------------------------------------------

    /**
     * The sentence is `feedbackSubmitErrorMessage`, which already wraps the
     * platform's own reason. It is shown as given — never reworded, never
     * replaced with something friendlier.
     */
    @Test
    fun `the failure sentence is rendered verbatim`() {
        val message = "Couldn't send feedback. The request timed out."
        setUp(filled.copy(submitError = message))

        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
    }

    // ---- Sent --------------------------------------------------------------

    /**
     * On success the form's footer is replaced: no submit button, no screenshot
     * picker, and a confirmation with its own dismiss. Leaving the button up
     * would invite a second send of feedback that already landed.
     */
    @Test
    fun `the sent state replaces the submit button and the picker`() {
        var dismissals = 0
        setUp(filled.copy(submitted = true), onDismiss = { dismissals++ })

        compose.onNodeWithText("Feedback sent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Send feedback").assertDoesNotExist()
        compose.onNodeWithTag(FEEDBACK_ADD_SCREENSHOT_TAG).assertDoesNotExist()

        compose.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(1, dismissals)
    }

    // ---- Attachments -------------------------------------------------------

    /**
     * `if attachments.count < maxAttachments { addButton }` — the fifth
     * screenshot has nowhere to go, and the backend would reject it.
     */
    @Test
    fun `the add tile disappears at the attachment cap`() {
        setUp(filled.copy(attachments = (1..FEEDBACK_MAX_ATTACHMENTS).map { attachment("$it") }))

        compose.onNodeWithTag(FEEDBACK_ADD_SCREENSHOT_TAG).assertDoesNotExist()
        compose.onNodeWithText("$FEEDBACK_MAX_ATTACHMENTS/$FEEDBACK_MAX_ATTACHMENTS")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the add tile is offered below the cap and reports the tap`() {
        var adds = 0
        setUp(filled.copy(attachments = listOf(attachment("a"))), onAddScreenshots = { adds++ })

        compose.onNodeWithTag(FEEDBACK_ADD_SCREENSHOT_TAG).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, adds)
    }

    /** Removal names the attachment, so the right thumbnail goes. */
    @Test
    fun `removing a screenshot reports which one`() {
        val removed = mutableListOf<String>()
        setUp(
            filled.copy(attachments = listOf(attachment("a"), attachment("b"))),
            onRemoveAttachment = { removed += it.id },
        )

        compose.onAllNodesWithContentDescription("Remove screenshot")[1]
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("b"), removed)
    }

    /**
     * Decoding and re-encoding four screenshots is not instant. While it runs
     * the picker shows spinner tiles and an "Adding…" note instead of a count,
     * so the pick visibly registers rather than leaving a dead pause.
     */
    @Test
    fun `a pick in progress shows the adding note instead of a count`() {
        setUp(filled.copy(isLoadingAttachments = true, pendingPickCount = 2))

        compose.onNodeWithText("Adding…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FEEDBACK_ADD_SCREENSHOT_TAG).assertDoesNotExist()
    }

    // ---- Rating ------------------------------------------------------------

    /** The scale reports the step that was tapped, not an index. */
    @Test
    fun `tapping a rating step reports its value`() {
        var rating = -1
        setUp(filled, onRatingChange = { rating = it })

        compose.onNodeWithText("4").performScrollTo().performClick()

        assertEquals(4, rating)
    }

    // ---- Chrome ------------------------------------------------------------

    @Test
    fun `the back chevron dismisses`() {
        var dismissals = 0
        setUp(filled, onDismiss = { dismissals++ })

        compose.onNodeWithTag(FEEDBACK_BACK_TAG).performClick()

        assertEquals(1, dismissals)
    }

    /**
     * iOS logs this from the presenting view. Logging it from the screen means
     * a new entry point cannot silently drop it from the funnel.
     */
    @Test
    fun `the screen event is logged once`() {
        val analytics = RecordingAnalytics()
        setUp(filled, analytics = analytics)
        compose.waitForIdle()

        // Screen tracking moved to the presenter, where iOS puts it:
        // `ModalOverlay(screen = "feedback")` logs it when this is presented,
        // so a new overlay cannot ship untracked (HARDENING.md H-10).
        // ModalOverlayTest pins the presenter's behaviour and
        // ScreenTrackingCoverageTest pins the name.
        assertEquals(emptyList<String>(), analytics.names("screen"))
    }

    /**
     * The founders' phone number is the fallback channel — "text us" is the
     * whole point of this surface — so the paragraph carrying it is pinned.
     */
    @Test
    fun `the founders card carries the support phone number`() {
        setUp(filled)

        compose.onNodeWithText(
            "Tell us how we can improve. We respond to all inquiries within 2 " +
                "business days. Feel free to also text us at 716 602 1101.",
        ).performScrollTo().assertIsDisplayed()
    }
}
