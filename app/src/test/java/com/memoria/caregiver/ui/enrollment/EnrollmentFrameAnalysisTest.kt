package com.memoria.caregiver.ui.enrollment

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-frame decisions the enrollment camera makes, tested without a camera.
 *
 * Spec: `EnrollmentVisionCamera.swift`, `PARITY.md` §26. These are the rules a
 * refactor could quietly break and no screenshot would catch: the per-facing
 * sign constants, the two different framing geometries, the two work skips, the
 * origin flip from Vision's bottom-left to ML Kit's top-left, and the 15 Hz gate.
 */
class EnrollmentFrameAnalysisTest {

    // ---- rate limiting ----------------------------------------------------

    private val frameNanos = (1_000_000_000L / 15)

    @Test
    fun `first frame always processes`() {
        val limiter = EnrollmentRateLimiter()
        assertTrue(limiter.shouldProcess(0L))
    }

    @Test
    fun `first frame processes even at a large clock value`() {
        // iOS seeds lastProcess with Date.distantPast so the first frame passes
        // whatever the clock reads. A naive `lastNanos = 0` start would too, but
        // only because nanoTime happens to be positive on most JVMs.
        val limiter = EnrollmentRateLimiter()
        assertTrue(limiter.shouldProcess(Long.MIN_VALUE / 2))
    }

    @Test
    fun `frames closer together than 15 Hz are dropped`() {
        val limiter = EnrollmentRateLimiter()
        limiter.shouldProcess(0L)
        assertFalse(limiter.shouldProcess(frameNanos - 1))
    }

    @Test
    fun `a frame one period later processes`() {
        val limiter = EnrollmentRateLimiter()
        limiter.shouldProcess(0L)
        // iOS gates on `>= 1.0 / targetHz`, and 1/15 s is not a whole number of
        // nanoseconds, so the first integer nanosecond at or past it is the one
        // that passes.
        assertTrue(limiter.shouldProcess(frameNanos + 1))
    }

    @Test
    fun `a dropped frame does not advance the clock`() {
        val limiter = EnrollmentRateLimiter()
        limiter.shouldProcess(0L)
        limiter.shouldProcess(frameNanos / 2)
        // If the dropped frame had reset the timer this would still be blocked.
        assertTrue(limiter.shouldProcess(frameNanos + 1))
    }

    @Test
    fun `the rate limiter runs at 15 Hz`() {
        val limiter = EnrollmentRateLimiter()
        var processed = 0
        // 100 fps for one second — a rate that is not a whole multiple of 15,
        // so the count is the limiter's doing and not the stream's.
        for (i in 0 until 100) {
            if (limiter.shouldProcess(i * 10_000_000L)) processed++
        }
        assertEquals(15, processed)
    }

    // ---- pose signs -------------------------------------------------------

    @Test
    fun `front and rear yaw signs are opposite`() {
        assertEquals(-1.0, yawSign(EnrollmentFacing.Front), 0.0)
        assertEquals(1.0, yawSign(EnrollmentFacing.Rear), 0.0)
    }

    @Test
    fun `the same head turn maps to opposite arcs on the two cameras`() {
        val front = poseFrom(headEulerAngleY = 30f, headEulerAngleX = 0f, facing = EnrollmentFacing.Front)
        val rear = poseFrom(headEulerAngleY = 30f, headEulerAngleX = 0f, facing = EnrollmentFacing.Rear)
        assertEquals(-30.0, front.yawDeg, 1e-6)
        assertEquals(30.0, rear.yawDeg, 1e-6)
        assertNotEquals(front.yawDeg, rear.yawDeg, 1e-6)
    }

    @Test
    fun `pitch sign is negative on both cameras`() {
        assertEquals(
            -20.0,
            poseFrom(0f, 20f, EnrollmentFacing.Front).pitchDeg,
            1e-6,
        )
        assertEquals(
            -20.0,
            poseFrom(0f, 20f, EnrollmentFacing.Rear).pitchDeg,
            1e-6,
        )
    }

