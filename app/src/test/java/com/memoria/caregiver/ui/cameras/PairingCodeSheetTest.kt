@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * PairingCodeSheet behavior. Spec: PairingCodeSheet.swift.
 *
 * The sheet has one job — put six digits and three steps in front of someone
 * standing at a wall phone — so the tests are about what survives each of the
 * three outcomes. The instructions in particular are load-bearing: they are the
 * only place the flow is explained, and they must be there while the code is
 * still loading, not just once it lands.
 *
 * There is no auto-dismiss here, unlike the add-sensor sheet. That asymmetry is
 * iOS's and is pinned below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PairingCodeSheetTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp(
        state: PairingCodeState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onClose: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    PairingCodeSheet(state = state, onClose = onClose)
                }
            }
        }
    }

    private val ready = PairingCodeState(
        room = "Living room",
        phase = PairingCodePhase.Ready(
            code = "204817",
            expiresAt = currentInstant() + 5.minutes,
        ),
    )

    // ---- Loading -----------------------------------------------------------

    /**
     * The header, the instructions and Close are outside the swapped content, so
     * the caregiver can read what to do while the code is still being minted.
     */
    @Test
    fun `the sheet explains itself before the code arrives`() {
        setUp(PairingCodeState(room = "Living room"))

        compose.onNodeWithText("PAIR A NEW PHONE").assertExists()
        compose.onNodeWithText("for Living room").assertExists()
        compose.onNodeWithText("Open MemoriaSensor on the wall phone.").assertExists()
        compose.onNodeWithText("Close").assertExists()
        compose.onNodeWithText("Expires in", substring = true).assertDoesNotExist()
    }

    // ---- Ready -------------------------------------------------------------

    @Test
    fun `the code is shown with a countdown and all three steps`() {
        setUp(ready)

        compose.onNodeWithText("204817").assertExists()
        compose.onNodeWithText("Expires in", substring = true).assertExists()
        compose.onNodeWithText("Open MemoriaSensor on the wall phone.").assertExists()
        compose.onNodeWithText("Enter the code above when prompted.").assertExists()
        compose.onNodeWithText("The camera will appear here once it's paired.").assertExists()
    }

    /** This sheet's expiry copy carries an em dash; the add-sensor sheet's does not. */
    @Test
    fun `an expired code says so in this sheet's own words`() {
        setUp(
            PairingCodeState(
                room = "Living room",
                phase = PairingCodePhase.Ready(
                    code = "204817",
                    expiresAt = currentInstant() - 30.seconds,
                ),
            ),
        )

        compose.onNodeWithText("Code expired — close and try again.").assertExists()
    }

    // ---- Failure -----------------------------------------------------------

    @Test
    fun `a failed mint shows the server's own sentence and no code`() {
        setUp(
            PairingCodeState(
                room = "Living room",
                phase = PairingCodePhase.Error("The server isn't ready yet (camera_busy)."),
            ),
        )

        compose.onNodeWithText("The server isn't ready yet (camera_busy).").assertExists()
        compose.onNodeWithText("Expires in", substring = true).assertDoesNotExist()
        // Close is the only way out; there is no retry on this sheet.
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Close").assertExists()
    }

    // ---- Close -------------------------------------------------------------

    @Test
    fun `close is reported`() {
        var closes = 0
        setUp(ready, onClose = { closes++ })

        compose.onNodeWithText("Close").performClick()

        assertEquals(1, closes)
    }

    // ---- Analytics ---------------------------------------------------------

    /**
     * `.trackScreen("pairing_code")` is attached where this sheet is presented,
     * so the screen event belongs to the presenter — the same split
     * CameraDetailScreen records.
     */
    @Test
    fun `the sheet reports no screen of its own`() {
        val analytics = RecordingAnalytics()
        setUp(ready, analytics = analytics)
        compose.waitForIdle()

        assertEquals(emptyList<String>(), analytics.names("screen"))
    }
}
