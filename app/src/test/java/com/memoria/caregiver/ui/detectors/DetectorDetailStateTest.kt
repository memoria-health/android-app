@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.model.Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The detector editor's logic, tested without a composition.
 * Spec: `Features/Patient/Settings/DetectorDetailSheet.swift`, PARITY.md §17.
 *
 * Weighted toward the two behaviors §17 singles out — the dry-run preview, and
 * the re-sync of the staged pickers from what the backend re-infers — plus the
 * edges the Swift gets right quietly and a rewrite drops: which of the two
 * commit paths re-reads category and sensitivity, that a `PATCH` carries only
 * what moved, and that the post-refine poll needs a strictly newer row.
 *
 * The fixtures are local rather than shared: `DetectorScreenFixtures` builds
 * presets for the library and row screens and exposes none of the custom,
 * sustained, criteria-carrying fields this editor is about.
 */
class DetectorDetailStateTest {

    private val t0 = Instant.parse("2026-07-26T10:00:00Z")

    private fun detector(
        source: Detector.Source = Detector.Source.CUSTOM,
        alertTrigger: Detector.AlertTrigger = Detector.AlertTrigger.LEADING,
        isSustained: Boolean = true,
        durationSeconds: Int? = null,
        whereRooms: List<String>? = null,
        whenScope: String = "ALWAYS",
        category: Detector.Category = Detector.Category.SAFETY,
        sensitivity: Detector.Sensitivity = Detector.Sensitivity.STANDARD,
        identifier: String? = "custom_ab12cd34",
        userCriteria: List<String>? = listOf("Counts: she is at the table."),
        refinements: List<String>? = null,
        customPrompt: String? = "tell me if she skips lunch",
        isOn: Boolean = true,
        updatedAt: Instant = t0,
    ) = Detector(
        id = "33333333-3333-3333-3333-333333333333",
        patientId = "22222222-2222-2222-2222-222222222222",
        name = "Skipped lunch",
        iconName = "fork.knife",
        description = "Watches the kitchen table around midday.",
        category = category,
        source = source,
        alertTrigger = alertTrigger,
        isSustained = isSustained,
        durationSeconds = durationSeconds,
        whereRooms = whereRooms,
        whenScope = whenScope,
        sensitivity = sensitivity,
        isOn = isOn,
        customPrompt = customPrompt,
        identifier = identifier,
        userCriteria = userCriteria,
        refinements = refinements,
        createdAt = t0,
        updatedAt = updatedAt,
    )

    private fun state(d: Detector = detector()) = DetectorDetailState(detector = d)

    // -----------------------------------------------------------------------
    // Which wait the criteria card is showing
    // -----------------------------------------------------------------------

    @Test
    fun `each of the three waits gets its own sentence`() {
        assertEquals(
            "Re-reading your change…",
            state().copy(regenerating = true).generatingLabel,
        )
        assertEquals(
            "Applying your change…",
            state().copy(rewriting = true).generatingLabel,
        )
        assertEquals(
            "Writing the instructions…",
            state(detector(identifier = null, userCriteria = null)).generatingLabel,
        )
    }

    /**
     * A dry run in flight wins over a recompile: the caregiver just tapped
     * Re-generate, and "applying" would claim their change had been saved.
     */
    @Test
    fun `a dry run in flight outranks a recompile`() {
        val s = state().copy(regenerating = true, rewriting = true)
        assertEquals("Re-reading your change…", s.generatingLabel)
    }

    /**
     * A compile poll runs while the caregiver may be reading. Blanking criteria
     * that are already on screen would take away what they came here for.
     */
    @Test
    fun `a still-compiling detector that already has criteria keeps showing them`() {
        val s = state(detector(identifier = null, userCriteria = listOf("Counts: at the table.")))
        assertNull(s.generatingLabel)
        assertEquals(listOf("Counts: at the table."), s.shownCriteria)
    }

    // -----------------------------------------------------------------------
    // Who gets to choose the alert edge
    // -----------------------------------------------------------------------

    @Test
    fun `only a custom sustained non-absence detector chooses its alert edge`() {
        assertTrue(state().showsTimingChoice)
        assertFalse(state(detector(source = Detector.Source.PRESET)).showsTimingChoice)
        assertFalse(state(detector(isSustained = false)).showsTimingChoice)
        assertFalse(
            state(detector(alertTrigger = Detector.AlertTrigger.ABSENCE)).showsTimingChoice,
        )
    }

    // -----------------------------------------------------------------------
    // The dry run, and the re-sync PARITY.md §17 warns about
    // -----------------------------------------------------------------------

