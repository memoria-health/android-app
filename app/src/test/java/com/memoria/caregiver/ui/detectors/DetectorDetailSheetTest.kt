@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.detectors

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Detector-editor behaviour that only a composed screen can show.
 * Spec: `Features/Patient/Settings/DetectorDetailSheet.swift`, PARITY.md §17.
 *
 * The arithmetic — what is dirty, what the PATCH carries, what a preview
 * re-syncs — is covered by `DetectorDetailStateTest`. These assert the rules are
 * actually wired to the surface: that the dry-run badge is tied to a preview,
 * that an inert Save does not fire, that a preset gets no refine box. Layout is
 * judged by screenshot (DECISIONS.md D-004).
 *
 * Existence is asserted without scrolling on purpose: the body is a plain
 * `Column` in a `verticalScroll`, not a lazy list, so every child is composed
 * whether or not it is on screen. `performScrollTo` is used only where something
 * is about to be tapped or typed into, which does need real bounds.
 */
@RunWith(RobolectricTestRunner::class)
class DetectorDetailSheetTest {

    @get:Rule val compose = createComposeRule()

    private val t0 = Instant.parse("2026-07-26T10:00:00Z")

    private val staged = mutableListOf<StagedEdits>()
    private val typed = mutableListOf<String>()
    private var regenerates = 0
    private var turnOns = 0
    private var saves = 0
    private var dones = 0

    private fun detector(
        source: Detector.Source = Detector.Source.CUSTOM,
        alertTrigger: Detector.AlertTrigger = Detector.AlertTrigger.LEADING,
        isSustained: Boolean = true,
        identifier: String? = "custom_ab12cd34",
        userCriteria: List<String>? = listOf("Counts: she is at the table."),
        refinements: List<String>? = null,
        customPrompt: String? = "tell me if she skips lunch",
        isOn: Boolean = true,
        accuracyCaveat: Boolean? = null,
    ) = Detector(
        id = "33333333-3333-3333-3333-333333333333",
        patientId = "22222222-2222-2222-2222-222222222222",
        name = "Skipped lunch",
        iconName = "fork.knife",
        description = "Watches the kitchen table around midday.",
        category = Detector.Category.SAFETY,
        source = source,
        alertTrigger = alertTrigger,
        isSustained = isSustained,
        whenScope = "ALWAYS",
        sensitivity = Detector.Sensitivity.STANDARD,
        isOn = isOn,
        customPrompt = customPrompt,
        identifier = identifier,
        userCriteria = userCriteria,
        refinements = refinements,
        accuracyCaveat = accuracyCaveat,
        createdAt = t0,
        updatedAt = t0,
    )

    private fun setUp(state: DetectorDetailState) {
        compose.setContent {
            MemoriaTheme {
                DetectorDetailSheet(
                    state = state,
                    onFeedbackChange = { typed += it },
                    onStagedChange = { staged += it },
                    onRegenerate = { regenerates++ },
                    onTurnOn = { turnOns++ },
                    onSave = { saves++ },
                    onDone = { dones++ },
                )
            }
        }
    }

    private fun state(
        d: Detector = detector(),
        rooms: List<String> = listOf("Kitchen", "Bedroom"),
    ) = DetectorDetailState(detector = d, roomOptions = rooms)

    // -----------------------------------------------------------------------
    // The dry run
    // -----------------------------------------------------------------------

    /**
     * The pill is the caregiver's only signal that what they are reading has not
     * been saved. Losing it turns a dry run into something that looks committed.
     */
    @Test
    fun `a preview badges the card and shows the previewed criteria`() {
        setUp(state().copy(previewCriteria = listOf("Doesn't count: snacks.")))

        compose.onNodeWithText("PREVIEW · NOT SAVED").assertExists()
        compose.onNodeWithText("Doesn't count: snacks.").assertExists()
        compose.onNodeWithText("Counts: she is at the table.").assertDoesNotExist()
    }

    @Test
    fun `the saved card carries no preview badge`() {
        setUp(state())

        compose.onNodeWithText("PREVIEW · NOT SAVED").assertDoesNotExist()
        compose.onNodeWithText("Counts: she is at the table.").assertExists()
    }

    @Test
    fun `typing in the refine box reports the words`() {
        setUp(state())

        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("ignore snacks")
        assertEquals(listOf("ignore snacks"), typed)
    }

