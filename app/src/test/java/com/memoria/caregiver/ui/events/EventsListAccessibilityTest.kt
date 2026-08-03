package com.memoria.caregiver.ui.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the events list announces. Spec: FINDINGS.md F-31.
 *
 * Two things here were invisible to a screen reader. The banner's dismiss is a
 * drawn 12dp cross with no text, so the only way to clear an error announced as
 * an unnamed button next to the error. And the three filter chips announced as
 * three identical buttons: the solid-ink fill that says which one is live is
 * the whole state of the control, and it was drawn only.
 */
@RunWith(RobolectricTestRunner::class)
class EventsListAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private var dismissed = false

    private fun setUp(reviewError: String? = null) {
        compose.setContent {
            var state by remember {
                mutableStateOf(
                    EventsListState(
                        events = listOf(sampleEvent(id = "a", title = "Possible fall")),
                        reviewError = reviewError,
                    ),
                )
            }
            MemoriaTheme {
                EventsListScreen(
                    state = state,
                    onFilterChange = { state = state.copy(filter = it) },
                    onEventTap = {},
                    onDismissReviewError = {
                        dismissed = true
                        state = state.copy(reviewError = null)
                    },
                )
            }
        }
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private fun isTab() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    // ---- the banner dismiss ------------------------------------------------

    @Test
    fun `the banner dismiss is named`() {
        setUp(reviewError = "Could not mark that reviewed.")
        compose.onNodeWithTag(EventsListReviewErrorDismissTestTag)
            .assert(hasContentDescription("Dismiss"))
    }

    @Test
    fun `the banner dismiss announces as a button`() {
        setUp(reviewError = "Could not mark that reviewed.")
        compose.onNodeWithTag(EventsListReviewErrorDismissTestTag).assert(isButton())
    }

    /**
     * The 12dp glyph is the smallest tap target in the app and it was left drawn
     * at 12dp on purpose: Compose inflates the hit rect to the platform's 48dp
     * minimum, so a tap 15dp clear of it still lands, and growing the layout box
     * would have pushed the banner's message text sideways. See the F-31 report.
     */
    @Test
    fun `a tap 15dp clear of the 12dp dismiss still clears the banner`() {
        setUp(reviewError = "Could not mark that reviewed.")
        compose.onNodeWithTag(EventsListReviewErrorDismissTestTag).performTouchInput {
            click(Offset(-15.dp.toPx(), height / 2f))
        }
        assertTrue(dismissed)
    }

    // ---- the filter chips --------------------------------------------------

    @Test
    fun `a filter chip announces as a tab, not as a button`() {
        setUp()
        compose.onNodeWithText("Unreviewed").assert(isTab())
    }

    /**
     * The state that was drawn and never spoken. Without it a caregiver who
     * cannot see the fill has no way to learn which filter the list is under —
     * and the list itself gives no clue, because an empty Unreviewed and an
     * empty All look the same.
     */
    @Test
    fun `the live filter is the selected one and the others are not`() {
        setUp()
        compose.onNodeWithText("Unreviewed").assertIsSelected()
        compose.onNodeWithText("Reviewed").assertIsNotSelected()
        compose.onNodeWithText("All").assertIsNotSelected()
    }

    @Test
    fun `choosing another filter moves the selection`() {
        setUp()
        compose.onNodeWithText("Reviewed").performClick()

        compose.onNodeWithText("Reviewed").assertIsSelected()
        compose.onNodeWithText("Unreviewed").assertIsNotSelected()
    }

    @Test
    fun `an event card announces as a button`() {
        setUp()
        compose.onNodeWithText("Possible fall").assert(isButton())
    }
}
