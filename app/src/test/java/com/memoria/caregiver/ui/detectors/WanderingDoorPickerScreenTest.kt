package com.memoria.caregiver.ui.detectors

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.data.model.DetectedDoor
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.WanderingDoorInput
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WanderingDoorPicker behavior. Spec: `WanderingDoorPickerView.swift`, PARITY.md §23.
 *
 * Most of this screen is arithmetic that decides what goes on the wire — which
 * dot a tap hit, where a tap-placed door lands, which stored door is "the same
 * door" as a detected one — so most of these are plain unit tests over the pure
 * functions. The composition is exercised only where the wiring itself is the
 * risk: that the CTA is inert with nothing selected, that a tap reaches the
 * selection callback, and that the status copy reaches the screen unedited.
 *
 * Layout is not asserted; parity is judged by screenshot (DECISIONS.md D-004).
 */
@RunWith(RobolectricTestRunner::class)
class WanderingDoorPickerScreenTest {

    @get:Rule val compose = createComposeRule()

    // A 400x200 frame at the origin — no letterboxing, so a normalized 0.5/0.5
    // is at (200, 100) and the arithmetic stays readable.
    private val fit = Rect(Offset.Zero, Size(400f, 200f))

    private val frontDoor = DetectedDoor(box = listOf(0.10, 0.20, 0.10, 0.30), label = "front door")
    private val backDoor = DetectedDoor(box = listOf(0.70, 0.20, 0.10, 0.30), label = "back door")

    // ---- Tap handling ------------------------------------------------------

    /** iOS `handleTap` step 1: a tap on a VLM dot selects it. */
    @Test
    fun `tapping a detected door selects it`() {
        val after = WanderingDoorSelection().tapped(
            tap = boxCenter(frontDoor.box, fit),
            fit = fit,
            doors = listOf(frontDoor, backDoor),
            hitRadius = 30f,
        )

        assertEquals(setOf(frontDoor.id), after.selectedDoorIds)
        assertTrue(after.manualDoors.isEmpty())
    }

    /** Selection is a toggle — the same tap twice leaves nothing selected. */
    @Test
    fun `tapping a selected detected door deselects it`() {
        val doors = listOf(frontDoor, backDoor)
        val tap = boxCenter(frontDoor.box, fit)
        val selected = WanderingDoorSelection().tapped(tap, fit, doors, 30f)

        val after = selected.tapped(tap, fit, doors, 30f)

        assertTrue(after.selectedDoorIds.isEmpty())
    }

    /**
     * iOS `handleTap` step 3 then step 2: empty space places a door, and tapping
     * that marker again removes it. This is the only way to undo a placement, so
     * a regression here strands a caregiver with a door they cannot delete.
     */
    @Test
    fun `tapping empty space places a door and tapping it again removes it`() {
        val tap = Offset(200f, 100f)

        val placed = WanderingDoorSelection().tapped(tap, fit, doors = emptyList(), hitRadius = 30f)
        assertEquals(1, placed.manualDoors.size)

        val removed = placed.tapped(tap, fit, doors = emptyList(), hitRadius = 30f)
        assertTrue(removed.manualDoors.isEmpty())
    }

    /** The placed box is centred on the tap and carries the 0.12 x 0.20 default. */
    @Test
    fun `a placed door is centred on the tap`() {
        val placed = WanderingDoorSelection()
            .tapped(Offset(200f, 100f), fit, doors = emptyList(), hitRadius = 30f)

        val box = placed.manualDoors.single().box
        assertEquals(0.5 - 0.06, box[0], 1e-9)
        assertEquals(0.5 - 0.10, box[1], 1e-9)
        assertEquals(0.12, box[2], 1e-9)
        assertEquals(0.20, box[3], 1e-9)
    }

    /**
     * iOS clamps with `min(max(v, 0), 1 - size)`. A door placed in the corner has
     * to stay whole and inside the frame, or the backend gets a box that runs off
     * the image.
     */
    @Test
    fun `a door placed at the corner is clamped inside the frame`() {
        val topLeft = WanderingDoorSelection()
            .tapped(Offset(0f, 0f), fit, doors = emptyList(), hitRadius = 30f)
            .manualDoors.single().box
        assertEquals(0.0, topLeft[0], 1e-9)
        assertEquals(0.0, topLeft[1], 1e-9)

        val bottomRight = WanderingDoorSelection()
            .tapped(Offset(399f, 199f), fit, doors = emptyList(), hitRadius = 30f)
            .manualDoors.single().box
        assertEquals(1.0 - 0.12, bottomRight[0], 1e-9)
        assertEquals(1.0 - 0.20, bottomRight[1], 1e-9)
    }

