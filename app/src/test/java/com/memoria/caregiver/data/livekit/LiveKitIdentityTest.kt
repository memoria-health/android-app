package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    /**
     * The Live tab mints **broadcaster** on connect, before any call, so
     * starting one is a mic toggle rather than a reconnect. `viewer` is carried
     * because iOS carries both.
     */
    @Test
    fun `the two token roles are the strings the backend accepts`() {
        assertEquals("broadcaster", LiveKitIdentity.BROADCASTER_ROLE)
        assertEquals("viewer", LiveKitIdentity.VIEWER_ROLE)
    }

    /**
     * Pins that the identity does not change with the device locale.
     *
     * Kotlin's no-argument `lowercase()` already lowers with `Locale.ROOT` —
     * that is why it replaced `toLowerCase()`, which used the device locale and
     * did map `I` to a dotless `ı` on a Turkish device. So this test guards a
     * guarantee the stdlib gives rather than one this code adds. It is here
     * because the two spellings differ by nothing visible at a call site, and
     * this is the one string where a silent change evicts a caregiver's iPhone
     * from the live stream.
     */
    @Test
    fun `lowercasing does not depend on the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                "caregiver_ii11-2222_android",
                LiveKitIdentity.caregiverIdentity("II11-2222"),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }
}
