package com.memoria.caregiver.ui.enrollment

import com.memoria.caregiver.data.enrollment.FaceCoverageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FaceCoverageModelSink] — the adapter that lets face enrollment be built at
 * all. Spec: FINDINGS.md F-5.
 *
 * The camera and the model were both finished and tested; nothing implemented
 * the interface between them, so `EnrollmentCamera` could not be constructed.
 * These tests pin that the join actually carries each value through, because a
 * silently-dropped argument here degrades the face gallery rather than failing
 * loudly.
 */
class FaceCoverageModelSinkTest {

    private val model = FaceCoverageModel()
    private val sink = FaceCoverageModelSink(model)

    /**
     * A frontal frame that clears the floor should register the frontal
     * requirement. If the sink dropped `quality` or `framed`, the model would
     * reject the frame and coverage would never complete — enrollment would
     * simply never finish, with no error to explain it.
     */
    @Test
    fun `a good frontal frame reaches the model and counts`() {
        repeat(2) {
            sink.ingest(
                yawDeg = 0.0,
                pitchDeg = 0.0,
                quality = 0.9f,
                framed = true,
                brightness = 0.8f,
                faceHeight = 0.5,
                jpeg = byteArrayOf(1, 2, 3),
            )
        }
        assertTrue("frontal frames should have registered", model.state.value.frontalCount > 0)
    }

    /**
     * The pose has to arrive unmangled, or an arc greens for the wrong
     * direction and the caregiver is asked to turn a way they already have.
     */
    @Test
    fun `the yaw is passed through, not dropped or zeroed`() {
        repeat(2) {
            sink.ingest(
                yawDeg = 35.0,
                pitchDeg = 0.0,
                quality = 0.9f,
                framed = true,
                brightness = 0.8f,
                faceHeight = 0.5,
                jpeg = byteArrayOf(9),
            )
        }
        assertTrue(
            "a 35 degree turn should have kept frames for at least one arc",
            model.state.value.segments.any { it.frameCount > 0 },
        )
    }

    /**
     * The kept frames are what gets uploaded. A sink that passed `{ null }`
     * would let coverage complete with an empty gallery — enrollment would look
     * successful and re-identification would have nothing to work from.
     */
    @Test
    fun `the jpeg bytes survive the hand-off`() {
        val frame = byteArrayOf(7, 7, 7)
        repeat(2) {
            sink.ingest(
                yawDeg = 0.0,
                pitchDeg = 0.0,
                quality = 0.95f,
                framed = true,
                brightness = 0.9f,
                faceHeight = 0.5,
                jpeg = frame,
            )
        }
        val kept = model.allFrames()
        assertTrue("a frame should have been retained", kept.isNotEmpty())
        assertNotNull(kept.first())
        assertSame("the retained frame should be the one handed in", frame, kept.first())
    }

    /**
     * The camera calls this off the main thread to gate its own encode, so the
     * adapter must not add work or state. Same value in, same value out.
     */
    @Test
    fun `the quality floor is the model's own, unaltered`() {
        listOf(0.0, 12.0, 45.0, 70.0).forEach { magnitude ->
            assertEquals(
                model.qualityFloor(magnitude),
                sink.qualityFloor(magnitude),
                0.0f,
            )
        }
    }

    /**
     * A face leaving the frame must reach the model, or the guidance keeps
     * coaching someone who has already walked away.
     */
    @Test
    fun `a lost face reaches the model`() {
        sink.ingest(
            yawDeg = 0.0,
            pitchDeg = 0.0,
            quality = 0.9f,
            framed = true,
            brightness = 0.8f,
            faceHeight = 0.5,
            jpeg = null,
        )
        sink.noteFaceLost()
        assertTrue("the model should report no face", !model.state.value.faceVisible)
    }
}
