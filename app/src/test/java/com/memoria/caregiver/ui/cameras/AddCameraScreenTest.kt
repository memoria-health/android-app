@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * AddCameraScreen behavior. Spec: AddCameraSheet.swift.
 *
 * Two things are worth pinning here. The first is the gate on Generate code: a
 * pairing code is minted server-side and inserts a real `cameras` row, so a tap
 * with no room name would leave an unnamed placeholder in the caregiver's list.
 * The second is that the sheet closes itself when the wall phone pairs — that is
 * the whole payoff of the flow, and it is invisible until it does not happen.
 *
 * The countdown's formatting is tested as pure logic rather than through the
 * composition, because rendering it means reading the wall clock and asserting
 * on "4:59 or 5:00 depending on when the test ran" is not a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AddCameraScreenTest {

    @get:Rule val compose = createComposeRule()

    /** Stands in for the Pi wizard this port does not build. */
    private val sensorSetupMarker = "SENSOR SETUP SLOT"

    private fun setUp(
        state: AddCameraState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onTabChange: (AddCameraTab) -> Unit = {},
        onRoomChange: (String) -> Unit = {},
        onIconChange: (String) -> Unit = {},
        onGenerate: () -> Unit = {},
        onRetry: () -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    AddCameraScreen(
                        state = state,
                        onTabChange = onTabChange,
                        onRoomChange = onRoomChange,
                        onIconChange = onIconChange,
                        onGenerate = onGenerate,
                        onRetry = onRetry,
                        onDone = onDone,
                        sensorSetup = { Text(sensorSetupMarker) },
                    )
                }
            }
        }
    }

    // ---- Generate code -----------------------------------------------------

    @Test
    fun `a code cannot be minted without a room name`() {
        var generates = 0
        setUp(AddCameraState(), onGenerate = { generates++ })

        compose.onNodeWithText("Generate code").performClick()

        assertEquals(0, generates)
    }

    /**
     * iOS trims before both the disable check and the request, so spaces are not
     * a room name.
     */
    @Test
    fun `a room of spaces is not a room name`() {
        var generates = 0
        setUp(AddCameraState(room = "   "), onGenerate = { generates++ })

        compose.onNodeWithText("Generate code").performClick()

        assertEquals(0, generates)
    }

    @Test
    fun `a named room mints a code`() {
        var generates = 0
        setUp(AddCameraState(room = "Living room"), onGenerate = { generates++ })

        compose.onNodeWithText("Generate code").performClick()

        assertEquals(1, generates)
    }

    @Test
    fun `the room name is reported as it is typed`() {
        val typed = mutableListOf<String>()
        setUp(AddCameraState(), onRoomChange = { typed += it })

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Kitchen")

        assertEquals(listOf("Kitchen"), typed)
    }

    @Test
    fun `choosing an icon reports it`() {
        val chosen = mutableListOf<String>()
        setUp(AddCameraState(), onIconChange = { chosen += it })

        compose.onNodeWithContentDescription("bed.double").performClick()

        assertEquals(listOf("bed.double"), chosen)
    }

    // ---- The code ----------------------------------------------------------

    @Test
    fun `the minted code is shown with its room and a live countdown`() {
        var dones = 0
        setUp(
            AddCameraState(
                room = "Living room",
                phase = PhonePairingPhase.Showing(
                    code = "483920",
                    expiresAt = currentInstant() + 5.minutes,
                ),
            ),
            onDone = { dones++ },
        )

        compose.onNodeWithText("for Living room").assertExists()
        compose.onNodeWithText("483920").assertExists()
        compose.onNodeWithText("Expires in", substring = true).assertExists()
        // The form is gone; the four states are exclusive.
        compose.onNodeWithText("Generate code").assertDoesNotExist()

        compose.onNodeWithText("Done").performClick()

        assertEquals(1, dones)
    }

    @Test
    fun `an expired code says so instead of counting down`() {
        setUp(
            AddCameraState(
                room = "Living room",
                phase = PhonePairingPhase.Showing(
                    code = "483920",
                    expiresAt = currentInstant() - 60.seconds,
                ),
            ),
        )

        compose.onNodeWithText("Code expired, close and try again.").assertExists()
    }

    /**
     * The payoff of the flow: the caregiver types the code into the wall phone
     * and this sheet gets out of the way on its own.
     */
    @Test
    fun `the sheet closes itself the moment its camera pairs`() {
        var dones = 0
        setUp(
            AddCameraState(
                room = "Living room",
                phase = PhonePairingPhase.Showing(
                    code = "483920",
                    expiresAt = currentInstant() + 5.minutes,
                ),
                generatedCameraPaired = true,
            ),
            onDone = { dones++ },
        )
        compose.waitForIdle()

        assertEquals(1, dones)
    }

    @Test
    fun `an unpaired camera leaves the sheet open`() {
        var dones = 0
        setUp(
            AddCameraState(
                room = "Living room",
                phase = PhonePairingPhase.Showing(
                    code = "483920",
                    expiresAt = currentInstant() + 5.minutes,
                ),
            ),
            onDone = { dones++ },
        )
        compose.waitForIdle()

        assertEquals(0, dones)
    }

    // ---- Failure -----------------------------------------------------------

    @Test
    fun `a failed request shows the server's own sentence over a retry`() {
        var retries = 0
        setUp(
            AddCameraState(
                room = "Living room",
                phase = PhonePairingPhase.Error("You're offline."),
            ),
            onRetry = { retries++ },
        )

        compose.onNodeWithText("You're offline.").assertExists()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    // ---- The two device classes -------------------------------------------

    @Test
    fun `the sensor tab is offered and reported`() {
        val tabs = mutableListOf<AddCameraTab>()
        setUp(AddCameraState(), onTabChange = { tabs += it })

        compose.onNodeWithText("Memoria™ Sensor").performClick()

        assertEquals(listOf(AddCameraTab.Sensor), tabs)
    }

    /**
     * The Pi wizard is not part of this port (QUESTIONS.md Q5), so the screen
     * must hand its tab straight to the slot rather than render a phone form
     * that would mint the wrong kind of pairing.
     */
    @Test
    fun `the sensor tab renders the wizard slot and nothing of the phone flow`() {
        setUp(AddCameraState(tab = AddCameraTab.Sensor))

        compose.onNodeWithText(sensorSetupMarker).assertExists()
        compose.onNodeWithText("Generate code").assertDoesNotExist()
        compose.onNodeWithText("e.g. Living room").assertDoesNotExist()
    }

    // ---- Analytics ---------------------------------------------------------

    /**
     * `.trackScreen("add_camera")` is attached where the sheet is presented, so
     * the phone tab reports nothing of its own; `.trackScreen("sensor_setup")`
     * is attached inside the Swift file, so the sensor tab does.
     */
    @Test
    fun `the phone tab reports no screen of its own`() {
        val analytics = RecordingAnalytics()
        setUp(AddCameraState(), analytics = analytics)
        compose.waitForIdle()

        assertEquals(emptyList<String>(), analytics.names("screen"))
    }

    @Test
    fun `the sensor tab reports the setup screen`() {
        val analytics = RecordingAnalytics()
        setUp(AddCameraState(tab = AddCameraTab.Sensor), analytics = analytics)
        compose.waitForIdle()

        assertEquals(listOf("sensor_setup"), analytics.names("screen"))
    }

    // ---- Countdown formatting (pure) ---------------------------------------

    private val fixedNow = Instant.fromEpochSeconds(1_780_000_000L)

    @Test
    fun `the countdown reads minutes and zero-padded seconds`() {
        assertEquals(
            "Expires in 4:07",
            countdownText(fixedNow + 247.seconds, fixedNow, AddCameraExpiredText),
        )
        assertEquals(
            "Expires in 0:09",
            countdownText(fixedNow + 9.seconds, fixedNow, AddCameraExpiredText),
        )
        assertEquals(
            "Expires in 10:00",
            countdownText(fixedNow + 600.seconds, fixedNow, AddCameraExpiredText),
        )
    }

    /** iOS truncates toward zero, so 4.9s left reads 0:04 on both platforms. */
    @Test
    fun `a partial second is truncated rather than rounded`() {
        assertEquals(
            "Expires in 0:04",
            countdownText(fixedNow + 4_900.milliseconds, fixedNow, AddCameraExpiredText),
        )
    }

    /**
     * The two sheets word this differently in the Swift — a comma on one, an em
     * dash on the other — and copy ports verbatim, so the difference is pinned
     * rather than tidied away.
     */
    @Test
    fun `an expired code carries each sheet's own wording`() {
        assertEquals(
            "Code expired, close and try again.",
            countdownText(fixedNow, fixedNow, AddCameraExpiredText),
        )
        assertEquals(
            "Code expired — close and try again.",
            countdownText(fixedNow - 30.seconds, fixedNow, PairingCodeExpiredText),
        )
    }
}