    /** Re-generating with nothing typed would ask the backend to compile nothing. */
    @Test
    fun `re-generate is inert with an empty box`() {
        setUp(state())

        compose.onNodeWithText("Re-generate")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        assertEquals(0, regenerates)
    }

    @Test
    fun `re-generate fires once there are words`() {
        setUp(state().withFeedback("ignore snacks"))

        compose.onNodeWithText("Re-generate").performScrollTo().performClick()
        assertEquals(1, regenerates)
    }

    @Test
    fun `a dry run in flight shows the shimmer instead of criteria`() {
        // The shimmer and the button spinner are both infinite animations, and
        // an infinite animation never lets the test clock go idle. Driving the
        // clock by hand is the documented way round it.
        compose.mainClock.autoAdvance = false
        setUp(state().withFeedback("ignore snacks").copy(regenerating = true))

        compose.onNodeWithText("Re-reading your change…").assertExists()
        compose.onNodeWithText("Counts: she is at the table.").assertDoesNotExist()
        compose.onNodeWithText("Re-generating…").assertExists()
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    @Test
    fun `save is inert on an unchanged sheet`() {
        setUp(state())

        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, saves)
    }

    @Test
    fun `save fires once something has changed`() {
        setUp(state().withFeedback("ignore snacks"))

        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(1, saves)
    }