    @Test
    fun `magnitude is the hypotenuse of yaw and pitch`() {
        assertEquals(5.0, EnrollmentPose(3.0, 4.0).magnitudeDeg, 1e-9)
        // Sign never survives into magnitude.
        assertEquals(5.0, EnrollmentPose(-3.0, -4.0).magnitudeDeg, 1e-9)
    }

    @Test
    fun `ML Kit degrees are not converted again`() {
        // iOS multiplies Vision's radians by 180 divided by pi. ML Kit already
        // reports degrees, so a stray conversion here would be a 57x error.
        assertEquals(1.0, poseFrom(0f, -1f, EnrollmentFacing.Rear).pitchDeg, 1e-9)
    }

    // ---- upright frame size + rotation mapping ----------------------------

    @Test
    fun `quarter turns swap the frame dimensions`() {
        assertEquals(720, uprightWidth(1280, 720, 90))
        assertEquals(1280, uprightHeight(1280, 720, 90))
        assertEquals(720, uprightWidth(1280, 720, 270))
        assertEquals(1280, uprightHeight(1280, 720, 270))
    }

    @Test
    fun `half turns and no turn keep the frame dimensions`() {
        assertEquals(1280, uprightWidth(1280, 720, 0))
        assertEquals(720, uprightHeight(1280, 720, 0))
        assertEquals(1280, uprightWidth(1280, 720, 180))
        assertEquals(720, uprightHeight(1280, 720, 180))
    }

    @Test
    fun `negative and over-wound rotations normalise`() {
        assertEquals(uprightWidth(1280, 720, 90), uprightWidth(1280, 720, -270))
        assertEquals(sourceX(3, 5, 8, 4, 90), sourceX(3, 5, 8, 4, 450))
        assertEquals(sourceY(3, 5, 8, 4, 90), sourceY(3, 5, 8, 4, -270))
    }

    @Test
    fun `upright corners map to the expected source corners`() {
        val w = 8
        val h = 4
        // No rotation: identity.
        assertEquals(0, sourceX(0, 0, w, h, 0))
        assertEquals(0, sourceY(0, 0, w, h, 0))

        // 90 degrees clockwise: upright is 4 wide, 8 tall. Upright top-left
        // comes from the source bottom-left.
        assertEquals(0, sourceX(0, 0, w, h, 90))
        assertEquals(h - 1, sourceY(0, 0, w, h, 90))

        // 180: opposite corner.
        assertEquals(w - 1, sourceX(0, 0, w, h, 180))
        assertEquals(h - 1, sourceY(0, 0, w, h, 180))

        // 270: upright top-left comes from the source top-right.
        assertEquals(w - 1, sourceX(0, 0, w, h, 270))
        assertEquals(0, sourceY(0, 0, w, h, 270))
    }

    @Test
    fun `every upright pixel maps to a distinct source pixel`() {
        val w = 8
        val h = 4
        for (rotation in listOf(0, 90, 180, 270)) {
            val uw = uprightWidth(w, h, rotation)
            val uh = uprightHeight(w, h, rotation)
            val seen = mutableSetOf<Pair<Int, Int>>()
            for (y in 0 until uh) {
                for (x in 0 until uw) {
                    val sx = sourceX(x, y, w, h, rotation)
                    val sy = sourceY(x, y, w, h, rotation)
                    assertTrue("x out of range at rotation $rotation", sx in 0 until w)
                    assertTrue("y out of range at rotation $rotation", sy in 0 until h)
                    seen.add(sx to sy)
                }
            }
            assertEquals("rotation $rotation is not a bijection", w * h, seen.size)
        }
    }

    @Test
    fun `out of range requests clamp to an edge pixel`() {
        assertEquals(0, sourceX(-5, 0, 8, 4, 0))
        assertEquals(7, sourceX(99, 0, 8, 4, 0))
        assertEquals(3, sourceY(0, 99, 8, 4, 0))
    }

