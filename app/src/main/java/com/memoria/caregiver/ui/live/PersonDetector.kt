package com.memoria.caregiver.ui.live

/**
 * A person bounding box, normalized to 0..1 of the frame's width and height,
 * top-left origin. See [ArgbFrame] for why the origin differs from iOS and why
 * that is a convention rather than a behavior change.
 */
data class NormalizedBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** One detection: where the person is, and how sure the detector is. */
data class DetectedPerson(
    val box: NormalizedBox,
    val confidence: Float,
)

/**
 * On-device person **detection** — boxes, never segmentation.
 *
 * Spec: `Features/Patient/Live/BlurredVideoView.swift`, PARITY.md §12. The Swift
 * comment states the reason outright and it is the whole design: a detector
 * works at any distance, near and far, whereas person *segmentation* is tuned
 * for close-up subjects and **drops a person who walks away**. For a privacy
 * feature that is the exact wrong failure direction, so segmentation is not an
 * acceptable substitute here however convenient the API.
 *
 * iOS uses Apple Vision's `VNDetectHumanRectanglesRequest` with
 * `upperBodyOnly = false`, i.e. full-body boxes. The Swift describes itself as
 * mirroring the web dashboard's COCO-SSD approach, and PARITY.md §12 concludes
 * that Android should follow **web's** approach rather than iOS's — an
 * SSD/MobileNet-class person detector, which produces the box semantics the
 * shared constants in [PersonBoxMath] and [BlurBoxHold] were tuned against.
 *
 * **This interface has no implementation in the repository.** No detection model
 * is vendored yet, and inventing one would be a guess at the thing the
 * cross-platform constants were tuned against. The seam is declared so the blur
 * pipeline, its maths and its tests are complete and reviewable; wiring a real
 * detector is a single class, not a change to any caller here.
 */
interface PersonDetector {
    // TODO(REQ-23): no implementation exists, and BlurredVideoView is never
    // called, so the "Blur people" toggle currently blurs nothing. The pipeline
    // around this interface is complete and tested; only the model is missing.
    // Until it lands the toggle must not present itself as on — see
    // HARDENING.md H-12.

    /**
     * Load the model and warm the accelerator ahead of the first real frame.
     *
     * iOS calls `BlurVideoRenderer.warmUp()` from the app root at launch
     * (PARITY.md §3 lists "warm blur model" as a launch step) so the first
     * blurred frame is not slow, and guards it so repeat calls are free.
     * **The host must call this at launch**; implementations must be safe to
     * call more than once and must not block the caller's thread.
     */
    fun warmUp()

    /**
     * People visible in [frame], which is already upright. Called on the video
     * decode thread at most [BlurBoxHold.DETECT_INTERVAL_MILLIS] apart, never
     * concurrently. Implementations return every candidate they have with its
     * confidence — filtering is [PersonBoxMath]'s job, so the threshold lives in
     * one place next to the padding it was tuned with.
     */
    fun detect(frame: ArgbFrame): List<DetectedPerson>
}

/**
 * Confidence filtering, padding, and normalized-to-pixel conversion — the whole
 * of iOS `personBoxes(in:)` minus the Vision call. Pure, so the two constants
 * that actually decide what a caregiver sees can be pinned by a JVM test.
 *
 * Both numbers are **cross-platform product constants**, not Apple tuning:
 * PARITY.md §12 records them as shared with the web dashboard, and the Swift
 * annotates each one as mirroring web. A silently rounded value here is a
 * privacy difference between the two clients, not a rendering nicety.
 */
object PersonBoxMath {

    /**
     * Uniform padding around each person box so the blur sits proud of the
     * body. Web `PAD`. Applied as a fraction of the box's **own** size, to each
     * of the four edges — so a padded box is 1.36x as wide and 1.36x as tall as
     * the raw detection.
     *
     * iOS: `r.insetBy(dx: -r.width * boxPad, dy: -r.height * boxPad)`, where the
     * negative inset expands.
     */
    const val PAD: Float = 0.18f

    /**
     * Lowest detector confidence that still gets blurred. Web-shared. Lower
     * catches farther and more partial people at the cost of false positives,
     * which for a privacy feature is the cheap direction to be wrong in.
     *
     * iOS compares `obs.confidence >= minConfidence`, so the threshold value
     * itself passes.
     */
    const val MIN_CONFIDENCE: Float = 0.25f

    /**
     * Padded pixel rectangles for everyone confident enough to blur.
     *
     * The result is **not** clamped to the frame: iOS lets the padded rect hang
     * off the edge and intersects only when compositing, which is what keeps a
     * person standing at the edge blurred all the way to the border.
     */
    fun boxes(people: List<DetectedPerson>, frameWidth: Int, frameHeight: Int): List<PixelRect> {
        if (people.isEmpty()) return emptyList()
        return people.mapNotNull { person ->
            if (person.confidence < MIN_CONFIDENCE) null
            else pixelRect(person.box, frameWidth, frameHeight)
        }
    }

    /** One normalized box, padded and scaled into frame pixels. */
    fun pixelRect(box: NormalizedBox, frameWidth: Int, frameHeight: Int): PixelRect {
        val left = box.x * frameWidth
        val top = box.y * frameHeight
        val width = box.width * frameWidth
        val height = box.height * frameHeight
        // Both insets read the pre-padding size, exactly as CGRect.insetBy does.
        val padX = width * PAD
        val padY = height * PAD
        return PixelRect(
            left = left - padX,
            top = top - padY,
            right = left + width + padX,
            bottom = top + height + padY,
        )
    }
}

/**
 * Whether people-blur can actually blur anything on this build.
 *
 * **One flag, read by the UI, so the toggle cannot claim to be on while the
 * pipeline is inert.** [PersonDetector] has no implementation (REQ-23), so
 * today this is false everywhere. It is a `const` rather than a runtime probe
 * because the model's presence is a build-time fact: it either ships in the APK
 * or it does not.
 *
 * The failure this exists to prevent (HARDENING.md H-12): a caregiver turns on
 * "Blur people" for a bedroom camera, the switch shows on, and the video is not
 * blurred. **A privacy control that silently does nothing is worse than one that
 * is visibly unavailable** — the first makes someone believe they are protected.
 *
 * When a detector is vendored, flip this to true in the same change that wires
 * it, and the toggle re-enables itself with no other edit.
 */
object PeopleBlurAvailability {
    const val IS_AVAILABLE: Boolean = false
}
