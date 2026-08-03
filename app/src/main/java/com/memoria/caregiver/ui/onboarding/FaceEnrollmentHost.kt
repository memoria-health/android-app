package com.memoria.caregiver.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.memoria.caregiver.data.enrollment.FaceCoverageModel
import com.memoria.caregiver.data.enrollment.GuidanceStyle
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.enrollment.EnrollmentCamera
import com.memoria.caregiver.ui.enrollment.EnrollmentCameraPreview
import com.memoria.caregiver.ui.enrollment.EnrollmentFacing
import com.memoria.caregiver.ui.enrollment.FaceCoverageModelSink
import com.memoria.caregiver.ui.enrollment.PlaceholderQualityScorer
import com.memoria.caregiver.ui.permissions.AppPermission
import com.memoria.caregiver.ui.permissions.PermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Hosts [FaceEnrollmentScreen]: runs the camera, feeds the coverage model and
 * uploads what it kept.
 *
 * Spec: `Features/Onboarding/Enrollment/FaceEnrollmentView.swift`, reached from
 * two places — the patient Settings identity card ("Enroll" / "Re-enroll", no
 * consent step) and the post-create onboarding flow (after the consent screen).
 *
 * ## What is joined here
 *
 * `EnrollmentCamera` and `FaceCoverageModel` were both built and both tested,
 * and **nothing implemented the interface between them in production** —
 * `FaceCoverageModelSink` exists precisely to join them (FINDINGS.md F-5). This
 * is the first production caller of all three.
 *
 * ## One model, two cameras
 *
 * The coverage model is remembered ABOVE the facing, so switching between the
 * rear and front camera keeps every arc already captured. iOS shares one
 * `@StateObject` across the switch for the same reason. The camera is rebuilt
 * per facing because `EnrollmentCamera` binds one `CameraSelector` for its
 * lifetime.
 *
 * ## Permission
 *
 * The gate does **not** prompt on composition — see `RuntimePermission.kt` and
 * DIFFERENCES.md D13. The request follows a tap: the caller fires
 * [PermissionState.request] from the row that means "start enrollment", and
 * failing that, the placeholder's own "Open Settings" affordance asks the first
 * time and opens the system settings page afterwards, which is the only thing
 * that helps once Android has stopped showing the dialog.
 */
