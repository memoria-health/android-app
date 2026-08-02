package com.memoria.caregiver.ui.detectors

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detector-row behaviour. Spec: DetectorRow.swift, PARITY.md §21.
 *
 * The wandering-hint copy and the where·when label are derived in
 * `DetectorLogicTest`; these check that the row surfaces them, that the toggle
 * reports the new value under the tracked name, and that a delete cannot happen
 * without the confirmation.
 */
@RunWith(RobolectricTestRunner::class)
class DetectorRowTest {

    @get:Rule val compose = createComposeRule()

    private val fx = DetectorScreenFixtures

    private val toggles = mutableListOf<Boolean>()
    private var edits = 0
    private var deletes = 0
    private var doorLoads = 0

    private fun setUp(
        state: DetectorRowState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    DetectorRow(
                        state = state,
                        onToggleOn = { toggles += it },
                        onEdit = { edits++ },
                        onDelete = { deletes++ },
                        onLoadDoorConfigs = { doorLoads++ },
                    )
                }
            }
        }
    }

    /** A toggle logged with its old value reads backwards in the funnel. */
    @Test
    fun `the toggle reports and logs the new value`() {
        val analytics = RecordingAnalytics()
        setUp(DetectorRowState(fx.detector(isOn = true, identifier = "fall")), analytics)
        // NemaraToggle is built from `clickable(role = Role.Switch)` rather than
        // material3's Switch, so it carries a Role and not a ToggleableState —
        // `isToggleable()` would find nothing.
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertEquals(listOf(false), toggles)
        assertEquals(
            listOf(RecordingAnalytics.Entry("toggle", "detector.toggle", "off")),
            analytics.entries,
        )
    }

    /** "On" does not mean "watching" until that sensor's exit doors are set. */
    @Test
    fun `wandering with no exit doors says it is not active yet`() {
        setUp(DetectorRowState(fx.detector(isOn = true)))
        compose.onNodeWithText(
            "Not active yet. Open a sensor in the Sensors tab and set its exit doors to turn wandering on",
        ).assertIsDisplayed()
        assertEquals(1, doorLoads)
    }

    @Test
    fun `wandering with a configured sensor says how many are active`() {
        setUp(
            DetectorRowState(
                detector = fx.detector(isOn = true),
                doorConfigs = mapOf("cam" to Detector.DoorConfig(doors = listOf(fx.door()))),
            ),
        )
        compose.onNodeWithText("Active on 1 sensor · set exit doors per sensor in the Sensors tab")
            .assertIsDisplayed()
    }

    @Test
    fun `a non-wandering detector never shows the hint and never fetches doors`() {
        setUp(DetectorRowState(fx.detector(isOn = true, identifier = "fall")))
        assertTrue(
            compose.onAllNodesWithText(
                "Not active yet. Open a sensor in the Sensors tab and set its exit doors to turn wandering on",
            ).fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(0, doorLoads)
    }

    @Test
    fun `the where and when pill reads from the detector`() {
        setUp(
            DetectorRowState(
                fx.detector(whereRooms = listOf("Kitchen", "Hallway"), whenScope = "ALWAYS"),
            ),
        )
        compose.onNodeWithText("Kitchen, Hallway").assertIsDisplayed()
        compose.onNodeWithText("Always").assertIsDisplayed()
    }

    @Test
    fun `a fine-detail detector carries the compact accuracy notice`() {
        setUp(DetectorRowState(fx.detector(accuracyCaveat = true)))
        compose.onNodeWithText(AccuracyCaveatShort).assertIsDisplayed()
    }

    /**
     * Delete is destructive and irreversible from the row, so it is gated behind
     * the confirmation. Reaching the menu item must not be enough.
     */
    @Test
    fun `deleting needs the confirmation`() {
        setUp(DetectorRowState(fx.detector(name = "Left the house")))
        compose.onNodeWithTag(DetectorRowMenuTestTag).performClick()
        compose.onNodeWithText("Delete detector").performClick()
        assertEquals(0, deletes)

        compose.onNodeWithText("Delete \"Left the house\"?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun `edit opens straight from the menu`() {
        setUp(DetectorRowState(fx.detector()))
        compose.onNodeWithTag(DetectorRowMenuTestTag).performClick()
        compose.onNodeWithText("Edit detector").performClick()
        assertEquals(1, edits)
    }
}
