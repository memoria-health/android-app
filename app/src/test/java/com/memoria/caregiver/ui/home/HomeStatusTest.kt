@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.home

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.cameras.testCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The Home verdict.
 *
 * These tests are mostly about one rule — **never aggregate raw data, aggregate
 * per-person verdicts** — because every bug the iOS version closed came from
 * breaking it. Pooling made one person's working cameras hide another's
 * blindness, reported falls without saying whose, and credited quiet streaks to
 * people nobody was watching.
 *
 * So the cases below are deliberately weighted toward *two* patients in
 * *different* situations, which is where pooling shows up.
 */
class HomeStatusTest {

    private val now = 1_750_000_000_000L
    private fun minsAgo(m: Long) = Instant.fromEpochMilliseconds(now - m * 60_000L)
    private fun hoursAgo(h: Long) = minsAgo(h * 60)
    private fun daysAgo(d: Long) = hoursAgo(d * 24)

    private fun ev(
        id: String = "e1",
        patientId: String = "p1",
        title: String = "Movement",
        location: String = "",
        at: Instant = minsAgo(5),
        reviewed: Boolean = false,
    ): DetectionEvent = DetectionEvent(
        id = id,
        patientId = patientId,
        category = Detector.Category.ROUTINE,
        source = Detector.Source.PRESET,
        iconName = "",
        title = title,
        location = location,
        occurredAt = at,
        hasClip = false,
        narrative = "",
        opened = false,
        reviewed = reviewed,
        createdAt = at,
    )

    private fun online(id: String = "c1") = testCamera(id = id, online = true)
    private fun offline(id: String = "c1") = testCamera(id = id, online = false)

    private fun statusOf(
        events: List<DetectionEvent> = emptyList(),
        cameras: List<Camera> = listOf(online()),
        patient: Patient = patient(),
    ) = HomeStatus.statusFor(patient, events, cameras, now)

    // ---- Severity, per person ----

    @Test
    fun `a recent unreviewed fall is urgent`() {
        val s = statusOf(events = listOf(ev(title = "Possible fall", at = minsAgo(20))))
        assertEquals(HomeStatus.Level.URGENT, s.level)
    }

    @Test
    fun `a serious moment already reviewed is not urgent`() {
        // Reviewed means a human has looked. Interrupting them again with it
        // would train them to ignore the one that matters.
        val s = statusOf(events = listOf(ev(title = "Possible fall", at = minsAgo(20), reviewed = true)))
        assertFalse(s.level == HomeStatus.Level.URGENT)
    }

    @Test
    fun `a serious moment older than three hours is no longer urgent`() {
        val s = statusOf(events = listOf(ev(title = "Possible fall", at = hoursAgo(4))))
        assertEquals(HomeStatus.Level.ATTENTION, s.level)
    }

    @Test
    fun `an ordinary unreviewed moment is attention, not urgent`() {
        val s = statusOf(events = listOf(ev(title = "Movement", at = minsAgo(30))))
        assertEquals(HomeStatus.Level.ATTENTION, s.level)
    }

    @Test
    fun `no cameras is blind, and blind is not quiet`() {
        val s = statusOf(cameras = emptyList())
        assertEquals(HomeStatus.Level.BLIND, s.level)
        assertEquals("no cameras set up yet", s.headline)
    }

    @Test
    fun `every camera offline is blind`() {
        val s = statusOf(cameras = listOf(offline("c1"), offline("c2")))
        assertEquals(HomeStatus.Level.BLIND, s.level)
        assertEquals("cameras are offline", s.headline)
    }

    @Test
    fun `blind outranks attention`() {
        // The rule: with no working camera we cannot know whether there is
        // anything to attend to, so we must not present it as a mere backlog.
        val s = statusOf(
            events = listOf(ev(title = "Movement", at = minsAgo(30))),
            cameras = emptyList(),
        )
        assertEquals(HomeStatus.Level.BLIND, s.level)
    }

    @Test
    fun `urgent outranks blind`() {
        // A fall we actually saw beats not being able to see now.
        val s = statusOf(
            events = listOf(ev(title = "Possible fall", at = minsAgo(10))),
            cameras = emptyList(),
        )
        assertEquals(HomeStatus.Level.URGENT, s.level)
    }

    // ---- Streaks: the invented-reassurance bug ----

    @Test
    fun `a brand-new patient gets no quiet-night credit`() {
        // The bug this exists for: with nothing loaded, the loop happily counted
        // thirty nights nobody was ever watched for.
        val s = statusOf(events = emptyList())
        assertEquals(0, s.quietNights)
        assertNull(s.daysWithoutFall)
    }

    @Test
    fun `days without a fall reads as at-least when no fall is on record`() {
        // "+" is honesty about the window: all we can say is "at least as long
        // as we have records for".
        val s = statusOf(events = listOf(ev(title = "Movement", at = daysAgo(9))))
        assertTrue("expected a + form, got ${s.daysWithoutFall}", s.daysWithoutFall!!.endsWith("+"))
    }

    @Test
    fun `days without a fall counts from the last fall when there is one`() {
        val s = statusOf(
            events = listOf(
                ev(id = "old", title = "Possible fall", at = daysAgo(5)),
                ev(id = "new", title = "Movement", at = daysAgo(1)),
            ),
        )
        assertEquals("5", s.daysWithoutFall)
    }

    // ---- Wording ----

    @Test
    fun `an event is described in plain words, hedged`() {
        // Hedged on purpose: these are model detections, and stating them as
        // fact would tell a caregiver their mother fell when it saw a cushion.
        val e = ev(title = "Possible fall", location = "Kitchen")
        assertEquals("what looked like a fall in the kitchen", HomeStatus.humanize(e))
    }

