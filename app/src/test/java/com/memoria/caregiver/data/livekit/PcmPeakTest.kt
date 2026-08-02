package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peak reader is where the waveform lives or dies. The iOS version shipped
 * reading the wrong sample layout and produced a permanently flat waveform
 * while audio flowed perfectly in both directions, so every branch here is
 * pinned.
 */
class PcmPeakTest {

    private val tolerance = 0.0005f

    @Test
    fun `float samples are normalised off the int16 scale, not off one`() {
        // WebRTC's AudioBuffer carries floats in ±32768, unlike iOS's ±1.0
        // AVAudioPCMBuffer. Half scale must read as half, not as 16383.
        val peak = pcmPeak(floatPcm(16383.5f, -8000f), sampleCount = 2)
        assertEquals(0.5f, peak!!, tolerance)
    }

    @Test
    fun `int16 samples are normalised by full scale`() {
        val peak = pcmPeak(shortPcm(16384, -100), sampleCount = 2)
        assertEquals(0.5f, peak!!, tolerance)
    }

    @Test
    fun `the most negative int16 sample does not overflow into a negative peak`() {
        // abs(Short.MIN_VALUE) is still Short.MIN_VALUE without widening, which
        // would produce a negative "peak" and a waveform bar of zero height on
        // the loudest possible sample.
        val peak = pcmPeak(shortPcm(Short.MIN_VALUE), sampleCount = 1)!!
        assertTrue("peak went negative: $peak", peak > 0f)
        assertEquals(1f, peak, tolerance)
    }

    @Test
    fun `a peak beyond full scale clamps to one`() {
        assertEquals(1f, pcmPeak(floatPcm(40_000f), sampleCount = 1)!!, tolerance)
    }

    @Test
    fun `magnitude is taken, so a negative swing counts`() {
        assertEquals(1f, pcmPeak(floatPcm(-32767f, 10f), sampleCount = 2)!!, tolerance)
    }

    @Test
    fun `every channel folds into one peak rather than doubling`() {
        // Two channels, interleaved, the second louder. One peak comes back.
        val peak = pcmPeak(floatPcm(1000f, 32767f, 1000f, 32767f), sampleCount = 4)
        assertEquals(1f, peak!!, tolerance)
    }

    @Test
    fun `silence reads as zero`() {
        assertEquals(0f, pcmPeak(floatPcm(0f, 0f), sampleCount = 2)!!, tolerance)
    }

    @Test
    fun `a wrong channel count still yields the buffer's real peak`() {
        // The channel count is only announced through initializeAudioProcessing.
        // If it is stale, the derived width is too large — the scan must still
        // cover the buffer rather than bail, because bailing is exactly the
        // flat-waveform failure this function exists to prevent.
        val peak = pcmPeak(floatPcm(0f, 0f, 32767f, 0f), sampleCount = 2)
        assertEquals(1f, peak!!, tolerance)
    }

    @Test
    fun `a sample count of zero is refused`() {
        assertNull(pcmPeak(floatPcm(1f), sampleCount = 0))
    }

    @Test
    fun `an empty buffer is refused`() {
        assertNull(pcmPeak(floatPcm(), sampleCount = 4))
    }

    @Test
    fun `a width under two bytes per sample is refused`() {
        assertNull(pcmPeak(bytePcm(4), sampleCount = 4))
    }

    @Test
    fun `the caller's buffer position and byte order are left alone`() {
        val buffer = floatPcm(16383.5f, 0f)
        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()
        val orderBefore = buffer.order()

        pcmPeak(buffer, sampleCount = 2)

        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
        assertEquals(orderBefore, buffer.order())
    }

    @Test
    fun `reading starts at the buffer's position, not at zero`() {
        val buffer = floatPcm(32767f, 1000f)
        buffer.position(Float.SIZE_BYTES) // skip the loud first sample
        assertEquals(1000f / PCM_FULL_SCALE, pcmPeak(buffer, sampleCount = 1)!!, tolerance)
    }
}
