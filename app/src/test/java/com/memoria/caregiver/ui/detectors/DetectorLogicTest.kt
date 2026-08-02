package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.DetectorPlan
import com.memoria.caregiver.data.model.InferDetectorResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * The logic behind the three detector screens, tested without composing
 * anything. Spec: DetectorLibraryView.swift, DetectorRow.swift,
 * AddDetectorView.swift; PARITY.md §17 and §22.
 *
 * These are the rules a refactor could quietly break and a screenshot would not
 * catch: which keys the reconcile touches, what the backend's scope grammar
 * looks like, and which optional fields the create body is allowed to carry.
 */
class DetectorLogicTest {

    private val fx = DetectorScreenFixtures

    // ---- library: selection accounting ------------------------------------

    private val libraryEntries = listOf(
        fx.entry("fall", name = "Fall", recommended = true),
        fx.entry("wandering", name = "Wandering", recommended = true, alreadyAdded = true),
        fx.entry("stove", name = "Stove left on", description = "Watches the hob."),
    )

    private fun library(
        mode: DetectorLibraryMode = DetectorLibraryMode.Add,
        selected: Set<String> = emptySet(),
        query: String = "",
    ) = DetectorLibraryState(
        mode = mode,
        entries = libraryEntries,
        selectedKeys = selected,
        query = query,
        isLoading = false,
    )

    /**
     * Selecting something the patient already has is not a new pick. Counting it
     * would enable the footer and then POST a key the server skips, so the
     * caregiver would see "Add 1 detector" produce nothing.
     */
    @Test
    fun `already-added keys never count as new picks`() {
        val state = library(selected = setOf("wandering"))
        assertEquals(0, state.newlySelectedCount)
        assertTrue(state.footerDisabled)
        assertEquals("Add detectors", state.footerTitle)
    }

    @Test
    fun `the footer counts and pluralises the new picks`() {
        assertEquals("Add 1 detector", library(selected = setOf("fall")).footerTitle)
        assertEquals("Add 2 detectors", library(selected = setOf("fall", "stove")).footerTitle)
    }

    /** Onboarding's Continue may legitimately be "no change, advance". */
    @Test
    fun `onboarding continue is always available`() {
        val state = library(mode = DetectorLibraryMode.Onboarding)
        assertEquals("Continue", state.footerTitle)
        assertFalse(state.footerDisabled)
    }

    @Test
    fun `a submit in flight replaces the footer title in both modes`() {
        assertEquals("Saving…", library(selected = setOf("fall")).copy(isSubmitting = true).footerTitle)
        assertEquals(
            "Saving…",
            library(mode = DetectorLibraryMode.Onboarding).copy(isSubmitting = true).footerTitle,
        )
    }

    /**
     * Add-mode never deletes. Only the onboarding reconcile removes what the
     * caregiver unchecked, and only for keys the library reported as present.
     */
    @Test
    fun `only onboarding produces deletions`() {
        assertEquals(emptySet<String>(), library(selected = setOf("fall")).keysToRemove)

        val onboarding = library(mode = DetectorLibraryMode.Onboarding, selected = setOf("fall"))
        assertEquals(setOf("wandering"), onboarding.keysToRemove)
        assertEquals(listOf("fall"), onboarding.keysToAdd)
    }

    @Test
    fun `an already-added entry is locked in add mode and toggleable in onboarding`() {
        val entry = libraryEntries[1]
        assertTrue(library().isLocked(entry))
        assertFalse(library(mode = DetectorLibraryMode.Onboarding).isLocked(entry))
    }

    // ---- library: search ---------------------------------------------------

    @Test
    fun `search matches the description as well as the name`() {
        assertEquals(listOf("stove"), library(query = "hob").filteredEntries.map { it.key })
    }

    @Test
    fun `search is case-insensitive and ignores surrounding spaces`() {
        assertEquals(listOf("fall"), library(query = "  FALL ").filteredEntries.map { it.key })
    }

    @Test
    fun `sections split on the recommended flag`() {
        val state = library()
        assertEquals(listOf("fall", "wandering"), state.recommendedEntries.map { it.key })
        assertEquals(listOf("stove"), state.otherEntries.map { it.key })
    }

