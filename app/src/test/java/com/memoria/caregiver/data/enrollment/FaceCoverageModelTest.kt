package com.memoria.caregiver.data.enrollment

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directional pose-coverage engine. Spec: `PARITY.md §20`, ported from
 * `Features/Onboarding/Enrollment/FaceCoverageModel.swift`.
 *
 * The three things this file exists to hold down, in `PARITY.md §20`'s words:
 * capture reads the RAW pose and only the needle is smoothed; the quality floor
 * is a RAMP, not a threshold; and the caption is debounced on a MONOTONIC clock.
 */
class FaceCoverageModelTest {

    private val clock = TestClock()
    private var jpegCalls = 0
    private var nextByte = 0

    private fun jpeg(): ByteArray? {
        jpegCalls++
        return byteArrayOf(nextByte++.toByte())
    }

    private fun model(
        segmentCount: Int = 12,
        frontalMaxDeg: Double = 10.0,
        extremeMinDeg: Double = 22.0,
        reachTargetDeg: Double = 45.0,
        minQuality: Float = 0.4f,
        minQualityFloor: Float = 0.25f,
        framesPerSegment: Int = 2,
        frontalTarget: Int = 2,
        guidanceDwellMillis: Long = 300L,
    ) = FaceCoverageModel(
        segmentCount = segmentCount,
        frontalMaxDeg = frontalMaxDeg,
        extremeMinDeg = extremeMinDeg,
        reachTargetDeg = reachTargetDeg,
        minQuality = minQuality,
        minQualityFloor = minQualityFloor,
        framesPerSegment = framesPerSegment,
        frontalTarget = frontalTarget,
        guidanceDwellMillis = guidanceDwellMillis,
        now = clock,
    )

    /** One frame, with everything that is not under test set to "fine". */
    private fun FaceCoverageModel.feed(
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        quality: Float = 1f,
        framed: Boolean = true,
        brightness: Float = 1f,
        faceHeight: Double = 1.0,
        jpeg: () -> ByteArray? = ::jpeg,
    ): Boolean = ingest(yaw, pitch, quality, framed, brightness, faceHeight, jpeg)

    /** A pose pointing at the centre of [segment], [mag] degrees from the hub. */
    private fun pose(segment: Int, mag: Double, segmentCount: Int = 12): Pose {
        val radians = (segment + 0.5) * (360.0 / segmentCount) * PI / 180.0
        return Pose(mag * cos(radians), mag * sin(radians))
    }

    private data class Pose(val yaw: Double, val pitch: Double)

    private fun FaceCoverageModel.feedPose(
        segment: Int,
        mag: Double = 25.0,
        quality: Float = 1f,
        framed: Boolean = true,
        brightness: Float = 1f,
        faceHeight: Double = 1.0,
        segmentCount: Int = 12,
        jpeg: () -> ByteArray? = ::jpeg,
    ): Boolean {
        val p = pose(segment, mag, segmentCount)
        return feed(p.yaw, p.pitch, quality, framed, brightness, faceHeight, jpeg)
    }

    /** Fills every arc to [FaceCoverageModel.framesPerSegment]. No frontal. */
    private fun FaceCoverageModel.coverAllArcs() {
        repeat(segmentCount) { seg ->
            repeat(framesPerSegment) { feedPose(seg, segmentCount = segmentCount) }
        }
    }

    // ── tuning constants ──────────────────────────────────────────

    @Test
    fun `the defaults are the iOS tuning, unrounded`() {
        val m = FaceCoverageModel()

        assertEquals(12, m.segmentCount)
        assertEquals(10.0, m.frontalMaxDeg, 0.0)
        assertEquals(22.0, m.extremeMinDeg, 0.0)
        assertEquals(45.0, m.reachTargetDeg, 0.0)
        assertEquals(0.4f, m.minQuality, 0.0f)
        assertEquals(0.25f, m.minQualityFloor, 0.0f)
        assertEquals(2, m.framesPerSegment)
        assertEquals(2, m.frontalTarget)
        // iOS guidanceDwell: TimeInterval = 0.3 seconds.
        assertEquals(300L, m.guidanceDwellMillis)
        assertEquals(0.4, FaceCoverageModel.NEEDLE_ALPHA, 0.0)
        assertEquals(0.28, FaceCoverageModel.TOO_FAR_HEIGHT, 0.0)
        assertEquals(0.28f, FaceCoverageModel.DARK_LUMA, 0.0f)
    }

