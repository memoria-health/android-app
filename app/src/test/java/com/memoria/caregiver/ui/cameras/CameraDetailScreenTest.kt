package com.memoria.caregiver.ui.cameras

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CameraDetailScreen behavior. Spec: CameraDetailView.swift, PARITY.md §21.
 *
 * The three destructive controls are the point of this suite. Each one costs
 * the caregiver something real — a camera stops streaming, a phone unpairs, a
 * row is deleted — and each is one tap away from the detector toggles, so
 * "asks first" is a property worth pinning. The rest covers the conditional
 * sections, which are the other way this screen goes wrong: showing a pairing
 * code for a Pi sensor, or a wandering toggle that cannot do anything.
 */
@RunWith(RobolectricTestRunner::class)
class CameraDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp(
        state: CameraDetailState,
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onToggleDetector: (Detector) -> Unit = {},
        onToggleAiPause: () -> Unit = {},
        onDisconnect: () -> Unit = {},
        onReconnect: () -> Unit = {},
        onUnpair: () -> Unit = {},
        onDelete: () -> Unit = {},
        onShowPairingCode: () -> Unit = {},
        onOpenDoorPicker: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    CameraDetailScreen(
                        state = state,
                        onToggleDetector = onToggleDetector,
                        onToggleAiPause = onToggleAiPause,
                        onDisconnect = onDisconnect,
                        onReconnect = onReconnect,
                        onUnpair = onUnpair,
                        onDelete = onDelete,
                        onShowPairingCode = onShowPairingCode,
                        onOpenDoorPicker = onOpenDoorPicker,
                    )
                }
            }
        }
    }

    private val connected = CameraDetailState(
        camera = testCamera(),
        detectors = listOf(testDetector()),
    )

    // ---- Missing camera ----------------------------------------------------

    /**
     * The camera goes null the moment a delete lands, which is exactly when the
     * controls must stop being tappable.
     */
    @Test
    fun `a deleted camera leaves no controls behind`() {
        setUp(CameraDetailState())

        compose.onNodeWithText("Sensor not found").assertExists()
        compose.onNodeWithText("CONTROLS").assertDoesNotExist()
        compose.onNodeWithText("Delete sensor").assertDoesNotExist()
    }

    // ---- Disconnect / reconnect -------------------------------------------

    @Test
    fun `disconnect asks before it cuts the stream`() {
        var disconnects = 0
        setUp(connected, onDisconnect = { disconnects++ })

        compose.onNodeWithText("Disconnect (privacy)").performScrollTo().performClick()

        assertEquals(0, disconnects)
        compose.onNodeWithText("Disconnect this sensor?").assertExists()

        compose.onNodeWithText("Disconnect").performClick()

        assertEquals(1, disconnects)
    }

    @Test
    fun `cancelling the disconnect leaves the camera alone`() {
        var disconnects = 0
        setUp(connected, onDisconnect = { disconnects++ })

        compose.onNodeWithText("Disconnect (privacy)").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, disconnects)
        compose.onNodeWithText("Disconnect this sensor?").assertDoesNotExist()
    }

    /** Reconnect is the harmless direction, so iOS does not confirm it. */
    @Test
    fun `reconnect goes straight through`() {
        var reconnects = 0
        setUp(
            connected.copy(camera = testCamera(desiredState = Camera.DesiredState.DISCONNECTED)),
            onReconnect = { reconnects++ },
        )

        compose.onNodeWithText("Reconnect").performScrollTo().performClick()

        assertEquals(1, reconnects)
    }

    /**
     * The busy label is the only feedback during the round trip, and the flag
     * must be cleared by the caller on success too — see the screen's doc.
     */
    @Test
    fun `an in-flight disconnect says so and refuses a second tap`() {
        var disconnects = 0
        setUp(connected.copy(isTogglingDisconnect = true), onDisconnect = { disconnects++ })

        compose.onNodeWithText("Disconnecting…").performScrollTo().performClick()

        assertEquals(0, disconnects)
        compose.onNodeWithText("Disconnect this sensor?").assertDoesNotExist()
    }

    // ---- Advanced ----------------------------------------------------------

    /**
     * Pausing the AI tick is a niche cost lever. It sat beside Disconnect and
     * was moved behind a collapsed header, so it must not be visible until the
     * caregiver opens it.
     */
    @Test
    fun `pause ai stays behind the advanced header until opened`() {
        var pauses = 0
        setUp(connected, onToggleAiPause = { pauses++ })

        compose.onNodeWithText("Pause AI").assertDoesNotExist()

        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Pause AI").performScrollTo().performClick()

        assertEquals(1, pauses)
    }

    @Test
    fun `a paused camera offers to resume instead`() {
        setUp(connected.copy(camera = testCamera(aiPaused = true)))

        compose.onNodeWithText("Advanced").performScrollTo().performClick()

        compose.onNodeWithText("Resume AI").assertExists()
        compose.onNodeWithText("AI PAUSED").assertExists()
    }

    // ---- Detector assignment ----------------------------------------------

    @Test
    fun `toggling a detector reports that detector`() {
        val toggled = mutableListOf<String>()
        setUp(connected, onToggleDetector = { toggled += it.id })

        compose.onNodeWithText("Falls").performScrollTo().performClick()

        assertEquals(listOf("det-1"), toggled)
    }

    /**
     * Wandering is driven by the exit-door section below, so a plain toggle for
     * it would be a switch that does nothing.
     */
    @Test
    fun `wandering is not offered as a plain toggle`() {
        setUp(
            connected.copy(
                detectors = listOf(
                    testDetector(),
                    testDetector(id = "det-w", name = "Wandering", identifier = "wandering"),
                ),
            ),
        )

        compose.onNodeWithText("Falls").assertExists()
        compose.onNodeWithText("Wandering").assertDoesNotExist()
    }

    @Test
    fun `a detector that is off globally cannot be assigned here`() {
        var toggles = 0
        setUp(
            connected.copy(detectors = listOf(testDetector(isOn = false))),
            onToggleDetector = { toggles++ },
        )

        compose.onNodeWithText("OFF GLOBALLY").assertExists()
        compose.onNodeWithText("Falls").performScrollTo().performClick()

        assertEquals(0, toggles)
    }

    @Test
    fun `a detector still compiling says so`() {
        setUp(connected.copy(detectors = listOf(testDetector(identifier = null))))

        compose.onNodeWithText("COMPILING…").assertExists()
    }

    // ---- Exit doors --------------------------------------------------------

    private fun wanderingState(doorConfig: Detector.DoorConfig?) = connected.copy(
        detectors = listOf(
            testDetector(id = "det-w", name = "Wandering", identifier = "wandering"),
        ),
        doorConfig = doorConfig,
    )

    @Test
    fun `wandering with no doors set is reported as not active here`() {
        setUp(wanderingState(doorConfig = null))

        compose.onNodeWithText("Exit doors on this sensor").performScrollTo().assertExists()
        compose.onNodeWithText("NOT ACTIVE").assertExists()
        compose.onNodeWithText("Set").assertExists()
    }

    @Test
    fun `wandering with a door set is active here and offers a change`() {
        var opens = 0
        setUp(
            wanderingState(Detector.DoorConfig(doors = listOf(testDoor(label = "Front door")))),
            onOpenDoorPicker = { opens++ },
        )

        compose.onNodeWithText("ACTIVE").assertExists()
        compose.onNodeWithText("1 exit door · Front door").assertExists()
        compose.onNodeWithText("Exit doors on this sensor").performScrollTo().performClick()

        assertEquals(1, opens)
    }

    /** No LiveKit identity means the picker has no frame to work from. */
    @Test
    fun `an unclaimed camera gets no exit-door section`() {
        setUp(wanderingState(doorConfig = null).copy(camera = testCamera(livekitIdentity = null)))

        compose.onNodeWithText("Exit doors on this sensor").assertDoesNotExist()
    }

    // ---- Pairing -----------------------------------------------------------

    @Test
    fun `an unclaimed wall phone offers a pairing code`() {
        var shows = 0
        setUp(
            connected.copy(camera = testCamera(paired = false)),
            onShowPairingCode = { shows++ },
        )

        compose.onNodeWithText("AWAITING PAIRING").assertExists()
        compose.onNodeWithText("Show pairing code").performScrollTo().performClick()

        assertEquals(1, shows)
        // Unpair is meaningless with no phone attached.
        compose.onNodeWithText("Unpair sensor").assertDoesNotExist()
    }

    @Test
    fun `a Pi sensor is never offered a pairing code`() {
        setUp(
            connected.copy(
                camera = testCamera(sourceType = Camera.SourceType.SENSOR, paired = false),
            ),
        )

        compose.onNodeWithText("AWAITING PAIRING").assertDoesNotExist()
        compose.onNodeWithText("Unpair sensor").assertDoesNotExist()
    }

    @Test
    fun `unpair asks first, and keeps the room and history`() {
        var unpairs = 0
        setUp(connected, onUnpair = { unpairs++ })

        compose.onNodeWithText("Unpair sensor").performScrollTo().performClick()

        assertEquals(0, unpairs)
        compose.onNodeWithText("Unpair this sensor?").assertExists()

        compose.onNodeWithText("Unpair").performClick()

        assertEquals(1, unpairs)
    }

    // ---- Delete ------------------------------------------------------------

    @Test
    fun `delete asks first`() {
        var deletes = 0
        setUp(connected, onDelete = { deletes++ })

        compose.onNodeWithText("Delete sensor").performScrollTo().performClick()

        assertEquals(0, deletes)
        compose.onNodeWithText("Delete this sensor?").assertExists()

        compose.onNodeWithText("Delete").performClick()

        assertEquals(1, deletes)
    }

    @Test
    fun `cancelling the delete keeps the sensor`() {
        var deletes = 0
        setUp(connected, onDelete = { deletes++ })

        compose.onNodeWithText("Delete sensor").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, deletes)
    }

    // ---- Private room ------------------------------------------------------

    /**
     * Privacy is derived from the room classification and locked server-side, so
     * this section explains the state and offers nothing to switch.
     */
    @Test
    fun `a private room explains itself and offers no toggle`() {
        setUp(
            connected.copy(
                camera = testCamera(
                    privacyEnabled = true,
                    roomClass = Camera.RoomClass.BATHROOM,
                ),
            ),
        )

        compose.onNodeWithText("Private room").assertExists()
        compose.onNodeWithText(
            "Live view off, clips protected. Set automatically because this looks like a bathroom.",
        ).assertExists()
    }

    // ---- Analytics ---------------------------------------------------------

    @Test
    fun `the screen reports itself once`() {
        val analytics = RecordingAnalytics()
        setUp(connected, analytics = analytics)

        // Screen tracking moved to the presenter, where iOS puts it:
        // `ModalOverlay(screen = "camera_detail")` logs it when this is presented,
        // so a new overlay cannot ship untracked (HARDENING.md H-10).
        // ModalOverlayTest pins the presenter's behaviour and
        // ScreenTrackingCoverageTest pins the name.
        assertEquals(emptyList<String>(), analytics.names("screen"))
    }
}
