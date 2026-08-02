package com.memoria.caregiver.ui.home

import com.memoria.caregiver.data.model.Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster's derivations, tested without a composition.
 *
 * Everything here is a rule a caregiver would notice breaking: the wrong
 * greeting at midnight, "1 New events", a red card for a routine event, or
 * "Welcome, " with nothing after it. None of them needs Compose to be checked,
 * so none of them is checked through Compose (HARNESS.md §4 tier 1).
 */
class RosterUiStateTest {

    // ---- Greeting ----------------------------------------------------------

    /**
     * `RosterView.swift:91-95`: `5..<12`, `12..<17`, else. The boundaries are
     * the whole rule, so all six of them are pinned rather than one hour from
     * the middle of each band.
     */
    @Test
    fun `greeting bands start and end where iOS says`() {
        assertEquals("Good morning", rosterGreeting(5))
        assertEquals("Good morning", rosterGreeting(11))
        assertEquals("Good afternoon", rosterGreeting(12))
        assertEquals("Good afternoon", rosterGreeting(16))
        assertEquals("Good evening", rosterGreeting(17))
        assertEquals("Good evening", rosterGreeting(23))
    }

    /**
     * The small hours fall through the `default` arm to "Good evening" rather
     * than into a fourth band. A caregiver checking on a parent at 3am is
     * greeted the same way as one checking at 9pm, and that is iOS's behavior,
     * not an oversight to correct.
     */
    @Test
    fun `hours before five greet as evening`() {
        assertEquals("Good evening", rosterGreeting(0))
        assertEquals("Good evening", rosterGreeting(4))
    }

    // ---- First name --------------------------------------------------------

    @Test
    fun `first name is the leading word of the display name`() {
        assertEquals("Ada", rosterFirstName("Ada Lovelace"))
    }

    @Test
    fun `a single-word display name is used whole`() {
        assertEquals("Aryaman", rosterFirstName("Aryaman"))
    }

    /**
     * Swift's `split(separator:)` omits empty subsequences, so a leading space
     * still yields the first real word. Kotlin's `split(" ")` keeps them, and a
     * naive port would greet the caregiver as "Welcome, ".
     */
    @Test
    fun `leading and repeated spaces do not produce an empty name`() {
        assertEquals("Mary", rosterFirstName("  Mary  Anne"))
    }

    @Test
    fun `a missing or blank caregiver falls back to Jane`() {
        assertEquals("Jane", rosterFirstName(null))
        assertEquals("Jane", rosterFirstName(""))
        assertEquals("Jane", rosterFirstName("   "))
    }

    // ---- Status line -------------------------------------------------------

    @Test
    fun `no unopened events reads All quiet`() {
        assertEquals("All quiet", patientRowStatusText(0))
    }

    /** The plural `s` is appended only above one — "1 New events" is the bug. */
    @Test
    fun `the count is singular at one and plural above it`() {
        assertEquals("1 New event", patientRowStatusText(1))
        assertEquals("2 New events", patientRowStatusText(2))
    }

    // ---- Unopened filtering ------------------------------------------------

    @Test
    fun `only unopened events count`() {
        val events = listOf(
            event(id = "a", opened = false),
            event(id = "b", opened = true),
            event(id = "c", opened = false),
        )

        assertEquals(listOf("a", "c"), unopenedEvents(events).map { it.id })
    }

    /** A patient with no entry in `eventsByPatient` is quiet, not absent. */
    @Test
    fun `a patient with no events at all yields an empty list`() {
        assertEquals(emptyList<String>(), unopenedEvents(null).map { it.id })
    }

    // ---- Escalation --------------------------------------------------------

    @Test
    fun `an unopened safety event escalates the row`() {
        val unopened = listOf(event(category = Detector.Category.SAFETY))

        assertTrue(patientRowHasUnopenedSafety(unopened, isPaused = false))
    }

    /**
     * The rule PARITY.md §15 spells out: routine events show the NEW count but
     * never paint the card red. Widening this would turn every medication
     * reminder into an emergency.
     */
    @Test
    fun `unopened routine events do not escalate the row`() {
        val unopened = listOf(
            event(id = "a", category = Detector.Category.ROUTINE),
            event(id = "b", category = Detector.Category.ROUTINE),
        )

        assertFalse(patientRowHasUnopenedSafety(unopened, isPaused = false))
    }

    /** `!isPaused &&` guards the whole expression on iOS. */
    @Test
    fun `a paused patient never escalates even with unopened safety events`() {
        val unopened = listOf(event(category = Detector.Category.SAFETY))

        assertFalse(patientRowHasUnopenedSafety(unopened, isPaused = true))
    }
}