    @Test
    fun `the dial is twelve equal arcs whose centres straddle the axes`() {
        val segments = model().state.value.segments

        assertEquals(12, segments.size)
        segments.forEachIndexed { i, segment ->
            assertEquals(i, segment.id)
            assertEquals((i + 0.5) * 30.0, segment.centreDeg, 1e-9)
            assertFalse(segment.covered)
            assertEquals(0, segment.frameCount)
        }
    }

    @Test
    fun `the initial state is an empty dial, searching`() {
        val s = model().state.value

        assertEquals(0, s.frontalCount)
        assertNull(s.currentSegment)
        assertEquals(0.0, s.currentMagDeg, 0.0)
        assertEquals(0f, s.currentQuality, 0.0f)
        assertFalse(s.faceVisible)
        assertFalse(s.wellFramed)
        assertEquals(0.0, s.needleAngleDeg, 0.0)
        assertEquals(0.0, s.needleFrac, 0.0)
        assertEquals(Guidance.SEARCHING, s.guidance)
        assertEquals(0, s.coveredCount)
        assertEquals(0.0, s.progress, 0.0)
        assertFalse(s.hasFrontal)
        assertFalse(s.isComplete)
        assertFalse(s.isSubmittable)
    }

    // ── the raw-vs-smoothed rule (PARITY.md §29 item 10) ──────────

    @Test
    fun `capture reads the RAW pose, so a held angle registers on the first frame`() {
        val m = model()
        // Seed the needle at the hub, so the smoothed pose lags well behind.
        m.feed(yaw = 0.0, pitch = 0.0)

        // Raw magnitude 30 clears the 22 capture threshold. The SMOOTHED pose is
        // 0 + 0.4 * (30 - 0) = 12, which does not — so if the gate ever read the
        // smoothed value, this frame would be dropped and the arc stays empty.
        val captured = m.feed(yaw = 30.0, pitch = 0.0)

        assertTrue(captured)
        assertEquals(1, m.state.value.coveredCount)
        assertEquals(30.0, m.state.value.currentMagDeg, 1e-9)
        // and the needle is the smoothed one: 12 / 22.
        assertEquals(12.0 / 22.0, m.state.value.needleFrac, 1e-9)
    }

    @Test
    fun `the needle snaps to the first pose, then eases at alpha 0 point 4`() {
        val m = model()

        m.feed(yaw = 11.0, pitch = 0.0)
        assertEquals(11.0 / 22.0, m.state.value.needleFrac, 1e-9)

        // 11 + 0.4 * (21 - 11) = 15
        m.feed(yaw = 21.0, pitch = 0.0)
        assertEquals(15.0 / 22.0, m.state.value.needleFrac, 1e-9)

        // 15 + 0.4 * (21 - 15) = 17.4
        m.feed(yaw = 21.0, pitch = 0.0)
        assertEquals(17.4 / 22.0, m.state.value.needleFrac, 1e-9)
    }

    @Test
    fun `the needle reaches the rim at the capture angle, not at the reach target`() {
        val m = model()

        // needleFrac is normalized by extremeMinDeg (22), which is what "1 =
        // capture angle" means. Normalizing by reachTargetDeg (45) would leave
        // the pointer short of the rim at the angle that actually captures.
        m.feed(yaw = 22.0, pitch = 0.0)
        assertEquals(1.0, m.state.value.needleFrac, 1e-9)

        m.feed(yaw = 90.0, pitch = 0.0)
        assertEquals(1.0, m.state.value.needleFrac, 1e-9)
    }