    /**
     * The whole point of the preview: the backend re-infers the alert trigger,
     * the active window and the rooms from the caregiver's words. Without this
     * the pickers keep showing the old shape while the criteria above them
     * describe the new one, and Save then writes the stale values back.
     */
    @Test
    fun `a preview re-syncs the alert trigger, the window and the rooms`() {
        val previewed = detector(
            alertTrigger = Detector.AlertTrigger.DURATION,
            durationSeconds = 900,
            whereRooms = listOf("Kitchen"),
            whenScope = "11:00-17:00",
        )

        val s = state().withPreview(previewed)

        assertEquals(Detector.AlertTrigger.DURATION, s.staged.alertTrigger)
        assertEquals(900, s.staged.durationSeconds)
        assertFalse(s.staged.allRooms)
        assertEquals(setOf("Kitchen"), s.staged.selectedRooms)
        assertEquals(WhenMode.Specific, s.staged.whenMode)
        assertEquals(HourMinute(11, 0), s.staged.whenStart)
        assertEquals(HourMinute(17, 0), s.staged.whenEnd)
    }

    /**
     * The refine response is a whole detector, so a naive re-sync would also
     * adopt its category and sensitivity — silently reverting an edit the
     * caregiver made in the pickers below and has not saved yet.
     */
    @Test
    fun `a preview leaves the caregiver's category and sensitivity edits alone`() {
        val edited = state().let {
            it.copy(
                staged = it.staged.copy(
                    category = Detector.Category.ROUTINE,
                    sensitivity = Detector.Sensitivity.HIGH,
                ),
            )
        }

        val s = edited.withPreview(detector(category = Detector.Category.SAFETY))

        assertEquals(Detector.Category.ROUTINE, s.staged.category)
        assertEquals(Detector.Sensitivity.HIGH, s.staged.sensitivity)
    }

    /** Nothing is persisted by a dry run, so the detector on screen must not move. */
    @Test
    fun `a preview stages criteria without touching the saved detector`() {
        val saved = detector(userCriteria = listOf("Counts: at the table."))
        val s = state(saved).withPreview(detector(userCriteria = listOf("Doesn't count: snacks.")))

        assertEquals(listOf("Doesn't count: snacks."), s.shownCriteria)
        assertEquals(listOf("Counts: at the table."), s.detector.userCriteria)
        assertTrue(s.isDirty)
    }

    /**
     * An empty preview still counts as a preview: it is what puts the
     * PREVIEW · NOT SAVED pill up, and losing it would show an unbadged empty
     * card that reads as the saved state.
     */
    @Test
    fun `a preview that produced no summary still stages a preview`() {
        val s = state().withPreview(detector(userCriteria = null))
        assertEquals(emptyList<String>(), s.previewCriteria)
    }

    @Test
    fun `typing in the refine box drops a preview compiled from other words`() {
        val s = state().withPreview(detector()).withFeedback("actually, ignore snacks")
        assertNull(s.previewCriteria)
    }

    // -----------------------------------------------------------------------
    // The two commit paths
    // -----------------------------------------------------------------------

    @Test
    fun `a committed refine consumes the words and re-syncs the pickers`() {
        val committed = detector(whereRooms = listOf("Kitchen"), whenScope = "11:00-17:00")
        val s = state().withFeedback("ignore snacks").withRefineCommitted(committed)

        assertEquals("", s.feedback)
        assertNull(s.previewCriteria)
        assertEquals(setOf("Kitchen"), s.staged.selectedRooms)
        assertEquals(WhenMode.Specific, s.staged.whenMode)
    }

