package com.memoria.caregiver.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detection cadence and the privacy hold.
 *
 * PARITY.md §12 calls the hold a privacy guarantee rather than a smoothing
 * nicety, so these are behaviour tests, not tuning tests: the failure they exist
 * to catch is a person being un-blurred for a frame because the detector blinked.
 */
class BlurBoxHoldTest {

    private val personA = PixelRect(0f, 0f, 10f, 20f)
    private val personB = PixelRect(50f, 0f, 60f, 20f)

    /** Counts calls so "detection was skipped" is assertable, not assumed. */
    private class Detections(private vararg val results: List<PixelRect>) {
        var calls = 0
            private set

        fun next(): List<PixelRect> {
            val result = results[minOf(calls, results.size - 1)]
            calls++
            return result
        }
    }

    // ---- constants ----

    @Test fun `detection interval is sixty six milliseconds, about fifteen a second`() {
        assertEquals(66L, BlurBoxHold.DETECT_INTERVAL_MILLIS)
    }

    @Test fun `the hold is fifteen hundred milliseconds, web HOLD_MS`() {
        assertEquals(1_500L, BlurBoxHold.HOLD_MILLIS)
    }

    // ---- cadence ----

    @Test fun `the very first frame runs detection`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA))

        val boxes = hold.onFrame(nowMillis = 8_000L, detect = detections::next)

        assertEquals(1, detections.calls)
        assertEquals(listOf(personA), boxes)
    }

    @Test fun `frames inside the interval reuse the held boxes without detecting`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA))
        hold.onFrame(1_000L, detections::next)

        // 30 fps between detections: several frames land inside one 66 ms window.
        val boxes = hold.onFrame(1_033L, detections::next)

        assertEquals("detection ran again inside the interval", 1, detections.calls)
        assertEquals(listOf(personA), boxes)
    }

    @Test fun `detection runs again exactly at the interval, not one tick later`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA))
        hold.onFrame(1_000L, detections::next)

        hold.onFrame(1_065L, detections::next)
        assertEquals(1, detections.calls)

        hold.onFrame(1_066L, detections::next)
        assertEquals(2, detections.calls)
    }

    // ---- the hold ----

    @Test fun `an empty detection keeps the previous boxes for the whole hold`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA), emptyList())
        hold.onFrame(1_000L, detections::next)

        // Detector blinks. The person must stay blurred.
        val boxes = hold.onFrame(1_100L, detections::next)

        assertEquals(2, detections.calls)
        assertEquals(listOf(personA), boxes)
    }

    @Test fun `a box survives to the last millisecond of the hold and not past it`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA), emptyList())
        hold.onFrame(1_000L, detections::next)

        assertEquals(listOf(personA), hold.onFrame(2_499L, detections::next))
        assertTrue(hold.onFrame(2_500L, detections::next).isEmpty())
    }

    /**
     * The expiry is applied on every frame, not only on detection frames.
     * Checking it only when detection runs would leave a stale box painted for
     * up to one cadence past its hold — harmless here, but the same slip in the
     * other direction is what un-blurs someone.
     */
    @Test fun `boxes expire between detections`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA), emptyList())
        hold.onFrame(1_000L, detections::next) // sighted, expires at 2_500
        hold.onFrame(2_450L, detections::next) // detection frame, empty, still held
        val callsAfterSecondDetection = detections.calls

        // Inside the 66 ms window, so no detection — but past the 1.5 s hold.
        val boxes = hold.onFrame(2_500L, detections::next)

        assertEquals(callsAfterSecondDetection, detections.calls)
        assertTrue(boxes.isEmpty())
    }

    @Test fun `a fresh detection replaces the held set rather than merging with it`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA, personB), listOf(personB))
        hold.onFrame(1_000L, detections::next)

        // personA has left. The detector says so, so the box goes immediately
        // instead of ghosting for the rest of the hold.
        val boxes = hold.onFrame(1_100L, detections::next)

        assertEquals(listOf(personB), boxes)
    }

    @Test fun `each detection restarts the hold for the boxes it returns`() {
        val hold = BlurBoxHold()
        val detections = Detections(listOf(personA), listOf(personA), emptyList())
        hold.onFrame(1_000L, detections::next)
        hold.onFrame(2_400L, detections::next) // re-sighted, expiry pushed to 3_900

        assertEquals(listOf(personA), hold.onFrame(3_800L, detections::next))
    }

    @Test fun `an empty room stays empty`() {
        val hold = BlurBoxHold()
        val detections = Detections(emptyList())

        assertTrue(hold.onFrame(1_000L, detections::next).isEmpty())
        assertTrue(hold.onFrame(1_100L, detections::next).isEmpty())
        assertTrue(hold.onFrame(9_000L, detections::next).isEmpty())
    }
}