    @Test
    fun `the needle angle follows the smoothed pose`() {
        val m = model()

        m.feed(yaw = 20.0, pitch = 0.0)
        assertEquals(0.0, m.state.value.needleAngleDeg, 1e-9)

        m.feed(yaw = 0.0, pitch = 20.0)
        // yaw 20 -> 12, pitch 0 -> 8; atan2(8, 12)
        assertEquals(atan2(8.0, 12.0) * 180.0 / PI, m.state.value.needleAngleDeg, 1e-9)
    }

    // ── geometry ──────────────────────────────────────────────────

    @Test
    fun `pointing right is arc zero, up is three, left is six, down is nine`() {
        assertEquals(0, segmentHitBy(yaw = 25.0, pitch = 0.0))
        assertEquals(3, segmentHitBy(yaw = 0.0, pitch = 25.0))
        assertEquals(6, segmentHitBy(yaw = -25.0, pitch = 0.0))
        assertEquals(9, segmentHitBy(yaw = 0.0, pitch = -25.0))
    }

    @Test
    fun `every arc centre lands in its own arc`() {
        repeat(12) { seg ->
            val m = model()
            m.feedPose(seg)
            assertEquals(seg, m.state.value.currentSegment)
        }
    }

    @Test
    fun `a hair below zero degrees wraps to the last arc, not past the end`() {
        // atan2 of a vanishing negative pitch gives ~-0, which +360 rounds to
        // exactly 360.0 — index 12 without the clamp, which is out of bounds.
        val hit = segmentHitBy(yaw = 25.0, pitch = -1e-15)

        assertEquals(11, hit)
    }

    @Test
    fun `an arc owns the angles above its lower boundary`() {
        // 30 degrees is the boundary between arc 0 and arc 1. Either side of it
        // resolves to the arc it belongs to; the boundary value itself is a
        // floating-point knife edge and is deliberately not asserted.
        assertEquals(0, segmentHitAt(degrees = 29.99))
        assertEquals(1, segmentHitAt(degrees = 30.01))
    }

    private fun segmentHitAt(degrees: Double, mag: Double = 25.0): Int? {
        val radians = degrees * PI / 180.0
        return segmentHitBy(yaw = mag * cos(radians), pitch = mag * sin(radians))
    }

    private fun segmentHitBy(yaw: Double, pitch: Double): Int? {
        val m = model()
        m.feed(yaw = yaw, pitch = pitch)
        return m.state.value.currentSegment
    }

    // ── the frontal / dead-zone / capture bands ───────────────────

    @Test
    fun `a frontal pose has no arc and fills the frontal quota`() {
        val m = model()

        assertTrue(m.feed(yaw = 0.0, pitch = 0.0))
        assertNull(m.state.value.currentSegment)
        assertEquals(1, m.state.value.frontalCount)

        assertTrue(m.feed(yaw = 9.9, pitch = 0.0))
        assertEquals(2, m.state.value.frontalCount)

        // The quota is 2; a third frontal frame is not kept and costs no encode.
        val before = jpegCalls
        assertFalse(m.feed(yaw = 0.0, pitch = 0.0))
        assertEquals(2, m.state.value.frontalCount)
        assertEquals(before, jpegCalls)
    }

    @Test
    fun `exactly frontalMaxDeg is not frontal, and cannot green an arc either`() {
        val m = model()

        // mag < frontalMaxDeg is strict, so 10.0 falls into the directional
        // branch — where it fails mag >= extremeMinDeg. Nothing is captured.
        assertFalse(m.feed(yaw = 10.0, pitch = 0.0))
        assertEquals(0, m.state.value.frontalCount)
        assertEquals(0, m.state.value.coveredCount)
        assertEquals(0, m.state.value.currentSegment)
    }

    @Test
    fun `the dead zone between frontal and capture points at an arc but captures nothing`() {
        val m = model()

        val before = jpegCalls
        assertFalse(m.feedPose(segment = 4, mag = 21.99))

        assertEquals(4, m.state.value.currentSegment)
        assertEquals(0, m.state.value.coveredCount)
        assertEquals(before, jpegCalls)
    }

    @Test
    fun `exactly extremeMinDeg captures`() {
        val m = model()

        // Axis-aligned, so the magnitude is exactly 22 and the >= is really
        // being tested rather than a rounding artefact.
        assertTrue(m.feed(yaw = 22.0, pitch = 0.0))
        assertEquals(1, m.state.value.coveredCount)
        assertEquals(0, m.state.value.currentSegment)
    }

