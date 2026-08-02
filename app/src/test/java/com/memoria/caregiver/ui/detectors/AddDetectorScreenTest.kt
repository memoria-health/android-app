package com.memoria.caregiver.ui.detectors

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.DetectorPlan
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalTime

/**
 * Custom-detector creation. Spec: AddDetectorView.swift, PARITY.md §17.
 *
 * The request bodies and the scope grammar are covered by `DetectorLogicTest`.
 * These pin the two-stage flow itself: that stage 1 cannot be left with an empty
 * prompt, that the inferred fields only exist on stage 2, and that the parts of
 * stage 2 that depend on what was inferred appear or do not.
 */
@RunWith(RobolectricTestRunner::class)
class AddDetectorScreenTest {

    @get:Rule val compose = createComposeRule()

    private var generates = 0
    private var creates = 0
    private var backs = 0
    private var closes = 0
    private val categories = mutableListOf<Detector.Category>()
    private val triggers = mutableListOf<Detector.AlertTrigger>()
    private val days = mutableListOf<Int>()

    private fun setUp(state: AddDetectorState) {
        compose.setContent {
            MemoriaTheme {
                AddDetectorScreen(
                    state = state,
                    onNameChange = {},
                    onNoteChange = {},
                    onPromptChange = {},
                    onAlertTriggerChange = { triggers += it },
                    onCategoryChange = { categories += it },
                    onIconChange = {},
                    onCadenceChange = {},
                    onToggleDay = { days += it },
                    onWindowStartChange = {},
                    onWindowEndChange = {},
                    onDeadlineChange = {},
                    onGenerate = { generates++ },
                    onCreate = { creates++ },
                    onBackToEntry = { backs++ },
                    onClose = { closes++ },
                )
            }
        }
    }

    private val plan = DetectorPlan(
        watches = "the kitchen counter",
        fires = "as soon as the pills are taken",
        checks = "every weekday between 11am and 5pm",
        listens = true,
    )

    private fun review(
        trigger: Detector.AlertTrigger = Detector.AlertTrigger.LEADING,
        isSustained: Boolean = false,
        plan: DetectorPlan? = this.plan,
        timesUncertain: Boolean = false,
    ) = AddDetectorState(
        stage = AddDetectorStage.Review,
        name = "Medication check",
        prompt = "check the pills",
        alertTrigger = trigger,
        isSustained = isSustained,
        plan = plan,
        timesUncertain = timesUncertain,
        windowStart = LocalTime.of(11, 0),
        windowEnd = LocalTime.of(17, 0),
    )

    // ---- stage 1 -----------------------------------------------------------

    /** Nothing to infer from means the round trip would fail server-side. */
    @Test
    fun `generate is inert until the prompt has words in it`() {
        setUp(AddDetectorState(prompt = "   "))
        compose.onNodeWithText("✦ Generate").performClick()
        assertEquals(0, generates)
    }

    @Test
    fun `generate fires once the prompt is filled`() {
        setUp(AddDetectorState(prompt = "check the pills"))
        compose.onNodeWithText("✦ Generate").performClick()
        assertEquals(1, generates)
    }

    @Test
    fun `stage one shows none of the inferred fields`() {
        setUp(AddDetectorState(prompt = "check the pills"))
        assertTrue(compose.onAllNodesWithText("CATEGORY").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("HOW IT ALERTS").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the error from the server is shown verbatim`() {
        setUp(AddDetectorState(prompt = "x", error = "The request timed out."))
        compose.onNodeWithText("The request timed out.").assertIsDisplayed()
    }

    // ---- stage 2 -----------------------------------------------------------

    /** The plan is the whole point of the review stage: behaviour before it exists. */
    @Test
    fun `the review stage previews the compiled behaviour in plain words`() {
        setUp(review())
        compose.onNodeWithText("WHAT THIS WILL DO").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("the kitchen counter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Also listens, not just watches").performScrollTo().assertIsDisplayed()
    }

    /** A null plan means preview composition failed; an empty card would read as "nothing". */
    @Test
    fun `a missing plan hides the preview card entirely`() {
        setUp(review(plan = null))
        assertTrue(compose.onAllNodesWithText("WHAT THIS WILL DO").fetchSemanticsNodes().isEmpty())
    }

    /**
     * An instant detector has exactly one moment, so it gets a sentence rather
     * than a choice it cannot meaningfully make.
     */
    @Test
    fun `an instant detector describes its trigger instead of offering a choice`() {
        setUp(review(trigger = Detector.AlertTrigger.LEADING, isSustained = false))
        compose.onNodeWithText(
            "Alerts the moment it happens. Fires as soon as it's seen.",
        ).performScrollTo().assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Alerts when the activity's finished").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `a sustained detector offers all three edges and reports the pick`() {
        setUp(review(isSustained = true))
        compose.onNodeWithText("Alerts once it's gone on too long").performScrollTo().performClick()
        assertEquals(listOf(Detector.AlertTrigger.DURATION), triggers)
    }

    /** The absence schedule is the one editor the other triggers must not show. */
    @Test
    fun `only an absence detector gets the schedule editor`() {
        setUp(review(trigger = Detector.AlertTrigger.LEADING))
        assertTrue(compose.onAllNodesWithText("ALERT IF NOT SEEN BY").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `an absence detector gets the window pickers`() {
        setUp(review(trigger = Detector.AlertTrigger.ABSENCE))
        compose.onNodeWithText("BETWEEN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ALERT IF NOT SEEN BY").performScrollTo().assertIsDisplayed()
        // Display follows the device clock; the serialized scope is always AM/PM.
        compose.onNodeWithText("11:00 AM").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an uncertain inference asks the caregiver to confirm the times`() {
        setUp(review(trigger = Detector.AlertTrigger.ABSENCE, timesUncertain = true))
        compose.onNodeWithText("Memoria wasn't sure of the exact times — please confirm them.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `picking a category reports it`() {
        setUp(review())
        compose.onNodeWithText("Routine").performScrollTo().performClick()
        assertEquals(listOf(Detector.Category.ROUTINE), categories)
    }

    /** The picker shows the caregiver label, never the stored symbol name. */
    @Test
    fun `the icon picker shows a human label`() {
        setUp(review().copy(icon = "figure.walk.motion"))
        compose.onNodeWithText("Walking").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("figure.walk.motion").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the review footer creates and can go back to edit`() {
        setUp(review())
        compose.onNodeWithText("← Edit").performClick()
        assertEquals(1, backs)
        compose.onNodeWithText("Create detector").performClick()
        assertEquals(1, creates)
    }
}
