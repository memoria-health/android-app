package com.memoria.caregiver.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
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
                    ModalOverlay { Text("the form") }
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
                ModalOverlay {
                    Text("Close", modifier = Modifier.clickable { contentTaps++ })
                }
            }
        }

        compose.onNodeWithText("Close").performClick()

        assertEquals(1, contentTaps)
    }

    @Test
    fun `the content is what is shown`() {
        compose.setContent { MemoriaTheme { ModalOverlay { Text("the form") } } }

        compose.onNodeWithText("the form").assertIsDisplayed()
    }
}
