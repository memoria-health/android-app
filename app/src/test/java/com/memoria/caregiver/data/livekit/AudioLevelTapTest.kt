package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap itself is thin, but two things in it are worth pinning: it must fire
 * per buffer (a tap that silently stops is the flat-waveform bug), and it must
 * fold the announced channel count into the sample count it derives the sample
 * width from.
 */
class AudioLevelTapTest {

    private fun recordingTap(name: String = "_remote_"): Pair<AudioLevelTap, MutableList<Float>> {
        val peaks = mutableListOf<Float>()
        val tap = AudioLevelTap(name) { peaks += it }
        return Pair(tap, peaks)
    }

    @Test
    fun `the tap is always enabled and carries its name`() {
        val (tap, _) = recordingTap("_local_")
        assertTrue(tap.isEnabled())
        assertEquals("_local_", tap.getName())
    }

    @Test
    fun `each buffer forwards one peak`() {
        val (tap, peaks) = recordingTap()
        tap.initializeAudioProcessing(48_000, 1)

        tap.processAudio(numBands = 1, numFrames = 2, buffer = floatPcm(16383.5f, 0f))
        tap.processAudio(numBands = 1, numFrames = 2, buffer = floatPcm(32767f, 0f))

        assertEquals(2, peaks.size)
        assertEquals(0.5f, peaks[0], 0.0005f)
        assertEquals(1f, peaks[1], 0.0005f)
    }

    @Test
    fun `a stereo announcement is folded into the sample count`() {
        val (tap, peaks) = recordingTap()
        tap.initializeAudioProcessing(48_000, 2)

        // 1 band x 2 frames x 2 channels = 4 samples over 16 bytes = float32.
        tap.processAudio(numBands = 1, numFrames = 2, buffer = floatPcm(0f, 0f, 16383.5f, 0f))

        assertEquals(1, peaks.size)
        assertEquals(0.5f, peaks[0], 0.0005f)
    }

    @Test
    fun `one channel is assumed before the SDK announces anything`() {
        val (tap, peaks) = recordingTap()
        tap.processAudio(numBands = 1, numFrames = 2, buffer = floatPcm(32767f, 0f))
        assertEquals(listOf(1f), peaks)
    }

    @Test
    fun `a nonsense channel count does not zero the sample count`() {
        val (tap, peaks) = recordingTap()
        tap.initializeAudioProcessing(48_000, 0)
        tap.processAudio(numBands = 1, numFrames = 1, buffer = floatPcm(32767f))
        assertEquals(listOf(1f), peaks)
    }

    @Test
    fun `a buffer that cannot be read pushes nothing`() {
        val (tap, peaks) = recordingTap()
        tap.initializeAudioProcessing(48_000, 1)
        tap.processAudio(numBands = 0, numFrames = 0, buffer = floatPcm(1f))
        assertEquals(emptyList<Float>(), peaks)
    }

    @Test
    fun `int16 buffers are read too, so a layout change does not flatten the waveform`() {
        val (tap, peaks) = recordingTap()
        tap.initializeAudioProcessing(48_000, 1)
        tap.processAudio(numBands = 1, numFrames = 2, buffer = shortPcm(Short.MIN_VALUE, 0))
        assertEquals(listOf(1f), peaks)
    }

    @Test
    fun `a reset between buffers does not stop the tap`() {
        val (tap, peaks) = recordingTap()
        tap.initializeAudioProcessing(48_000, 1)
        tap.processAudio(numBands = 1, numFrames = 1, buffer = floatPcm(32767f))
        tap.resetAudioProcessing(16_000)
        tap.processAudio(numBands = 1, numFrames = 1, buffer = floatPcm(32767f))
        assertEquals(2, peaks.size)
    }
}
