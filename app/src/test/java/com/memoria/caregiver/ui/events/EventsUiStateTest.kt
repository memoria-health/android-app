package com.memoria.caregiver.ui.events

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
}
