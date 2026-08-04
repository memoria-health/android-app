package com.memoria.caregiver.ui.cameras

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room names must be unique per patient.
 *
 * Not a tidiness rule: the room name is a **key**. Wandering scopes itself by
 * it ([wanderingWatchesRoom]), the Home report groups by it, and the camera info
 * panel counts by it. Two cameras called "Hallway" make all three ambiguous, and
 * the ambiguity surfaces later as a feature quietly doing the wrong thing rather
 * than as an error anyone can see.
 */
class DuplicateRoomNameTest {

    private val hallway = testCamera(id = "cam-1", room = "Hallway")

    @Test
    fun `a free name is accepted`() {
        assertNull(duplicateRoomMessage("Kitchen", listOf(hallway)))
    }

    @Test
    fun `an exact duplicate is refused`() {
        assertNotNull(duplicateRoomMessage("Hallway", listOf(hallway)))
    }

    @Test
    fun `case does not let a duplicate through`() {
        // "HALLWAY" and "Hallway" are the same room to everything downstream,
        // so they have to be the same room here.
        assertNotNull(duplicateRoomMessage("HALLWAY", listOf(hallway)))
        assertNotNull(duplicateRoomMessage("hallway", listOf(hallway)))
    }

    @Test
    fun `surrounding whitespace does not let a duplicate through`() {
        assertNotNull(duplicateRoomMessage("  Hallway  ", listOf(hallway)))
    }

    @Test
    fun `a hidden camera does not reserve its name`() {
        // The caregiver has already removed it from view. Refusing a name on
        // account of a row they cannot see would be unexplainable.
        val hidden = testCamera(id = "cam-2", room = "Porch", disabled = true)
        assertNull(duplicateRoomMessage("Porch", listOf(hidden)))
    }

    @Test
    fun `an empty name is not reported as a duplicate`() {
        // The submit button is already disabled for it; reporting "a camera
        // named "" exists" would be nonsense on top of that.
        assertNull(duplicateRoomMessage("", listOf(hallway)))
        assertNull(duplicateRoomMessage("   ", listOf(hallway)))
    }

    @Test
    fun `the message names the room the caregiver typed`() {
        val message = duplicateRoomMessage("hallway", listOf(hallway))
        // Their spelling, not the stored one — being corrected with a different
        // capitalisation than you typed reads as a bug.
        assertTrue(message!!.contains("hallway"))
    }

    @Test
    fun `the first camera for a patient is always free`() {
        assertNull(duplicateRoomMessage("Hallway", emptyList()))
    }
}
