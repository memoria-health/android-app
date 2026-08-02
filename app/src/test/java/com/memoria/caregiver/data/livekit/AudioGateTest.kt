package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGateTest {

    private val kitchen = "cam_kitchen0"
    private val hallway = "cam_hallway0"

    @Test
    fun `nothing is audible by default`() {
        val gate = AudioGate()
        assertFalse(gate.isAudible(kitchen))
        assertEquals(MUTED_VOLUME, gate.volumeFor(kitchen), 0.0)
    }

    @Test
    fun `the per-tile toggle reports and applies the new state`() {
        val gate = AudioGate()
        assertTrue(gate.toggle(kitchen))
        assertTrue(gate.isAudible(kitchen))
        assertEquals(AUDIBLE_VOLUME, gate.volumeFor(kitchen), 0.0)

        assertFalse(gate.toggle(kitchen))
        assertFalse(gate.isAudible(kitchen))
        assertEquals(MUTED_VOLUME, gate.volumeFor(kitchen), 0.0)
    }

    @Test
    fun `a toggle touches only its own tile`() {
        val gate = AudioGate()
        gate.toggle(kitchen)
        assertFalse(gate.isAudible(hallway))
    }

    @Test
    fun `an active call makes its camera audible without unmuting it`() {
        val gate = AudioGate()
        gate.setActiveCall(kitchen)
        assertTrue(gate.isAudible(kitchen))
        assertEquals(emptySet<String>(), gate.audioEnabledByIdentity)
    }

    @Test
    fun `talk overlays the per-tile choices rather than replacing them`() {
        val gate = AudioGate()
        gate.toggle(hallway) // caregiver is listening to the hallway
        gate.setActiveCall(kitchen)

        assertTrue(gate.isAudible(kitchen))
        assertTrue(gate.isAudible(hallway))

        gate.setActiveCall(null)
        assertFalse(gate.isAudible(kitchen))
        assertTrue("silencing a tile must stick across a call", gate.isAudible(hallway))
    }

    @Test
    fun `a tile muted during a call on it is still audible until the call ends`() {
        val gate = AudioGate()
        gate.toggle(kitchen)
        gate.setActiveCall(kitchen)
        gate.toggle(kitchen) // caregiver mutes the tile mid-call

        assertTrue("talk wins while the call is up", gate.isAudible(kitchen))
        gate.setActiveCall(null)
        assertFalse(gate.isAudible(kitchen))
    }

    @Test
    fun `revoke removes the identity from the set, not just its volume`() {
        // The privacy hole: handleSubscribedAudio restores volume FROM this
        // set, so an entry left behind re-arms playback on the next
        // resubscribe — audio out of a bathroom, after the caregiver was told
        // it was cut.
        val gate = AudioGate()
        gate.toggle(kitchen)
        gate.revoke(kitchen)

        assertFalse(kitchen in gate.audioEnabledByIdentity)
        assertEquals(MUTED_VOLUME, gate.volumeFor(kitchen), 0.0)
    }

    @Test
    fun `revoke also drops the talk overlay for that camera`() {
        val gate = AudioGate()
        gate.setActiveCall(kitchen)
        gate.revoke(kitchen)

        assertFalse(gate.isAudible(kitchen))
        assertEquals(null, gate.activeCallIdentity)
    }

    @Test
    fun `revoking one camera leaves an active call on another alone`() {
        val gate = AudioGate()
        gate.setActiveCall(hallway)
        gate.revoke(kitchen)

        assertEquals(hallway, gate.activeCallIdentity)
        assertTrue(gate.isAudible(hallway))
    }

    @Test
    fun `a revoked camera stays muted after being re-subscribed`() {
        val gate = AudioGate()
        gate.toggle(kitchen)
        gate.revoke(kitchen)
        // A resubscribe asks the gate what volume to restore.
        assertEquals(MUTED_VOLUME, gate.volumeFor(kitchen), 0.0)
    }

    @Test
    fun `clear resets both halves for a fresh patient session`() {
        val gate = AudioGate()
        gate.toggle(kitchen)
        gate.setActiveCall(hallway)
        gate.clear()

        assertEquals(emptySet<String>(), gate.audioEnabledByIdentity)
        assertEquals(null, gate.activeCallIdentity)
        assertFalse(gate.isAudible(kitchen))
        assertFalse(gate.isAudible(hallway))
    }
}
