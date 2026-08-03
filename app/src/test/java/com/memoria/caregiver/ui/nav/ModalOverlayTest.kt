package com.memoria.caregiver.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ModalOverlay` — the thing that makes a conditional branch behave like a sheet.
 *
 * iOS gets this for free: a presented `.sheet` covers its presenter and makes it
 * untouchable. In Compose a later sibling in a `Box` is only *drawn* on top, and
 * hit testing walks straight through it wherever the overlay has no pointer
 * handler of its own — so the tab bar behind a form stays tappable and a stray
 * tap switches tabs under a half-filled screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ModalOverlayTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `a tap that misses the overlay's content does not reach what is behind`() {
        var behindTaps = 0
        compose.setContent {
            MemoriaTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable { behindTaps++ },
                    )
                    ModalOverlay(screen = "test_screen") { Text("the form") }
                }
            }
        }

        // The middle of the screen, which the one-line content does not occupy.
        compose.onRoot().performTouchInput { click(Offset(centerX, centerY + 300f)) }
        compose.waitForIdle()

        assertEquals(0, behindTaps)
    }

    @Test
    fun `the overlay's own content still receives its taps`() {
        // The scrim must swallow only what nothing else wanted. If it took
        // everything, the overlay would be a picture rather than a screen.
        var contentTaps = 0
        compose.setContent {
            MemoriaTheme {
                ModalOverlay(screen = "test_screen") {
                    Text("Close", modifier = Modifier.clickable { contentTaps++ })
                }
            }
        }

        compose.onNodeWithText("Close").performClick()

        assertEquals(1, contentTaps)
    }

    @Test
    fun `the content is what is shown`() {
        compose.setContent {
            MemoriaTheme { ModalOverlay(screen = "test_screen") { Text("the form") } }
        }

        compose.onNodeWithText("the form").assertIsDisplayed()
    }

    @Test
    fun `presenting an overlay logs its screen, once`() {
        // The structural half of HARDENING.md H-10: `screen` is a required
        // parameter on the one composable every full-surface overlay goes
        // through, so a screen added to the app cannot ship untracked.
        val analytics = RecordingAnalytics()
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme { ModalOverlay(screen = "camera_detail") { Text("the form") } }
            }
        }
        compose.waitForIdle()

        assertEquals(listOf("camera_detail"), analytics.names("screen"))
    }

    @Test
    fun `presenting a different screen at the same call site logs again`() {
        // The patient stack reuses one call site for six destinations, and the
        // event stack swaps the event under an open detail. Keying the effect on
        // the name rather than on Unit is what stops the second one going
        // unrecorded.
        val analytics = RecordingAnalytics()
        var screen by mutableStateOf("event_detail")
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme { ModalOverlay(screen = screen) { Text("the form") } }
            }
        }
        compose.waitForIdle()
        screen = "event_review"
        compose.waitForIdle()

        assertEquals(listOf("event_detail", "event_review"), analytics.names("screen"))
    }
}
