package com.memoria.caregiver.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.memoria.caregiver.ui.media.PickedImage
import com.memoria.caregiver.ui.media.decodePickedImage
import com.memoria.caregiver.ui.onboarding.PATIENT_PHOTO_CONTENT_TYPE
import com.memoria.caregiver.ui.onboarding.PATIENT_PHOTO_UNREADABLE_MESSAGE
import com.memoria.caregiver.ui.onboarding.PATIENT_PHOTO_UNSUPPORTED_MESSAGE
import com.memoria.caregiver.ui.onboarding.compressPatientPhotoJpeg
import com.memoria.caregiver.ui.onboarding.rememberPatientPhotoPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Change photo" on the patient profile card: pick, downscale, upload.
 *
 * Spec: `PatientSettingsView.uploadPickedPhoto`. Unlike the new-patient form,
 * the patient row already exists here, so the upload runs straight away rather
 * than waiting for a create.
 *
 * The compression constants are `NewPatientPhoto.kt`'s and are shared rather
 * than re-derived — the Swift carries the same routine on both screens and its
 * comment says to keep the two in step, which is easier to honour when there is
 * only one copy.
 *
 * @return the lambda `PatientSettingsScreen`'s `onPickPhoto` expects.
 */
@Composable
fun rememberPatientPhotoUpload(viewModel: PatientSettingsViewModel): () -> Unit {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver

    return rememberPatientPhotoPicker { uri ->
        // Null is a back-out of the picker. iOS's binding simply stays put, so
        // the current photo must be left alone rather than cleared.
        if (uri == null) return@rememberPatientPhotoPicker
        scope.launch {
            // Decode and re-encode off the main thread: opening the stream is
            // disk I/O and the downscale is a full-image resample.
            when (val picked = withContext(Dispatchers.IO) { decodePickedImage(resolver, uri) }) {
                is PickedImage.Decoded -> {
                    val jpeg = withContext(Dispatchers.IO) {
                        compressPatientPhotoJpeg(picked.bitmap)
                    }
                    // The source bitmap is dead once it has been re-encoded, and
                    // a full-resolution camera photo is tens of megabytes.
                    picked.bitmap.recycle()
                    viewModel.uploadPhoto(jpeg, PATIENT_PHOTO_CONTENT_TYPE)
                }

                PickedImage.Unreadable ->
                    viewModel.reportPhotoPickFailure(PATIENT_PHOTO_UNREADABLE_MESSAGE)

                PickedImage.Unsupported ->
                    viewModel.reportPhotoPickFailure(PATIENT_PHOTO_UNSUPPORTED_MESSAGE)
            }
        }
    }
}