    @Test
    fun `a hair under extremeMinDeg does not`() {
        val m = model()

        assertFalse(m.feed(yaw = 21.99, pitch = 0.0))
        assertEquals(0, m.state.value.coveredCount)
    }

    @Test
    fun `an unframed face can never green an arc, or take a frontal`() {
        val m = model()

        val before = jpegCalls
        assertFalse(m.feedPose(segment = 2, framed = false))
        assertFalse(m.feed(yaw = 0.0, pitch = 0.0, framed = false))

        assertEquals(0, m.state.value.coveredCount)
        assertEquals(0, m.state.value.frontalCount)
        assertEquals(before, jpegCalls)
        // …but the pose still tracks, so the dial keeps drawing.
        assertNull(m.state.value.currentSegment)
        assertFalse(m.state.value.wellFramed)
        assertTrue(m.state.value.faceVisible)
    }

    @Test
    fun `a null jpeg is not a capture`() {
        val m = model()

        assertFalse(m.feedPose(segment = 1, jpeg = { null }))
        assertFalse(m.feed(yaw = 0.0, pitch = 0.0, jpeg = { null }))

        assertEquals(0, m.state.value.coveredCount)
        assertEquals(0, m.state.value.frontalCount)
    }

    // ── the quality ramp ──────────────────────────────────────────

    @Test
    fun `the quality floor is strict frontal and relaxes linearly to the extreme`() {
        val m = model()

        assertEquals(0.4f, m.qualityFloor(0.0), 0.0f)
        assertEquals(0.4f, m.qualityFloor(10.0), 0.0f) // the guard is `> frontalMaxDeg`
        assertEquals(0.325f, m.qualityFloor(27.5), 1e-6f) // halfway down the ramp
        assertEquals(0.25f, m.qualityFloor(45.0), 1e-6f)
        assertEquals(0.25f, m.qualityFloor(80.0), 1e-6f) // t clamps at 1
    }

    @Test
    fun `the ramp is monotonically non-increasing`() {
        val m = model()

        var previous = m.qualityFloor(0.0)
        var mag = 0.0
        while (mag <= 90.0) {
            val floor = m.qualityFloor(mag)
            assertTrue("floor rose at $mag", floor <= previous + 1e-7f)
            previous = floor
            mag += 0.5
        }
    }

    @Test
    fun `a degenerate reach target cannot divide by zero`() {
        val m = model(frontalMaxDeg = 10.0, reachTargetDeg = 10.0)

        // span = max(1, 0) = 1, so one degree past frontal is already "full
        // extreme" rather than an infinity.
        assertEquals(0.25f, m.qualityFloor(11.0), 1e-6f)
    }

    @Test
    fun `quality exactly at the floor is kept, a hair under is not`() {
        val m = model()
        val floor = m.qualityFloor(25.0)

        assertTrue(m.feedPose(segment = 3, mag = 25.0, quality = floor))
        assertFalse(m.feedPose(segment = 4, mag = 25.0, quality = floor - 0.001f))
        assertEquals(1, m.state.value.coveredCount)
    }

    @Test
    fun `a profile that would fail the frontal bar still passes the relaxed one`() {
        val m = model()

        // 0.3 is below the strict 0.4 frontal floor, so the hub frame is
        // refused…
        assertFalse(m.feed(yaw = 0.0, pitch = 0.0, quality = 0.3f))

        // …and above the floor 40 degrees out, so the same score keeps a
        // profile. A flat bar would reject every profile even in perfect light.
        assertTrue(m.qualityFloor(40.0) < 0.3f)
        assertTrue(m.feedPose(segment = 5, mag = 40.0, quality = 0.3f))
    }

    // ── most-extreme retention ────────────────────────────────────