    @Test
    fun `the missing-detector message names the key`() {
        assertEquals(
            "Couldn't find the detector to remove for “stove”.",
            missingDetectorForKeyMessage("stove"),
        )
    }

    // ---- row ---------------------------------------------------------------

    /**
     * A sensor only counts once it has at least one exit door. "No door leads
     * outside" and a sensor that was never configured both mean wandering is not
     * running there.
     */
    @Test
    fun `active sensors exclude no-exit-door and unconfigured cameras`() {
        val state = DetectorRowState(
            detector = fx.detector(),
            doorConfigs = mapOf(
                "a" to Detector.DoorConfig(doors = listOf(fx.door())),
                "b" to Detector.DoorConfig(doors = listOf(fx.door()), noExitDoor = true),
                "c" to Detector.DoorConfig(doors = emptyList()),
            ),
        )
        assertEquals(1, state.activeSensorCount)
    }

    @Test
    fun `the wandering hint pluralises the sensor count`() {
        fun hint(count: Int) = DetectorRowState(
            detector = fx.detector(),
            doorConfigs = (0 until count).associate { "cam$it" to Detector.DoorConfig(doors = listOf(fx.door())) },
        ).wanderingHintText

        assertTrue(hint(1).startsWith("Active on 1 sensor ·"))
        assertTrue(hint(2).startsWith("Active on 2 sensors ·"))
        assertEquals(
            "Not active yet. Open a sensor in the Sensors tab and set its exit doors to turn wandering on",
            hint(0),
        )
    }

    @Test
    fun `the hint only shows for the wandering detector while it is on`() {
        assertTrue(DetectorRowState(fx.detector(isOn = true)).showsWanderingHint)
        assertFalse(DetectorRowState(fx.detector(isOn = false)).showsWanderingHint)
        assertFalse(DetectorRowState(fx.detector(identifier = "fall", isOn = true)).showsWanderingHint)
    }

    @Test
    fun `an ALWAYS scope is humanised and anything else is shown as stored`() {
        assertEquals("Always", DetectorRowState(fx.detector(whenScope = " always ")).whenLabel)
        assertEquals(
            "MON,WED 8:00 AM - 9:00 AM",
            DetectorRowState(fx.detector(whenScope = "MON,WED 8:00 AM - 9:00 AM")).whenLabel,
        )
    }

    // ---- when-scope grammar ------------------------------------------------

    /**
     * The backend parses this text directly, so the AM/PM token is not
     * cosmetic — it is grammar, and it must survive a 24-hour device.
     */
    @Test
    fun `scope times always carry an AM or PM token`() {
        assertEquals("12:00 AM", formatScopeTime(LocalTime.of(0, 0)))
        assertEquals("8:05 AM", formatScopeTime(LocalTime.of(8, 5)))
        assertEquals("12:30 PM", formatScopeTime(LocalTime.of(12, 30)))
        assertEquals("11:59 PM", formatScopeTime(LocalTime.of(23, 59)))
    }

    @Test
    fun `a daily cadence serialises the bare window`() {
        assertEquals(
            "8:00 AM - 9:00 AM",
            formatWhenScope(
                Detector.ScheduleConfig.Cadence.DAILY,
                setOf(0, 2),
                LocalTime.of(8, 0),
                LocalTime.of(9, 0),
            ),
        )
    }

    @Test
    fun `a weekly cadence prefixes the picked days in order`() {
        assertEquals(
            "MON,WED 8:00 AM - 9:00 AM",
            formatWhenScope(
                Detector.ScheduleConfig.Cadence.WEEKLY,
                setOf(2, 0),
                LocalTime.of(8, 0),
                LocalTime.of(9, 0),
            ),
        )
    }

    @Test
    fun `a weekly cadence with no days picked falls back to the bare window`() {
        assertEquals(
            "8:00 AM - 9:00 AM",
            formatWhenScope(
                Detector.ScheduleConfig.Cadence.WEEKLY,
                emptySet(),
                LocalTime.of(8, 0),
                LocalTime.of(9, 0),
            ),
        )
    }

