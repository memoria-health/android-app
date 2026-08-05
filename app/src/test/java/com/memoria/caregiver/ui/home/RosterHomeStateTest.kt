@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.home

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.remote.HomeReport
import com.memoria.caregiver.ui.cameras.testCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.ExperimentalTime

/**
 * What the Home screen shows, given what the server did or did not say.
 *
 * The three sources — server report, server briefing, locally-computed sentence
 * — are all allowed to be missing, so the interesting behaviour is entirely in
 * which one wins and what happens when it is absent. The backend's own
 * docstring requires the app to survive all three being unavailable.
 */
class RosterHomeStateTest {

    private val now = 1_750_000_000_000L
    private val ada = patient(id = "p1", preferredName = "Ada")
    private val joey = patient(id = "p2", preferredName = "Joey")

    private fun stateOf(
        patients: List<com.memoria.caregiver.data.model.Patient> = listOf(ada),
        cameras: Map<String, List<Camera>> = mapOf("p1" to listOf(testCamera(online = true))),
        report: HomeReport? = null,
        briefing: String? = null,
    ): RosterState {
        val statuses = HomeStatus.statuses(patients, emptyMap(), cameras, now)
        return RosterState(
            patients = statuses.map { it.patient },
            camerasByPatient = cameras,
            statuses = statuses,
            report = report,
            briefing = briefing,
        )
    }

    // ---- Which verdict wins ----

    @Test
    fun `with no server report the locally-computed verdict shows`() {
        // And it shows immediately — no network call has to return for the
        // screen to answer the question it exists to answer.
        assertEquals("All quiet for Ada", stateOf().verdict.line)
    }

    @Test
    fun `a server report replaces the local verdict`() {
        // One author for the whole summary. Two authors produce a highlight and
        // a sub-line that contradict each other.
        val state = stateOf(report = HomeReport(severity = "warn", highlight = "Ada was up at 3am"))
        assertEquals("Ada was up at 3am", state.verdict.line)
        assertEquals(HomeStatus.Level.ATTENTION, state.verdict.level)
        assertEquals("", state.verdict.sub)
    }

    @Test
    fun `an empty highlight is not a report`() {
        // A model that declined to write one must not blank the headline.
        val state = stateOf(report = HomeReport(severity = "ok", highlight = ""))
        assertEquals("All quiet for Ada", state.verdict.line)
    }

    @Test
    fun `report severity maps onto the app's own levels`() {
        assertEquals(
            HomeStatus.Level.URGENT,
            stateOf(report = HomeReport(severity = "urgent", highlight = "x")).verdict.level,
        )
        assertEquals(
            HomeStatus.Level.QUIET,
            stateOf(report = HomeReport(severity = "ok", highlight = "x")).verdict.level,
        )
        // Anything unrecognised reads as quiet rather than throwing: a new
        // severity string from the server must not blank the screen.
        assertEquals(
            HomeStatus.Level.QUIET,
            stateOf(report = HomeReport(severity = "something-new", highlight = "x")).verdict.level,
        )
    }

    // ---- The prose, and its three levels of fallback ----

    @Test
    fun `the report description wins when there is one`() {
        val state = stateOf(
            report = HomeReport(severity = "ok", highlight = "h", description = "From the server."),
            briefing = "From the proxy.",
        )
        assertEquals("From the server.", state.briefingText)
    }

    @Test
    fun `an empty description falls through to the briefing`() {
        val state = stateOf(
            report = HomeReport(severity = "ok", highlight = "h", description = ""),
            briefing = "From the proxy.",
        )
        assertEquals("From the proxy.", state.briefingText)
    }

    @Test
    fun `with neither, the locally-computed sentence shows`() {
        // The property that matters: the screen is never blank, and never
        // waiting. Both endpoints can be absent on an older backend.
        val text = stateOf().briefingText
        assertTrue(text.isNotEmpty())
        assertTrue(text.contains("**Ada**"))
    }

    // ---- Streaks ----

    @Test
    fun `no streak chip across several people`() {
        // "12 days" silently mixing two patients is worse than no chip at all.
        val state = stateOf(
            patients = listOf(ada, joey),
            cameras = mapOf("p1" to listOf(testCamera(online = true)), "p2" to listOf(testCamera(online = true))),
        )
        assertNull(state.streak)
    }

    @Test
    fun `no streak chip when there is nothing to count from`() {
        // A brand-new patient has no loaded events, so daysWithoutFall is null
        // and the chip must not invent a number.
        assertNull(stateOf().streak)
    }

    // ---- The refetch trigger ----

    @Test
    fun `the fact signature changes when someone's reality changes`() {
        // A briefing must never outlive the facts it narrates. The signature is
        // what the screen watches, so it has to move when a camera does.
        val watched = stateOf(cameras = mapOf("p1" to listOf(testCamera(online = true))))
        val blind = stateOf(cameras = mapOf("p1" to emptyList()))
        assertNotEquals(watched.factSignature, blind.factSignature)
    }

    @Test
    fun `the fact signature is stable when nothing has changed`() {
        // The other half: idling on Home must not spend a call per recomposition.
        assertEquals(stateOf().factSignature, stateOf().factSignature)
    }

    // ---- Ordering ----

    @Test
    fun `the roster is in triage order, worst first`() {
        val state = stateOf(
            patients = listOf(ada, joey),
            cameras = mapOf("p1" to listOf(testCamera(online = true)), "p2" to emptyList()),
        )
        // Joey is blind, Ada is fine. Whoever needs eyes floats to the top, and
        // the list order comes from the same statuses the headline is built
        // from — so the two can never disagree about who needs attention.
        assertEquals("p2", state.patients.first().id)
    }
}