    @Test
    fun `an event with no location does not say in the empty string`() {
        assertEquals("what looked like a fall", HomeStatus.humanize(ev(title = "Possible fall")))
    }

    @Test
    fun `choking and door activity have their own wording`() {
        assertEquals("possible choking", HomeStatus.humanize(ev(title = "Possible choking")))
        assertEquals("activity near the door", HomeStatus.humanize(ev(title = "Wandering")))
    }

    // ---- The fold across people ----

    private val ada = patient(id = "p1", preferredName = "Ada")
    private val joey = patient(id = "p2", preferredName = "Joey")

    private fun fold(
        events: Map<String, List<DetectionEvent>> = emptyMap(),
        cameras: Map<String, List<Camera>> = mapOf("p1" to listOf(online()), "p2" to listOf(online())),
        patients: List<Patient> = listOf(ada, joey),
    ) = HomeStatus.verdict(HomeStatus.statuses(patients, events, cameras, now))

    @Test
    fun `one person blind is never hidden by the other's working cameras`() {
        // THE pooling bug. Ada is fine; Joey has no cameras. A summary that
        // said "all quiet" because most of the household is fine would be
        // exactly wrong.
        val v = fold(cameras = mapOf("p1" to listOf(online()), "p2" to emptyList()))
        assertEquals(HomeStatus.Level.BLIND, v.level)
        assertTrue("the line must name who is unwatched", v.line.contains("Joey"))
        assertFalse("must not name the person who is fine", v.line.contains("Ada"))
        assertEquals("Everyone else looks fine", v.sub)
    }

    @Test
    fun `everyone blind says so without naming a single person`() {
        val v = fold(cameras = mapOf("p1" to emptyList(), "p2" to emptyList()))
        assertEquals("No one is being watched right now", v.line)
    }

    @Test
    fun `an urgent moment names whose it is`() {
        // Anonymous falls were a real complaint: "something needs attention"
        // across two patients is not actionable.
        val v = fold(events = mapOf("p2" to listOf(ev(patientId = "p2", title = "Possible fall", at = minsAgo(10)))))
        assertEquals(HomeStatus.Level.URGENT, v.level)
        assertTrue(v.line.contains("Joey"))
    }

    @Test
    fun `all quiet only when everyone is quiet`() {
        val v = fold()
        assertEquals(HomeStatus.Level.QUIET, v.level)
        assertEquals("All quiet for everyone", v.line)
    }

    @Test
    fun `a single patient is a fold of one and reads naturally`() {
        val v = HomeStatus.verdict(
            HomeStatus.statuses(listOf(ada), emptyMap(), mapOf("p1" to listOf(online())), now),
        )
        assertEquals("All quiet for Ada", v.line)
    }

    @Test
    fun `no patients at all is a welcome, not a verdict`() {
        val v = HomeStatus.verdict(emptyList())
        assertEquals("Welcome", v.line)
    }

    @Test
    fun `statuses come back worst first`() {
        val statuses = HomeStatus.statuses(
            listOf(ada, joey),
            mapOf("p2" to listOf(ev(patientId = "p2", title = "Possible fall", at = minsAgo(5)))),
            mapOf("p1" to listOf(online()), "p2" to listOf(online())),
            now,
        )
        assertEquals(HomeStatus.Level.URGENT, statuses.first().level)
        assertEquals("Joey", statuses.first().name)
    }

    // ---- The immediate sentence ----

    @Test
    fun `the template names people in bold so it matches the AI prose`() {
        val statuses = HomeStatus.statuses(listOf(ada), emptyMap(), mapOf("p1" to listOf(online())), now)
        assertTrue(HomeStatus.templateBriefing(statuses).contains("**Ada**"))
    }

    @Test
    fun `the template groups the calm and names the rest`() {
        val statuses = HomeStatus.statuses(
            listOf(ada, joey),
            emptyMap(),
            mapOf("p1" to listOf(online()), "p2" to emptyList()),
            now,
        )
        val text = HomeStatus.templateBriefing(statuses)
        assertTrue(text.contains("**Ada**"))
        assertTrue(text.contains("**Joey**"))
        assertTrue(text.contains("no cameras set up yet"))
    }

    @Test
    fun `the briefing subject is the person when there is only one`() {
        val one = HomeStatus.statuses(listOf(ada), emptyMap(), mapOf("p1" to listOf(online())), now)
        assertEquals("Ada", HomeStatus.briefingName(one))
        val two = HomeStatus.statuses(listOf(ada, joey), emptyMap(), emptyMap(), now)
        assertEquals("everyone", HomeStatus.briefingName(two))
    }

    // ---- Camera truth ----

    @Test
    fun `camera detail counts watching, paused and offline separately`() {
        // The briefing prompt forbids the model from mentioning camera status,
        // because THIS is the place that states it exactly. Two sources for one
        // fact is how "3 offline" ends up beside "everything is fine".
        val detail = HomeStatus.cameraDetail(
            listOf(ada),
            mapOf(
                "p1" to listOf(
                    testCamera(id = "a", online = true, aiPaused = false),
                    testCamera(id = "b", online = true, aiPaused = true),
                    testCamera(id = "c", online = false),
                ),
            ),
        )
        assertEquals("Ada" to "1 watching · 1 on but AI paused · 1 offline", detail.single())
    }

    @Test
    fun `camera detail says plainly when there are none`() {
        assertEquals("Ada" to "no cameras set up", HomeStatus.cameraDetail(listOf(ada), emptyMap()).single())
    }
}