    // ---- box normalisation ------------------------------------------------

    @Test
    fun `pixel boxes normalise against the upright frame`() {
        val box = normalizeBox(
            left = 180, top = 320, right = 540, bottom = 960,
            frameWidth = 720, frameHeight = 1280,
        )
        assertEquals(0.25f, box.left, 1e-6f)
        assertEquals(0.25f, box.top, 1e-6f)
        assertEquals(0.75f, box.right, 1e-6f)
        assertEquals(0.75f, box.bottom, 1e-6f)
        assertEquals(0.5f, box.height, 1e-6f)
    }

    @Test
    fun `a box running off the frame is NOT clamped`() {
        // The whole point of wellFramed is catching spill. Clamping here would
        // make every half-cut face look perfectly framed.
        val box = normalizeBox(-72, -128, 360, 640, 720, 1280)
        assertTrue(box.left < 0f)
        assertTrue(box.top < 0f)
        assertFalse(wellFramed(box, EnrollmentFacing.Rear))
    }

    @Test
    fun `a zero-sized frame yields an empty box rather than dividing by zero`() {
        assertEquals(Rect.Zero, normalizeBox(0, 0, 10, 10, 0, 0))
    }

    // ---- framing ----------------------------------------------------------

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        Rect(left, top, right, bottom)

    /** Centred, comfortably inside both geometries. */
    private fun goodBox() = box(0.30f, 0.35f, 0.70f, 0.65f)

    @Test
    fun `a face smaller than 0_16 of the frame is never framed`() {
        val small = box(0.40f, 0.40f, 0.60f, 0.40f + 0.159f)
        assertFalse(wellFramed(small, EnrollmentFacing.Front))
        assertFalse(wellFramed(small, EnrollmentFacing.Rear))

        val justBigEnough = box(0.40f, 0.40f, 0.60f, 0.40f + 0.16f)
        assertTrue(wellFramed(justBigEnough, EnrollmentFacing.Front))
        assertTrue(wellFramed(justBigEnough, EnrollmentFacing.Rear))
    }

    @Test
    fun `front gates on the visible ring, rear gates on the frame edge`() {
        // Vertically high in the frame: inside the rear sensor, outside the
        // front ring. This is the case PARITY 26 calls out — a face spilling
        // out of the circle that would wrongly read as fine on a frame-edge gate.
        val high = box(0.30f, 0.05f, 0.70f, 0.45f)
        assertFalse(wellFramed(high, EnrollmentFacing.Front))
        assertTrue(wellFramed(high, EnrollmentFacing.Rear))
    }

    @Test
    fun `front vertical band boundaries`() {
        assertTrue(wellFramed(box(0.30f, 0.20f, 0.70f, 0.80f), EnrollmentFacing.Front))
        assertFalse(wellFramed(box(0.30f, 0.199f, 0.70f, 0.799f), EnrollmentFacing.Front))
        assertFalse(wellFramed(box(0.30f, 0.201f, 0.70f, 0.801f), EnrollmentFacing.Front))
    }

    @Test
    fun `front horizontal band boundaries`() {
        assertTrue(wellFramed(box(0.04f, 0.30f, 0.96f, 0.70f), EnrollmentFacing.Front))
        assertFalse(wellFramed(box(0.039f, 0.30f, 0.96f, 0.70f), EnrollmentFacing.Front))
        assertFalse(wellFramed(box(0.04f, 0.30f, 0.961f, 0.70f), EnrollmentFacing.Front))
    }

    @Test
    fun `rear edge boundaries`() {
        assertTrue(wellFramed(box(0.02f, 0.02f, 0.98f, 0.98f), EnrollmentFacing.Rear))
        assertFalse(wellFramed(box(0.019f, 0.02f, 0.98f, 0.98f), EnrollmentFacing.Rear))
        assertFalse(wellFramed(box(0.02f, 0.02f, 0.98f, 0.981f), EnrollmentFacing.Rear))
    }

