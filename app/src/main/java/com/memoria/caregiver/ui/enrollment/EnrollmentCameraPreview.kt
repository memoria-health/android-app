package com.memoria.caregiver.ui.enrollment

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

/** Test tag on the preview surface, so the enrollment shells can assert it is shown. */
const val ENROLLMENT_PREVIEW_TAG: String = "enrollment.preview"

/**
 * Live camera preview.
 *
 * Spec: `EnrollmentCameraPreview` in `EnrollmentVisionCamera.swift`.
 *
 * Mirrors horizontally when [mirrored] (front self-enroll) so the person sees
 * themselves as in a mirror. **The captured frame is never mirrored** — that
 * happens on the analysis stream, which is left alone.
 *
 * `PreviewView` already mirrors the front camera and leaves the rear one alone,
 * which is exactly what iOS configures by hand, so in the two cases the app
 * actually uses no extra transform is applied. [previewNeedsExtraFlip] states
 * that relationship in one place and covers the other two combinations, so the
 * flag is honoured rather than quietly ignored.
 *
 * `ScaleType.FILL_CENTER` is `.resizeAspectFill`; [EnrollmentGeometry] assumes it.
 * `ImplementationMode.COMPATIBLE` backs the preview with a `TextureView`: the
 * front dial clips this preview into a circle, and a `SurfaceView` — the
 * performance-mode default — cannot be clipped to a rounded shape or
 * transformed by a `graphicsLayer`.
 */
@Composable
fun EnrollmentCameraPreview(
    camera: EnrollmentCamera,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
) {
    val flip = previewNeedsExtraFlip(camera.facing, mirrored)
    AndroidView(
        modifier = modifier
            .testTag(ENROLLMENT_PREVIEW_TAG)
            .then(if (flip) Modifier.graphicsLayer { scaleX = -1f } else Modifier),
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view -> camera.attachPreview(view.surfaceProvider) },
        onRelease = { camera.detachPreview() },
    )
}
