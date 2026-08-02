package com.memoria.caregiver.ui.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Events tab behavior. Spec: `Features/Patient/Events/PatientEventsView.swift`,
 * PARITY.md §15.
 *
 * The 20s poll, the `PATCH {opened: true}` and the APNs deep link are the host's
 * and are not exercised here. What is: the default filter, that filtering
 * actually filters, that the empty copy tracks the filter, and that a tap
 * reports the event the caregiver touched.
 */
@RunWith(RobolectricTestRunner::class)
class EventsListScreenTest {

    @get:Rule val compose = createComposeRule()

    private val fall = sampleEvent(id = "a", title = "Possible fall")
    private val meds = sampleEvent(id = "b", title = "Took medication", reviewed = true)

    private var tapped: DetectionEvent? = null
    private var dismissedError = false

    /**
     * One composition, with the filter and the banner wired to real state so
     * both can be driven the way a caregiver drives them. `setContent` may only
     * run once per test.
     */
    private fun setUp(
        events: List<DetectionEvent> = listOf(fall, meds),
        reviewError: String? = null,
    ) {
        compose.setContent {
            var state by remember {
                mutableStateOf(EventsListState(events = events, reviewError = reviewError))
            }
            MemoriaTheme {
                EventsListScreen(
                    state = state,
                    onFilterChange = { state = state.copy(filter = it) },
                    onEventTap = { tapped = it },
                    onDismissReviewError = {
                        dismissedError = true
                        state = state.copy(reviewError = null)
                    },
                )
            }
        }
    }

    @Test
    fun `opens on unreviewed, so a reviewed event is not listed`() {
        setUp()
        compose.onNodeWithText("Possible fall").assertIsDisplayed()
        compose.onNodeWithText("Took medication").assertDoesNotExist()
    }

    @Test
    fun `switching to reviewed swaps which events are listed`() {
        setUp()
        compose.onNodeWithText("Reviewed").performClick()
        compose.onNodeWithText("Took medication").assertIsDisplayed()
        compose.onNodeWithText("Possible fall").assertDoesNotExist()
    }

    @Test
    fun `all shows both`() {
        setUp()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Possible fall").assertIsDisplayed()
        compose.onNodeWithText("Took medication").assertIsDisplayed()
    }

    /**
     * Three different sentences, driven by the chip. iOS deliberately does not
     * reuse one "no events" string, because "all caught up" and "nothing has
     * happened yet" are different news.
     */
    @Test
    fun `the empty copy follows the filter`() {
        setUp(events = emptyList())
        compose.onNodeWithText("All caught up").assertIsDisplayed()
        compose.onNodeWithText("No events are waiting for your review.").assertIsDisplayed()

        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Nothing to see here yet").assertIsDisplayed()
        compose.onNodeWithText("When a detector notices something, events will show up here.")
            .assertIsDisplayed()

        compose.onNodeWithText("Reviewed").performClick()
        compose.onNodeWithText("Nothing reviewed yet").assertIsDisplayed()
        compose.onNodeWithText("Events you've reviewed will appear here.").assertIsDisplayed()
    }

    /**
     * The host needs the event itself, not a position in a list it may have
     * replaced under us: it has to decide whether to send `PATCH {opened: true}`
     * before presenting the detail.
     */
    @Test
    fun `tapping a card reports that event`() {
        setUp()
        compose.onNodeWithText("Possible fall").performScrollTo().performClick()
        assertEquals("a", tapped?.id)
    }

    /** iOS renders `api.lastReviewError` verbatim and lets it be dismissed. */
    @Test
    fun `the review error is shown verbatim and can be dismissed`() {
        val message = "Couldn't save your review. Check your connection."
        setUp(reviewError = message)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag(EventsListReviewErrorDismissTestTag).performClick()
        assertTrue(dismissedError)
        compose.onNodeWithText(message).assertDoesNotExist()
    }
}