    @Test
    fun `an arc holds no more than framesPerSegment, and one frame covers it`() {
        val m = model()

        assertTrue(m.feedPose(segment = 7, mag = 25.0))
        assertTrue(m.state.value.segments[7].covered)
        assertEquals(1, m.state.value.segments[7].frameCount)
        assertEquals(1, m.state.value.coveredCount)

        assertTrue(m.feedPose(segment = 7, mag = 30.0))
        assertTrue(m.feedPose(segment = 7, mag = 40.0))

        assertEquals(2, m.state.value.segments[7].frameCount)
        assertEquals(1, m.state.value.coveredCount)
    }

    @Test
    fun `a shallower frame is rejected once the arc is full, and costs no encode`() {
        val m = model()
        m.feedPose(segment = 7, mag = 30.0)
        m.feedPose(segment = 7, mag = 40.0)

        val before = jpegCalls
        assertFalse(m.feedPose(segment = 7, mag = 25.0))

        assertEquals(before, jpegCalls)
        assertEquals(2, m.state.value.segments[7].frameCount)
    }

    @Test
    fun `an equally extreme frame does not evict, it is strictly steeper or nothing`() {
        val m = model()
        m.feedPose(segment = 7, mag = 30.0)
        m.feedPose(segment = 7, mag = 30.0)

        val before = jpegCalls
        assertFalse(m.feedPose(segment = 7, mag = 30.0))

        assertEquals(before, jpegCalls)
    }

    @Test
    fun `eviction replaces the least extreme frame, not the oldest`() {
        val m = model(framesPerSegment = 2)
        val steep = byteArrayOf(1)
        val shallow = byteArrayOf(2)
        val steeper = byteArrayOf(3)

        m.feedPose(segment = 0, mag = 40.0, jpeg = { steep })
        m.feedPose(segment = 0, mag = 25.0, jpeg = { shallow })
        m.feedPose(segment = 0, mag = 30.0, jpeg = { steeper })

        val kept = m.allFrames()
        assertEquals(2, kept.size)
        assertSame(steep, kept[0]) // 40, still the steepest
        assertSame(steeper, kept[1]) // 30 replaced the 25
    }

    @Test
    fun `the encode is only paid for frames that are kept`() {
        val m = model()

        m.feedPose(segment = 0, framed = false) // unframed
        m.feedPose(segment = 0, mag = 15.0) // dead zone
        m.feedPose(segment = 0, quality = 0.0f) // under the floor
        assertEquals(0, jpegCalls)

        m.feedPose(segment = 0)
        assertEquals(1, jpegCalls)
    }

    // ── upload order ──────────────────────────────────────────────

    @Test
    fun `allFrames is frontal first, then arcs in id order`() {
        val m = model()
        val frontalA = byteArrayOf(10)
        val frontalB = byteArrayOf(11)
        val arcTwo = byteArrayOf(20)
        val arcFive = byteArrayOf(50)

        m.feedPose(segment = 5, jpeg = { arcFive })
        m.feed(yaw = 0.0, pitch = 0.0, jpeg = { frontalA })
        m.feedPose(segment = 2, jpeg = { arcTwo })
        m.feed(yaw = 0.0, pitch = 0.0, jpeg = { frontalB })

        assertEquals(
            listOf(frontalA, frontalB, arcTwo, arcFive),
            m.allFrames(),
        )
    }

    // ── completion predicates ─────────────────────────────────────

    @Test
    fun `complete needs a frontal and all twelve arcs`() {
        val m = model()
        m.coverAllArcs()

        assertEquals(12, m.state.value.coveredCount)
        assertEquals(1.0, m.state.value.progress, 0.0)
        assertFalse("all arcs but no frontal is not complete", m.state.value.isComplete)

        m.feed(yaw = 0.0, pitch = 0.0)
        assertTrue(m.state.value.isComplete)
    }

    @Test
    fun `submittable is a frontal plus half the cone`() {
        val m = model()
        repeat(5) { m.feedPose(it) }
        m.feed(yaw = 0.0, pitch = 0.0)

        assertFalse("5 of 12 is under half", m.state.value.isSubmittable)

        m.feedPose(5)
        assertTrue(m.state.value.isSubmittable)
        assertFalse(m.state.value.isComplete)
    }

