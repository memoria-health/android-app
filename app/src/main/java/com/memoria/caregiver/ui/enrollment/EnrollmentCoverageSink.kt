package com.memoria.caregiver.ui.enrollment

/**
 * What [EnrollmentCamera] needs from the coverage model.
 *
 * On iOS the camera holds a `FaceCoverageModel` directly. Here it holds this
 * interface instead, for one reason: the camera and the coverage model are
 * separate units of the port, and a narrow seam lets each be tested without the
 * other. `FaceCoverageModel` implements this; nothing else should.
 *
 * ## Threading
 *
 * [qualityFloor] is called **on the camera's analysis thread**, once per
 * processed frame, so that the camera gates its JPEG encode on exactly the
 * floor the model will capture with. It must read only immutable state — iOS
 * marks its counterpart `nonisolated` for the same reason.
 *
 * [ingest] and [noteFaceLost] are always delivered **on the main thread**, so an
 * implementation may hold ordinary Compose state.
 */
interface EnrollmentCoverageSink {

    /**
     * Quality floor for a pose magnitude in degrees: strict when frontal,
     * ramping down as the face turns.
     *
     * Called off the main thread — see the class note.
     */
    fun qualityFloor(magnitudeDeg: Double): Float

    /**
     * One detected face. Called on the main thread.
     *
     * @param yawDeg positive turning right, sign already applied.
     * @param pitchDeg positive looking up, sign already applied.
     * @param quality capture quality 0…1.
     * @param framed the camera's facing-aware framing verdict.
     * @param brightness scene luma 0…1, or 1.0 when the frame cleared the
     *   quality floor and brightness was therefore never measured.
     * @param faceHeight face box height as a fraction of the frame height.
     * @param jpeg the un-mirrored upright frame, or null when this frame is not
     *   worth keeping.
     */
    fun ingest(
        yawDeg: Double,
        pitchDeg: Double,
        quality: Float,
        framed: Boolean,
        brightness: Float,
        faceHeight: Double,
        jpeg: ByteArray?,
    )

    /** No face in this frame. Called on the main thread. */
    fun noteFaceLost()
}
