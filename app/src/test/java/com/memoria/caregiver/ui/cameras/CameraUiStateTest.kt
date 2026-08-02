package com.memoria.caregiver.ui.cameras

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.Detector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The decisions behind the two camera screens. Spec: PatientCamerasView.swift,
 * CameraDetailView.swift, PARITY.md §21.
 *
 * All of this is precedence: which of four connection states a row reports,
 * which badges survive, when the exit-door section is meaningful. Getting one
 * of those orders wrong shows a caregiver "OFFLINE — tap Reconnect" for a phone
 * whose app is shut, which is advice that cannot work. None of it is visible in
 * a screenshot, so it is tested here rather than through a composition.
 */
class CameraUiStateTest {

    private val defaultLocale = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(defaultLocale)

    // ---- Sensor label ------------------------------------------------------

    @Test fun `sensor number is zero-padded to two digits`() {
        assertEquals("SENSOR 01", sensorLabel(1))
        assertEquals("SENSOR 12", sensorLabel(12))
    }

    @Test fun `sensor number keeps three digits rather than truncating`() {
        assertEquals("SENSOR 100", sensorLabel(100))
    }

    /**
     * Swift's `String(format:)` is locale-independent; Kotlin's `String.format`
     * follows the default locale and would render Arabic-Indic digits here. The
     * rest of the line is untranslated English, so that would be a bug.
     */
    @Test fun `sensor number stays ASCII under a non-Latin default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("SENSOR 07", sensorLabel(7))
    }

    // ---- Status line precedence -------------------------------------------

    @Test fun `online phone reads online`() {
        assertEquals("SENSOR 01 · ONLINE", cameraStatusLine(testCamera()))
    }

    @Test fun `offline phone reads offline`() {
        assertEquals("SENSOR 01 · OFFLINE", cameraStatusLine(testCamera(online = false)))
    }

    @Test fun `unpaired phone asks to be re-paired, whatever else is true`() {
        val camera = testCamera(paired = false, online = true, aiPaused = true, disabled = true)
        assertEquals("SENSOR 01 · UNPAIRED — TAP TO RE-PAIR", cameraStatusLine(camera))
    }

    /**
     * A Pi sensor is never "unpaired" in the wall-phone sense: it gets its
     * ingest token at provision time, so `paired = false` on one must fall
     * through to the ordinary online/offline wording.
     */
    @Test fun `unpaired sensor camera is not offered a re-pair`() {
        val camera = testCamera(sourceType = Camera.SourceType.SENSOR, paired = false)
        assertEquals("SENSOR 01 · ONLINE", cameraStatusLine(camera))
    }

    @Test fun `a closed app beats the plain offline wording`() {
        val camera = testCamera(online = false, appPresent = false)
        assertEquals("SENSOR 01 · APP CLOSED", cameraStatusLine(camera))
    }

    /**
     * The caregiver's own intent beats the lagging `online` column: a sensor
     * they just disconnected must read OFFLINE at once, though the reconciler
     * takes a few seconds to flip the flag.
     */
    @Test fun `a disconnect the caregiver asked for reads offline while still online`() {
        val camera = testCamera(online = true, desiredState = Camera.DesiredState.DISCONNECTED)
        assertEquals("SENSOR 01 · OFFLINE", cameraStatusLine(camera))
    }

    @Test fun `a closed app beats a caregiver disconnect`() {
        val camera = testCamera(
            online = false,
            appPresent = false,
            desiredState = Camera.DesiredState.DISCONNECTED,
        )
        assertEquals("SENSOR 01 · APP CLOSED", cameraStatusLine(camera))
    }

    // ---- Row badges --------------------------------------------------------

    @Test fun `a settled camera earns no badges`() {
        assertEquals(emptyList<CameraRowBadge>(), cameraRowBadges(testCamera()))
    }

    @Test fun `unpaired suppresses every other badge`() {
        val camera = testCamera(paired = false, disabled = true, aiPaused = true, online = false)
        assertEquals(listOf(CameraRowBadge.UNPAIRED), cameraRowBadges(camera))
    }

    @Test fun `a closed app replaces the disconnected dot`() {
        val camera = testCamera(
            online = false,
            appPresent = false,
            desiredState = Camera.DesiredState.DISCONNECTED,
        )
        assertEquals(listOf(CameraRowBadge.APP_CLOSED), cameraRowBadges(camera))
    }

    /**
     * Hidden and AI-paused are independent user settings and stack — the row has
     * to be able to say both at once.
     */
    @Test fun `hidden and ai paused are additive`() {
        val camera = testCamera(disabled = true, aiPaused = true)
        assertEquals(
            listOf(CameraRowBadge.HIDDEN, CameraRowBadge.AI_PAUSED),
            cameraRowBadges(camera),
        )
    }

    @Test fun `a disconnected camera can still be hidden and paused`() {
        val camera = testCamera(
            desiredState = Camera.DesiredState.DISCONNECTED,
            disabled = true,
            aiPaused = true,
        )
        assertEquals(
            listOf(
                CameraRowBadge.DISCONNECTED_DOT,
                CameraRowBadge.HIDDEN,
                CameraRowBadge.AI_PAUSED,
            ),
            cameraRowBadges(camera),
        )
    }

    // ---- Detail header -----------------------------------------------------

    @Test fun `detail line carries the livekit identity`() {
        assertEquals("SENSOR 01 · cam_1a2b3c4d", cameraDetailLine(testCamera()))
    }

    @Test fun `detail line says so when there is no identity yet`() {
        assertEquals(
            "SENSOR 01 · no identity",
            cameraDetailLine(testCamera(livekitIdentity = null)),
        )
    }

    @Test fun `room class label names the classified room`() {
        assertEquals("bathroom", roomClassLabel(testCamera(roomClass = Camera.RoomClass.BATHROOM)))
        assertEquals("bedroom", roomClassLabel(testCamera(roomClass = Camera.RoomClass.BEDROOM)))
    }

    /**
     * Privacy can only be on for bathroom or bedroom, so these two are contract
     * drift. The copy still has to read as a sentence.
     */
    @Test fun `room class label falls back to the generic pair`() {
        assertEquals("bathroom or bedroom", roomClassLabel(testCamera(roomClass = null)))
        assertEquals(
            "bathroom or bedroom",
            roomClassLabel(testCamera(roomClass = Camera.RoomClass.OTHER)),
        )
    }

    // ---- Exit doors --------------------------------------------------------

    @Test fun `door status reads unset when there is no config`() {
        assertEquals("Not set · any doorway counts as leaving", doorStatusText(null))
    }

    @Test fun `door status reads unset when the config holds no doors`() {
        assertEquals(
            "Not set · any doorway counts as leaving",
            doorStatusText(Detector.DoorConfig()),
        )
    }

    @Test fun `no exit door is its own sentence, not a count of zero`() {
        assertEquals(
            "No door leads outside · wandering off here",
            doorStatusText(Detector.DoorConfig(noExitDoor = true)),
        )
    }

    @Test fun `a single door shows its label when it has one`() {
        val config = Detector.DoorConfig(doors = listOf(testDoor(label = "Front door")))
        assertEquals("1 exit door · Front door", doorStatusText(config))
    }

    @Test fun `a single unlabelled door does not print an empty label`() {
        assertEquals(
            "1 exit door set",
            doorStatusText(Detector.DoorConfig(doors = listOf(testDoor(label = null)))),
        )
        assertEquals(
            "1 exit door set",
            doorStatusText(Detector.DoorConfig(doors = listOf(testDoor(label = "")))),
        )
    }

    @Test fun `several doors are counted`() {
        val config = Detector.DoorConfig(doors = listOf(testDoor(), testDoor(), testDoor()))
        assertEquals("3 exit doors set", doorStatusText(config))
    }

    // ---- Derived detail state ---------------------------------------------

    private val wandering = testDetector(id = "det-w", name = "Wandering", identifier = "wandering")

    /**
     * Wandering is driven by the exit-door section, which is only active once
     * doors are set, so a plain toggle for it would be a switch that does
     * nothing.
     */
    @Test fun `wandering is kept out of the plain toggle list`() {
        val state = CameraDetailState(detectors = listOf(testDetector(), wandering))
        assertEquals(listOf("det-1"), state.togglableDetectors.map { it.id })
        assertEquals("det-w", state.wanderingDetector?.id)
    }

    @Test fun `a custom detector still compiling is not mistaken for wandering`() {
        val compiling = testDetector(id = "det-c", identifier = null)
        val state = CameraDetailState(detectors = listOf(compiling))
        assertEquals(listOf("det-c"), state.togglableDetectors.map { it.id })
        assertNull(state.wanderingDetector)
    }

    @Test fun `wandering is inactive here until a door is set`() {
        assertFalse(CameraDetailState().isWanderingActiveHere)
        assertFalse(CameraDetailState(doorConfig = Detector.DoorConfig()).isWanderingActiveHere)
    }

    @Test fun `declaring no exit door leaves wandering inactive here`() {
        val state = CameraDetailState(
            doorConfig = Detector.DoorConfig(doors = listOf(testDoor()), noExitDoor = true),
        )
        assertFalse(state.isWanderingActiveHere)
    }

    @Test fun `one door makes wandering active here`() {
        val state = CameraDetailState(doorConfig = Detector.DoorConfig(doors = listOf(testDoor())))
        assertTrue(state.isWanderingActiveHere)
    }

    @Test fun `the exit-door section needs wandering on and a claimed camera`() {
        fun state(isOn: Boolean, identity: String?) = CameraDetailState(
            camera = testCamera(livekitIdentity = identity),
            detectors = listOf(testDetector(identifier = "wandering", isOn = isOn)),
        )
        assertTrue(state(isOn = true, identity = "cam_1").showsExitDoors)
        assertFalse(state(isOn = false, identity = "cam_1").showsExitDoors)
        assertFalse(state(isOn = true, identity = null).showsExitDoors)
    }

    @Test fun `the exit-door section is absent without a wandering detector`() {
        val state = CameraDetailState(camera = testCamera(), detectors = listOf(testDetector()))
        assertFalse(state.showsExitDoors)
    }

    // ---- Pairing sections --------------------------------------------------

    @Test fun `only an unclaimed wall phone offers a pairing code`() {
        assertTrue(CameraDetailState(camera = testCamera(paired = false)).showsPairingCode)
        assertFalse(CameraDetailState(camera = testCamera(paired = true)).showsPairingCode)
    }

    /**
     * A Pi sensor gets its ingest token at provision time, so a 6-digit code
     * would be misleading on this surface.
     */
    @Test fun `a sensor camera never offers a pairing code`() {
        val camera = testCamera(sourceType = Camera.SourceType.SENSOR, paired = false)
        assertFalse(CameraDetailState(camera = camera).showsPairingCode)
    }

    @Test fun `only a claimed wall phone offers unpair`() {
        assertTrue(CameraDetailState(camera = testCamera(paired = true)).showsUnpair)
        assertFalse(CameraDetailState(camera = testCamera(paired = false)).showsUnpair)
        val sensor = testCamera(sourceType = Camera.SourceType.SENSOR, paired = true)
        assertFalse(CameraDetailState(camera = sensor).showsUnpair)
    }

    @Test fun `neither pairing section appears without a camera`() {
        assertFalse(CameraDetailState().showsPairingCode)
        assertFalse(CameraDetailState().showsUnpair)
    }

    // ---- AI-paused chip ----------------------------------------------------

    @Test fun `the ai-paused chip shows on an otherwise visible camera`() {
        assertTrue(CameraDetailState(camera = testCamera(aiPaused = true)).showsAiPausedChip)
    }

    /**
     * On a hidden or disconnected camera the stronger state is the true one, and
     * the chip would tell the caregiver the wrong thing about why nothing is
     * happening.
     */
    @Test fun `the ai-paused chip is suppressed by hidden or disconnected`() {
        val hidden = testCamera(aiPaused = true, disabled = true)
        assertFalse(CameraDetailState(camera = hidden).showsAiPausedChip)
        val gone = testCamera(
            aiPaused = true,
            desiredState = Camera.DesiredState.DISCONNECTED,
        )
        assertFalse(CameraDetailState(camera = gone).showsAiPausedChip)
    }

    // ---- Busy gating -------------------------------------------------------

    @Test fun `either state flip blocks both control rows`() {
        assertFalse(CameraDetailState().controlsBusy)
        assertTrue(CameraDetailState(isTogglingAiPause = true).controlsBusy)
        assertTrue(CameraDetailState(isTogglingDisconnect = true).controlsBusy)
    }

    @Test fun `a detector row is inert while saving and while off globally`() {
        assertTrue(detectorRowEnabled(testDetector(), isBusy = false))
        assertFalse(detectorRowEnabled(testDetector(), isBusy = true))
        assertFalse(detectorRowEnabled(testDetector(isOn = false), isBusy = false))
    }
}
