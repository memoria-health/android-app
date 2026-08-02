package com.memoria.caregiver.ui.onboarding

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.enrollment.EnrollmentFacing
import com.memoria.caregiver.ui.enrollment.EnrollmentGeometry
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra

/**
 * Guided face enrollment: the shell around the two dials. Spec: PARITY.md §26-27
 * and `Features/Onboarding/Enrollment/FaceEnrollmentView.swift`,
 * `FrontEnrollmentView.swift`, `RearEnrollmentView.swift`.
 *
 * **Rear is the default**, because the model is a nurse pointing at the resident
 * and walking around them. Front is self-enroll with the mirrored dial. Both
 * feed one coverage model, so switching camera keeps the arcs already captured —
 * which is why the picker takes the facing from state rather than owning it.
 *
 * This unit renders. It does not open a camera, run face detection or upload:
 * the preview arrives as a slot, the pose data arrives as [CoverageDialState],
 * and Done and Skip are reported.
 */
@Composable
fun FaceEnrollmentScreen(
    state: FaceEnrollmentState,
    modifier: Modifier = Modifier,
    /**
     * The live preview for the current facing — `EnrollmentCameraPreview`, with
     * `mirrored` set for the front camera. Passed in rather than built here
     * because it needs an `EnrollmentCamera`, and binding and unbinding that
     * camera is the capture unit's job, not the shell's.
     *
     * **Only the preview is mirrored — the captured JPEG must be the true
     * un-mirrored face**, or the re-ID gallery is poisoned (PARITY.md §26).
     */
    preview: @Composable () -> Unit,
    onSkip: () -> Unit,
    onFacingChange: (EnrollmentFacing) -> Unit,
    onOpenSettings: () -> Unit,
    onSubmit: () -> Unit,
) {
    // Milestone cues live here rather than in the coverage model, so the model
    // stays headless, and rather than in the camera, so no frame callback ever
    // touches the vibrator.
    EnrollmentMilestoneHaptics(
        coveredCount = state.coverage.covered.size,
        frontalCount = state.frontalCount,
        isComplete = state.isComplete,
    )

    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        Header(onSkip = onSkip)

        CameraPicker(
            facing = state.facing,
            // Locked once coverage is complete: the captures are in, and
            // switching camera at that point can only confuse.
            enabled = !state.isComplete,
            onSelect = onFacingChange,
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.facing) {
                EnrollmentFacing.Front -> FrontEnrollmentShell(
                    state = state,
                    preview = preview,
                    onOpenSettings = onOpenSettings,
                )

                EnrollmentFacing.Rear -> RearEnrollmentShell(
                    state = state,
                    preview = preview,
                    onOpenSettings = onOpenSettings,
                )
            }
        }

        Footer(state = state, onSubmit = onSubmit)
    }
}

/**
 * Client-side ceiling on uploaded frames; keep in step with the backend cap.
 * Two frames per arc plus the frontal pair is about 26, so 40 leaves headroom
 * without letting a pathological session post hundreds of images.
 */
const val FACE_ENROLLMENT_MAX_UPLOAD: Int = 40

/**
 * Test handle for the header's Skip, which shares its text with the invisible
 * spacer that balances the title.
 */
const val FaceEnrollmentSkipTestTag: String = "face_enrollment.skip"

/**
 * The picker's two segments, in the Swift's order — rear first, because that is
 * the default and the flow the product is built around.
 *
 * The labels live here rather than on [EnrollmentFacing] itself: that enum
 * belongs to the camera unit, where a UI string has no business.
 */
private val CameraOptions = listOf(
    EnrollmentFacing.Rear to "Rear camera",
    EnrollmentFacing.Front to "Front camera",
)

/**
 * Everything the enrollment shell renders.
 *
 * [coverage] is the dial's view of the coverage model; [frontalCount] is here
 * too because the milestone haptic fires on every frontal frame kept, not only
 * the first, and the dial only needs to know whether there is one.
 */
@Immutable
data class FaceEnrollmentState(
    val facing: EnrollmentFacing = EnrollmentFacing.Rear,
    /** Camera permission granted. False shows the placeholder's Settings link. */
    val cameraAuthorized: Boolean = false,
    /** The capture session is configured and delivering frames. */
    val cameraConfigured: Boolean = false,
    val setupError: String? = null,
    val coverage: CoverageDialState = CoverageDialState(),
    val frontalCount: Int = 0,
    /** From the coverage model: a frontal frame plus every arc. */
    val isComplete: Boolean = false,
    /** The settled caption under the headline; null most of the time. */
    val guidanceCaption: String? = null,
    /**
     * Tracked face box for the rear ring: `EnrollmentCamera.faceBox`, normalised
     * with a top-left origin against the upright frame.
     */
    val faceBox: Rect? = null,
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
) {
    /** True when the preview should be showing rather than the placeholder. */
    val previewReady: Boolean get() = cameraAuthorized && cameraConfigured
}

// ---------------------------------------------------------------------------
// Front: the self-enroll dial
// ---------------------------------------------------------------------------

/**
 * A mirrored circular preview inside the coverage ring. The arc you point your
 * nose at charges up and snaps green when captured.
 */