    @Test
    fun `submittable rounds the half up on an odd dial`() {
        val m = model(segmentCount = 5)
        m.feed(yaw = 0.0, pitch = 0.0)
        m.feedPose(0, segmentCount = 5)
        m.feedPose(1, segmentCount = 5)

        assertFalse("ceil(2.5) is 3, so two arcs is short", m.state.value.isSubmittable)

        m.feedPose(2, segmentCount = 5)
        assertTrue(m.state.value.isSubmittable)
    }

    @Test
    fun `submittable is never true without a frontal`() {
        val m = model()
        m.coverAllArcs()

        assertFalse(m.state.value.isSubmittable)
    }

    @Test
    fun `progress is covered arcs over the dial`() {
        val m = model()
        m.feedPose(0)
        m.feedPose(1)
        m.feedPose(1) // a second frame in an arc does not move progress

        assertEquals(2.0 / 12.0, m.state.value.progress, 1e-9)
    }

    // ── losing the face ───────────────────────────────────────────

    @Test
    fun `losing the face collapses the cursor but keeps the frames`() {
        val m = model()
        m.feed(yaw = 0.0, pitch = 0.0)
        m.feedPose(3, mag = 40.0)

        m.noteFaceLost()

        val s = m.state.value
        assertFalse(s.faceVisible)
        assertFalse(s.wellFramed)
        assertNull(s.currentSegment)
        assertEquals(0.0, s.currentMagDeg, 0.0)
        assertEquals(0f, s.currentQuality, 0.0f)
        assertEquals(0.0, s.needleFrac, 0.0)
        assertEquals(1, s.frontalCount)
        assertEquals(1, s.coveredCount)
        assertEquals(2, m.allFrames().size)
    }

    @Test
    fun `losing the face leaves the needle angle where it was`() {
        val m = model()
        m.feed(yaw = 0.0, pitch = 30.0)
        val angle = m.state.value.needleAngleDeg

        m.noteFaceLost()

        // Only the reach collapses. iOS leaves the direction alone so the
        // pointer does not spin back to zero on a one-frame dropout.
        assertEquals(angle, m.state.value.needleAngleDeg, 0.0)
    }

    @Test
    fun `the needle re-snaps to the raw pose after a dropout`() {
        val m = model()
        m.feed(yaw = 0.0, pitch = 0.0)

        m.noteFaceLost()
        m.feed(yaw = 22.0, pitch = 0.0)

        // Not 0 + 0.4 * 22: the smoothing state was discarded with the face.
        assertEquals(1.0, m.state.value.needleFrac, 1e-9)
    }

    // ── the debounced caption state machine ───────────────────────

    @Test
    fun `a caption only commits after the dwell has elapsed`() {
        val m = model()

        m.feed(yaw = 0.0, pitch = 0.0)
        assertEquals(Guidance.SEARCHING, m.state.value.guidance)

        clock.advance(299)
        m.feed(yaw = 0.0, pitch = 0.0)
        assertEquals("299ms is not 300", Guidance.SEARCHING, m.state.value.guidance)

        clock.advance(1)
        m.feed(yaw = 0.0, pitch = 0.0)
        assertEquals(Guidance.ACTIVE, m.state.value.guidance)
    }

    @Test
    fun `a state that does not hold restarts the dwell`() {
        val m = model()
        settleTo(m, Guidance.ACTIVE) { it.feed(yaw = 0.0, pitch = 0.0) }

        // A flicker of "not framed" starts the clock…
        clock.advance(100)
        m.feed(yaw = 0.0, pitch = 0.0, framed = false)
        // …and a good frame 100ms later throws that candidate away.
        clock.advance(100)
        m.feed(yaw = 0.0, pitch = 0.0)
        // A second flicker: its dwell starts here, not at the first one.
        clock.advance(100)
        m.feed(yaw = 0.0, pitch = 0.0, framed = false)
        clock.advance(299)
        m.feed(yaw = 0.0, pitch = 0.0, framed = false)
        assertEquals(Guidance.ACTIVE, m.state.value.guidance)

        clock.advance(1)
        m.feed(yaw = 0.0, pitch = 0.0, framed = false)
        assertEquals(Guidance.SEARCHING, m.state.value.guidance)
    }

