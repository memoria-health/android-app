package com.memoria.caregiver.data.enrollment

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Front self-enroll (the person turns) vs rear (the nurse orbits). Only affects
 * the wording of the minimal caption.
 */
enum class GuidanceStyle {
    SUBJECT_TURNS,
    CAMERA_ORBITS,
}

/**
 * Coarse, debounced state for the single caption line. Kept dwell-filtered so
 * the text never strobes. The low-quality states ([TOO_FAR] / [TOO_DARK] /
 * [HOLD_STILL]) are ACTIONABLE — they name the likely fixable cause instead of
 * a bare "hold still". [FRONTAL_NEEDED] is the terminal nudge: every arc is
 * filled but the straight-ahead (hub) frame is still missing, so completion is
 * one face-on away.
 */
enum class Guidance {
    SEARCHING,
    TOO_FAR,
    TOO_DARK,
    HOLD_STILL,
    ACTIVE,
    FRONTAL_NEEDED,
    DONE,
}

/** One directional arc of the dial, as the dial renders it. */
data class SegmentState(
    val id: Int,
    /** Centre direction of this arc, degrees (0 = right, 90 = up). */
    val centreDeg: Double,
    /** How many frames are being kept for this arc. */
    val frameCount: Int,
) {
    val covered: Boolean get() = frameCount > 0
}

/**
 * Everything the two dials draw, as one immutable snapshot.
 *
 * The kept JPEG bytes are deliberately NOT in here. iOS carries them inside its
 * published `Segment`, which is harmless on a value type nobody persists, but on
 * Android an observable state class is the kind of thing that ends up in a
 * `SavedStateHandle`. Frames stay private to [FaceCoverageModel] and leave only
 * through [FaceCoverageModel.allFrames]; the dial only ever needed [covered].
 */
data class FaceCoverageState(
    val segments: List<SegmentState>,
    val frontalCount: Int = 0,
    /** Arc the nose currently points at; null when frontal or no face. */
    val currentSegment: Int? = null,
    /**
     * Live RAW magnitude (deg). Feeds the capture threshold + guidance; the
     * dial's charge fill uses [needleFrac] (the smoothed value), not this.
     */
    val currentMagDeg: Double = 0.0,
    /** Live capture quality of the current face (0…1). */
    val currentQuality: Float = 0f,
    /** Whether a face is being detected right now. */
    val faceVisible: Boolean = false,
    /**
     * Whether the current face is well-framed enough to capture (fully in
     * frame). When false the needle greys out — the user's cue that they are
     * not lined up, even at a good angle.
     */
    val wellFramed: Boolean = false,
    /** Smoothed nose direction, model degrees (0 = right, 90 = up). */
    val needleAngleDeg: Double = 0.0,
    /** How far the needle reaches toward the rim, 0…1 (1 = capture angle). */
    val needleFrac: Double = 0.0,
    /** Coarse, debounced caption state (see [FaceCoverageModel.caption]). */
    val guidance: Guidance = Guidance.SEARCHING,
) {
    val coveredCount: Int get() = segments.count { it.covered }

    val progress: Double get() = coveredCount.toDouble() / segments.size

    val hasFrontal: Boolean get() = frontalCount > 0

    /** Every arc touched at least to the minimum extreme, plus a frontal. */
    val isComplete: Boolean get() = hasFrontal && coveredCount == segments.size

    /** Enough to submit usefully (frontal + a majority of the cone). */
    val isSubmittable: Boolean
        get() = hasFrontal && coveredCount >= ceil(segments.size * 0.5).toInt()
}

