package com.memoria.caregiver.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.detectors.DetectorLibraryHost
import com.memoria.caregiver.ui.detectors.DetectorLibraryMode
import com.memoria.caregiver.ui.media.PickedImage
import com.memoria.caregiver.ui.media.decodePickedImage
import com.memoria.caregiver.ui.permissions.PermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts [NewPatientScreen] and the three steps that follow a successful create.
 *
 * Spec: `Features/Onboarding/NewPatientView.swift`, presented as a sheet from
 * both the roster (the `+` and the empty-roster CTA) and the Settings tab's "Add
 * another patient".
 *
 * ## Ordering that is not incidental
 *
 * The photo is picked here and uploaded **after** `createPatient` returns,
 * because the upload endpoint is keyed by patient id and there is none until
 * then. The upload is also best-effort: the patient row already exists by that
 * point, so a failed photo must not read as a failed create. iOS logs and moves
 * on; so does this.
 *
 * ## What "finished" means
 *
 * A successful create does not close this screen — it swaps to
 * [PostCreateEnrollmentFlow], which runs the detector library, the
 * facial-detection consent and the capture in one host, and only its completion
 * dismisses. Neither platform navigates into the new patient afterwards.
 */
@Composable
fun NewPatientHost(
    api: NemaraApiClient,
    cameraPermission: PermissionState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What the form starts as. The app never passes this — a new patient starts
     * empty — and it exists so a test can begin with a date of birth already
     * chosen. The alternative is driving the Material date-picker dialog to
     * reach the *second* thing worth testing, which would make every test of the
     * create depend on the date picker's rendering.
     */
    initialState: NewPatientState = NewPatientState(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resolver = context.contentResolver

    var state by remember { mutableStateOf(initialState) }
    // The JPEG bytes live beside the state rather than in it: they cannot be
    // uploaded until the create returns an id, so they have to outlive the form,
    // and a several-hundred-kilobyte array has no business in a UI state class.
    var pickedJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var createdPatientId by remember { mutableStateOf<String?>(null) }

    val created = createdPatientId
    if (created != null) {
        // The consent step is the one part of the post-create flow that owns no
        // Back handling: the library step has the library host's and the capture
        // step has the enrollment host's, and each of those registers deeper, so
        // this one only ever fires on the consent screen. Without it Back there
        // reached the Activity and quit the app on a patient that had just been
        // created — FINDINGS.md F-25.
        //
        // It ends the flow, which is what "Maybe later" on that screen does. It
        // deliberately does NOT go back to the form: the patient exists, and a
        // form that could be submitted twice is worse than no way back.
        BackHandler(onBack = onDismiss)
        PostCreateEnrollmentFlow(
            modifier = modifier,
            onFinish = onDismiss,
            onDontAskAgain = { FaceConsentPrefs.setDismissed(context, created) },
            detectorLibrary = { onContinue ->
                DetectorLibraryHost(
                    api = api,
                    patientId = created,
                    mode = DetectorLibraryMode.Onboarding,
                    // Onboarding's submit reconciles and then continues; its
                    // leading button is "Skip", which continues without saving.
                    onFinished = onContinue,
                    onLeading = onContinue,
                )
            },
            faceEnrollment = { onComplete ->
                FaceEnrollmentHost(
                    api = api,
                    patientId = created,
                    permission = cameraPermission,
                    onComplete = onComplete,
                )
            },
        )
        return
    }

    val pickPhoto = rememberPatientPhotoPicker { uri ->
        // Null is a back-out. iOS's binding simply stays put, so the current
        // selection must be left alone rather than cleared.
        if (uri == null) return@rememberPatientPhotoPicker
        state = state.copy(isLoadingPhoto = true, submitError = null)
        scope.launch {
            val result = withContext(Dispatchers.IO) { decodePickedImage(resolver, uri) }
            when (result) {
                is PickedImage.Decoded -> {
                    val jpeg = withContext(Dispatchers.IO) {
                        compressPatientPhotoJpeg(result.bitmap)
                    }
                    val preview = result.bitmap.asImageBitmap()
                    pickedJpeg = jpeg
                    // Only this pick's three fields are written, and the rest of
                    // the state is read as it is NOW rather than from a value
                    // captured before the decode — the caregiver may have been
                    // typing throughout (FINDINGS.md F-12).
                    state = state.copy(
                        photoPreview = preview,
                        isLoadingPhoto = false,
                        submitError = null,
                    )
                }

                PickedImage.Unreadable -> state = state.copy(
                    isLoadingPhoto = false,
                    submitError = PATIENT_PHOTO_UNREADABLE_MESSAGE,
                )

                PickedImage.Unsupported -> state = state.copy(
                    isLoadingPhoto = false,
                    submitError = PATIENT_PHOTO_UNSUPPORTED_MESSAGE,
                )
            }
        }
    }

    // The screen's own close is an X in its header; Back has to mean the same
    // thing or it falls through to the Activity and quits the app on top of a
    // half-filled form — FINDINGS.md F-25.
    BackHandler(onBack = onDismiss)

    NewPatientScreen(
        state = state,
        modifier = modifier,
        onClose = onDismiss,
        onFullNameChange = { state = state.copy(fullName = it) },
        onDateOfBirthChange = { state = state.copy(dateOfBirth = it) },
        onGenderChange = { state = state.copy(gender = it) },
        onRelationChange = { state = state.copy(relation = it) },
        onCountryChange = { state = state.copy(country = it) },
        onPickPhoto = pickPhoto,
        onSubmit = {
            if (!state.canSubmit || state.isSubmitting) return@NewPatientScreen
            state = state.copy(isSubmitting = true, submitError = null)
            scope.launch {
                // The request body is built from the form as it stood at the
                // tap, which is what iOS reads at the top of its `submit()`.
                val request = state.toCreateRequest()
                val jpeg = pickedJpeg
                try {
                    val patient = api.createPatient(request)
                    if (jpeg != null) {
                        // Best-effort, deliberately swallowed. The patient row
                        // exists; a photo that failed to upload must not read as
                        // a create that failed, and the caregiver can set the
                        // photo from patient settings.
                        runCatching {
                            api.uploadPatientPhoto(
                                patient.id,
                                jpeg,
                                PATIENT_PHOTO_CONTENT_TYPE,
                            )
                        }
                    }
                    createdPatientId = patient.id
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Throwable) {
                    // Inline, so the caregiver knows the patient was NOT created.
                    state = state.copy(submitError = newPatientErrorFor(e))
                } finally {
                    state = state.copy(isSubmitting = false)
                }
            }
        },
    )
}

/**
 * Verbatim from `ApiError.errorDescription`, which is what Swift's
 * `error.localizedDescription` resolves to for everything the create can throw.
 */
internal fun newPatientErrorFor(e: Throwable): String = when (e) {
    is com.memoria.caregiver.data.remote.ApiError -> e.errorDescription
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
