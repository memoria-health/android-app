package com.memoria.caregiver.ui.cameras

import com.memoria.caregiver.data.model.Detector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which cameras the wandering detector watches.
 *
 * This decides whether the exit-doors section appears on a sensor, so both
 * directions cost something real: too wide puts a doors section on a
 * living-room camera that will never see an exit, and too narrow silently drops
 * a door the caregiver meant to watch.
 *
 * The case-insensitivity is not defensive tidiness — the backend stores room
 * names in capitals ("HALLWAY") while the caregiver typed "Hallway", and
 * comparing them as-is showed the section on the wrong cameras (iOS `6065961`).
 */
class WanderingRoomScopeTest {

    private fun wandering(rooms: List<String>?): Detector =
        testDetector(identifier = "wandering", whereRooms = rooms)

    @Test
    fun `a matching room is watched`() {
        assertTrue(wanderingWatchesRoom("Hallway", wandering(listOf("Hallway"))))
    }

    @Test
    fun `the backend's capitals still match what the caregiver typed`() {
        // The actual bug. where_rooms comes back "HALLWAY"; the camera's room is
        // "Hallway" as entered.
        assertTrue(wanderingWatchesRoom("Hallway", wandering(listOf("HALLWAY"))))
    }

    @Test
    fun `the caregiver's capitals match the backend's stored name`() {
        assertTrue(wanderingWatchesRoom("HALLWAY", wandering(listOf("Hallway"))))
    }

    @Test
    fun `a room that is not in the list is not watched`() {
        // The point of the feature: a living-room sensor carries no doors
        // section when wandering only watches the hallway.
        assertFalse(wanderingWatchesRoom("Living room", wandering(listOf("Hallway"))))
    }

    @Test
    fun `one of several rooms matches`() {
        assertTrue(
            wanderingWatchesRoom("Kitchen", wandering(listOf("HALLWAY", "KITCHEN", "PORCH"))),
        )
    }

    @Test
    fun `null where_rooms means every camera`() {
        // Pre-feature behaviour, and the right default for a safety detector.
        assertTrue(wanderingWatchesRoom("Anywhere", wandering(null)))
    }

    @Test
    fun `empty where_rooms means every camera`() {
        assertTrue(wanderingWatchesRoom("Anywhere", wandering(emptyList())))
    }

    @Test
    fun `the ALL ROOMS sentinel means every camera`() {
        // The backend writes this literal rather than null when the caregiver
        // picks everything, so it has to be understood as a wildcard and not
        // matched as a room name.
        assertTrue(wanderingWatchesRoom("Bedroom", wandering(listOf("ALL ROOMS"))))
        assertTrue(wanderingWatchesRoom("Bedroom", wandering(listOf("ALL"))))
    }

    @Test
    fun `a room genuinely named all rooms is not confused with the sentinel`() {
        // Reading the other way: the sentinel is checked against the DETECTOR's
        // list, never against the camera's own name, so a camera called
        // "All rooms" is matched only if the detector actually lists it.
        assertFalse(wanderingWatchesRoom("All rooms", wandering(listOf("Hallway"))))
    }
}