/**
 * Directional pose-coverage engine shared by both enrollment dials (front
 * FaceID-style nose-dial + rear auto-tracking ring). Port of iOS
 * `Features/Onboarding/Enrollment/FaceCoverageModel.swift`; spec `PARITY.md §20`.
 *
 * A detected face's head pose is mapped to a point on a dial:
 * ```
 *   x = yaw   (turning right = +)      y = pitch (looking up = +)
 *   angle     = atan2(y, x)            // which direction the nose points
 *   magnitude = hypot(x, y)  (degrees) // how EXTREME the angle is
 * ```
 *
 * The dial is split into [segmentCount] equal arcs. A segment is "covered" once
 * we have captured at least one good-quality frame whose direction lands in that
 * arc AND whose magnitude clears [extremeMinDeg] (so a token tilt does not count
 * — we want real profiles). We also take a dedicated frontal capture
 * (magnitude ≈ 0). Per segment we keep the [framesPerSegment] MOST EXTREME
 * frames above the quality floor.
 *
 * Guidance is **visual, not verbal**: the model exposes a pose needle (direction
 * + how-far) that the dial draws as a pointer the user steers into the arcs.
 * **Capture reads the RAW pose** so a held angle registers instantly (smoothing
 * the capture signal just made it feel slow — `PARITY.md §29` item 10). The
 * needle's *rendering* is smoothed on its own so the pointer glides, and text
 * jitter is tamed by a debounce — neither touches capture speed. Capture
 * requires the face to be fully in frame — a half-cut face cannot green an arc —
 * and [FaceCoverageState.wellFramed] going false surfaces the recenter hint.
 *
 * Pure logic, no camera, UI or ML Kit dependency — the camera feeds it
 * [ingest]; the dials render [state].
 *
 * **Threading.** iOS holds this on `@MainActor`. Nothing here is synchronized,
 * so confine [ingest] / [noteFaceLost] to a single thread (the camera analyzer's
 * executor, or the main dispatcher — one of them, consistently). The one
 * exception is [qualityFloor], which reads only constructor values and is
 * therefore safe to call from the camera thread to gate its JPEG encode on the
 * same floor this model captures with.
 */
