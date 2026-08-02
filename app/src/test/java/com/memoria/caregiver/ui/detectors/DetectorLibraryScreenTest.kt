package com.memoria.caregiver.ui.detectors

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.data.model.DetectorLibraryEntry
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detection-library behaviour. Spec: DetectorLibraryView.swift, PARITY.md §22.
 *
 * The selection arithmetic is covered by `DetectorLogicTest`; these assert the
 * parts only a composed screen can show — that a locked entry really is inert,
 * that a footer with nothing to add really does not submit, and that the two
 * modes present different chrome. Layout is judged by screenshot
 * (DECISIONS.md D-004).
 */
@RunWith(RobolectricTestRunner::class)
class DetectorLibraryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val fx = DetectorScreenFixtures

    private val entries = listOf(
        fx.entry("fall", name = "Fall", recommended = true),
        fx.entry("wandering", name = "Wandering", recommended = true, alreadyAdded = true),
        fx.entry("stove", name = "Stove left on", accuracyCaveat = true),
    )

    private val toggled = mutableListOf<String>()
    private var submits = 0
    private var retries = 0
    private var leadingActions = 0

    private fun setUp(state: DetectorLibraryState) {
        compose.setContent {
            MemoriaTheme {
                DetectorLibraryScreen(
                    state = state,
                    onQueryChange = {},
                    onToggleEntry = { entry: DetectorLibraryEntry -> toggled += entry.key },
                    onRetry = { retries++ },
                    onLeadingAction = { leadingActions++ },
                    onSubmit = { submits++ },
                )
            }
        }
    }

    private fun loaded(
        mode: DetectorLibraryMode = DetectorLibraryMode.Add,
        selected: Set<String> = emptySet(),
    ) = DetectorLibraryState(
        mode = mode,
        entries = entries,
        selectedKeys = selected,
        isLoading = false,
    )

    /** Re-adding a detector the patient already has would POST a key the server skips. */
    @Test
    fun `an already-added entry renders ADDED and cannot be tapped`() {
        setUp(loaded())
        compose.onNodeWithText("ADDED").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wandering").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `tapping an entry reports its key`() {
        setUp(loaded())
        compose.onNodeWithText("Fall").performScrollTo().performClick()
        assertEquals(listOf("fall"), toggled)
    }

    /** Nothing new picked means nothing to POST, so the footer must be inert. */
    @Test
    fun `the footer does not submit with no new picks`() {
        setUp(loaded(selected = setOf("wandering")))
        compose.onNodeWithText("Add detectors").performClick()
        assertEquals(0, submits)
    }

    @Test
    fun `the footer submits once a new key is picked`() {
        setUp(loaded(selected = setOf("fall")))
        compose.onNodeWithText("Add 1 detector").performClick()
        assertEquals(1, submits)
    }

    /**
     * Onboarding's Continue is always live: unchecking everything and advancing
     * is a legitimate outcome, and the reconcile has deletions to run.
     */
    @Test
    fun `onboarding continue submits even with nothing selected`() {
        setUp(loaded(mode = DetectorLibraryMode.Onboarding))
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, submits)
    }

    @Test
    fun `onboarding leads with Skip and shows the first-run intro`() {
        setUp(loaded(mode = DetectorLibraryMode.Onboarding))
        compose.onNodeWithText("Add a few pre-built detections").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        assertEquals(1, leadingActions)
    }

    @Test
    fun `add mode leads with Cancel and shows no first-run intro`() {
        setUp(loaded())
        assertTrue(
            compose.onAllNodesWithText("Add a few pre-built detections").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, leadingActions)
    }

    /** The sentence from the API reaches the screen unedited. */
    @Test
    fun `a load failure shows the server message over a working retry`() {
        setUp(DetectorLibraryState(isLoading = false, loadError = "The network connection was lost."))
        compose.onNodeWithText("Couldn't load the detection library.").assertIsDisplayed()
        compose.onNodeWithText("The network connection was lost.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    /** Fine-detail entries carry the expectations line inline, at the enroll moment. */
    @Test
    fun `an entry with an accuracy caveat shows the compact notice`() {
        setUp(loaded())
        compose.onNodeWithText(AccuracyCaveatShort).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the standing footer note is always present`() {
        setUp(loaded())
        compose.onNodeWithText("These start watching as soon as you add a camera.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}