    // ---- HH:MM parsing -----------------------------------------------------

    @Test
    fun `parses a well-formed inferred time`() {
        assertEquals(LocalTime.of(9, 5), parseHHMM("09:05"))
    }

    @Test
    fun `rejects anything that is not exactly five characters`() {
        assertNull(parseHHMM(null))
        assertNull(parseHHMM("9:05"))
        assertNull(parseHHMM("09:05:00"))
    }

    /**
     * Swift's `Int(_:)` is ASCII-only. `toIntOrNull()` is not, so without the
     * explicit range check the same payload would produce a window on Android
     * and a blank on iOS.
     */
    @Test
    fun `rejects non-ASCII digits`() {
        assertNull(parseHHMM("٠٩:٠٥"))
    }

    @Test
    fun `rejects an out-of-range clock`() {
        assertNull(parseHHMM("99:00"))
        assertNull(parseHHMM("09:99"))
    }

    // ---- create body -------------------------------------------------------

    private val reviewed = AddDetectorState(
        stage = AddDetectorStage.Review,
        name = "  Medication check  ",
        note = " Mom's afternoon pills ",
        prompt = " check the pills ",
        icon = "pills",
        category = Detector.Category.ROUTINE,
        sensitivity = Detector.Sensitivity.HIGH,
    )

    /** Create-as-draft: the detector is made OFF and reviewed before it runs. */
    @Test
    fun `a created detector starts off`() {
        assertFalse(reviewed.toCreateDetectorRequest().isOn)
    }

    /** Dissolved 2026-07-18 — `when_scope` is the whole windows mechanism now. */
    @Test
    fun `schedule_config is never sent`() {
        assertNull(reviewed.toCreateDetectorRequest().scheduleConfig)
        assertNull(
            reviewed.copy(alertTrigger = Detector.AlertTrigger.ABSENCE).toCreateDetectorRequest().scheduleConfig,
        )
    }

    @Test
    fun `a reactive detector sends ALWAYS and an absence detector sends its window`() {
        assertEquals("ALWAYS", reviewed.toCreateDetectorRequest().whenScope)

        val absence = reviewed.copy(
            alertTrigger = Detector.AlertTrigger.ABSENCE,
            cadence = Detector.ScheduleConfig.Cadence.WEEKLY,
            days = setOf(0),
            windowStart = LocalTime.of(11, 0),
            windowEnd = LocalTime.of(17, 0),
        )
        assertEquals("MON 11:00 AM - 5:00 PM", absence.toCreateDetectorRequest().whenScope)
    }

    /**
     * A duration threshold on a trigger that never reads it, or an episode gap on
     * an instant detector, configures a bar the detector cannot reach.
     */
    @Test
    fun `duration and episode gap are only sent when they apply`() {
        val instant = reviewed.copy(durationSeconds = 120, episodeGapSeconds = 300)
        assertNull(instant.toCreateDetectorRequest().durationSeconds)
        assertNull(instant.toCreateDetectorRequest().episodeGapSeconds)

        val sustained = instant.copy(alertTrigger = Detector.AlertTrigger.DURATION, isSustained = true)
        assertEquals(120, sustained.toCreateDetectorRequest().durationSeconds)
        assertEquals(300, sustained.toCreateDetectorRequest().episodeGapSeconds)
    }

    @Test
    fun `blank name and note fall back to the placeholder copy`() {
        val blank = reviewed.copy(name = "   ", note = "\n ").toCreateDetectorRequest()
        assertEquals("Custom detector", blank.name)
        assertEquals("Custom detector", blank.description)
    }

    @Test
    fun `the sent fields are trimmed`() {
        val body = reviewed.toCreateDetectorRequest()
        assertEquals("Medication check", body.name)
        assertEquals("Mom's afternoon pills", body.description)
        assertEquals("check the pills", body.customPrompt)
        assertEquals("pills", body.iconName)
        assertEquals(Detector.Sensitivity.HIGH, body.sensitivity)
    }

    // ---- inference ---------------------------------------------------------