    /**
     * Dirty but invalid: there are pending words, so the sheet has something to
     * save, but the caregiver picked "if it lasts too long" without saying how
     * long. Saving that would arm a detector that never fires.
     */
    @Test
    fun `a duration trigger with no time warns and blocks save`() {
        val base = state()
        setUp(
            base.copy(staged = base.staged.copy(alertTrigger = Detector.AlertTrigger.DURATION))
                .withFeedback("ignore snacks"),
        )

        compose.onNodeWithText("Set a time so I know when it's gone on too long.").assertExists()
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, saves)
    }

    /** The field is minutes; the wire is seconds. Reached by tag because with a
     *  duration trigger staged there are two text fields on screen. */
    @Test
    fun `the duration field takes minutes and stages seconds`() {
        val base = state()
        setUp(
            base.copy(
                staged = base.staged.copy(alertTrigger = Detector.AlertTrigger.DURATION),
            ),
        )

        compose.onNodeWithTag(DurationFieldTag).performScrollTo().performTextInput("15")
        assertEquals(900, staged.lastOrNull()?.durationSeconds)
    }

    // -----------------------------------------------------------------------
    // Draft
    // -----------------------------------------------------------------------

    /** Create-as-draft: a new detector lands here off and is turned on deliberately. */
    @Test
    fun `an off detector offers to turn itself on`() {
        setUp(state(detector(isOn = false)))

        compose.onNodeWithText("This detector is off").assertExists()
        compose.onNodeWithText("Turn on").performScrollTo().performClick()
        assertEquals(1, turnOns)
    }

    @Test
    fun `an on detector shows no draft banner`() {
        setUp(state())
        compose.onNodeWithText("This detector is off").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------
    // Preset versus custom
    // -----------------------------------------------------------------------

    /**
     * What a preset looks for is Memoria's, not the caregiver's — the prompt,
     * the refine box and the alert-edge choice are all custom-only. Rooms and
     * the window are NOT: presets stay re-scopable.
     */
    @Test
    fun `a preset hides the prompt, the refine box and the alert-edge chips`() {
        setUp(state(detector(source = Detector.Source.PRESET)))

        compose.onNodeWithText("WHAT YOU ASKED FOR").assertDoesNotExist()
        compose.onNodeWithText("ASK FOR A CHANGE").assertDoesNotExist()
        compose.onNodeWithText("Re-generate").assertDoesNotExist()
        compose.onNodeWithText("When it happens").assertDoesNotExist()
        compose.onNodeWithText("WHERE IT RUNS").assertExists()
        compose.onNodeWithText("WHEN IT RUNS").assertExists()
    }

    @Test
    fun `a custom detector shows the words it was built from and offers the edge`() {
        setUp(state())

        compose.onNodeWithText("tell me if she skips lunch").assertExists()
        compose.onNodeWithText("If it lasts too long").assertExists()
    }

    @Test
    fun `an instant custom detector has no edge to choose`() {
        setUp(state(detector(isSustained = false)))
        compose.onNodeWithText("If it lasts too long").assertDoesNotExist()
    }

    @Test
    fun `past change requests are listed back`() {
        setUp(state(detector(refinements = listOf("don't count snacks"))))

        compose.onNodeWithText("CHANGES YOU'VE ASKED FOR").assertExists()
        compose.onNodeWithText("don't count snacks").assertExists()
    }

    // -----------------------------------------------------------------------
    // Pickers
    // -----------------------------------------------------------------------

    @Test
    fun `tapping a room scopes the detector to it`() {
        setUp(state())

        compose.onNodeWithText("Kitchen").performScrollTo().performClick()

        val edit = staged.lastOrNull()
        assertNotNull(edit)
        assertFalse(edit!!.allRooms)
        assertEquals(setOf("Kitchen"), edit.selectedRooms)
    }

    @Test
    fun `with no cameras paired the rooms card says so`() {
        setUp(state(rooms = emptyList()))

        compose.onNodeWithText("No cameras yet. This runs everywhere once you pair one.")
            .assertExists()
        compose.onNodeWithText("All rooms").assertExists()
    }

    /**
     * A stored `ALWAYS` has no window of its own, so switching to specific hours
     * pre-fills the 08:00-20:00 default. The clock format follows the device, as
     * the iOS picker follows the locale, so either rendering is correct.
     */
    @Test
    fun `picking specific hours reveals the default window`() {
        val base = state()
        setUp(base.copy(staged = base.staged.copy(whenMode = WhenMode.Specific)))

        compose.onNodeWithText("to").assertExists()
        val twelveHour = compose.onAllNodesWithText("8:00 AM").fetchSemanticsNodes().isNotEmpty()
        val twentyFourHour = compose.onAllNodesWithText("08:00").fetchSemanticsNodes().isNotEmpty()
        assertTrue("the window start must render in one of the two clock formats", twelveHour || twentyFourHour)
    }

    /**
     * The old inline row picker only appeared while a detector was ON, so an OFF
     * draft could not be tuned before it was turned on.
     */
    @Test
    fun `sensitivity is editable while the detector is off`() {
        setUp(state(detector(isOn = false)))

        compose.onNodeWithText("High").performScrollTo().performClick()
        assertEquals(Detector.Sensitivity.HIGH, staged.lastOrNull()?.sensitivity)
    }

    @Test
    fun `the category chips stage a change`() {
        setUp(state())

        compose.onNodeWithText("Routine").performScrollTo().performClick()
        assertEquals(Detector.Category.ROUTINE, staged.lastOrNull()?.category)
    }

    // -----------------------------------------------------------------------
    // Compile, caveats, chrome
    // -----------------------------------------------------------------------

    @Test
    fun `a compiled detector with no summary shows the finalizing note`() {
        setUp(state(detector(userCriteria = null)))

        compose.onNodeWithText(
            "Memoria is finalizing this detector's details. Check back in a moment.",
        ).assertExists()
    }

    @Test
    fun `a fine-detail detector carries the expectations notice`() {
        setUp(state(detector(accuracyCaveat = true)))
        compose.onNodeWithText(AccuracyCaveatFull).assertExists()
    }

    @Test
    fun `the failure sentence is rendered verbatim`() {
        setUp(state().copy(error = PREVIEW_FAILURE_MESSAGE))
        compose.onNodeWithText(PREVIEW_FAILURE_MESSAGE).assertExists()
    }

    @Test
    fun `Done dismisses the sheet`() {
        setUp(state())

        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dones)
    }

    // -----------------------------------------------------------------------
    // The sensitivity explainer
    // -----------------------------------------------------------------------

    @Test
    fun `the sensitivity eyebrow carries an explainer affordance`() {
        setUp(state())
        compose.onNodeWithContentDescription("What does sensitivity mean?").assertExists()
    }

    /**
     * Composed directly rather than through the info button, so the assertion
     * does not depend on a modal sheet's animation — the same reason
     * `HelpChatSheet` is reachable on its own.
     */
    @Test
    fun `the sensitivity sheet explains the trade-off and closes`() {
        var dismissed = 0
        compose.setContent { MemoriaTheme { SensitivityInfoSheet(onDismiss = { dismissed++ }) } }

        compose.onNodeWithText(
            "How sure Memoria needs to be before it alerts you. Higher catches more, " +
                "but with more false alarms.",
        ).assertExists()
        compose.onNodeWithText("RECOMMENDED").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissed)
    }
}
