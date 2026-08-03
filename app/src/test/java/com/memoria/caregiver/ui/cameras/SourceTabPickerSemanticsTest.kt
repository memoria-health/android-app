@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.ExperimentalTime

/**
 * The sensor-type segmented control's semantics. Spec: FINDINGS.md F-31,
 * `AddCameraSheet.swift`, which uses a real `Picker("Sensor type", selection:)
 * .pickerStyle(.segmented)`.
 *
 * A SwiftUI Picker gives VoiceOver three things for free: the control's name,
 * the role, and which segment is chosen. The hand-built Compose version had
 * none of them — two Boxes with a background colour and a tap. A caregiver
 * using a screen reader heard two labels and could not tell which one the sheet
 * was showing, on the screen that decides what kind of sensor they are adding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SourceTabPickerSemanticsTest {

    @get:Rule val compose = createComposeRule()

    private var chosen: AddCameraTab? = null

    private fun setUp(state: AddCameraState = AddCameraState()) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    AddCameraScreen(
                        state = state,
                        onTabChange = { chosen = it },
                        onRoomChange = {},
                        onIconChange = {},
                        onGenerate = {},
                        onRetry = {},
                        onDone = {},
                        sensorSetup = { Text("SENSOR SETUP SLOT") },
                    )
                }
            }
        }
    }

    private fun isTab() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The name iOS's Picker publishes, which had no counterpart at all. */
    @Test
    fun `the control names itself`() {
        setUp()
        compose.onNodeWithContentDescription("Sensor type").assertExists()
    }

    @Test
    fun `each segment announces as a tab`() {
        setUp()
        compose.onNodeWithText(AddCameraTab.Phone.label).assert(isTab())
        compose.onNodeWithText(AddCameraTab.Sensor.label).assert(isTab())
    }

    /**
     * The whole point. Selection was drawn as a card-coloured fill and nothing
     * else, so it did not exist for anyone not looking at it.
     */
    @Test
    fun `the chosen segment is the selected one`() {
        setUp(AddCameraState(tab = AddCameraTab.Phone))
        compose.onNodeWithText(AddCameraTab.Phone.label).assertIsSelected()
        compose.onNodeWithText(AddCameraTab.Sensor.label).assertIsNotSelected()
    }

    @Test
    fun `selection follows the state, not the tap order`() {
        setUp(AddCameraState(tab = AddCameraTab.Sensor))
        compose.onNodeWithText(AddCameraTab.Sensor.label).assertIsSelected()
        compose.onNodeWithText(AddCameraTab.Phone.label).assertIsNotSelected()
    }

    @Test
    fun `tapping a segment still reports the choice`() {
        setUp(AddCameraState(tab = AddCameraTab.Phone))
        compose.onNodeWithText(AddCameraTab.Sensor.label).performClick()
        assertEquals(AddCameraTab.Sensor, chosen)
    }
}
