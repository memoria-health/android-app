package com.memoria.caregiver.ui.events

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [EventReviewScreen] behavior. Spec:
 * `Features/Patient/Events/EventReviewView.swift`, PARITY.md §15, FINDINGS.md
 * F-14.
 *
 * What a caregiver would notice and a refactor could quietly break: that the
 * question names the right event, that each of the three answers reports its
 * own choice rather than a neighbour's, that the notes reach the host as typed,
 * and that submitting is one tap with nothing in its way. Layout is judged by
 * screenshot (DECISIONS.md D-004) and is not asserted here.
 *
 * The submit is deliberately fire-and-forget from this screen's point of view:
 * `EventsListViewModel.submitReview` closes the modal stack before the PATCH
 * goes out, so there is no in-flight or failed state to render and none is
 * tested.
 */
@RunWith(RobolectricTestRunner::class)
class EventReviewScreenTest {

    @get:Rule val compose = createComposeRule()

    private val choices = mutableListOf<DetectionEvent.ReviewChoice>()
    private val notes = mutableListOf<String>()
    private var submits = 0
    private var backs = 0

    private fun setUp(
        state: EventReviewState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    EventReviewScreen(
                        state = state,
                        onChoiceChange = { choices += it },
                        onNotesChange = { notes += it },
                        onSubmit = { submits++ },
                        onBack = { backs++ },
                    )
                }
            }
        }
    }

    private fun review(
        title: String = "Possible fall",
        category: Detector.Category = Detector.Category.SAFETY,
        choice: DetectionEvent.ReviewChoice = DetectionEvent.ReviewChoice.CORRECT,
        notesText: String = "",
    ) = EventReviewState(
        event = sampleEvent(title = title, category = category),
        choice = choice,
        notes = notesText,
    )

    // ---- The question ------------------------------------------------------

    /**
     * The derived question and subtitles have to actually reach the screen. A
     * safety review names the event; wiring the wrong one in would show a
     * caregiver a question about the wrong thing and still look plausible.
     */
    @Test
    fun `a safety review asks about the event and offers the safety wording`() {
        setUp(review(title = "Possible fall"))

        compose.onNodeWithText("Was this a real possible fall?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yes, that was a real possible fall.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("It wasn't a possible fall.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hard to tell from the clip.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a routine review asks about the flag and offers the routine wording`() {
        setUp(review(title = "Skipped breakfast", category = Detector.Category.ROUTINE))

        compose.onNodeWithText("Was this the right thing to flag?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yes, that's the right thing to flag.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("We shouldn't have flagged this.").performScrollTo().assertIsDisplayed()
    }

    /** The three answers are always all offered, whichever one is active. */
    @Test
    fun `all three answers are on screen`() {
        setUp(review())

        compose.onNodeWithText("Correct").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not correct").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not sure").performScrollTo().assertIsDisplayed()
    }

    // ---- Choosing ----------------------------------------------------------

    /**
     * Three near-identical rows are exactly the shape that a copy-paste gets
     * wrong, and a row that reports its neighbour's choice would record the
     * opposite of what the caregiver said — training the detector backwards.
     */
    @Test
    fun `each answer reports its own choice`() {
        setUp(review())

        compose.onNodeWithTag(reviewChoiceTestTag(DetectionEvent.ReviewChoice.WRONG))
            .performScrollTo().performClick()
        compose.onNodeWithTag(reviewChoiceTestTag(DetectionEvent.ReviewChoice.UNSURE))
            .performScrollTo().performClick()
        compose.onNodeWithTag(reviewChoiceTestTag(DetectionEvent.ReviewChoice.CORRECT))
            .performScrollTo().performClick()

        assertEquals(
            listOf(
                DetectionEvent.ReviewChoice.WRONG,
                DetectionEvent.ReviewChoice.UNSURE,
                DetectionEvent.ReviewChoice.CORRECT,
            ),
            choices,
        )
    }

    /**
     * Nothing is reported until the caregiver taps. The screen opens with
     * "Correct" already selected, and a screen that announced that selection on
     * entry would turn every opened review into an answered one.
     */
    @Test
    fun `opening the screen reports no choice`() {
        setUp(review())

        assertEquals(emptyList<DetectionEvent.ReviewChoice>(), choices)
    }

    // ---- Notes -------------------------------------------------------------

    /** The placeholder is the prompt for what a useful note looks like. */
    @Test
    fun `an empty notes field shows the example`() {
        setUp(review())

        compose.onNodeWithText(
            "E.g. she tripped on the rug between the couch and coffee table…",
        ).performScrollTo().assertIsDisplayed()
    }

    /** Once there is a note, the example gets out of the way. */
    @Test
    fun `a filled notes field hides the example`() {
        setUp(review(notesText = "she caught herself on the counter"))

        compose.onNodeWithText(
            "E.g. she tripped on the rug between the couch and coffee table…",
        ).assertDoesNotExist()
        compose.onNodeWithText("she caught herself on the counter")
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * Typed text reaches the host unedited. `submitReview` sends what it is
     * given, so anything trimmed or dropped here is lost from the record the
     * detector is tuned against.
     */
    @Test
    fun `typing a note reports it`() {
        setUp(review())

        compose.onNodeWithTag(EVENT_REVIEW_NOTES_TAG).performScrollTo().performTextInput("rug")

        assertEquals(listOf("rug"), notes)
    }

    // ---- Submitting and leaving --------------------------------------------

    /**
     * There is no "choose something first" gate: `choice` defaults to
     * `.correct`, so the button is live from the moment the screen opens.
     */
    @Test
    fun `submitting is one tap with nothing chosen and nothing typed`() {
        setUp(review())

        // The footer is pinned outside the scroll container, so there is
        // nothing to scroll it into view — and that is the point of pinning it.
        compose.onNodeWithText("Submit review").performClick()

        assertEquals(1, submits)
        assertEquals(0, backs)
    }

    @Test
    fun `the back chevron leaves without submitting`() {
        setUp(review())

        compose.onNodeWithTag(EVENT_REVIEW_BACK_TAG).performClick()

        assertEquals(1, backs)
        assertEquals(0, submits)
    }

    /** The step counter is how the caregiver knows this is the last screen. */
    @Test
    fun `the screen names itself as step two of two`() {
        setUp(review())

        compose.onNodeWithText("Review event").assertIsDisplayed()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
    }

    // ---- Analytics ---------------------------------------------------------

    /**
     * iOS logs this from the pushing view. Logged by the screen here so a second
     * caller cannot lose it — and logged once, not once per recomposition.
     */
    @Test
    fun `the screen logs itself once`() {
        val analytics = RecordingAnalytics()
        setUp(review(), analytics = analytics)

        compose.onNodeWithTag(reviewChoiceTestTag(DetectionEvent.ReviewChoice.WRONG))
            .performScrollTo().performClick()

        // Screen tracking moved to the presenter, where iOS puts it:
        // `ModalOverlay(screen = "event_review")` logs it when this is presented,
        // so a new overlay cannot ship untracked (HARDENING.md H-10).
        // ModalOverlayTest pins the presenter's behaviour and
        // ScreenTrackingCoverageTest pins the name.
        assertEquals(emptyList<String>(), analytics.names("screen"))
    }

    /**
     * The submit button is a `NemaraButton`, which is the app's single
     * instrumentation point — unlike step 1's footer, which iOS builds by hand
     * precisely so it logs nothing.
     */
    @Test
    fun `submitting is instrumented`() {
        val analytics = RecordingAnalytics()
        setUp(review(), analytics = analytics)

        compose.onNodeWithText("Submit review").performClick()

        assertEquals(listOf("Submit review"), analytics.names("button"))
    }
}
