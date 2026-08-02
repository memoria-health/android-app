package com.memoria.caregiver.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Waveform geometry. Spec: DesignSystem/Components/NemaraWaveform.swift and
 * PARITY.md §28 ("right-aligned bars, newest on the right, 40 bars, 6% floor so
 * silence still shows a baseline").
 *
 * Everything here is pure: the composable does nothing but multiply these
 * numbers by the container it is handed, so the whole risk surface is in this
 * file.
 */
class NemaraWaveformTest {

    private val floor = WaveformDefaultMinHeightFraction

    /** Four bars is enough to see the alignment; 40 only makes the expectations long. */
    private fun fractions(samples: List<Float>) = waveformFractions(samples, 4, floor).toList()

    // ---- Trailing window ----

    @Test fun `trailing window keeps the newest samples and drops the oldest`() {
        val samples = (0 until 50).map { it.toFloat() }
        val window = waveformTrailingSamples(samples, 40)
        assertEquals(40, window.size)
        assertEquals(10f, window.first(), 0f)
        assertEquals(49f, window.last(), 0f)
    }

    @Test fun `a warming-up buffer passes through untouched`() {
        val samples = listOf(0.1f, 0.2f, 0.3f)
        assertEquals(samples, waveformTrailingSamples(samples, 40))
        assertEquals(emptyList<Float>(), waveformTrailingSamples(emptyList(), 40))
    }

    // ---- Right alignment: the behavior the component exists for ----

    @Test fun `samples fill from the right so the shape slides leftward`() {
        assertEquals(
            listOf(floor, floor, 0.1f, 0.2f),
            fractions(listOf(0.1f, 0.2f)),
        )
    }

    @Test fun `a single sample lands in the rightmost bar only`() {
        val f = waveformFractions(listOf(0.9f), WaveformDefaultBarCount, floor)
        assertEquals(0.9f, f[WaveformDefaultBarCount - 1], 0f)
        assertEquals(floor, f[WaveformDefaultBarCount - 2], 0f)
        assertEquals(floor, f[0], 0f)
    }

    @Test fun `an exactly full window uses every sample in order`() {
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), fractions(listOf(0.1f, 0.2f, 0.3f, 0.4f)))
    }

    // ---- Floor and clamping ----

    @Test fun `silence still shows a baseline at six percent`() {
        assertEquals(0.06f, floor, 0f)
        assertEquals(listOf(floor, floor, floor, floor), fractions(emptyList()))
        // A near-silent level is lifted to the floor rather than rendered flat.
        assertEquals(listOf(floor, floor, floor, floor), fractions(listOf(0f, 0.01f, 0.02f, 0.059f)))
    }

    @Test fun `levels above one clamp to the full container height`() {
        assertEquals(listOf(1f, 1f, floor, 0.5f), fractions(listOf(1f, 4.2f, -3f, 0.5f)))
    }

    @Test fun `there is always one fraction per bar whatever the sample count`() {
        assertEquals(12, waveformFractions((0 until 100).map { 0.5f }, 12, floor).size)
        assertEquals(12, waveformFractions(emptyList(), 12, floor).size)
    }

    /**
     * The trailing window and the right-alignment offset are two separate steps
     * in the Swift and they have to agree: `waveformLevel` subtracts `barCount`
     * from the window length, so feeding it the unwindowed list would shift the
     * whole waveform. This pins the composed result.
     */
    @Test fun `windowing then aligning shows the newest barCount samples in order`() {
        val samples = (0 until 10).map { it / 10f }
        val window = waveformTrailingSamples(samples, 3)
        assertEquals(
            listOf(0.7f, 0.8f, 0.9f),
            waveformFractions(window, 3, floor).toList(),
        )
    }

    // ---- Bar width and centring ----

    @Test fun `bars plus spacing divide the container exactly`() {
        val spacing = 2f
        val width = 200f
        val barWidth = waveformBarWidth(width, 40, spacing, 1f)
        assertEquals(3.05f, barWidth, 1e-5f)
        assertEquals(0f, waveformRowOrigin(width, barWidth, 40, spacing), 1e-4f)
    }

    @Test fun `a container too narrow for 40 bars clamps the bar width and centres the overflow`() {
        val spacing = 2f
        val width = 40f
        val barWidth = waveformBarWidth(width, 40, spacing, 1f)
        assertEquals(1f, barWidth, 0f)
        // 40 * 1 + 39 * 2 = 118, so 39 hangs off each edge.
        assertEquals(-39f, waveformRowOrigin(width, barWidth, 40, spacing), 1e-4f)
    }

    /** A zero-size container is a real Compose state (first layout pass). */
    @Test fun `a zero-width container still yields the minimum bar width`() {
        assertEquals(1f, waveformBarWidth(0f, 40, 2f, 1f), 0f)
    }

    // ---- Tuning constants carried from the Swift ----

    /**
     * Not a restatement of the implementation: these five numbers are the ones a
     * refactor silently rounds or "tidies". They are the contract with the iOS
     * screenshots.
     */
    @Test fun `tuning constants match NemaraWaveform swift`() {
        assertEquals(40, WaveformDefaultBarCount)
        assertEquals(0.06f, WaveformDefaultMinHeightFraction, 0f)
        assertEquals(2.dp, WaveformBarSpacing)
        assertEquals(1.dp, WaveformMinBarWidth)
        assertEquals(80, WaveformAnimationDurationMillis)
    }
}

/**
 * The animation must restart only when the bar heights actually change.
 *
 * HARDENING.md H-2: the effect was keyed on the FloatArray instance, which
 * `waveformFractions` returns fresh every composition. Compose compares keys
 * with `==`, which for arrays is referential identity, so every recomposition
 * restarted it and cut in-flight animations short — a stutter on the talk-mode
 * waveform exactly while a caregiver is speaking.
 */
class WaveformAnimationKeyTest {

    @org.junit.Test
    fun `equal level sets produce equal keys`() {
        val a = waveformFractions(listOf(0.2f, 0.5f, 0.9f), 3, 0.06f)
        val b = waveformFractions(listOf(0.2f, 0.5f, 0.9f), 3, 0.06f)

        // The defect: two arrays with identical contents are NOT equal.
        org.junit.Assert.assertNotEquals(a, b)
        // The fix: their list views are, so the effect does not restart.
        org.junit.Assert.assertEquals(a.toList(), b.toList())
    }

    @org.junit.Test
    fun `different level sets produce different keys`() {
        val a = waveformFractions(listOf(0.2f, 0.5f, 0.9f), 3, 0.06f)
        val b = waveformFractions(listOf(0.2f, 0.5f, 0.4f), 3, 0.06f)
        org.junit.Assert.assertNotEquals(a.toList(), b.toList())
    }
}