    @Test
    fun `there is no centering gate`() {
        // Hard against the right edge of both geometries but fully inside them.
        // iOS deliberately dropped the centre check — it made profiles slow to
        // land — so an off-centre face must still be capturable.
        val offCentre = box(0.55f, 0.25f, 0.95f, 0.75f)
        assertTrue(wellFramed(offCentre, EnrollmentFacing.Front))
        assertTrue(wellFramed(offCentre, EnrollmentFacing.Rear))
    }

    @Test
    fun `framing is unchanged by the Vision to ML Kit origin flip`() {
        // iOS works bottom-left up; this port works top-left down. Both vertical
        // bands are symmetric about 0.5, so the numbers carry over untouched.
        // If either bound is ever made asymmetric, this fails and the flip has
        // to be handled explicitly.
        fun flipped(r: Rect) = Rect(r.left, 1f - r.bottom, r.right, 1f - r.top)
        val cases = listOf(
            goodBox(),
            box(0.30f, 0.05f, 0.70f, 0.45f),
            box(0.30f, 0.55f, 0.70f, 0.95f),
            box(0.05f, 0.05f, 0.95f, 0.95f),
        )
        for (facing in EnrollmentFacing.entries) {
            for (case in cases) {
                assertEquals(
                    "flip changed the verdict for $case on $facing",
                    wellFramed(case, facing),
                    wellFramed(flipped(case), facing),
                )
            }
        }
    }

    // ---- face selection ---------------------------------------------------

    @Test
    fun `the largest face wins`() {
        val boxes = listOf(
            box(0.0f, 0.0f, 0.1f, 0.1f),
            box(0.2f, 0.2f, 0.6f, 0.7f),
            box(0.7f, 0.7f, 0.9f, 0.9f),
        )
        assertEquals(1, indexOfLargestBox(boxes))
    }

    @Test
    fun `no faces yields no index`() {
        assertEquals(-1, indexOfLargestBox(emptyList()))
    }

    // ---- the two work skips ----------------------------------------------

    /** Mirrors the coverage model's ramp: 0.4 frontal, 0.25 at 45 degrees. */
    private fun ramp(mag: Double): Float {
        if (mag <= 10.0) return 0.4f
        val t = minOf(1.0, (mag - 10.0) / 35.0).toFloat()
        return 0.4f - t * (0.4f - 0.25f)
    }

    private fun decide(
        box: Rect = goodBox(),
        yaw: Float = 0f,
        pitch: Float = 0f,
        quality: Float = 1f,
        facing: EnrollmentFacing = EnrollmentFacing.Rear,
    ) = decideFrame(box, yaw, pitch, facing, quality, ::ramp)

    @Test
    fun `a good framed frame encodes a JPEG and skips the luma pass`() {
        val d = decide(quality = 0.9f)
        assertTrue(d.framed)
        assertTrue(d.shouldEncodeJpeg)
        assertFalse(d.shouldComputeSceneLuma)
    }

    @Test
    fun `an unframed face never produces a JPEG, however good the quality`() {
        // This is what stops a half-cut face greening an arc.
        val d = decide(box = box(0.30f, 0.05f, 0.70f, 0.45f), quality = 1f, facing = EnrollmentFacing.Front)
        assertFalse(d.framed)
        assertFalse(d.shouldEncodeJpeg)
    }

    @Test
    fun `a low-quality frame skips the JPEG and measures brightness instead`() {
        val d = decide(quality = 0.3f)
        assertFalse(d.shouldEncodeJpeg)
        assertTrue(d.shouldComputeSceneLuma)
    }

    @Test
    fun `the floor is the angle-aware ramp, not a flat threshold`() {
        // 0.3 fails head-on and passes at a profile: the same frame quality,
        // two verdicts. A flat bar would reject every profile in perfect light.
        assertFalse(decide(quality = 0.3f, yaw = 0f).shouldEncodeJpeg)
        assertTrue(decide(quality = 0.3f, yaw = 40f).shouldEncodeJpeg)
    }

