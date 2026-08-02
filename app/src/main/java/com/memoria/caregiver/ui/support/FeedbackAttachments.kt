package com.memoria.caregiver.ui.support

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.ByteArrayOutputStream

/**
 * The screenshot-attachment tuning values and the downscale + JPEG encode that
 * uses them. Port of `FeedbackView.swift`'s `maxAttachments` and the static
 * `compressedJPEG(_:)`.
 *
 * These live in this package, not in the data layer, because on iOS they live
 * on the view too — and because losing any one of them is invisible in review
 * and expensive at runtime: a full-resolution phone screenshot is several
 * megabytes, the backend caps each image at roughly three, and the whole
 * request is base64 so every byte costs a third more on the wire.
 *
 * The screen itself never calls [compressFeedbackJpeg]. Decoding a picked
 * `Uri` needs a `ContentResolver` and is I/O, so the host does the work and
 * hands back finished [FeedbackAttachment]s; this file exists so the host does
 * not have to re-derive the numbers.
 */

/**
 * At most four screenshots per submission, matching the backend's per-request
 * limit. `FeedbackView.swift:27`.
 */
const val FEEDBACK_MAX_ATTACHMENTS: Int = 4

/**
 * Longest edge, in pixels, after downscaling. `FeedbackView.swift:89`.
 * Keeps an iPhone/Android screenshot comfortably under the backend's per-image
 * cap while staying legible enough for a founder to read the UI in it.
 */
const val FEEDBACK_MAX_IMAGE_DIMENSION: Int = 1600

/**
 * JPEG quality. iOS asks for `jpegData(compressionQuality: 0.6)` on a 0-1
 * scale; Android's `Bitmap.compress` takes 0-100, so 0.6 is 60.
 */
const val FEEDBACK_JPEG_QUALITY: Int = 60

/**
 * The system photo picker, configured the way `FeedbackView`'s `PhotosPicker`
 * is: images only, at most [FEEDBACK_MAX_ATTACHMENTS] per pick. Returns the
 * lambda `FeedbackScreen`'s `onAddScreenshots` expects.
 *
 * **`maxItems` is the absolute cap, not the remaining slots.** iOS passes
 * `maxSelectionCount: maxAttachments` unconditionally, and a pick REPLACES the
 * current set rather than appending to it — `loadAttachments` assigns
 * `attachments = loaded`. A host that appends would let the caregiver past four.
 *
 * [onPicked] receives an empty list when the caregiver picks nothing, which iOS
 * treats as "clear the attachments", not as "no change".
 *
 * The system picker needs no storage permission on any supported API level: it
 * runs out of process and hands back a read grant for exactly what was chosen.
 */
@Composable
fun rememberFeedbackScreenshotPicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(FEEDBACK_MAX_ATTACHMENTS),
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
 * **Only ever shrinks.** The Swift compares the longest edge against the
 * maximum and leaves anything smaller untouched, so a 200px crop is not blown
 * up to 1600 and re-encoded — that would grow the payload while losing detail.
 *
 * Both edges are floored at 1: an extreme aspect ratio (a 2000x1 strip) would
 * otherwise round the short edge to zero and produce a bitmap that cannot be
 * created.
 */
fun feedbackScaledSize(
    width: Int,
    height: Int,
    maxDimension: Int = FEEDBACK_MAX_IMAGE_DIMENSION,
): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxDimension) return width to height
    val factor = maxDimension.toDouble() / longest.toDouble()
    return maxOf(1, Math.round(width * factor).toInt()) to
        maxOf(1, Math.round(height * factor).toInt())
}

/**
 * Downscale [bitmap] to [FEEDBACK_MAX_IMAGE_DIMENSION] on its longest edge, then
 * JPEG-encode at [FEEDBACK_JPEG_QUALITY].
 *
 * `filter = true` on the rescale is the counterpart of the Swift's
 * `UIGraphicsImageRenderer` draw, which is bilinear-filtered; nearest-neighbour
 * on a 4x downscale of text-heavy UI produces visible aliasing that then costs
 * *more* JPEG bytes, not fewer.
 *
 * The scaled intermediate is recycled when it is not the caller's bitmap, so a
 * four-screenshot pick does not hold four full-size copies alive waiting on the
 * collector.
 */
fun compressFeedbackJpeg(bitmap: Bitmap): ByteArray {
    val (width, height) = feedbackScaledSize(bitmap.width, bitmap.height)
    val scaled =
        if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, /* filter = */ true)
        }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, FEEDBACK_JPEG_QUALITY, out)
    if (scaled !== bitmap) scaled.recycle()
    return out.toByteArray()
}