    /**
     * The patch-only path is the one that DOES re-read category and sensitivity
     * — they were just sent, and the server's echo is authoritative over what
     * the pickers hold.
     */
    @Test
    fun `a patch commit adopts the server's category and sensitivity`() {
        val echoed = detector(
            category = Detector.Category.ROUTINE,
            sensitivity = Detector.Sensitivity.LOW,
        )
        val s = state().withPatchCommitted(echoed)

        assertEquals(Detector.Category.ROUTINE, s.staged.category)
        assertEquals(Detector.Sensitivity.LOW, s.staged.sensitivity)
        assertFalse(s.isDirty)
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    @Test
    fun `the patch carries only the field that moved`() {
        val s = state().let { it.copy(staged = it.staged.withRoomToggled("Kitchen")) }
        val patch = s.buildPatch()

        assertEquals(listOf("Kitchen"), patch?.whereRooms)
        assertNull(patch?.whenScope)
        assertNull(patch?.category)
        assertNull(patch?.sensitivity)
        assertNull(patch?.alertTrigger)
    }

    @Test
    fun `an unchanged sheet has no patch to send`() {
        assertNull(state().buildPatch())
        assertFalse(state().savePlan.hasWork)
    }

    /**
     * `duration_seconds` is only ever sent alongside a duration trigger, so
     * switching away leaves the stored threshold on the backend rather than
     * clearing it — which is what pre-fills the field if the caregiver switches
     * back.
     */
    @Test
    fun `switching off a duration trigger sends no duration`() {
        val d = detector(alertTrigger = Detector.AlertTrigger.DURATION, durationSeconds = 600)
        val s = state(d).let {
            it.copy(staged = it.staged.copy(alertTrigger = Detector.AlertTrigger.TRAILING))
        }
        val patch = s.buildPatch()

        assertEquals(Detector.AlertTrigger.TRAILING, patch?.alertTrigger)
        assertNull(patch?.durationSeconds)
    }

    /**
     * A duration trigger with no time would fire never. It blocks Save outright,
     * so the sheet below is dirty for another reason and still cannot be saved,
     * and the invalid timing is left out of the PATCH.
     */
    @Test
    fun `a duration trigger with no time blocks save even when something else changed`() {
        val s = state()
            .let { it.copy(staged = it.staged.copy(alertTrigger = Detector.AlertTrigger.DURATION)) }
            .withFeedback("ignore snacks")

        assertTrue(s.timingInvalid)
        assertTrue(s.isDirty)
        assertFalse(s.canSave)
        assertNull(s.buildPatch())
    }

    /** Typing a change and tapping Save without previewing still applies it. */
    @Test
    fun `pending words alone are enough to save`() {
        val s = state().withFeedback("  ignore snacks  ")

        assertTrue(s.isDirty)
        assertTrue(s.canSave)
        assertEquals("ignore snacks", s.savePlan.refineFeedback)
        assertNull(s.savePlan.patch)
    }

    /** Presets have no prompt to recompile, so their words go nowhere. */
    @Test
    fun `a preset never refines`() {
        val s = state(detector(source = Detector.Source.PRESET)).withFeedback("ignore snacks")
        assertNull(s.pendingRefine)
    }

    @Test
    fun `whitespace-only words are not a pending change`() {
        assertNull(state().withFeedback("   \n ").pendingRefine)
        assertFalse(state().withFeedback("   \n ").canRegenerate)
    }

    // -----------------------------------------------------------------------
    // The post-refine poll
    // -----------------------------------------------------------------------

    /**
     * The committed refine returns before the rewrite finishes, so the row it
     * echoes still carries the old summary and the same timestamp. Accepting an
     * equal timestamp would end the poll on that row and leave the stale summary
     * up until the sheet was reopened.
     */
    @Test
    fun `the poll waits for a strictly newer row`() {
        val s = state()
        assertFalse(s.isFresher(detector(updatedAt = t0)))
        assertFalse(s.isFresher(detector(updatedAt = t0 - 1.seconds)))
        assertTrue(s.isFresher(detector(updatedAt = t0 + 1.seconds)))
    }

    // -----------------------------------------------------------------------
    // Rooms and the window
    // -----------------------------------------------------------------------

    /** An empty room scope is meaningless: it would scope the detector to nothing. */
    @Test
    fun `deselecting the last room snaps back to all rooms`() {
        val staged = StagedEdits.initial(detector(whereRooms = listOf("Kitchen")))
        assertFalse(staged.allRooms)

        val cleared = staged.withRoomToggled("Kitchen")
        assertTrue(cleared.allRooms)
        assertEquals(emptySet<String>(), cleared.selectedRooms)
    }

    @Test
    fun `an unparseable window falls back to always with the eight to eight default`() {
        val (mode, start, end) = parseWhenScope("MON,WED 8:00 AM - 9:00 AM")
        assertEquals(WhenMode.Always, mode)
        assertEquals(HourMinute(8, 0), start)
        assertEquals(HourMinute(20, 0), end)
    }

    @Test
    fun `the duration field is minutes in and clamped seconds out`() {
        assertEquals(60, durationSecondsFromInput("1"))
        assertEquals(60, durationSecondsFromInput("0"))
        assertEquals(1440 * 60, durationSecondsFromInput("99999"))
        assertNull(durationSecondsFromInput(""))
        assertEquals("15", durationMinutesText(900))
        assertEquals("", durationMinutesText(null))
    }

    // -----------------------------------------------------------------------
    // Copy
    // -----------------------------------------------------------------------

    @Test
    fun `the sensitivity hint names the trade-off for every level`() {
        assertEquals("Fewer alerts, only the clearest signals", sensitivityHint(Detector.Sensitivity.LOW))
        assertEquals("Recommended baseline", sensitivityHint(Detector.Sensitivity.STANDARD))
        assertEquals("More alerts", sensitivityHint(Detector.Sensitivity.HIGH))
        assertEquals("Detector off", sensitivityHint(Detector.Sensitivity.OFF))
    }

    @Test
    fun `categories read as Safety and Routine`() {
        assertEquals("Safety", categoryLabel(Detector.Category.SAFETY))
        assertEquals("Routine", categoryLabel(Detector.Category.ROUTINE))
    }
}
