package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loudspeaker rule.
 *
 * Until this file existed the rule was written twice — once in `TalkMode.kt`
 * with the headset case handled and never wired, once in `LiveKitClient` wired
 * and without it (FINDINGS.md F-1). Neither copy had a test that covered the
 * headset case, which is the one that matters: getting it wrong does not break
 * the feature, it broadcasts a private conversation.
 */
class AudioRoutingTest {

    @Test
    fun `the earpiece is overridden`() {
        // The failure the rule exists for: publishing the mic drops output to
        // the earpiece, and a caregiver monitoring a room from across the house
        // hears nothing until they hold the phone to their ear.
        assertTrue(shouldForceSpeakerphone(AudioRouteKind.Earpiece))
    }

    @Test
    fun `an unknown route is overridden`() {
        // Too loud is recoverable. A caregiver concluding Talk is broken is not.
        assertTrue(shouldForceSpeakerphone(AudioRouteKind.Unknown))
    }

    @Test
    fun `the speakerphone is left alone because it is already the goal`() {
        assertFalse(shouldForceSpeakerphone(AudioRouteKind.Speakerphone))
    }

    @Test
    fun `a wired headset is left alone`() {
        // Not the earpiece, so not the failure being guarded against. Yanking a
        // headset user to the loudspeaker puts a conversation about someone's
        // parent on speaker in public — worse than the bug this prevents.
        assertFalse(shouldForceSpeakerphone(AudioRouteKind.WiredHeadset))
    }

    @Test
    fun `a bluetooth headset is left alone`() {
        assertFalse(shouldForceSpeakerphone(AudioRouteKind.BluetoothHeadset))
    }

    /**
     * Guards the enum itself: a route added without a decision would otherwise
     * be silently absent from the rule. `when` over an enum is exhaustive, so a
     * new constant breaks the compile — this pins the set so the break is read
     * as "decide what this route does", not as noise.
     */
    @Test
    fun `every route the app models has a decision`() {
        assertEquals(5, AudioRouteKind.entries.size)
        AudioRouteKind.entries.forEach { shouldForceSpeakerphone(it) }
    }
}