    @Test
    fun `quality exactly on the floor is kept`() {
        assertTrue(decide(quality = 0.4f, yaw = 0f).shouldEncodeJpeg)
        assertFalse(decide(quality = 0.4f, yaw = 0f).shouldComputeSceneLuma)
    }

    @Test
    fun `the decision carries the raw pose, signed per facing`() {
        val front = decide(yaw = 25f, pitch = 15f, facing = EnrollmentFacing.Front)
        assertEquals(-25.0, front.yawDeg, 1e-6)
        assertEquals(-15.0, front.pitchDeg, 1e-6)
        assertEquals(
            Math.hypot(25.0, 15.0),
            front.magnitudeDeg,
            1e-6,
        )
    }

    // ---- scene luma -------------------------------------------------------

    private class FlatFrame(
        override val width: Int,
        override val height: Int,
        private val value: Int,
    ) : EnrollmentFrame {
        var samples = 0
            private set

        override fun luma(x: Int, y: Int): Int {
            samples++
            return value
        }
    }

    @Test
    fun `a uniform frame averages to its own luma`() {
        assertEquals(128f / 255f, sceneLuma(FlatFrame(720, 1280, 128)), 1e-6f)
        assertEquals(0f, sceneLuma(FlatFrame(720, 1280, 0)), 1e-6f)
        assertEquals(1f, sceneLuma(FlatFrame(720, 1280, 255)), 1e-6f)
    }

    @Test
    fun `luma is a coarse subsample, not a full frame scan`() {
        val frame = FlatFrame(720, 1280, 100)
        sceneLuma(frame)
        // iOS: step = max(1, size / 24) on each axis. 720/24 = 30 lands 24
        // columns; 1280/24 truncates to 53, which lands 25 rows.
        assertEquals(24 * 25, frame.samples)
        assertTrue(frame.samples < 720 * 1280 / 100)
    }

    @Test
    fun `a frame smaller than the sample grid still samples every pixel once`() {
        val frame = FlatFrame(4, 3, 10)
        sceneLuma(frame)
        assertEquals(12, frame.samples)
    }

    @Test
    fun `an empty frame reports full brightness rather than dividing by zero`() {
        assertEquals(1f, sceneLuma(FlatFrame(0, 0, 0)), 0f)
    }

    @Test
    fun `a gradient averages across the sampled grid`() {
        val frame = object : EnrollmentFrame {
            override val width = 24
            override val height = 24
            override fun luma(x: Int, y: Int) = if (x < 12) 0 else 255
        }
        assertEquals(0.5f, sceneLuma(frame), 1e-6f)
    }

    // ---- JPEG downscale ---------------------------------------------------

    @Test
    fun `the JPEG is downscaled to 640 wide`() {
        assertEquals(640f / 720f, jpegScale(720), 1e-6f)
        assertEquals(640f / 1280f, jpegScale(1280), 1e-6f)
    }

    @Test
    fun `a frame narrower than 640 is never enlarged`() {
        assertEquals(1f, jpegScale(480), 0f)
        assertEquals(1f, jpegScale(640), 0f)
    }

    // ---- preview mirroring ------------------------------------------------

    @Test
    fun `the two combinations the app uses need no extra flip`() {
        // Front self-enroll is mirrored, rear nurse-orbit is not, and
        // PreviewView already does exactly that.
        assertFalse(previewNeedsExtraFlip(EnrollmentFacing.Front, mirrored = true))
        assertFalse(previewNeedsExtraFlip(EnrollmentFacing.Rear, mirrored = false))
    }

    @Test
    fun `asking for the opposite of the default flips the preview`() {
        assertTrue(previewNeedsExtraFlip(EnrollmentFacing.Front, mirrored = false))
        assertTrue(previewNeedsExtraFlip(EnrollmentFacing.Rear, mirrored = true))
    }
}
