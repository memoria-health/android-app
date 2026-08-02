package com.memoria.caregiver.ui.onboarding

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.ByteArrayOutputStream

/**
 * The patient-photo downscale values and the encode that uses them. Port of
 * `NewPatientView.swift`'s static `downsampledJPEG(from:maxDimension:quality:)`
 * and its call site.
 *
 * **The Swift carries the same routine in `PatientSettingsView` and says to keep
 * the two in sync.** This is the Android copy of that pair, so if the settings
 * screen grows a photo encode it should call these rather than re-derive them.
 *
 * The screen never calls [compressPatientPhotoJpeg]: decoding a picked `Uri`
 * needs a `ContentResolver` and is I/O, so the host does the work off the main
 * thread and hands back a preview plus the bytes. This file exists so the host
 * does not have to guess the numbers.
 */

/**
 * Longest edge, in pixels, after downscaling. 1024 at
 * [PATIENT_PHOTO_JPEG_QUALITY] lands well under the backend's 1 MiB cap for any
 * reasonable input.
 */
const val PATIENT_PHOTO_MAX_DIMENSION: Int = 1024

/**
 * JPEG quality. iOS asks for `jpegData(compressionQuality: 0.8)` on a 0-1 scale;
 * Android's `Bitmap.compress` takes 0-100, so 0.8 is 80.
 */
const val PATIENT_PHOTO_JPEG_QUALITY: Int = 80

/** Content type sent with the upload, matching the Swift's `"image/jpeg"`. */
const val PATIENT_PHOTO_CONTENT_TYPE: String = "image/jpeg"

/** Shown when the picked item's bytes cannot be read. Verbatim from the Swift. */
const val PATIENT_PHOTO_UNREADABLE_MESSAGE: String = "Couldn't read the selected photo."

/** Shown when the bytes are not an image this platform can decode. Verbatim. */
const val PATIENT_PHOTO_UNSUPPORTED_MESSAGE: String = "That image format isn't supported."

/**
 * The system photo picker, configured as the Swift's `PhotosPicker` is: images
 * only, one item. [onPicked] receives null when the caregiver backs out, which
 * — as on iOS, where the binding simply stays put — must leave the current
 * selection alone rather than clearing it.
 *
 * Needs no storage permission on any supported API level: the picker runs out of
 * process and returns a read grant for exactly what was chosen.
 */
@Composable
fun rememberPatientPhotoPicker(onPicked: (Uri?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
        onPicked,
    )
    return remember(launcher) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

/**
 * The pixel size an image of [width] x [height] is drawn at before encoding.
 *
 * **Only ever shrinks**, as the Swift's `scale = longest > maxDimension ?
 * maxDimension / longest : 1.0` does: a 300px crop is not blown up to 1024 and
 * re-encoded, which would grow the upload while losing detail.
 *
 * Both edges are floored at 1 so an extreme aspect ratio cannot round the short
 * edge to zero and produce a bitmap that cannot be created.
 */
fun patientPhotoScaledSize(
    width: Int,
    height: Int,
    maxDimension: Int = PATIENT_PHOTO_MAX_DIMENSION,
): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxDimension) return width to height
    val factor = maxDimension.toDouble() / longest.toDouble()
    return maxOf(1, Math.round(width * factor).toInt()) to
        maxOf(1, Math.round(height * factor).toInt())
}

/**
 * Downscale [bitmap] to [PATIENT_PHOTO_MAX_DIMENSION] on its longest edge, then
 * JPEG-encode at [PATIENT_PHOTO_JPEG_QUALITY].
 *
 * `filter = true` matches the Swift's `UIGraphicsImageRenderer` draw, which is
 * bilinear-filtered. The scaled intermediate is recycled when it is not the
 * caller's bitmap, so a re-pick does not strand a full-size copy.
 */
fun compressPatientPhotoJpeg(bitmap: Bitmap): ByteArray {
    val (width, height) = patientPhotoScaledSize(bitmap.width, bitmap.height)
    val scaled =
        if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, /* filter = */ true)
        }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, PATIENT_PHOTO_JPEG_QUALITY, out)
    if (scaled !== bitmap) scaled.recycle()
    return out.toByteArray()
}