    private fun inference(
        trigger: Detector.AlertTrigger = Detector.AlertTrigger.LEADING,
        schedule: Detector.ScheduleConfig? = null,
        plan: DetectorPlan? = null,
    ) = InferDetectorResponse(
        alertTrigger = trigger,
        isSustained = true,
        durationSeconds = 90,
        category = Detector.Category.ROUTINE,
        iconName = "pills",
        sensitivity = Detector.Sensitivity.LOW,
        scheduleConfig = schedule,
        episodeGapSeconds = 240,
        plan = plan,
    )

    @Test
    fun `inference advances to the review stage and carries the silent fields`() {
        val state = AddDetectorState().applyingInference(inference())
        assertEquals(AddDetectorStage.Review, state.stage)
        assertEquals(90, state.durationSeconds)
        assertEquals(240, state.episodeGapSeconds)
        assertEquals(Detector.Sensitivity.LOW, state.sensitivity)
        assertEquals("pills", state.icon)
    }

    /** The deadline is the window end under the unified window(hit|miss) rule. */
    @Test
    fun `a missing deadline falls back to the window end`() {
        val state = AddDetectorState().applyingInference(
            inference(
                trigger = Detector.AlertTrigger.ABSENCE,
                schedule = Detector.ScheduleConfig(
                    cadence = Detector.ScheduleConfig.Cadence.DAILY,
                    windowStart = "11:00",
                    windowEnd = "17:00",
                    deadline = null,
                ),
            ),
        )
        assertEquals(LocalTime.of(17, 0), state.deadline)
        assertTrue(state.timesUncertain)
    }

    @Test
    fun `only an absence detector can be uncertain about its times`() {
        val reactive = AddDetectorState().applyingInference(inference(schedule = null))
        assertFalse(reactive.timesUncertain)

        val absence = AddDetectorState().applyingInference(
            inference(
                trigger = Detector.AlertTrigger.ABSENCE,
                schedule = Detector.ScheduleConfig(
                    windowStart = "11:00",
                    windowEnd = "17:00",
                    deadline = "17:00",
                ),
            ),
        )
        assertFalse(absence.timesUncertain)
    }

    @Test
    fun `a null plan is carried through so the preview card can be hidden`() {
        assertNull(AddDetectorState().applyingInference(inference()).plan)
        val plan = DetectorPlan(watches = "the kitchen", fires = "at once", checks = "every minute", listens = true)
        assertEquals(plan, AddDetectorState().applyingInference(inference(plan = plan)).plan)
    }

    // ---- generate gating ---------------------------------------------------

    @Test
    fun `generate needs a prompt that is more than whitespace`() {
        assertFalse(AddDetectorState(prompt = "   \n ").canGenerate)
        assertTrue(AddDetectorState(prompt = "check the pills").canGenerate)
        assertFalse(AddDetectorState(prompt = "check the pills", busy = true).canGenerate)
    }

    /**
     * Only a sustained activity has an edge to pick between. An absence detector
     * is sustained-shaped but has exactly one moment, so it gets the sentence.
     */
    @Test
    fun `the trigger choice appears only for sustained non-absence detectors`() {
        assertTrue(AddDetectorState(isSustained = true).showsTriggerChoice)
        assertFalse(AddDetectorState(isSustained = false).showsTriggerChoice)
        assertFalse(
            AddDetectorState(isSustained = true, alertTrigger = Detector.AlertTrigger.ABSENCE).showsTriggerChoice,
        )
    }

    // ---- icon labels -------------------------------------------------------

    @Test
    fun `curated icons show their product label`() {
        assertEquals("Walking", detectorIconLabel("figure.walk.motion"))
        assertEquals("Bed / Sleep", detectorIconLabel("bed.double"))
        assertEquals(20, DetectorIconOptions.size)
        assertTrue(DetectorIconOptions.all { detectorIconLabel(it) != it })
    }

    /** Anything inferred outside the set must still never read as developer text. */
    @Test
    fun `an unknown icon is prettified rather than shown raw`() {
        assertEquals("Stand", detectorIconLabel("figure.stand.fill"))
        assertEquals("Crop Circle", detectorIconLabel("person.crop.circle"))
        assertEquals("Wave 3 Right", detectorIconLabel("wave.3.right"))
    }
}