    /**
     * The canvas is bigger than the frame when the aspect ratios differ, and iOS
     * takes the tap gesture on the whole canvas. A tap on the letterbox bar must
     * therefore place nothing rather than a door pinned to the edge.
     */
    @Test
    fun `a tap outside the frame places nothing`() {
        val letterboxed = Rect(Offset(0f, 50f), Size(400f, 100f))

        val after = WanderingDoorSelection()
            .tapped(Offset(200f, 10f), letterboxed, doors = emptyList(), hitRadius = 30f)

        assertTrue(after.manualDoors.isEmpty())
        assertTrue(after.selectedDoorIds.isEmpty())
    }

    /**
     * Order matters: a detected dot is tested before a manual one, so a tap that
     * is in range of both toggles the detected door instead of deleting the
     * manual marker.
     */
    @Test
    fun `a detected dot wins over a manual dot in the same place`() {
        val tap = boxCenter(frontDoor.box, fit)
        val overlapping = WanderingDoorSelection(manualDoors = listOf(ManualDoor(frontDoor.box)))

        val after = overlapping.tapped(tap, fit, doors = listOf(frontDoor), hitRadius = 30f)

        assertEquals(setOf(frontDoor.id), after.selectedDoorIds)
        assertEquals(1, after.manualDoors.size)
    }

    /** Nothing within the 30pt radius is a placement, not a near miss. */
    @Test
    fun `a tap beyond the hit radius places a door instead of selecting`() {
        val center = boxCenter(frontDoor.box, fit)

        val after = WanderingDoorSelection().tapped(
            tap = Offset(center.x + 31f, center.y),
            fit = fit,
            doors = listOf(frontDoor),
            hitRadius = 30f,
        )

        assertTrue(after.selectedDoorIds.isEmpty())
        assertEquals(1, after.manualDoors.size)
    }

    /** iOS `nearest` keeps the closest, and the first of two equal distances. */
    @Test
    fun `nearestIndex takes the closest candidate within the radius`() {
        val centers = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(4f, 0f))

