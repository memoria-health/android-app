package com.memoria.caregiver.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NemaraButton behavior. Spec: PARITY.md §13, DesignSystem/Components/NemaraButton.swift.
 *
 * The analytics assertions are the important ones. PARITY.md §13 calls this
 * component "the single instrumentation point for the whole app": if logging
 * lives inside the button, analytics coverage cannot rot as screens are added.
 * A port that drops it compiles, renders identically, passes every visual check,
 * and silently ships an app with no button telemetry at all.
 */
@RunWith(RobolectricTestRunner::class)
class NemaraButtonTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp(
        analytics: RecordingAnalytics,
        title: String = "Save",
        track: String? = null,
        enabled: Boolean = true,
        onClick: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    NemaraButton(title = title, track = track, enabled = enabled, onClick = onClick)
                }
            }
        }
    }

    @Test
    fun `logs the title on tap`() {
        val analytics = RecordingAnalytics()
        setUp(analytics, title = "Save")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("Save"), analytics.names("button"))
    }

    /**
     * iOS logs BEFORE running the action, deliberately. Actions routinely
     * navigate away or tear down the composition, so logging afterwards would
     * drop exactly the taps that matter most for a funnel.
     */
    @Test
    fun `logs before invoking the action`() {
        val analytics = RecordingAnalytics()
        setUp(analytics, title = "Save") { analytics.trace += "action" }
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("log:button:Save", "action"), analytics.trace)
    }

    /**
     * The track label exists so analytics survive copy and localization changes
     * and can disambiguate buttons that share a title.
     */
    @Test
    fun `track label overrides the visible title`() {
        val analytics = RecordingAnalytics()
        setUp(analytics, title = "Talk", track = "live.talk_start")
        compose.onNodeWithText("Talk").performClick()
        assertEquals(listOf("live.talk_start"), analytics.names("button"))
    }

    @Test
    fun `falls back to the title when no track label is given`() {
        val analytics = RecordingAnalytics()
        setUp(analytics, title = "Add a detector", track = null)
        compose.onNodeWithText("Add a detector").performClick()
        assertEquals(listOf("Add a detector"), analytics.names("button"))
    }

    /** A disabled button must log nothing — a logged tap that did nothing is a lie in the funnel. */
    @Test
    fun `disabled button neither acts nor logs`() {
        val analytics = RecordingAnalytics()
        var acted = false
        setUp(analytics, title = "Save", enabled = false) { acted = true }
        compose.onNodeWithText("Save").performClick()
        assertTrue(analytics.entries.isEmpty())
        assertEquals(false, acted)
    }

    @Test
    fun `each tap logs once`() {
        val analytics = RecordingAnalytics()
        setUp(analytics, title = "Save")
        repeat(3) { compose.onNodeWithText("Save").performClick() }
        assertEquals(3, analytics.names("button").size)
    }
}