class FaceCoverageModel(
    /** Number of equal arcs the dial is split into. */
    val segmentCount: Int = 12,
    /** Below this magnitude we treat the pose as "frontal". */
    val frontalMaxDeg: Double = 10.0,
    /** A directional capture must clear this magnitude to REGISTER an arc. */
    val extremeMinDeg: Double = 22.0,
    /**
     * The "full extreme" angle — the magnitude at which the angle-aware quality
     * floor bottoms out at [minQualityFloor] (see [qualityFloor]). Vision's yaw
     * estimate degrades near true profile (~±70°), so this is the practical
     * extreme ceiling; tune on device.
     */
    val reachTargetDeg: Double = 45.0,
    /**
     * Capture-quality floor for a FRONTAL frame — kept strict so the most-used
     * pose is high quality. Profiles are relaxed toward [minQualityFloor] (see
     * [qualityFloor]): capture quality is intrinsically lower for turned faces,
     * so a flat high bar would reject every profile even in perfect light — and
     * make a "get better light" message a lie. The ramp keeps "low quality"
     * meaning a fixable cause.
     *
     * **Calibration warning.** 0.4 / 0.25 are Vision `faceCaptureQuality`
     * numbers. ML Kit has no equivalent metric, so whatever score the camera
     * feeds [ingest] must have these floors re-derived against it — carrying the
     * iOS numbers onto a different scale silently rejects or accepts the wrong
     * frames. See `QUESTIONS.md` Q2 and `REQUIREMENTS.md` REQ-18.
     */
    val minQuality: Float = 0.4f,
    /**
     * The relaxed end of the ramp — the floor at a full extreme angle. Below
     * this, even a profile is too poor to keep. Same calibration warning as
     * [minQuality].
     */
    val minQualityFloor: Float = 0.25f,
    /** Frames retained per segment (most-extreme kept). */
    val framesPerSegment: Int = 2,
    /** Frontal frames retained. */
    val frontalTarget: Int = 2,
    /**
     * How long a coarse guidance state must persist before the caption commits
     * to it — kills flicker on transient pose noise. iOS `guidanceDwell: 0.3`
     * seconds; milliseconds here because [now] is a millisecond clock.
     */
    val guidanceDwellMillis: Long = 300L,
    /**
     * MONOTONIC milliseconds. iOS uses `ProcessInfo.systemUptime` rather than
     * `Date()` so an NTP or clock jump mid-enrollment cannot freeze or strobe
     * the caption; [MONOTONIC_MILLIS] is the same guarantee here.
     */
    private val now: () -> Long = MONOTONIC_MILLIS,
) {

    /** Kept frame: the encoded JPEG, how extreme it was, and its quality. */
    private class KeptFrame(val data: ByteArray, val mag: Double, val quality: Float)

    private val arcDeg: Double = 360.0 / segmentCount
    private val centreDeg: DoubleArray =
        DoubleArray(segmentCount) { (it + 0.5) * arcDeg }
    private val frames: List<MutableList<KeptFrame>> =
        List(segmentCount) { mutableListOf() }
    private val frontal: MutableList<ByteArray> = mutableListOf()

    // ── live state, mirroring the iOS @Published properties one-for-one ──
    private var frontalCount = 0
    private var currentSegment: Int? = null
    private var currentMag = 0.0
    private var currentQuality = 0f
    private var faceVisible = false
    private var wellFramed = false
    private var needleAngleDeg = 0.0
    private var needleFrac = 0.0
    private var guidance = Guidance.SEARCHING

    // guidance-debounce state (jitter is calmed HERE, in the text — not by
    // smoothing the capture signal, which only made capture slow).
    private var guidanceCandidate = Guidance.SEARCHING
    private var guidanceCandidateSince = now()

    // needle-RENDERING-only pose smoothing (see ingest). Purely cosmetic —
    // capture reads the raw pose, so this adds zero capture lag.
    private var needleYaw = 0.0
    private var needlePitch = 0.0
    private var haveNeedle = false

    // latest diagnostic signals from the camera, used to explain a low-quality
    // frame (see instantGuidance).
    private var lastBrightness = 1f // 0…1 scene luma
    private var lastFaceHeight = 1.0 // face box height / frame

    private val _state = MutableStateFlow(snapshot())

    /** What the dials observe. */
    val state: StateFlow<FaceCoverageState> = _state.asStateFlow()

    // ── ingest ────────────────────────────────────────────────────

    /**
     * Feed one detected face. [framed] is the camera's facing-aware framing
     * verdict (fully in frame). [jpeg] is a lambda so the caller only pays the
     * encode when we keep the frame. Returns true if the frame was captured.
     *
     * [quality] is supplied by the caller and never computed here — see the
     * calibration warning on [minQuality].
     */
    fun ingest(
        yawDeg: Double,
        pitchDeg: Double,
        quality: Float,
        framed: Boolean,
        brightness: Float,
        faceHeight: Double,
        jpeg: () -> ByteArray?,
    ): Boolean {
        // Read the RAW pose directly — a held angle registers instantly.
        // (Smoothing the capture signal added lag = "wait longer in every
        // direction". Jitter is calmed in the debounced caption instead.)
        val mag = sqrt(yawDeg * yawDeg + pitchDeg * pitchDeg)
        currentMag = mag
        currentQuality = quality
        faceVisible = true
        wellFramed = framed
        lastBrightness = brightness
        lastFaceHeight = faceHeight

        // Needle RENDERING only: smooth the pose so the pointer glides instead
        // of twitching. Capture uses the raw `mag` above, so this adds zero lag
        // — the mistake before was smoothing the signal that GATES capture;
        // this smooths only the one that DRAWS the needle.
        if (haveNeedle) {
            needleYaw += NEEDLE_ALPHA * (yawDeg - needleYaw)
            needlePitch += NEEDLE_ALPHA * (pitchDeg - needlePitch)
        } else {
            needleYaw = yawDeg
            needlePitch = pitchDeg
            haveNeedle = true
        }
        val needleMag = sqrt(needleYaw * needleYaw + needlePitch * needlePitch)
        needleAngleDeg = atan2(needlePitch, needleYaw) * 180.0 / PI
        needleFrac = min(1.0, needleMag / extremeMinDeg)

        var captured = false
        if (mag < frontalMaxDeg) {
            currentSegment = null
            if (framed && frontal.size < frontalTarget && quality >= qualityFloor(mag)) {
                val data = jpeg()
                if (data != null) {
                    frontal.add(data)
                    frontalCount = frontal.size
                    captured = true
                }
            }
        } else {
            val seg = segmentIndex(yawDeg = yawDeg, pitchDeg = pitchDeg)
            currentSegment = seg
            if (framed && mag >= extremeMinDeg && quality >= qualityFloor(mag)) {
                val kept = frames[seg]
                if (kept.size < framesPerSegment) {
                    val data = jpeg()
                    if (data != null) {
                        kept.add(KeptFrame(data, mag, quality))
                        captured = true
                    }
                } else {
                    val minIdx = kept.indices.minByOrNull { kept[it].mag }
                    if (minIdx != null && mag > kept[minIdx].mag) {
                        val data = jpeg()
                        if (data != null) {
                            // replace the least-extreme stored frame with this
                            // steeper one
                            kept[minIdx] = KeptFrame(data, mag, quality)
                            captured = true
                        }
                    }
                }
                if (captured) {
                    kept.sortByDescending { it.mag }
                }
            }
        }
        commitGuidance(instantGuidance())
        publish()
        return captured
    }

    /**
     * The camera detected no face this frame — collapse the live cursor +
     * needle so the dial reads "no target." Captured frames are kept.
     */
    fun noteFaceLost() {
        faceVisible = false
        wellFramed = false
        currentSegment = null
        currentMag = 0.0
        currentQuality = 0f
        needleFrac = 0.0
        haveNeedle = false
        commitGuidance(instantGuidance())
        publish()
    }

    // ── queries the upload uses ───────────────────────────────────

    /** Every kept frame for upload — frontal first, then each covered arc. */
    fun allFrames(): List<ByteArray> =
        frontal + frames.flatMap { seg -> seg.map { it.data } }

    /**
     * Quality floor for a pose magnitude — strict when frontal, ramping down
     * toward [minQualityFloor] as the face turns, matching how capture quality
     * naturally drops for profiles. Safe to call from the camera thread: it
     * reads only immutable constructor values, so the camera can gate its JPEG
     * encode on the same floor this model captures with.
     */
    fun qualityFloor(mag: Double): Float {
        if (mag <= frontalMaxDeg) return minQuality
        val span = max(1.0, reachTargetDeg - frontalMaxDeg)
        val t = min(1.0, (mag - frontalMaxDeg) / span).toFloat()
        return minQuality - t * (minQuality - minQualityFloor)
    }

    // ── minimal, debounced caption ────────────────────────────────

    /** Instantaneous coarse state, before debouncing. */
    private fun instantGuidance(): Guidance {
        if (isCompleteNow()) return Guidance.DONE
        if (!faceVisible || !wellFramed) return Guidance.SEARCHING

        // Quality below the (angle-aware) floor blocks capture — name the
        // likely FIXABLE cause instead of a bare "hold". This must cover the
        // straight-ahead frame too: in a dim room the frontal capture cannot
        // clear the strict 0.4 floor, and without this it would silently stall
        // on "look straight ahead to finish" with no reason given.
        val onUncoveredArc =
            (currentSegment?.let { frames[it].isEmpty() } ?: false) &&
                currentMag >= extremeMinDeg
        val seekingFrontal = frontalCount == 0 && currentMag < frontalMaxDeg
        if ((onUncoveredArc || seekingFrontal) && currentQuality < qualityFloor(currentMag)) {
            if (lastFaceHeight < TOO_FAR_HEIGHT) return Guidance.TOO_FAR
            if (lastBrightness < DARK_LUMA) return Guidance.TOO_DARK
            return Guidance.HOLD_STILL // distance + light are fine → motion/blur
        }

        // all arcs done, only the straight-ahead (hub) frame is missing
        if (coveredCountNow() == segmentCount && frontalCount == 0) {
            return Guidance.FRONTAL_NEEDED
        }
        return Guidance.ACTIVE
    }

    /**
     * Only let the caption change once a new state has persisted for
     * [guidanceDwellMillis] (completion snaps immediately).
     */
    private fun commitGuidance(instant: Guidance) {
        val nowMillis = now()
        if (instant != guidanceCandidate) {
            guidanceCandidate = instant
            guidanceCandidateSince = nowMillis
        }
        if (guidance == guidanceCandidate) return
        if (guidanceCandidate == Guidance.DONE ||
            nowMillis - guidanceCandidateSince >= guidanceDwellMillis
        ) {
            guidance = guidanceCandidate
        }
    }

    // ── geometry ──────────────────────────────────────────────────

    private fun segmentIndex(yawDeg: Double, pitchDeg: Double): Int {
        var a = atan2(pitchDeg, yawDeg) * 180.0 / PI // −180…180, 0 = right
        if (a < 0) a += 360.0
        return min(segmentCount - 1, (a / arcDeg).toInt())
    }

    // ── snapshot plumbing ─────────────────────────────────────────

    private fun coveredCountNow(): Int = frames.count { it.isNotEmpty() }

    private fun isCompleteNow(): Boolean =
        frontalCount > 0 && coveredCountNow() == segmentCount

    private fun snapshot(): FaceCoverageState =
        FaceCoverageState(
            segments = List(segmentCount) { SegmentState(it, centreDeg[it], frames[it].size) },
            frontalCount = frontalCount,
            currentSegment = currentSegment,
            currentMagDeg = currentMag,
            currentQuality = currentQuality,
            faceVisible = faceVisible,
            wellFramed = wellFramed,
            needleAngleDeg = needleAngleDeg,
            needleFrac = needleFrac,
            guidance = guidance,
        )

    private fun publish() {
        _state.value = snapshot()
    }

    companion object {
        /**
         * Needle-rendering smoothing factor. Render-only: the capture gate
         * reads the raw pose (`PARITY.md §29` item 10).
         */
        const val NEEDLE_ALPHA: Double = 0.4

        /** Face smaller than this (fraction of frame height) reads as "too far". */
        const val TOO_FAR_HEIGHT: Double = 0.28

        /** Scene luma below this reads as "too dark". */
        const val DARK_LUMA: Float = 0.28f

        /**
         * Monotonic milliseconds, immune to wall-clock and NTP jumps — the
         * `ProcessInfo.systemUptime` analog.
         */
        val MONOTONIC_MILLIS: () -> Long = { System.nanoTime() / 1_000_000L }

        /**
         * The single caption shown under the static instruction. Most of the
         * time it is null — the needle carries the guidance. Only the "stuck"
         * states speak up, and only after they have settled.
         */
        fun caption(guidance: Guidance, style: GuidanceStyle): String? =
            when (guidance) {
                // front: the recovery line — shown whenever the face is cut off
                // or the needle cannot go green (not framed). Names the hub so
                // it is unambiguous which of the two circles to line up with.
                Guidance.SEARCHING ->
                    if (style == GuidanceStyle.CAMERA_ORBITS) {
                        "Point the camera at their face"
                    } else {
                        "Re-center your face on the inner circle"
                    }
                Guidance.TOO_FAR ->
                    if (style == GuidanceStyle.CAMERA_ORBITS) {
                        "Move a little closer to them"
                    } else {
                        "Move a little closer"
                    }
                Guidance.TOO_DARK ->
                    if (style == GuidanceStyle.CAMERA_ORBITS) {
                        "Move them into better light"
                    } else {
                        "Find brighter light"
                    }
                Guidance.HOLD_STILL -> "Hold still"
                Guidance.FRONTAL_NEEDED ->
                    if (style == GuidanceStyle.CAMERA_ORBITS) {
                        "Now face them head-on to finish"
                    } else {
                        "Now look straight ahead to finish"
                    }
                Guidance.ACTIVE, Guidance.DONE -> null
            }
    }
}