        assertEquals(2, nearestIndex(centers, Offset(5f, 0f), radius = 30f))
        assertNull(nearestIndex(centers, Offset(100f, 0f), radius = 30f))
    }

    // ---- Restoring an existing configuration -------------------------------

    /** A stored door that matches a detected one above IoU 0.5 comes back selected. */
    @Test
    fun `an existing door matching a detection is restored as a selection`() {
        val stored = Detector.DoorConfig(
            doors = listOf(Detector.DoorConfig.Door(box = frontDoor.box, source = "vlm")),
        )

        val selection = existingSelection(stored, listOf(frontDoor, backDoor))

        assertEquals(setOf(frontDoor.id), selection.selectedDoorIds)
        assertTrue(selection.manualDoors.isEmpty())
    }

    /**
     * An unmatched stored door comes back as a manual marker. Without this a
     * caregiver reopening the picker after the VLM stopped finding their door
     * would see their saved configuration silently empty.
     */
    @Test
    fun `an existing door matching no detection is restored as a manual door`() {
        val orphan = listOf(0.40, 0.40, 0.10, 0.10)
        val stored = Detector.DoorConfig(
            doors = listOf(Detector.DoorConfig.Door(box = orphan, source = "manual")),
        )

        val selection = existingSelection(stored, listOf(frontDoor))

        assertTrue(selection.selectedDoorIds.isEmpty())
        assertEquals(listOf(ManualDoor(orphan)), selection.manualDoors)
    }

    /** "No door leads outside" restores nothing — pre-selecting would contradict it. */
    @Test
    fun `a no-exit-door configuration restores nothing`() {
        val stored = Detector.DoorConfig(
            doors = listOf(Detector.DoorConfig.Door(box = frontDoor.box)),
            noExitDoor = true,
        )

        assertEquals(WanderingDoorSelection(), existingSelection(stored, listOf(frontDoor)))
        assertEquals(WanderingDoorSelection(), existingSelection(null, listOf(frontDoor)))
    }

    /** A row with no box, or a malformed one, is skipped rather than crashing. */
    @Test
    fun `an existing door without a usable box is skipped`() {
        val stored = Detector.DoorConfig(
            doors = listOf(
                Detector.DoorConfig.Door(box = null),
                Detector.DoorConfig.Door(box = listOf(0.1, 0.2)),
            ),
        )

        assertEquals(WanderingDoorSelection(), existingSelection(stored, listOf(frontDoor)))
    }

    // ---- Detection results -------------------------------------------------

    /** A detection landing on a door the caregiver already placed is dropped. */
    @Test
    fun `detections stacked on a manual door are dropped`() {
        val kept = dropStackedDetections(
            found = listOf(frontDoor, backDoor),
            manualDoors = listOf(ManualDoor(frontDoor.box)),
        )

        assertEquals(listOf(backDoor), kept)
    }

    /** A merely nearby detection is kept — the rule is IoU > 0.4, not "close". */
    @Test
    fun `a detection that only brushes a manual door is kept`() {
        val nearby = ManualDoor(listOf(0.15, 0.20, 0.10, 0.30))

        assertEquals(
            listOf(frontDoor),
            dropStackedDetections(listOf(frontDoor), listOf(nearby)),
        )
    }

    // ---- The payload -------------------------------------------------------

    /** Selected VLM doors first with their labels, then the manual ones. */
    @Test
    fun `the payload carries sources and labels`() {
        val manual = ManualDoor(listOf(0.40, 0.40, 0.12, 0.20))
        val selection = WanderingDoorSelection(
            selectedDoorIds = setOf(backDoor.id),
            manualDoors = listOf(manual),
        )

        val payload = wanderingDoorInputs(listOf(frontDoor, backDoor), selection)

        assertEquals(
            listOf(
                WanderingDoorInput(box = backDoor.box, label = "back door", source = "vlm"),
                WanderingDoorInput(box = manual.box, label = null, source = "manual"),
            ),
            payload,
        )
    }

    /** iOS `setButtonTitle` — singular at one, plural elsewhere. */
    @Test
    fun `the CTA title counts the selection`() {
        assertEquals("Set exit doors", setExitDoorsTitle(0))
        assertEquals("Set 1 exit door", setExitDoorsTitle(1))
        assertEquals("Set 3 exit doors", setExitDoorsTitle(3))
    }

    // ---- Geometry ----------------------------------------------------------

    /** A 16:9 image in a 4:3 container is letterboxed top and bottom, centred. */
    @Test
    fun `fittedRect letterboxes a wide image`() {
        val rect = fittedRect(imageSize = Size(1600f, 900f), container = Size(400f, 300f))

        assertEquals(0f, rect.left, 0.001f)
        assertEquals(400f, rect.width, 0.001f)
        assertEquals(37.5f, rect.top, 0.001f)
        assertEquals(225f, rect.height, 0.001f)
    }

    /** The other axis: a 4:3 image in a 2:1 container is pillarboxed, centred. */
    @Test
    fun `fittedRect pillarboxes a tall image`() {
        val rect = fittedRect(imageSize = Size(1200f, 900f), container = Size(400f, 200f))

        assertEquals(200f / 3f, rect.left, 0.01f)
        assertEquals(800f / 3f, rect.width, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(200f, rect.height, 0.01f)
    }

    /** No frame yet: iOS passes the container as the image size, so it fills. */
    @Test
    fun `fittedRect falls back to the whole container for an empty image`() {
        assertEquals(
            Rect(Offset.Zero, Size(400f, 200f)),
            fittedRect(imageSize = Size(0f, 0f), container = Size(400f, 200f)),
        )
    }

    @Test
    fun `iou is 1 for the same box and 0 for disjoint boxes`() {
        assertEquals(1.0, iou(frontDoor.box, frontDoor.box), 1e-9)
        assertEquals(0.0, iou(frontDoor.box, backDoor.box), 1e-9)
        assertEquals(0.0, iou(listOf(0.1, 0.1), frontDoor.box), 1e-9)
    }

    // ---- The composition ---------------------------------------------------

    private fun setUp(
        state: WanderingDoorPickerState,
        onCancel: () -> Unit = {},
        onRescan: () -> Unit = {},
        onSelectionChange: (WanderingDoorSelection) -> Unit = {},
        onSave: (List<WanderingDoorInput>) -> Unit = {},
        onNoExitDoor: () -> Unit = {},
    ) {
        compose.setContent {
            MemoriaTheme {
                WanderingDoorPickerScreen(
                    state = state,
                    onCancel = onCancel,
                    onRescan = onRescan,
                    onSelectionChange = onSelectionChange,
                    onSave = onSave,
                    onNoExitDoor = onNoExitDoor,
                )
            }
        }
    }

    private fun ready(selection: WanderingDoorSelection = WanderingDoorSelection()) =
        WanderingDoorPickerState(
            cameraName = "Hallway",
            phase = WanderingDoorPhase.Ready,
            detectedDoors = listOf(frontDoor, backDoor),
            selection = selection,
        )

    /**
     * iOS: `.disabled(selectionCount == 0)`. Saving nothing would clear a
     * configuration the caregiver never asked to clear.
     */
    @Test
    fun `the CTA is inert with nothing selected`() {
        var saved: List<WanderingDoorInput>? = null
        setUp(ready(), onSave = { saved = it })

        compose.onNodeWithText("Set exit doors").performClick()

        assertNull(saved)
    }

    /** With a door selected the CTA saves that door, as "vlm". */
    @Test
    fun `the CTA saves the selected doors`() {
        var saved: List<WanderingDoorInput>? = null
        setUp(
            ready(WanderingDoorSelection(selectedDoorIds = setOf(frontDoor.id))),
            onSave = { saved = it },
        )

        compose.onNodeWithText("Set 1 exit door").performClick()

        assertEquals(
            listOf(WanderingDoorInput(box = frontDoor.box, label = "front door", source = "vlm")),
            saved,
        )
    }

    /** The declaration that turns wandering off for this camera. */
    @Test
    fun `no door leads outside is reported`() {
        var declared = false
        setUp(ready(), onNoExitDoor = { declared = true })

        compose.onNodeWithText("No door leads outside").performClick()

        assertTrue(declared)
    }

    /**
     * A tap on the canvas has to reach the hoisted selection. The screen holds no
     * copy of its own, so a broken gesture would look like a dead picker.
     */
    @Test
    fun `a tap on the canvas reports a new selection`() {
        var reported: WanderingDoorSelection? = null
        setUp(
            WanderingDoorPickerState(phase = WanderingDoorPhase.Ready),
            onSelectionChange = { reported = it },
        )

        compose.onNodeWithTag(FrameCanvasTestTag).performClick()

        // Clicked dead centre of a frame with no image, which iOS treats as
        // filling the canvas: one manual door, centred on the tap.
        val box = reported?.manualDoors?.single()?.box
        assertEquals(0.44, box?.get(0) ?: 0.0, 0.02)
        assertEquals(0.40, box?.get(1) ?: 0.0, 0.02)
    }

    /** The eyebrow is the only thing that says detection is still running. */
    @Test
    fun `the eyebrow announces detection while it runs`() {
        setUp(ready().copy(isDetectingDoors = true))

        compose.onNodeWithText("TAP EACH EXIT DOOR · FINDING DOORS…").assertIsDisplayed()
    }

    /**
     * A blocked or failed load renders the host's sentence verbatim, and "Try
     * again" is the same reload the toolbar's Rescan runs.
     */
    @Test
    fun `a failed load shows the message and retries`() {
        var rescans = 0
        setUp(
            WanderingDoorPickerState(phase = WanderingDoorPhase.Failed("Camera is offline.")),
            onRescan = { rescans += 1 },
        )

        compose.onNodeWithText("Camera is offline.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(1, rescans)
    }

    /** Rescan is refused while a load or a save is already in flight. */
    @Test
    fun `rescan is inert while busy`() {
        var rescans = 0
        setUp(
            WanderingDoorPickerState(phase = WanderingDoorPhase.Saving),
            onRescan = { rescans += 1 },
        )

        compose.onNodeWithTag(RescanTestTag).performClick()

        assertEquals(0, rescans)
    }

    /** The title names the sensor, because exit doors are per sensor. */
    @Test
    fun `the title names the camera`() {
        setUp(ready())

        compose.onNodeWithText("Hallway · exit doors").assertIsDisplayed()
    }
}