    @Test
    fun `completion snaps past the dwell`() {
        val m = model()
        // The clock never moves, so nothing else could ever commit.
        m.coverAllArcs()
        assertEquals(Guidance.SEARCHING, m.state.value.guidance)

        m.feed(yaw = 0.0, pitch = 0.0)

        assertEquals(Guidance.DONE, m.state.value.guidance)
    }

    @Test
    fun `done outranks a lost face`() {
        val m = model()
        m.coverAllArcs()
        m.feed(yaw = 0.0, pitch = 0.0)

        clock.advance(10_000)
        m.noteFaceLost()

        assertEquals(Guidance.DONE, m.state.value.guidance)
    }

    @Test
    fun `no face reads as searching`() {
        val m = model()
        settleTo(m, Guidance.ACTIVE) { it.feed(yaw = 0.0, pitch = 0.0) }

        settleTo(m, Guidance.SEARCHING) { it.noteFaceLost() }
    }

    @Test
    fun `a face that is not framed reads as searching, not as a quality problem`() {
        val m = model()

        settleTo(m, Guidance.SEARCHING) { it.feedPose(0, framed = false, faceHeight = 0.1) }
    }

    // ── the actionable low-quality states ─────────────────────────

    @Test
    fun `a small face on an uncovered arc reads as too far`() {
        val m = model()

        settleTo(m, Guidance.TOO_FAR) {
            it.feedPose(0, quality = 0f, faceHeight = 0.27, brightness = 1f)
        }
    }

    @Test
    fun `a dark scene with a big enough face reads as too dark`() {
        val m = model()

        settleTo(m, Guidance.TOO_DARK) {
            it.feedPose(0, quality = 0f, faceHeight = 0.5, brightness = 0.27f)
        }
    }

    @Test
    fun `distance and light both fine reads as hold still`() {
        val m = model()

        settleTo(m, Guidance.HOLD_STILL) {
            it.feedPose(0, quality = 0f, faceHeight = 0.5, brightness = 0.9f)
        }
    }

    @Test
    fun `distance is diagnosed before light when both are bad`() {
        val m = model()

        settleTo(m, Guidance.TOO_FAR) {
            it.feedPose(0, quality = 0f, faceHeight = 0.1, brightness = 0.1f)
        }
    }

    @Test
    fun `the thresholds are strict — exactly at the limit is fine`() {
        val m = model()

        settleTo(m, Guidance.HOLD_STILL) {
            it.feedPose(0, quality = 0f, faceHeight = 0.28, brightness = 0.28f)
        }
    }

    @Test
    fun `a low-quality frontal is diagnosed too, so enrollment cannot stall silently`() {
        val m = model()
        // Every arc is covered; only the hub frame is left, and the room is dim
        // so it cannot clear the strict 0.4 floor. Without the seekingFrontal
        // branch this would sit on "look straight ahead to finish" forever.
        m.coverAllArcs()

        settleTo(m, Guidance.TOO_DARK) {
            it.feed(yaw = 0.0, pitch = 0.0, quality = 0.1f, faceHeight = 0.5, brightness = 0.1f)
        }
    }

    @Test
    fun `a low-quality frame on an already covered arc is not a complaint`() {
        val m = model()
        m.feed(yaw = 0.0, pitch = 0.0)
        m.feedPose(0)

        settleTo(m, Guidance.ACTIVE) {
            it.feedPose(0, quality = 0f, faceHeight = 0.1, brightness = 0.1f)
        }
    }

    @Test
    fun `a low-quality frame in the dead zone is not a complaint either`() {
        val m = model()
        m.feed(yaw = 0.0, pitch = 0.0) // frontal already in hand

        settleTo(m, Guidance.ACTIVE) {
            it.feedPose(0, mag = 15.0, quality = 0f, faceHeight = 0.1, brightness = 0.1f)
        }
    }

    @Test
    fun `all arcs and no frontal is the terminal nudge`() {
        val m = model()
        m.coverAllArcs()

        settleTo(m, Guidance.FRONTAL_NEEDED) { it.feedPose(0) }
    }

