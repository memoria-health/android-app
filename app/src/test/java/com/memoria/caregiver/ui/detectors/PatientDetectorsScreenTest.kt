package com.memoria.caregiver.ui.detectors

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PatientDetectorsScreen] behaviour. Spec: `PatientDetectorsView.swift`,
 * FINDINGS.md F-14.
 *
 * What a caregiver would notice and a refactor could quietly break: that a
 * patient with nothing configured still learns what a detector is and how to add
 * one, that a detector goes under the right heading, that an empty group says so
 * instead of vanishing, and that a row's toggle and menu act on that row's own
 * detector. Layout is judged by screenshot per DECISIONS.md D-004, so it is not
 * asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class PatientDetectorsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val fx = DetectorScreenFixtures

    private val toggles = mutableListOf<Pair<String, Boolean>>()
    private val edits = mutableListOf<String>()
    private val deletes = mutableListOf<String>()
    private var addCustom = 0
    private var openLibrary = 0
    private var doorLoads = 0

    /** A preset that is NOT wandering, so no row fetches exit doors by accident. */
    private fun preset(name: String, id: String = name, isOn: Boolean = true) =
        fx.detector(id = id, name = name, identifier = "fall", isOn = isOn)

    private fun custom(name: String, id: String = name, isOn: Boolean = true) =
        preset(name = name, id = id, isOn = isOn).copy(source = Detector.Source.CUSTOM)

    private fun setUp(
        state: PatientDetectorsState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    PatientDetectorsScreen(
                        state = state,
                        onAddCustomDetector = { addCustom++ },
                        onOpenLibrary = { openLibrary++ },
                        onToggleDetector = { detector, isOn -> toggles += detector.id to isOn },
                        onEditDetector = { edits += it.id },
                        onDeleteDetector = { deletes += it.id },
                        onLoadDoorConfigs = { doorLoads++ },
                    )
                }
            }
        }
    }

    // ---- Empty state -------------------------------------------------------

    @Test
    fun `a patient with no detectors explains itself and still offers both ways in`() {
        setUp(PatientDetectorsState())

        compose.onNodeWithText("No detectors yet").assertIsDisplayed()
        compose.onNodeWithText(
            "Add a custom detector to describe in plain language what Memoria should watch for.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Add a custom detector").assertIsDisplayed()
        compose.onNodeWithText("Add from detection library").assertIsDisplayed()
    }

    /**
     * The empty card is the whole body when there is nothing configured, so the
     * grouped headings must not be sitting under it.
     */
    @Test
    fun `the empty card replaces the sections rather than joining them`() {
        setUp(PatientDetectorsState())

        assertEquals(0, compose.onAllNodesWithText("BUILT-IN").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("DETECTORS").fetchSemanticsNodes().size)
    }

    @Test
    fun `each add button reports its own tap`() {
        setUp(PatientDetectorsState())

        compose.onNodeWithText("Add a custom detector").performScrollTo().performClick()
        compose.onNodeWithText("Add from detection library").performScrollTo().performClick()

        assertEquals(1, addCustom)
        assertEquals(1, openLibrary)
    }

    // ---- Grouping ----------------------------------------------------------

    @Test
    fun `built-in and custom detectors land under their own headings`() {
        setUp(
            PatientDetectorsState(
                detectors = listOf(preset("Fall"), custom("Kettle left on")),
            ),
        )

        compose.onNodeWithText("BUILT-IN").assertIsDisplayed()
        compose.onNodeWithText("CUSTOM").assertIsDisplayed()
        compose.onNodeWithText("Fall").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kettle left on").performScrollTo().assertIsDisplayed()
    }

    /**
     * A caregiver who has only ever used the library still needs to learn the
     * custom group exists, and how to fill it.
     */
    @Test
    fun `a group with nothing in it says so instead of vanishing`() {
        setUp(PatientDetectorsState(detectors = listOf(preset("Fall"))))

        compose.onNodeWithText("None yet. Add a custom detector with the button above.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a patient with only custom detectors gets the built-in hint`() {
        setUp(PatientDetectorsState(detectors = listOf(custom("Kettle left on"))))

        compose.onNodeWithText("No built-in detectors yet.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the header chip counts what is on across both groups`() {
        setUp(
            PatientDetectorsState(
                detectors = listOf(
                    preset("Fall", isOn = true),
                    preset("Wandering", isOn = false),
                    custom("Kettle left on", isOn = true),
                ),
            ),
        )

        // NemaraChip uppercases whatever it is handed, on both platforms, so the
        // state's "2 active" reaches the screen as "2 ACTIVE".
        compose.onNodeWithText("2 ACTIVE").performScrollTo().assertIsDisplayed()
    }

    // ---- Rows act on their own detector -------------------------------------

    /**
     * Three rows across both cards, and the one acted on is neither the first of
     * the list nor the first of its own section — a callback wired to either is
     * exactly the mistake these three catch.
     */
    private fun threeRows() = PatientDetectorsState(
        detectors = listOf(
            preset("Fall", isOn = true),
            custom("Kettle", isOn = true),
            custom("Front step", isOn = false),
        ),
    )

    @Test
    fun `a toggle reports its own detector and the new value`() {
        setUp(threeRows())

        // Index 2 is the last custom row, which is off. NemaraToggle is
        // `clickable(role = Role.Switch)` rather than material3's Switch, so it
        // carries a Role and not a ToggleableState.
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))[2]
            .performScrollTo().performClick()

        assertEquals(listOf("Front step" to true), toggles)
    }

    @Test
    fun `edit opens the detail for the row it was tapped on`() {
        setUp(threeRows())

        compose.onAllNodesWithTag(DetectorRowMenuTestTag)[2].performScrollTo().performClick()
        compose.onNodeWithText("Edit detector").performClick()

        assertEquals(listOf("Front step"), edits)
    }

    @Test
    fun `a confirmed delete reports the row it was tapped on`() {
        setUp(threeRows())

        compose.onAllNodesWithTag(DetectorRowMenuTestTag)[2].performScrollTo().performClick()
        compose.onNodeWithText("Delete detector").performClick()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf("Front step"), deletes)
    }

    /**
     * The wandering row's hint is only honest once the exit doors are known, so
     * composing the list has to be what starts that fetch.
     */
    @Test
    fun `the wandering row asks for the exit-door configs`() {
        setUp(
            PatientDetectorsState(
                detectors = listOf(
                    preset("Fall"),
                    fx.detector(id = "w", name = "Left the house", isOn = true),
                ),
            ),
        )

        assertEquals(1, doorLoads)
    }
}
