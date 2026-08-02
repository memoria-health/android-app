package com.memoria.caregiver.ui.live

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.livekit.LiveKitConnectionState
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * LiveVideoScreen behavior. Spec: `PatientLiveView.swift`, PARITY.md §18.
 *
 * What a caregiver would notice and a refactor could quietly break: that a
 * private room says so instead of showing a feed, that the bulk lever offers the
 * action that makes sense, that the audio toggle is reachable by a screen reader,
 * and that the call button is absent exactly where a call could not work. Layout
 * is judged by screenshot per DECISIONS.md D-004.
 *
 * Video is never attached here — the tracks map is empty, so tiles render the
 * loading placeholder. That is deliberate: the renderer's own behavior is the
 * watchdog's, and the watchdog is tested directly on the JVM in
 * `RenderWatchdogTest`, which is where the value is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveVideoScreenTest {

    @get:Rule val compose = createComposeRule()

    /** Every callback this screen can fire, recorded. */
    private class Recorder {
        val selected = mutableListOf<String?>()
        val audioToggled = mutableListOf<String>()
        val detailOpened = mutableListOf<String>()
        val expanded = mutableListOf<String>()
        val acknowledged = mutableListOf<String>()
        val clipsCaptured = mutableListOf<String>()
        var callTaps = 0
        var disconnectAlls = 0
        var reconnectAlls = 0
    }

    private fun setUp(state: LiveVideoState): Recorder {
        val recorder = Recorder()
        // Every live tile animates forever — the loading spinner on a tile with
        // no track, and the pulse inside the LIVE chip. With the clock
        // auto-advancing, `waitForIdle` inside every finder would never see an
        // idle frame and the test would hang rather than fail. Pausing the clock
        // leaves the composition fully rendered and just stops new frames. Same
        // reason, and the same fix, as AppSettingsScreenTest's spinner case.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    LiveVideoScreen(
                        state = state,
                        onSelectCamera = { recorder.selected += it },
                        onToggleAudio = { recorder.audioToggled += it },
                        onOpenCameraDetail = { recorder.detailOpened += it },
                        onExpandCamera = { recorder.expanded += it },
                        onAcknowledgeSensitiveRoom = { recorder.acknowledged += it },
                        onCallButtonTap = { recorder.callTaps++ },
                        onCaptureClip = { recorder.clipsCaptured += it },
                        onDisconnectAll = { recorder.disconnectAlls++ },
                        onReconnectAll = { recorder.reconnectAlls++ },
                    )
                }
            }
        }
        return recorder
    }

    // ---- Empty and offline states ------------------------------------------

    @Test
    fun `a home with no sensors explains how to pair one`() {
        setUp(LiveVideoState())

        compose.onNodeWithText("No sensors paired").assertIsDisplayed()
        compose.onNodeWithText(
            "Pair a Memoria Sensor from the Sensors tab to begin the live feed.",
        ).assertIsDisplayed()
    }

    /**
     * Paired-but-dark is a different situation from nothing-paired, and the two
     * cards must not be confused: one asks the caregiver to buy in, the other to
     * wait or to act.
     */
    @Test
    fun `paired sensors that are all dark get the offline card, not the empty one`() {
        setUp(
            LiveVideoState(
                cameras = listOf(
                    liveCamera(online = false, desiredState = Camera.DesiredState.DISCONNECTED),
                ),
            ),
        )

        compose.onNodeWithText("All sensors are offline").assertIsDisplayed()
        compose.onNodeWithText("No sensors paired").assertDoesNotExist()
    }

    @Test
    fun `a closed sensor app is named, because reconnect cannot fix it`() {
        setUp(LiveVideoState(cameras = listOf(appClosedCamera())))

        compose.onNodeWithText("Memoria Sensor is closed").assertIsDisplayed()
    }

    // ---- The bulk lever ------------------------------------------------------

    @Test
    fun `nothing live offers reconnect, and the tap is reported`() {
        val recorder = setUp(
            LiveVideoState(cameras = listOf(liveCamera(online = false, appPresent = false))),
        )

        compose.onNodeWithText("Reconnect all sensors").performScrollTo().performClick()

        assertEquals(1, recorder.reconnectAlls)
        assertEquals(0, recorder.disconnectAlls)
    }

    /**
     * Disconnect-all only appears when there is a live sensor to turn off, so it
     * never sits nonsensically under an all-offline card.
     */
    @Test
    fun `a live sensor offers disconnect, and the tap is reported`() {
        val recorder = setUp(LiveVideoState(cameras = listOf(liveCamera()), connectionState = LiveKitConnectionState.Connected))

        compose.onNodeWithText("Disconnect all sensors").performScrollTo().performClick()

        assertEquals(1, recorder.disconnectAlls)
        assertEquals(0, recorder.reconnectAlls)
    }

    /**
     * The bulk PATCH is in flight, so a rapid double-tap must not fire a second
     * overlapping toggle — that made the label flash between the two states.
     */
    @Test
    fun `a bulk toggle already in flight ignores the button`() {
        val recorder = setUp(
            LiveVideoState(cameras = listOf(liveCamera()), isTogglingMonitoring = true),
        )

        compose.onNodeWithText("Disconnect all sensors").performScrollTo().performClick()

        assertEquals(0, recorder.disconnectAlls)
    }

    @Test
    fun `no sensors at all means no bulk lever`() {
        setUp(LiveVideoState())

        compose.onNodeWithText("Reconnect all sensors").assertDoesNotExist()
        compose.onNodeWithText("Disconnect all sensors").assertDoesNotExist()
    }

    // ---- The sensor list -------------------------------------------------------

    @Test
    fun `the header counts the sensors that are actually live`() {
        setUp(
            LiveVideoState(
                cameras = listOf(
                    liveCamera(id = "a", room = "Living room"),
                    liveCamera(id = "b", room = "Kitchen", online = false),
                ),
            ),
        )

        compose.onNodeWithText("SENSORS · 1 ONLINE").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `each row leads to its own camera`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(
                    liveCamera(id = "cam-a", room = "Living room"),
                    liveCamera(id = "cam-b", room = "Kitchen", online = false),
                ),
            ),
        )

        compose.onNodeWithText("Kitchen").performScrollTo().performClick()

        assertEquals(listOf("cam-b"), recorder.detailOpened)
    }

    @Test
    fun `a closed sensor app is spelled out in the row, not left to the dot`() {
        setUp(LiveVideoState(cameras = listOf(appClosedCamera())))

        compose.onNodeWithText("APP CLOSED").performScrollTo().assertIsDisplayed()
    }

    // ---- The private-room explainer ---------------------------------------------

    @Test
    fun `a private room explains itself once and the dismissal is reported`() {
        val camera = liveCamera(
            id = "cam-p",
            room = "Bedroom",
            privacyEnabled = true,
            roomClass = Camera.RoomClass.BEDROOM,
        )
        val recorder = setUp(LiveVideoState(cameras = listOf(camera), connectionState = LiveKitConnectionState.Connected))

        compose.onNodeWithText("Bedroom is a private room").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss private-room notice for Bedroom")
            .performClick()

        assertEquals(listOf("cam-p"), recorder.acknowledged)
    }

    @Test
    fun `an acknowledged explainer stays gone`() {
        val camera = liveCamera(id = "cam-p", room = "Bedroom", privacyEnabled = true)
        setUp(
            LiveVideoState(
                cameras = listOf(camera),
                acknowledgedSensitiveRoomIds = setOf("cam-p"),
            ),
        )

        compose.onNodeWithText("Bedroom is a private room").assertDoesNotExist()
    }

    // ---- The tile ----------------------------------------------------------------

    /**
     * The dignity placard, not a feed and not a spinner. The backend has severed
     * this camera's media, so the tile has to say the live view is intentionally
     * off rather than look broken.
     */
    @Test
    fun `a private camera's tile shows the placard instead of a feed`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(room = "Bedroom", privacyEnabled = true)),
                connectionState = LiveKitConnectionState.Connected,
                acknowledgedSensitiveRoomIds = setOf("cam-1"),
            ),
        )

        compose.onNodeWithText("Private room").assertIsDisplayed()
        compose.onNodeWithText(
            "Live view is off for this room.\nInsights still arrive on your phone.",
        ).assertIsDisplayed()
    }

    /** There is no audio to listen to on a private camera; the backend sends none. */
    @Test
    fun `a private camera's tile offers no audio toggle`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(room = "Bedroom", privacyEnabled = true)),
                connectionState = LiveKitConnectionState.Connected,
                acknowledgedSensitiveRoomIds = setOf("cam-1"),
            ),
        )

        compose.onNodeWithContentDescription("Unmute Bedroom audio").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mute Bedroom audio").assertDoesNotExist()
    }

    @Test
    fun `the audio toggle names what it will do and reports the identity`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(room = "Living room", livekitIdentity = "cam_aaaa")),
                connectionState = LiveKitConnectionState.Connected,
            ),
        )

        compose.onNodeWithContentDescription("Unmute Living room audio").performClick()

        assertEquals(listOf("cam_aaaa"), recorder.audioToggled)
    }

    @Test
    fun `an already-unmuted tile offers to mute`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(room = "Living room", livekitIdentity = "cam_aaaa")),
                connectionState = LiveKitConnectionState.Connected,
                audioEnabledIdentities = setOf("cam_aaaa"),
            ),
        )

        compose.onNodeWithContentDescription("Mute Living room audio").assertIsDisplayed()
    }

    @Test
    fun `expanding a tile reports the camera`() {
        val recorder = setUp(
            LiveVideoState(cameras = listOf(liveCamera(room = "Living room")), connectionState = LiveKitConnectionState.Connected),
        )

        compose.onNodeWithContentDescription("Expand Living room").performClick()

        assertEquals(listOf("cam-1"), recorder.expanded)
    }

    @Test
    fun `the ai-paused badge shows while the feed keeps streaming`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(aiPaused = true)),
                connectionState = LiveKitConnectionState.Connected,
                videoTracksByIdentity = emptyMap(),
            ),
        )

        compose.onNodeWithText("AI PAUSED").assertIsDisplayed()
    }

    /** The mono subtitle, which is the only place a sensor's number appears on a tile. */
    @Test
    fun `an idle tile carries its sensor number`() {
        setUp(
            LiveVideoState(cameras = listOf(liveCamera(displayId = 9)), connectionState = LiveKitConnectionState.Connected),
        )

        compose.onNodeWithText("SENSOR 09").assertIsDisplayed()
    }

    @Test
    fun `the call target counts up while a call is live`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1")),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
                talk = TalkUiState(TalkCallState.ACTIVE, callStartedAtMillis = 1_000_000L),
                nowMillis = 1_000_000L + 65_000L,
            ),
        )

        compose.onNodeWithText("ON AIR · 01:05").assertIsDisplayed()
    }

    // ---- The call button ------------------------------------------------------------

    @Test
    fun `the call target offers a call, and the tap is reported`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1")),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
            ),
        )

        compose.onNodeWithContentDescription("Start call").performClick()

        assertEquals(1, recorder.callTaps)
    }

    /**
     * A gateway camera has no speaker — the caregiver's voice plays through the
     * wall phone — so calling one dials into a room where nothing can answer.
     */
    @Test
    fun `a gateway camera offers no call`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1", sourceType = Camera.SourceType.SENSOR)),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
            ),
        )

        compose.onNodeWithContentDescription("Start call").assertDoesNotExist()
    }

    @Test
    fun `a private camera offers no call`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1", privacyEnabled = true)),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
                acknowledgedSensitiveRoomIds = setOf("cam-1"),
            ),
        )

        compose.onNodeWithContentDescription("Start call").assertDoesNotExist()
    }

    /**
     * A connecting call must not accept a second tap: the mic publish and the
     * first `/talk/start` are already in flight.
     */
    @Test
    fun `a connecting call ignores further taps`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1")),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
                talk = TalkUiState(TalkCallState.CONNECTING),
            ),
        )

        compose.onNodeWithContentDescription("Connecting").performClick()

        assertEquals(0, recorder.callTaps)
    }

    @Test
    fun `a live call offers to end it`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1")),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
                talk = TalkUiState(TalkCallState.ACTIVE, callStartedAtMillis = 0L),
                nowMillis = 0L,
            ),
        )

        compose.onNodeWithContentDescription("End call").assertIsDisplayed()
    }

    // ---- Errors and the dev-only capture ------------------------------------------------

    /**
     * The failure sentence is the host's, rendered verbatim on the tile so it is
     * visible without dismissing the carousel.
     */
    @Test
    fun `a call failure is shown on the call target's tile, verbatim`() {
        setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1")),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
                lkError = "Call failed: The network connection was lost.",
            ),
        )

        compose.onNodeWithText("Call failed: The network connection was lost.")
            .assertIsDisplayed()
    }

    /** Hard-false in release, so the button must not exist unless the flag is on. */
    @Test
    fun `the clip-capture trigger is absent unless the dev flag is on`() {
        setUp(LiveVideoState(cameras = listOf(liveCamera(room = "Living room")), connectionState = LiveKitConnectionState.Connected))

        compose.onNodeWithContentDescription("Capture 15-second clip from Living room")
            .assertDoesNotExist()
    }

    @Test
    fun `the clip-capture trigger reports the camera when the dev flag is on`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1", room = "Living room")),
                connectionState = LiveKitConnectionState.Connected,
                clipCaptureEnabled = true,
            ),
        )

        compose.onNodeWithContentDescription("Capture 15-second clip from Living room")
            .performClick()

        assertEquals(listOf("cam-1"), recorder.clipsCaptured)
    }

    @Test
    fun `a capture already in flight ignores the trigger and shows its note`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1", room = "Living room")),
                connectionState = LiveKitConnectionState.Connected,
                clipCaptureEnabled = true,
                capturingCameraId = "cam-1",
                captureNote = LiveCaptureNote("cam-1", "Recording 15s — check the receiver"),
            ),
        )

        compose.onNodeWithText("Recording 15s — check the receiver").assertIsDisplayed()
        compose.onNodeWithContentDescription("Capture 15-second clip from Living room")
            .performClick()

        assertEquals(emptyList<String>(), recorder.clipsCaptured)
    }

    // ---- Selection resolution ---------------------------------------------------------

    /**
     * The first camera coming online must resolve a selection on its own, or no
     * tile is the call target and the Call button never appears — the bug the
     * iOS `onChange(of: onlineCameras)` handler exists to fix.
     */
    @Test
    fun `a live set with no selection resolves one`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(liveCamera(id = "cam-1")),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = null,
            ),
        )
        compose.waitForIdle()

        assertTrue("cam-1" in recorder.selected)
    }

    /** A selection that is still live must not be disturbed. */
    @Test
    fun `a still-valid selection is left alone`() {
        val recorder = setUp(
            LiveVideoState(
                cameras = listOf(
                    liveCamera(id = "cam-1", livekitIdentity = "cam_aaaa"),
                    liveCamera(id = "cam-2", room = "Kitchen", livekitIdentity = "cam_bbbb"),
                ),
                connectionState = LiveKitConnectionState.Connected,
                selectedCameraId = "cam-1",
            ),
        )
        compose.waitForIdle()

        assertEquals(emptyList<String?>(), recorder.selected)
    }
}
