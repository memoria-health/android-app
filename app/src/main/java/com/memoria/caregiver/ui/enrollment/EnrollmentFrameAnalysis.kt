package com.memoria.caregiver.ui.enrollment

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Every per-frame decision the enrollment camera makes, lifted out of the
 * CameraX/ML Kit callback so it can be tested on the JVM.
 *
 * Spec: `Features/Onboarding/Enrollment/EnrollmentVisionCamera.swift`,
 * `PARITY.md` §26. Nothing in this file touches Android, the camera, or ML Kit.
 *
 * ## Coordinate convention
 *
 * iOS works in Vision's normalised space: origin **bottom-left**. ML Kit returns
 * **pixel** rects with a **top-left** origin. Everything below is written in
 * normalised **top-left** coordinates — the Android-native convention — and
 * [normalizeBox] is the single place the pixel-to-normalised conversion happens.
 *
 * The flip does not change [wellFramed]'s numbers, because both of its vertical
 * bands are symmetric about 0.5: iOS's `minY >= 0.20 && maxY <= 0.80` becomes
 * `top >= 0.20 && bottom <= 0.80` under `y' = 1 - y`. That is a coincidence of
 * the chosen constants, not a general rule, so `EnrollmentFrameAnalysisTest`
 * pins it — if either bound is ever made asymmetric, the test says so.
 */
enum class EnrollmentFacing { Front, Rear }

/**
 * Device-tuned and iOS-carried constants. Every value here has a named
 * counterpart in `EnrollmentVisionCamera.swift`.
 */
object EnrollmentTuning {

    /**
     * Process at ~this rate. iOS: `targetHz = 15` — "Vision every frame is
     * wasteful at 30 fps", and the same is true of ML Kit.
     */
    const val TARGET_HZ: Double = 15.0

    // ── device-tune sign constants (verify once on a real device) ──────
    //
    // Carried verbatim from iOS, where they are marked "verify once on a real
    // device". They were calibrated against Vision; ML Kit's Euler angles are
    // a different measurement, so PARITY.md §26 expects BOTH to be re-verified
    // on Android hardware. If the dial fills OPPOSITE to the way the head
    // turns, flip the matching constant here — nothing else needs to change.

    /**
     * Yaw sign so a left/right turn maps to the arc the user expects. The front
     * preview is mirrored, so its sign is flipped versus the rear.
     * (iOS device-verified: front -1 gives nose-left → left arc.)
     */
    const val FRONT_YAW_SIGN: Double = -1.0

    /** Rear yaw sign. (iOS device-verified at +1.) */
    const val REAR_YAW_SIGN: Double = 1.0

    /**
     * Pitch sign so up/down maps to the top/bottom arcs. Same for both cameras.
     * (iOS device-verified good at -1.)
     */
    const val PITCH_SIGN: Double = -1.0

    // ── framing geometry (differs front vs rear) ──────────────────────

    /** A face smaller than this fraction of the frame height is never framed. */
    const val MIN_FACE_HEIGHT: Float = 0.16f

    /** Front: horizontal band, ~full width. */
    const val FRONT_MIN_X: Float = 0.04f
    const val FRONT_MAX_X: Float = 0.96f

    /**
     * Front: vertical band ≈ the visible ring. The round preview aspect-fills
     * the tall sensor into a circle, cropping top and bottom, so only the
     * middle ~[0.22, 0.78] of the sensor is actually inside the ring. This band
     * is a hair looser than that crop. Gate on the sensor frame instead and a
     * face spilling out of the ring (too close, chin cut off) wrongly reads as
     * fine.
     */
    const val FRONT_MIN_Y: Float = 0.20f
    const val FRONT_MAX_Y: Float = 0.80f

    /**
     * Rear: full-bleed preview with a face-tracking ring, so the whole sensor
     * frame is visible — gate on the frame edge.
     */
    const val REAR_EDGE: Float = 0.02f

    // ── capture ───────────────────────────────────────────────────────

    /** iOS session preset `.hd1280x720`, applied to the analysis stream. */
    const val CAPTURE_WIDTH: Int = 1280
    const val CAPTURE_HEIGHT: Int = 720

