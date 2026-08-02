package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelBufferTest {

    private val kitchen = "cam_kitchen0"

    @Test
    fun `the capacity is eighty samples`() {
        assertEquals(80, AUDIO_LEVEL_CAPACITY)
    }

    @Test
    fun `a push with no active call is dropped`() {
        val buffer = AudioLevelBuffer()
        assertFalse(buffer.push(0.9f, fromLocal = false, activeCallIdentity = null))
        assertEquals(emptyMap<String, List<Float>>(), buffer.levels)
    }

    @Test
    fun `a peak pushed with no active call is still recorded for the next bar`() {
        val buffer = AudioLevelBuffer()
        buffer.push(0.8f, fromLocal = true, activeCallIdentity = null)
        // The call starts; the very first bar must reflect the mic peak we
        // already saw rather than starting from zero.
        buffer.push(0f, fromLocal = false, activeCallIdentity = kitchen)
        assertEquals(listOf(0.8f), buffer.levels[kitchen])
    }

    @Test
    fun `the louder of the two taps wins`() {
        val buffer = AudioLevelBuffer()
        buffer.push(0.2f, fromLocal = true, activeCallIdentity = kitchen)
        buffer.push(0.7f, fromLocal = false, activeCallIdentity = kitchen)
        buffer.push(0.1f, fromLocal = true, activeCallIdentity = kitchen)

        // Third bar: local dropped to 0.1 but remote is still 0.7, so the
        // waveform tracks whoever is louder instead of combing.
        assertEquals(listOf(0.2f, 0.7f, 0.7f), buffer.levels[kitchen])
    }

    @Test
    fun `levels accumulate only for the camera in the call`() {
        val buffer = AudioLevelBuffer()
        buffer.push(0.5f, fromLocal = false, activeCallIdentity = kitchen)
        assertEquals(setOf(kitchen), buffer.levels.keys)
    }

    @Test
    fun `the window trims from the front at capacity`() {
        val buffer = AudioLevelBuffer(capacity = 3)
        listOf(0.1f, 0.2f, 0.3f, 0.4f).forEach {
            buffer.push(it, fromLocal = false, activeCallIdentity = kitchen)
        }
        assertEquals(listOf(0.2f, 0.3f, 0.4f), buffer.levels[kitchen])
    }

    @Test
    fun `the default window never grows past eighty`() {
        val buffer = AudioLevelBuffer()
        repeat(200) { buffer.push(it / 200f, fromLocal = false, activeCallIdentity = kitchen) }
        assertEquals(AUDIO_LEVEL_CAPACITY, buffer.levels[kitchen]!!.size)
        assertEquals(199 / 200f, buffer.levels[kitchen]!!.last(), 0f)
    }

    @Test
    fun `a push that lands reports a change`() {
        val buffer = AudioLevelBuffer()
        assertTrue(buffer.push(0.5f, fromLocal = false, activeCallIdentity = kitchen))
    }

    @Test
    fun `ending a call flattens the waveform and clears both peaks`() {
        val buffer = AudioLevelBuffer()
        buffer.push(0.9f, fromLocal = true, activeCallIdentity = kitchen)
        buffer.push(0.8f, fromLocal = false, activeCallIdentity = kitchen)
        buffer.endCall()

        assertEquals(emptyMap<String, List<Float>>(), buffer.levels)

        // A later call must not inherit the old peaks.
        buffer.push(0f, fromLocal = true, activeCallIdentity = kitchen)
        assertEquals(listOf(0f), buffer.levels[kitchen])
    }

    @Test
    fun `a room teardown clears the drawn levels`() {
        val buffer = AudioLevelBuffer()
        buffer.push(0.9f, fromLocal = true, activeCallIdentity = kitchen)
        buffer.clearLevels()
        assertEquals(emptyMap<String, List<Float>>(), buffer.levels)
    }

    @Test
    fun `an unsubscribed camera loses its levels and nobody else's`() {
        val buffer = AudioLevelBuffer()
        buffer.push(0.9f, fromLocal = true, activeCallIdentity = kitchen)
        buffer.push(0.4f, fromLocal = true, activeCallIdentity = "cam_hallway0")
        buffer.remove(kitchen)

        assertEquals(setOf("cam_hallway0"), buffer.levels.keys)
    }
}
