package com.memoria.caregiver.ui.events

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Event detail behavior. Spec: `Features/Patient/Events/EventDetailView.swift`,
 * PARITY.md §15.
 *
 * The clip-URL polling belongs to the host, so each test simply states which
 * [ClipState] the host has reached. What is asserted here is what the screen
 * does with it — and in particular the two flags that are policy rather than
 * decoration: `clip_suppressed` is terminal, and `clip_censored` hides the video
 * until a deliberate tap.
 */
@RunWith(RobolectricTestRunner::class)
class EventDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private val analytics = RecordingAnalytics()
    private var wentBack = false
    private var startedReview = false

    /** The stand-in for the host's ExoPlayer, so "is the video showing" is assertable. */
    private val playerMarker = "PLAYER"

    private fun setUp(state: EventDetailState) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    EventDetailScreen(
                        state = state,
                        onBack = { wentBack = true },
                        onReviewEvent = { startedReview = true },
                        clipPlayer = { Text(playerMarker) },
                    )
                }
            }
        }
    }

    @Test
    fun `while the clip resolves the screen shows the skeleton, not the player`() {
        setUp(EventDetailState(event = sampleEvent(), clip = ClipState.Pending))
        compose.onNodeWithText("Loading clip…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(playerMarker).assertDoesNotExist()
        compose.onNodeWithText("Possible fall").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Someone appeared to fall near the counter.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a resolved clip hands the url to the player`() {
        setUp(
            EventDetailState(
                event = sampleEvent(),
                clip = ClipState.Loaded("https://example.test/clip.mp4"),
            ),
        )
        compose.onNodeWithText(playerMarker).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loading clip…").assertDoesNotExist()
    }

    /**
     * `clip_suppressed` means there is no clip and there never will be, so it
     * wins over whatever the clip-URL call reported. Showing a player here would
     * be a bug even with a URL in hand, and showing "Generating clip…" would be
     * an endless lie.
     *
     * The copy is reason-neutral on purpose: it must not claim privacy, because
     * private rooms do get clips now, and it must not hide failures.
     */
    @Test
    fun `a suppressed clip is terminal and says so`() {
        setUp(
            EventDetailState(
                event = sampleEvent(clipSuppressed = true),
                clip = ClipState.Loaded("https://example.test/clip.mp4"),
            ),
        )
        compose.onNodeWithText("No clip for this event").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "The insight was captured, but video of this moment couldn’t be saved.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(playerMarker).assertDoesNotExist()
        compose.onNodeWithText("Loading clip…").assertDoesNotExist()
    }

    /**
     * The dignity cover. It is per-open and never persisted, so there is no
     * "don't ask again" to assert — only that the video starts covered and that
     * one deliberate tap uncovers it.
     */
    @Test
    fun `a censored clip stays covered until the caregiver asks for it`() {
        setUp(
            EventDetailState(
                event = sampleEvent(clipCensored = true),
                clip = ClipState.Loaded("https://example.test/clip.mp4"),
            ),
        )
        compose.onNodeWithText("Sensitive moment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This video has been censored to maintain dignity.")
            .performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("View video").performClick()

        compose.onNodeWithText("Sensitive moment").assertDoesNotExist()
        compose.onNodeWithText(playerMarker).performScrollTo().assertIsDisplayed()
    }

    /** An uncensored clip must not acquire a cover it never had. */
    @Test
    fun `an uncensored clip has no cover`() {
        setUp(
            EventDetailState(
                event = sampleEvent(),
                clip = ClipState.Loaded("https://example.test/clip.mp4"),
            ),
        )
        compose.onNodeWithText("Sensitive moment").assertDoesNotExist()
        compose.onNodeWithText("View video").assertDoesNotExist()
    }

    @Test
    fun `the footer advances to step two`() {
        setUp(EventDetailState(event = sampleEvent()))
        // The footer is pinned outside the scroll area, so there is nothing to
        // scroll it into view.
        compose.onNodeWithText("Review this event →").performClick()
        assertTrue(startedReview)
    }

    @Test
    fun `the back chevron reports back`() {
        setUp(EventDetailState(event = sampleEvent()))
        compose.onNodeWithTag(EventDetailBackTestTag).performClick()
        assertTrue(wentBack)
    }

    /**
     * iOS attaches `.trackScreen("event_detail")` where this screen is
     * presented. The name is the analytics contract and outlives the copy.
     */
    @Test
    fun `opening the detail logs the screen`() {
        setUp(EventDetailState(event = sampleEvent()))
        assertTrue(
            analytics.entries.any {
                it.type == "screen" && it.name == "event_detail"
            },
        )
    }
}