@Composable
private fun FrontEnrollmentShell(
    state: FaceEnrollmentState,
    preview: @Composable () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (state.isComplete) {
        EnrollmentCompleteView()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (state.previewReady) {
                Box(Modifier.size(FrontPreviewSize).clip(CircleShape)) { preview() }
            } else {
                Box(
                    modifier = Modifier.size(FrontPreviewSize).clip(CircleShape).background(Card),
                    contentAlignment = Alignment.Center,
                ) {
                    EnrollmentPlaceholder(
                        authorized = state.cameraAuthorized,
                        error = state.setupError,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
            CoverageDial(
                state = state.coverage,
                modifier = Modifier.size(FrontPreviewSize + FrontRingInset),
                lineWidth = CoverageDialFrontLineWidth,
            )
        }

        EnrollmentGuidanceBanner(
            headline = "Slowly turn your head in every direction",
            caption = state.guidanceCaption,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }
}

/** iOS: `previewSize = 300`. */
private val FrontPreviewSize = 300.dp

/** iOS: the ring is the preview plus 38. */
private val FrontRingInset = 38.dp

// ---------------------------------------------------------------------------
// Rear: the nurse orbits
// ---------------------------------------------------------------------------

/**
 * Full-bleed preview with the ring locked onto the tracked face, eased so it
 * glides rather than snapping frame to frame. The headline talks to the nurse,
 * not the resident.
 */
@Composable
private fun RearEnrollmentShell(
    state: FaceEnrollmentState,
    preview: @Composable () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (state.isComplete) {
        EnrollmentCompleteView(Modifier.background(Paper))
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (state.previewReady) {
            preview()
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Card),
                contentAlignment = Alignment.Center,
            ) {
                EnrollmentPlaceholder(
                    authorized = state.cameraAuthorized,
                    error = state.setupError,
                    onOpenSettings = onOpenSettings,
                )
            }
        }

        val box = state.faceBox
        if (box != null && state.coverage.faceVisible) {
            val density = LocalDensity.current
            val viewSize = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
            // The rear preview is never mirrored, so the ring sits over the face
            // exactly as the sensor sees it.
            val rect = EnrollmentGeometry.viewRect(box, viewSize, mirrored = false)
            val target = with(density) {
                RearRing(
                    diameter = rearRingDiameter(rect.width, rect.height).toDp(),
                    centreX = rect.center.x.toDp(),
                    centreY = rect.center.y.toDp(),
                )
            }
            // iOS: .animation(.easeOut(duration: 0.18), value: camera.faceBox).
            val spec = tween<Dp>(durationMillis = 180, easing = LinearOutSlowInEasing)
            val diameter by animateDpAsState(target.diameter, spec, label = "rear-ring-diameter")
            val centreX by animateDpAsState(target.centreX, spec, label = "rear-ring-x")
            val centreY by animateDpAsState(target.centreY, spec, label = "rear-ring-y")

            CoverageDial(
                state = state.coverage,
                modifier = Modifier
                    .offset(x = centreX - diameter / 2, y = centreY - diameter / 2)
                    .size(diameter),
                lineWidth = CoverageDialRearLineWidth,
            )
        }

        EnrollmentGuidanceBanner(
            headline = "Slowly move around to every side of them",
            caption = state.guidanceCaption,
            modifier = Modifier.align(Alignment.TopCenter),
            onDark = true,
        )
    }
}

/** Where the rear ring sits and how big it is, in dp. */
private data class RearRing(val diameter: Dp, val centreX: Dp, val centreY: Dp)

/**
 * Ring diameter for a tracked face box: the box's longer side times 1.9, so the
 * ring clears the head with room for the arcs rather than cutting across it.
 */
fun rearRingDiameter(boxWidth: Float, boxHeight: Float): Float =
    maxOf(boxWidth, boxHeight) * REAR_RING_BOX_MULTIPLE

/** iOS: `max(rect.width, rect.height) * 1.9`. */
const val REAR_RING_BOX_MULTIPLE: Float = 1.9f

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun Header(onSkip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bottomHairline()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Skip",
            modifier = Modifier
                .testTag(FaceEnrollmentSkipTestTag)
                .plainClickable(onClick = onSkip),
            style = NemaraType.bodySmall,
            color = Ink3,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Face enrollment",
            style = NemaraType.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        // Invisible twin, so the title sits centered against Skip.
        Text(text = "Skip", modifier = Modifier.alpha(0f), style = NemaraType.bodySmall)
    }
}

/**
 * iOS: `Picker("Camera", selection:).pickerStyle(.segmented)`.
 *
 * Hand-built with the same geometry as `AddCameraScreen`'s and
 * `TermsGateScreen`'s — a 2dp track inset and a `Radius.sm` thumb inside a
 * `Radius.md` track — so the app has one segmented control rather than three
 * that drift.
 */
@Composable
private fun CameraPicker(
    facing: EnrollmentFacing,
    enabled: Boolean,
    onSelect: (EnrollmentFacing) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(Radius.md))
            .background(PaperDim)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((option, label) in CameraOptions) {
            val isSelected = option == facing
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (isSelected) Card else Color.Transparent)
                    .plainClickable(enabled = enabled) { onSelect(option) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    // UISegmentedControl draws the selected segment's label
                    // semibold and the rest regular, both at 13.
                    style = if (isSelected) NemaraType.bodySmallStrong else NemaraType.bodySmall,
                    color = if (isSelected) Ink else Ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Footer(state: FaceEnrollmentState, onSubmit: () -> Unit) {
    val ready = state.isComplete && !state.isSubmitting
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .topHairline()
            .padding(horizontal = 14.dp)
            .padding(top = Spacing.sm, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        state.submitError?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = NemaraType.bodySmall,
                color = Terra,
                textAlign = TextAlign.Center,
            )
        }
        NemaraButton(
            title = if (state.isSubmitting) "Saving…" else "Done",
            modifier = Modifier.alpha(if (ready) 1f else 0.5f),
            variant = NemaraButtonVariant.Primary,
            block = true,
            // One name, so the label flip to "Saving…" does not split the funnel.
            track = "Done",
            enabled = ready,
            onClick = onSubmit,
        )
    }
}
