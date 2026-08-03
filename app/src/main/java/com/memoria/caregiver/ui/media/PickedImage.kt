package com.memoria.caregiver.ui.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Reading a picked `Uri` back as pixels.
 *
 * iOS has no counterpart: `PhotosPickerItem.loadTransferable(type: Data.self)`
 * hands the bytes over and `UIImage(data:)` decodes them, both from the picker's
 * own async context. On Android the picker returns a `Uri` and a read grant, so
 * the host has to open a stream itself — and that is disk I/O plus a decode,
 * which must not happen on the main thread.
 *
 * Both photo surfaces need exactly this (the patient avatar and the feedback
 * screenshots), which is why it is written once rather than twice: they differ
 * only in the compression that follows, and those numbers already live beside
 * their own screens in `NewPatientPhoto.kt` and `FeedbackAttachments.kt`.
 *
 * The two failure modes are kept apart because the Swift shows a **different
 * sentence** for each: unreadable bytes versus an image format that will not
 * decode. Collapsing them would lose a message the caregiver acts on
 * differently (re-pick versus pick a different file).
 */
sealed interface PickedImage {

    data class Decoded(val bitmap: Bitmap) : PickedImage

    /** The stream could not be opened or read. iOS: "Couldn't read the selected photo." */
    data object Unreadable : PickedImage

    /** The bytes are not an image this platform decodes. iOS: "That image format isn't supported." */
    data object Unsupported : PickedImage
}

/**
 * Decode [uri] to a bitmap. **Blocking — call it from a background dispatcher.**
 *
 * `BitmapFactory` rather than `ImageDecoder`: the app supports API 26 and
 * `ImageDecoder` starts at 28. The bitmap is immediately downscaled and
 * re-encoded by the caller, so the loss of `ImageDecoder`'s sampling hints costs
 * nothing here.
 *
 * A decode that returns null and a decode that throws are the same outcome to a
 * caregiver, so both become [PickedImage.Unsupported]; only a failure to open
 * the stream at all is [PickedImage.Unreadable]. `OutOfMemoryError` is caught
 * with the rest deliberately — a 100-megapixel image is a bad pick, not a
 * reason to take the process down.
 */
fun decodePickedImage(resolver: ContentResolver, uri: Uri): PickedImage {
    val stream = try {
        resolver.openInputStream(uri)
    } catch (@Suppress("SwallowedException") e: Exception) {
        null
    } ?: return PickedImage.Unreadable

    return stream.use { input ->
        val bitmap = try {
            BitmapFactory.decodeStream(input)
        } catch (@Suppress("SwallowedException") e: Throwable) {
            null
        }
        if (bitmap == null) PickedImage.Unsupported else PickedImage.Decoded(bitmap)
    }
}
