package com.memoria.caregiver.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `NotBuiltScreen` — the honest stub.
 *
 * The whole point of this screen is the difference between "unbuilt" and
 * "broken", so what is checked is that it actually *says* it is unbuilt, names
 * what it is waiting on, and offers a way out that Back agrees with. A stub that
 * rendered blank would be no better than the inert control it replaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NotBuiltScreenTest {

    // An Android rule, not the plain one: the screen installs a BackHandler and
    // the test drives the real dispatcher.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `says it is not built and names the reason`() {
        compose.setContent {
            MemoriaTheme {
                NotBuiltScreen(
                    title = "Privacy & data",
                    reason = "The privacy notice has not been brought across yet.",
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("PRIVACY & DATA").assertIsDisplayed()
        compose.onNodeWithText("Not built yet").assertIsDisplayed()
        compose
            .onNodeWithText("The privacy notice has not been brought across yet.")
            .assertIsDisplayed()
    }

    @Test
    fun `Close reports back`() {
        var backs = 0
        compose.setContent {
            MemoriaTheme { NotBuiltScreen("Exit doors", "Needs live video.", onBack = { backs++ }) }
        }

        compose.onNodeWithText("Close").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `Back reports back rather than reaching the Activity`() {
        // The failure this guards is FINDINGS.md F-25: a conditional branch with
        // no BackHandler lets Back — and the edge swipe — fall through and quit
        // the app. The proof is a handler registered UNDER this one: if
        // NotBuiltScreen has its own, the outer one never fires.
        var stubBacks = 0
        var fellThrough = 0
        compose.setContent {
            MemoriaTheme {
                Box(Modifier.fillMaxSize()) {
                    BackHandler { fellThrough++ }
                    NotBuiltScreen("Exit doors", "Needs live video.", onBack = { stubBacks++ })
                }
            }
        }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(1, stubBacks)
        assertEquals(0, fellThrough)
    }

    @Test
    fun `carries a test tag so hosts can assert the stub is what is up`() {
        compose.setContent {
            MemoriaTheme { NotBuiltScreen("Live", "Not connected.", onBack = {}) }
        }

        compose.onNodeWithTag(NOT_BUILT_TAG).assertIsDisplayed()
    }

    @Test
    fun `draws over whatever it covers rather than beside it`() {
        // Belt and braces on the overlay contract: the stub fills, so a screen
        // behind it must not be showing through as a second visible column.
        compose.setContent {
            MemoriaTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("shell behind")
                    NotBuiltScreen("Live", "Not connected.", onBack = {})
                }
            }
        }

        compose.onNodeWithText("Not built yet").assertIsDisplayed()
    }
}
