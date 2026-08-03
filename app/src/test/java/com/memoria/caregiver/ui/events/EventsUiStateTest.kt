package com.memoria.caregiver.ui.events

import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The derivations and the copy the event screens render, tested without
 * rendering anything. Spec: `PatientEventsView.swift`, `EventDetailView.swift`.
 */
class EventsUiStateTest {

    private val unreviewedSafety = sampleEvent(id = "a", title = "Possible fall")
    private val reviewedRoutine = sampleEvent(id = "b", title = "Left the kitchen", reviewed = true)
    private val unreviewedRoutine = sampleEvent(id = "c", title = "Took medication")

    private val state = EventsListState(
        events = listOf(unreviewedSafety, reviewedRoutine, unreviewedRoutine),
    )

    /**
     * iOS: `@State private var filter: Filter = .unreviewed`. The work-first
     * default is the point of the tab — landing on All would bury what needs
     * attention under what does not.
     */
    @Test
    fun `the default filter is unreviewed`() {
        assertEquals(EventsFilter.Unreviewed, EventsListState().filter)
    }

    @Test
    fun `all three counts are computed regardless of the active filter`() {
        val counts = state.counts
        assertEquals(3, counts[EventsFilter.All])
        assertEquals(2, counts[EventsFilter.Unreviewed])
        assertEquals(1, counts[EventsFilter.Reviewed])
    }

    @Test
    fun `each filter admits the right rows, in the order given`() {
        assertEquals(
            listOf("a", "b", "c"),
            state.copy(filter = EventsFilter.All).visible.map { it.id },
        )
        assertEquals(
            listOf("a", "c"),
            state.copy(filter = EventsFilter.Unreviewed).visible.map { it.id },
        )
        assertEquals(
            listOf("b"),
            state.copy(filter = EventsFilter.Reviewed).visible.map { it.id },
        )
    }

    /**
     * The three empty states say different things on purpose: "all caught up"
     * is not the same statement as "nothing has happened yet".
     */
    @Test
    fun `empty copy differs per filter`() {
        assertEquals(
            EventsEmptyCopy(
                icon = "checkmark.circle",
                title = "All caught up",
                subtitle = "No events are waiting for your review.",
            ),
            eventsEmptyCopy(EventsFilter.Unreviewed),
        )
        assertEquals(
            EventsEmptyCopy(
                icon = "tray",
                title = "Nothing to see here yet",
                subtitle = "When a detector notices something, events will show up here.",
            ),
            eventsEmptyCopy(EventsFilter.All),
        )
        assertEquals(
            EventsEmptyCopy(
                icon = "tray",
                title = "Nothing reviewed yet",
                subtitle = "Events you've reviewed will appear here.",
            ),
            eventsEmptyCopy(EventsFilter.Reviewed),
        )
    }

    @Test
    fun `the chip labels are the iOS raw values`() {
        assertEquals(
            listOf("All", "Unreviewed", "Reviewed"),
            EventsFilter.entries.map { it.title },
        )
    }

    @Test
    fun `each clip state has its own placeholder label`() {
        assertEquals("Loading clip…", clipPlaceholderLabel(ClipState.Pending))
        assertEquals("Generating clip…", clipPlaceholderLabel(ClipState.Generating))
        assertEquals("Clip processing…", clipPlaceholderLabel(ClipState.NotReady))
    }

    /** A failure shows the reason, not a generic "something went wrong". */
    @Test
    fun `a failed clip shows the error verbatim`() {
        assertEquals(
            "The request timed out.",
            clipPlaceholderLabel(ClipState.Failed("The request timed out.")),
        )
    }

    @Test
    fun `a loaded clip has no placeholder`() {
        assertNull(clipPlaceholderLabel(ClipState.Loaded("https://example.test/clip.mp4")))
    }

    // -----------------------------------------------------------------------
    // Review copy
    // -----------------------------------------------------------------------

    private val routine = sampleEvent(
        title = "Skipped breakfast",
        category = Detector.Category.ROUTINE,
    )

    /**
     * The safety question names the event back to the caregiver, lowercased and
     * mid-sentence. A title that arrived capitalised must not stay capitalised
     * in the middle of the question.
     */
    @Test
    fun `a safety review asks about the event by name`() {
        assertEquals(
            "Was this a real possible fall?",
            reviewSubjectQuestion(sampleEvent(title = "Possible fall")),
        )
    }

    /**
     * A routine event is not a claim about something that happened, so its
     * question is about the flag rather than the event, and the title does not
     * appear at all.
     */
    @Test
    fun `a routine review asks about the flag, not the event`() {
        assertEquals("Was this the right thing to flag?", reviewSubjectQuestion(routine))
    }

    /** The two categories are worded differently on purpose. */
    @Test
    fun `the correct and wrong subtitles are per category`() {
        val safety = sampleEvent(title = "Possible fall")
        assertEquals(
            "Yes, that was a real possible fall.",
            reviewChoiceSubtext(safety, DetectionEvent.ReviewChoice.CORRECT),
        )
        assertEquals(
            "It wasn't a possible fall.",
            reviewChoiceSubtext(safety, DetectionEvent.ReviewChoice.WRONG),
        )
        assertEquals(
            "Yes, that's the right thing to flag.",
            reviewChoiceSubtext(routine, DetectionEvent.ReviewChoice.CORRECT),
        )
        assertEquals(
            "We shouldn't have flagged this.",
            reviewChoiceSubtext(routine, DetectionEvent.ReviewChoice.WRONG),
        )
    }

    /**
     * "Not sure" is about the clip rather than about the detection, so it reads
     * the same either way — iOS matches it with a wildcard on the category.
     */
    @Test
    fun `not sure reads the same for both categories`() {
        assertEquals(
            "Hard to tell from the clip.",
            reviewChoiceSubtext(sampleEvent(), DetectionEvent.ReviewChoice.UNSURE),
        )
        assertEquals(
            "Hard to tell from the clip.",
            reviewChoiceSubtext(routine, DetectionEvent.ReviewChoice.UNSURE),
        )
    }

    /**
     * `@State private var choice: ReviewChoice = .correct`. The screen opens
     * with an answer already selected, so "submit without choosing" is not a
     * state that can be reached and nothing guards against it.
     */
    @Test
    fun `a review opens on correct with empty notes`() {
        val state = EventReviewState(event = sampleEvent())
        assertEquals(DetectionEvent.ReviewChoice.CORRECT, state.choice)
        assertEquals("", state.notes)
    }
}