    /**
     * Downscale target for the embedded JPEG. ArcFace only needs a 112² aligned
     * crop, so a full-res selfie is wasted bytes.
     */
    const val JPEG_TARGET_WIDTH: Int = 640

    /** iOS `kCGImageDestinationLossyCompressionQuality: 0.8`, on Android's 0-100 scale. */
    const val JPEG_QUALITY: Int = 80

    /**
     * Scene luma is a coarse subsample: ~24 samples across each axis. iOS
     * derives the step as `max(1, width / 24)`.
     */
    const val LUMA_GRID: Int = 24
}

/**
 * Read-only pixel access to one upright camera frame.
 *
 * Only luma is exposed, because that is all the two consumers need: the scene
 * brightness estimate below, and whatever capture-quality metric answers
 * `QUESTIONS.md` Q2. Coordinates are **upright** — that is, already rotated for
 * display — so `(0, 0)` is the top-left of the frame as the user sees it.
 */
interface EnrollmentFrame {
    /** Upright frame width in pixels. */
    val width: Int

    /** Upright frame height in pixels. */
    val height: Int

    /** Luma at an upright pixel, 0-255. */
    fun luma(x: Int, y: Int): Int
}

/** Head pose in the coverage model's axes: yaw positive right, pitch positive up. */
data class EnrollmentPose(val yawDeg: Double, val pitchDeg: Double) {
    /** How extreme the pose is, in degrees — `hypot(yaw, pitch)`. */
    val magnitudeDeg: Double get() = sqrt(yawDeg * yawDeg + pitchDeg * pitchDeg)
}

/**
 * Everything the camera decides about one detected face, before it does any
 * expensive work. The two `should…` flags are the whole point: iOS skips work on
 * frames that will not be kept, and at 15 Hz both skips matter.
 */
data class EnrollmentFrameDecision(
    /** Normalised face box, top-left origin. Not clamped — see [normalizeBox]. */
    val box: Rect,
    val yawDeg: Double,
    val pitchDeg: Double,
    val magnitudeDeg: Double,
    val quality: Float,
    /** The angle-aware floor this frame was judged against. */
    val qualityFloor: Float,
    val framed: Boolean,
    /**
     * Encode the JPEG **only** when the frame is worth keeping — framed AND
     * clearing the angle-aware floor. An unframed face never produces a JPEG,
     * so a half-cut face can't green an arc.
     */
    val shouldEncodeJpeg: Boolean,
    /**
     * Scene brightness is only needed to EXPLAIN a low-quality frame, so it is
     * computed just then. On a good frame iOS passes a flat 1.0.
     */
    val shouldComputeSceneLuma: Boolean,
)

/** Brightness reported for a frame that cleared the quality floor. iOS: `1.0`. */
const val ENROLLMENT_BRIGHTNESS_NOT_MEASURED: Float = 1.0f

/**
 * Rate limiter for the analysis stream, on a **monotonic** clock.
 *
 * iOS rate-limits on `Date()`. A wall clock can step backwards (NTP, a manual
 * time change), which would stall the analyser until real time caught up.
 * `System.nanoTime()` cannot. Same 15 Hz, same first-frame-always-passes
 * behaviour — iOS seeds `lastProcess` with `Date.distantPast`.
 */
class EnrollmentRateLimiter(private val targetHz: Double = EnrollmentTuning.TARGET_HZ) {
    private var hasProcessed = false
    private var lastNanos = 0L

    fun shouldProcess(nowNanos: Long): Boolean {
        if (hasProcessed && (nowNanos - lastNanos) / 1_000_000_000.0 < 1.0 / targetHz) return false
        hasProcessed = true
        lastNanos = nowNanos
        return true
    }
}

/** The yaw sign for a camera. See [EnrollmentTuning]. */
fun yawSign(facing: EnrollmentFacing): Double =
    if (facing == EnrollmentFacing.Front) EnrollmentTuning.FRONT_YAW_SIGN else EnrollmentTuning.REAR_YAW_SIGN

/**
 * ML Kit head angles → the coverage model's pose axes.
 *
 * `headEulerAngleY` is the yaw and `headEulerAngleX` the pitch, both already in
 * degrees — iOS has to convert Vision's radians first, which is the only step
 * that drops out. The signs are applied here and nowhere else.
 */
