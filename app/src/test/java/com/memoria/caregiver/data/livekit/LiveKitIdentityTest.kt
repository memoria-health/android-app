package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highest-consequence string in the port.
 *
 * LiveKit treats participant identity as a unique key per room, so an Android
 * build that minted `_ios` would kick the caregiver's own iPhone off the stream
 * the moment they opened the Live tab — silently, with no error on either
 * device.
 */
class LiveKitIdentityTest {

    private val caregiverId = "22222222-2222-2222-2222-222222222222"

    @Test
    fun `the platform suffix is android`() {
        assertEquals("_android", LiveKitIdentity.PLATFORM_SUFFIX)
    }

    @Test
    fun `the identity never carries another platform's suffix`() {
        val identity = LiveKitIdentity.caregiverIdentity(caregiverId)
        assertFalse("would kick the caregiver's iPhone: $identity", identity.endsWith("_ios"))
        assertFalse("would kick the caregiver's browser: $identity", identity.endsWith("_web"))
        assertTrue(identity.endsWith("_android"))
    }

    @Test
    fun `the identity is the caregiver id under the caregiver prefix`() {
        assertEquals(
            "caregiver_22222222-2222-2222-2222-222222222222_android",
            LiveKitIdentity.caregiverIdentity(caregiverId),
        )
    }

    @Test
    fun `an uppercase uuid is lowercased`() {
        // Swift's uuidString is uppercase; the backend prefix check, the web
        // client and the LiveKit audit logs all expect lowercase.
        assertEquals(
            "caregiver_22222222-2222-2222-2222-222222222222_android",
            LiveKitIdentity.caregiverIdentity(caregiverId.uppercase()),
        )
    }

    @Test
    fun `the room is the patient id under the patient prefix`() {
        assertEquals(
            "patient_11111111-2222-3333-4444-555555555555",
            LiveKitIdentity.roomName("11111111-2222-3333-4444-555555555555"),
        )
    }

    @Test
    fun `an uppercase patient uuid is lowercased`() {
        assertEquals(
            "patient_11111111-2222-3333-4444-555555555555",
            LiveKitIdentity.roomName("11111111-2222-3333-4444-555555555555".uppercase()),
        )
    }
}
