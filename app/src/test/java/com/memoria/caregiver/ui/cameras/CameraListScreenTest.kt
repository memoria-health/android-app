package com.memoria.caregiver.ui.cameras

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CameraListScreen behavior. Spec: PatientCamerasView.swift.
 *
 * What a caregiver would notice and a refactor could quietly break: that an
 * empty home still offers the way to pair one, that a row leads to the right
 * camera, and that a row whose pairing was cleared says so instead of sitting
 * there looking merely offline. Layout is judged by screenshot, per
 * DECISIONS.md D-004, so it is not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class CameraListScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp(
        state: CameraListState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onSelectCamera: (String) -> Unit = {},
        onAddSensor: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    CameraListScreen(
                        state = state,
                        onSelectCamera = onSelectCamera,
                        onAddSensor = onAddSensor,
                    )
                }
            }
        }
    }

    // ---- Empty state -------------------------------------------------------

    @Test
    fun `an empty home explains itself and still offers pairing`() {
        setUp(CameraListState())

        compose.onNodeWithText("No sensors yet").assertIsDisplayed()
        compose.onNodeWithText(
            "Pair a Memoria Sensor from Add a sensor to start monitoring.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Add a sensor").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `adding a sensor reports the tap`() {
        var adds = 0
        setUp(CameraListState(), onAddSensor = { adds++ })

        compose.onNodeWithText("Add a sensor").performScrollTo().performClick()

        assertEquals(1, adds)
    }

    // ---- Rows --------------------------------------------------------------

    @Test
    fun `each row leads to its own camera`() {
        val selected = mutableListOf<String>()
        setUp(
            CameraListState(
                cameras = listOf(
                    testCamera(id = "cam-a", room = "Kitchen", displayId = 1),
                    testCamera(id = "cam-b", room = "Bedroom", displayId = 2),
                ),
            ),
            onSelectCamera = { selected += it },
        )

        compose.onNodeWithText("Bedroom").performScrollTo().performClick()

        assertEquals(listOf("cam-b"), selected)
    }

    @Test
    fun `the header counts the paired sensors`() {
        setUp(CameraListState(cameras = listOf(testCamera(), testCamera(id = "cam-b"))))

        compose.onNodeWithText("SENSORS · 2 PAIRED").assertIsDisplayed()
    }

    @Test
    fun `a row carries its status line`() {
        setUp(CameraListState(cameras = listOf(testCamera(displayId = 3, online = false))))

        compose.onNodeWithText("SENSOR 03 · OFFLINE").assertIsDisplayed()
    }

    /**
     * A cleared pairing has to look different from an ordinary offline row: the
     * fix is different (re-pair, not wait), and it is one tap away.
     */
    @Test
    fun `an unpaired row shows only the unpaired chip`() {
        setUp(
            CameraListState(
                cameras = listOf(testCamera(paired = false, disabled = true, aiPaused = true)),
            ),
        )

        compose.onNodeWithText("UNPAIRED").assertIsDisplayed()
        compose.onNodeWithText("HIDDEN").assertDoesNotExist()
        compose.onNodeWithText("AI PAUSED").assertDoesNotExist()
    }

    /**
     * The disconnected state is a bare dot, so its meaning lives entirely in the
     * accessibility label iOS attaches to it.
     */
    @Test
    fun `a disconnected row labels its dot for screen readers`() {
        setUp(
            CameraListState(
                cameras = listOf(
                    testCamera(desiredState = Camera.DesiredState.DISCONNECTED),
                ),
            ),
        )

        compose.onNodeWithContentDescription("Disconnected").assertExists()
    }
}