fun poseFrom(
    headEulerAngleY: Float,
    headEulerAngleX: Float,
    facing: EnrollmentFacing,
): EnrollmentPose = EnrollmentPose(
    yawDeg = headEulerAngleY.toDouble() * yawSign(facing),
    pitchDeg = headEulerAngleX.toDouble() * EnrollmentTuning.PITCH_SIGN,
)

/**
 * Upright frame size for a sensor buffer that must be rotated by
 * [rotationDegrees] to face the user the right way up.
 */
fun uprightWidth(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Int =
    if (isQuarterTurn(rotationDegrees)) sourceHeight else sourceWidth

/** See [uprightWidth]. */
fun uprightHeight(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Int =
    if (isQuarterTurn(rotationDegrees)) sourceWidth else sourceHeight

private fun isQuarterTurn(rotationDegrees: Int): Boolean =
    normalizedRotation(rotationDegrees).let { it == 90 || it == 270 }

private fun normalizedRotation(rotationDegrees: Int): Int =
    ((rotationDegrees % 360) + 360) % 360

/**
 * Inverse rotation: the source-buffer column an upright pixel came from.
 *
 * Used to read the luma plane without materialising a rotated copy of the
 * frame. Clamped, so a caller that asks for an out-of-range pixel reads an edge
 * pixel rather than corrupting memory.
 */
fun sourceX(x: Int, y: Int, sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Int =
    when (normalizedRotation(rotationDegrees)) {
        90 -> clamp(y, sourceWidth)
        180 -> clamp(sourceWidth - 1 - x, sourceWidth)
        270 -> clamp(sourceWidth - 1 - y, sourceWidth)
        else -> clamp(x, sourceWidth)
    }

/** See [sourceX]. */
fun sourceY(x: Int, y: Int, sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Int =
    when (normalizedRotation(rotationDegrees)) {
        90 -> clamp(sourceHeight - 1 - x, sourceHeight)
        180 -> clamp(sourceHeight - 1 - y, sourceHeight)
        270 -> clamp(x, sourceHeight)
        else -> clamp(y, sourceHeight)
    }

private fun clamp(v: Int, size: Int): Int = min(max(v, 0), max(size - 1, 0))

/**
 * ML Kit pixel rect → normalised, top-left origin.
 *
 * **Deliberately not clamped to [0, 1].** ML Kit will report a box that runs off
 * the frame when a face is half out of shot, and [wellFramed] exists precisely
 * to catch that spill. Clamping here would make every half-cut face look
 * perfectly framed.
 */
fun normalizeBox(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    frameWidth: Int,
    frameHeight: Int,
): Rect {
    if (frameWidth <= 0 || frameHeight <= 0) return Rect.Zero
    return Rect(
        left = left.toFloat() / frameWidth,
        top = top.toFloat() / frameHeight,
        right = right.toFloat() / frameWidth,
        bottom = bottom.toFloat() / frameHeight,
    )
}

/**
 * Framing verdict for a normalised face box.
 *
 * "Keep your whole face in the ring": the face must not spill past the visible
 * ring and must be big enough. There is deliberately **no centering gate** —
 * that made profiles slow to land. This is about clipping, not centre. When it
 * goes false, the front view shows the recenter hint.
 *
 * The two cameras have different geometry, so the boundary differs — see
 * [EnrollmentTuning.FRONT_MIN_Y] and [EnrollmentTuning.REAR_EDGE].
 */
fun wellFramed(box: Rect, facing: EnrollmentFacing): Boolean {
    if (box.height < EnrollmentTuning.MIN_FACE_HEIGHT) return false
    return if (facing == EnrollmentFacing.Front) {
        box.left >= EnrollmentTuning.FRONT_MIN_X &&
            box.right <= EnrollmentTuning.FRONT_MAX_X &&
            box.top >= EnrollmentTuning.FRONT_MIN_Y &&
            box.bottom <= EnrollmentTuning.FRONT_MAX_Y
    } else {
        box.left >= EnrollmentTuning.REAR_EDGE &&
            box.right <= 1f - EnrollmentTuning.REAR_EDGE &&
            box.top >= EnrollmentTuning.REAR_EDGE &&
            box.bottom <= 1f - EnrollmentTuning.REAR_EDGE
    }
}

/**
 * Index of the largest box, or -1 when there are none. iOS picks the face with
 * the largest bounding box when several are present.
 */
fun indexOfLargestBox(boxes: List<Rect>): Int {
    var best = -1
    var bestArea = Float.NEGATIVE_INFINITY
    boxes.forEachIndexed { index, box ->
        val area = box.width * box.height
        if (area > bestArea) {
            bestArea = area
            best = index
        }
    }
    return best
}

/**
 * Cheap average scene luma (0…1) over a coarse subsample of the frame — enough
 * to tell "the room is dark" from "the frame is blurry" so the low-quality coach
 * can say *why*. Only called on low-quality frames, so the per-frame cost is
 * negligible.
 *
 * iOS walks a BGRA buffer and weights the channels Rec.601
 * (`0.114 B + 0.587 G + 0.299 R`). Here the camera hands over a luma plane,
 * which already carries that same weighting, so the weights do not reappear.
 * The sample grid — `max(1, size / 24)` on each axis — is carried exactly.
 */
fun sceneLuma(frame: EnrollmentFrame): Float {
    val width = frame.width
    val height = frame.height
    if (width <= 0 || height <= 0) return 1f
    val stepX = max(1, width / EnrollmentTuning.LUMA_GRID)
    val stepY = max(1, height / EnrollmentTuning.LUMA_GRID)
    var sum = 0.0
    var count = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            sum += frame.luma(x, y)
            count++
            x += stepX
        }
        y += stepY
    }
    return if (count > 0) (sum / count / 255.0).toFloat() else 1f
}

/**
 * Everything the camera works out about one detected face before spending any
 * time on it.
 *
 * @param qualityFloor the coverage model's angle-aware floor. It is a ramp, not
 *   a threshold, and the camera gates its JPEG encode on the *same* floor the
 *   model captures with — so this is the model's own function, passed in.
 */
fun decideFrame(
    box: Rect,
    headEulerAngleY: Float,
    headEulerAngleX: Float,
    facing: EnrollmentFacing,
    quality: Float,
    qualityFloor: (Double) -> Float,
): EnrollmentFrameDecision {
    val pose = poseFrom(headEulerAngleY, headEulerAngleX, facing)
    val magnitude = pose.magnitudeDeg
    val floor = qualityFloor(magnitude)
    val framed = wellFramed(box, facing)
    return EnrollmentFrameDecision(
        box = box,
        yawDeg = pose.yawDeg,
        pitchDeg = pose.pitchDeg,
        magnitudeDeg = magnitude,
        quality = quality,
        qualityFloor = floor,
        framed = framed,
        shouldEncodeJpeg = framed && quality >= floor,
        shouldComputeSceneLuma = quality < floor,
    )
}

/**
 * Uniform scale for the embedded JPEG: shrink to
 * [EnrollmentTuning.JPEG_TARGET_WIDTH] wide, never enlarge.
 */
fun jpegScale(sourceWidth: Int): Float =
    if (sourceWidth > EnrollmentTuning.JPEG_TARGET_WIDTH) {
        EnrollmentTuning.JPEG_TARGET_WIDTH.toFloat() / sourceWidth
    } else {
        1f
    }

/**
 * Whether the preview needs a horizontal flip *on top of* the one `PreviewView`
 * already applies.
 *
 * CameraX mirrors the front-camera preview by default and does not mirror the
 * rear one, which is exactly what iOS asks for. This exists so the caller's
 * `mirrored` flag is honoured rather than silently ignored, and so the
 * front-preview/rear-preview combination is stated in one testable place.
 *
 * **The data output is never mirrored, on either platform.** Mirroring the
 * analysis frames would flip every yaw sign and poison the re-ID gallery with
 * mirror-image faces.
 */
fun previewNeedsExtraFlip(facing: EnrollmentFacing, mirrored: Boolean): Boolean =
    mirrored != (facing == EnrollmentFacing.Front)