@Composable
fun FaceEnrollmentHost(
    api: NemaraApiClient,
    patientId: String,
    permission: PermissionState,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Above the facing, so a camera switch keeps the arcs already filled.
    val coverage = remember(patientId) { FaceCoverageModel() }
    var facing by remember(patientId) { mutableStateOf(EnrollmentFacing.Rear) }
    var isSubmitting by remember(patientId) { mutableStateOf(false) }
    var submitError by remember(patientId) { mutableStateOf<String?>(null) }

    val granted = permission.granted

    // One camera per facing, bound while it is on screen and closed when it is
    // not. `DisposableEffect` rather than `remember`: an EnrollmentCamera owns a
    // CameraX binding, an ML Kit detector and an analysis thread, and leaking any
    // of the three keeps the camera light on after the screen is gone.
    val camera = remember(facing, granted) {
        if (!granted) {
            null
        } else {
            EnrollmentCamera(
                context = context,
                facing = facing,
                sink = FaceCoverageModelSink(coverage),
                // QUESTIONS.md Q2: this is a constant that clears every floor,
                // not a quality metric. It is what lets the loop run end to end;
                // it must be replaced before enrollment reaches a household.
                qualityScorer = PlaceholderQualityScorer,
            )
        }
    }

    DisposableEffect(camera, lifecycleOwner) {
        camera?.start(lifecycleOwner)
        onDispose { camera?.close() }
    }

    val coverageState by coverage.state.collectAsState()

    // Standing defaults for the window where there is no camera — permission not
    // held, so nothing is bound. Remembered against the camera rather than built
    // inline, because a fresh flow every recomposition would restart the
    // collector on every frame.
    val configured by remember(camera) {
        camera?.isConfigured ?: MutableStateFlow(false)
    }.collectAsState()
    val setupError by remember(camera) {
        camera?.setupError ?: MutableStateFlow(null)
    }.collectAsState()
    val faceBox by remember(camera) {
        camera?.faceBox ?: MutableStateFlow(null)
    }.collectAsState()

    val state = FaceEnrollmentState(
        facing = facing,
        cameraAuthorized = granted,
        cameraConfigured = configured,
        setupError = setupError,
        coverage = coverageState.toDialState(),
        frontalCount = coverageState.frontalCount,
        isComplete = coverageState.isComplete,
        guidanceCaption = FaceCoverageModel.caption(
            coverageState.guidance,
            // The rear camera is a nurse orbiting the resident; the front one is
            // the person turning their own head. Only the wording differs.
            if (facing == EnrollmentFacing.Rear) {
                GuidanceStyle.CAMERA_ORBITS
            } else {
                GuidanceStyle.SUBJECT_TURNS
            },
        ),
        faceBox = faceBox,
        isSubmitting = isSubmitting,
        submitError = submitError,
    )

    // Back means Skip, which is what the header's own Skip does: leave without
    // uploading. Anything else falls through to the Activity and quits the app
    // in the middle of a capture session — FINDINGS.md F-25.
    BackHandler(onBack = onComplete)

    FaceEnrollmentScreen(
        state = state,
        modifier = modifier,
        preview = {
            val live = camera
            // Only the FRONT preview is mirrored. The captured JPEG is never
            // mirrored — a mirrored face poisons the re-ID gallery (PARITY.md §26)
            // — and mirroring here touches the preview surface only.
            if (live != null) {
                EnrollmentCameraPreview(
                    camera = live,
                    mirrored = facing == EnrollmentFacing.Front,
                )
            }
        },
        onSkip = onComplete,
        onFacingChange = { facing = it },
        onOpenSettings = {
            // First refusal-free tap asks; after that Android has stopped
            // showing the dialog and the settings page is the only route back.
            if (!permission.granted && !permission.denied) {
                permission.request()
            } else {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        onSubmit = {
            val photos = coverage.allFrames().take(FACE_ENROLLMENT_MAX_UPLOAD)
            // Both guards are iOS's, and both matter: `submitFaceEmbeddings`
            // rejects an empty list rather than paying for a 422, and the button
            // is reachable regardless of what it is drawn as.
            if (photos.isEmpty() || isSubmitting) return@FaceEnrollmentScreen
            isSubmitting = true
            submitError = null
            scope.launch {
                try {
                    api.enrollment.submitFaceEmbeddings(patientId, photos)
                    onComplete()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Throwable) {
                    // Verbatim from the Swift, prefix included. The screen stays
                    // open: the frames are still in the model and Done can be
                    // tapped again.
                    submitError = "Submit failed: " + enrollmentErrorFor(e)
                } finally {
                    isSubmitting = false
                }
            }
        },
    )
}

/**
 * The camera permission gate, composed but not fired.
 *
 * Composing it is free and never prompts; calling [PermissionState.request] is
 * what shows the system dialog, and that call belongs to the tap that means
 * "start enrollment". Hoisted to a named function so both entry points ask for
 * the same permission the same way.
 */
@Composable
fun rememberEnrollmentPermission(): PermissionState =
    com.memoria.caregiver.ui.permissions.rememberPermissionState(AppPermission.Camera)

/** Reduces the model's full state to the subset the dial draws. */
private fun com.memoria.caregiver.data.enrollment.FaceCoverageState.toDialState() =
    CoverageDialState(
        segmentCount = segments.size,
        covered = segments.filter { it.covered }.map { it.id }.toSet(),
        currentSegment = currentSegment,
        needleAngleDeg = needleAngleDeg,
        needleFrac = needleFrac,
        faceVisible = faceVisible,
        wellFramed = wellFramed,
        hasFrontal = hasFrontal,
    )

/**
 * `error.localizedDescription`, which is what the Swift interpolates after its
 * "Submit failed: " prefix.
 */
internal fun enrollmentErrorFor(e: Throwable): String = when (e) {
    is com.memoria.caregiver.data.remote.ApiError -> e.errorDescription
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
