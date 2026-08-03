package com.memoria.caregiver.ui.enrollment

import com.memoria.caregiver.data.enrollment.FaceCoverageModel

/**
 * Joins [EnrollmentCoverageSink] to [FaceCoverageModel].
 *
 * ## Why this file has to exist
 *
 * `EnrollmentCamera` requires a sink and calls it correctly; `FaceCoverageModel`
 * has all three matching methods. **Nothing implemented the interface**, in main
 * or in test, so the camera could not be constructed and face enrollment could
 * not run at all (`FINDINGS.md` F-5). Two halves built to meet, never joined.
 *
 * The model deliberately does not declare the interface itself: it lives in
 * `data/enrollment/` and its own header says "pure logic, no camera, UI or ML
 * Kit dependency", which is what lets it be tested against recorded poses with
 * no Android on the path. Declaring a UI-layer interface there would spend that
 * property for nothing.
 *
 * ## The two signature differences, and why neither loses anything
 *
 * 1. **The JPEG.** The model takes `() -> ByteArray?` so it can decline to pay
 *    for an encode on a frame it will not keep. The sink takes an already-made
 *    `ByteArray?`. That is not a lost optimisation: `EnrollmentCamera` has
 *    already made exactly that decision one step earlier
 *    (`if (decision.shouldEncodeJpeg) encodeJpeg(...) else null`), so the value
 *    handed over is either a frame worth keeping or null. Wrapping it in a
 *    lambda re-satisfies the model's shape without encoding anything twice.
 * 2. **The return value.** `FaceCoverageModel.ingest` returns whether the frame
 *    registered an arc; the sink returns nothing. Discarded on purpose — the
 *    screen renders `model.state`, which already reflects the capture, and a
 *    second channel saying the same thing is a second thing to keep in sync.
 *
 * ## Threading
 *
 * [ingest] and [noteFaceLost] arrive on the main thread (the camera posts them
 * through `mainExecutor`), which is the single-thread confinement the model's
 * own note requires. [qualityFloor] is called **off** the main thread, from the
 * analyser, and is safe there because it reads only immutable constructor
 * values — the model documents that too, and iOS marks its counterpart
 * `nonisolated` for the same reason.
 */
class FaceCoverageModelSink(
    private val model: FaceCoverageModel,
) : EnrollmentCoverageSink {

    override fun qualityFloor(magnitudeDeg: Double): Float = model.qualityFloor(magnitudeDeg)

    override fun ingest(
        yawDeg: Double,
        pitchDeg: Double,
        quality: Float,
        framed: Boolean,
        brightness: Float,
        faceHeight: Double,
        jpeg: ByteArray?,
    ) {
        model.ingest(
            yawDeg = yawDeg,
            pitchDeg = pitchDeg,
            quality = quality,
            framed = framed,
            brightness = brightness,
            faceHeight = faceHeight,
            jpeg = { jpeg },
        )
    }

    override fun noteFaceLost() {
        model.noteFaceLost()
    }
}