    @Test
    fun `a partly covered dial with a good face is just active`() {
        val m = model()
        m.feed(yaw = 0.0, pitch = 0.0)

        settleTo(m, Guidance.ACTIVE) { it.feedPose(0) }
    }

    /** Feeds [frame] twice a full dwell apart and asserts the caption commits. */
    private fun settleTo(
        m: FaceCoverageModel,
        expected: Guidance,
        frame: (FaceCoverageModel) -> Unit,
    ) {
        frame(m)
        clock.advance(m.guidanceDwellMillis)
        frame(m)
        assertEquals(expected, m.state.value.guidance)
    }

    // ── the caption copy ──────────────────────────────────────────

    @Test
    fun `the front-facing captions are verbatim`() {
        val style = GuidanceStyle.SUBJECT_TURNS

        assertEquals(
            "Re-center your face on the inner circle",
            FaceCoverageModel.caption(Guidance.SEARCHING, style),
        )
        assertEquals("Move a little closer", FaceCoverageModel.caption(Guidance.TOO_FAR, style))
        assertEquals("Find brighter light", FaceCoverageModel.caption(Guidance.TOO_DARK, style))
        assertEquals("Hold still", FaceCoverageModel.caption(Guidance.HOLD_STILL, style))
        assertEquals(
            "Now look straight ahead to finish",
            FaceCoverageModel.caption(Guidance.FRONTAL_NEEDED, style),
        )
    }

    @Test
    fun `the camera-orbits captions are verbatim`() {
        val style = GuidanceStyle.CAMERA_ORBITS

        assertEquals(
            "Point the camera at their face",
            FaceCoverageModel.caption(Guidance.SEARCHING, style),
        )
        assertEquals(
            "Move a little closer to them",
            FaceCoverageModel.caption(Guidance.TOO_FAR, style),
        )
        assertEquals(
            "Move them into better light",
            FaceCoverageModel.caption(Guidance.TOO_DARK, style),
        )
        assertEquals("Hold still", FaceCoverageModel.caption(Guidance.HOLD_STILL, style))
        assertEquals(
            "Now face them head-on to finish",
            FaceCoverageModel.caption(Guidance.FRONTAL_NEEDED, style),
        )
    }

    @Test
    fun `the working states say nothing, in either style`() {
        for (style in GuidanceStyle.entries) {
            assertNull(FaceCoverageModel.caption(Guidance.ACTIVE, style))
            assertNull(FaceCoverageModel.caption(Guidance.DONE, style))
        }
    }

    @Test
    fun `hold still is the one caption that does not vary by style`() {
        assertEquals(
            FaceCoverageModel.caption(Guidance.HOLD_STILL, GuidanceStyle.SUBJECT_TURNS),
            FaceCoverageModel.caption(Guidance.HOLD_STILL, GuidanceStyle.CAMERA_ORBITS),
        )
    }

    @Test
    fun `every other caption does vary by style`() {
        for (g in listOf(
            Guidance.SEARCHING,
            Guidance.TOO_FAR,
            Guidance.TOO_DARK,
            Guidance.FRONTAL_NEEDED,
        )) {
            assertFalse(
                "$g should differ per style",
                FaceCoverageModel.caption(g, GuidanceStyle.SUBJECT_TURNS) ==
                    FaceCoverageModel.caption(g, GuidanceStyle.CAMERA_ORBITS),
            )
        }
    }

    // ── the clock ─────────────────────────────────────────────────

    @Test
    fun `the default clock is monotonic, not wall clock`() {
        // A caption debounced on Date() freezes or strobes when NTP steps the
        // clock mid-enrollment. Two reads of the default source can never go
        // backwards.
        val first = FaceCoverageModel.MONOTONIC_MILLIS()
        val second = FaceCoverageModel.MONOTONIC_MILLIS()

        assertTrue(second >= first)
    }

    private class TestClock(private var millis: Long = 0L) : () -> Long {
        override fun invoke(): Long = millis

        fun advance(by: Long) {
            millis += by
        }
    }
}
