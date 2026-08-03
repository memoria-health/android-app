package com.memoria.caregiver.ui.support

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `decodeAttachments` — the screenshot pipeline behind the feedback form.
 *
 * Three of these guard failures that only show up on the wire or on a device:
 * a base64 with line breaks in it that the backend cannot decode, a pick that
 * walks past the four-item cap, and duplicate ids that make the remove button
 * ambiguous.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackAttachmentDecodeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun jpegUri(width: Int = 40, height: Int = 40): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
        val file = File.createTempFile("shot", ".jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun decode(uris: List<Uri>) = decodeAttachments(context.contentResolver, uris)

    @Test
    fun `a picked screenshot becomes one attachment`() {
        assertEquals(1, decode(listOf(jpegUri())).size)
    }

    @Test
    fun `more than the cap is truncated to the cap`() {
        // The screen's own limit is four. A caregiver who long-presses their way
        // to twenty screenshots must not post twenty, and must not pay for
        // twenty decodes to find that out.
        val picked = List(20) { jpegUri() }

        assertEquals(FEEDBACK_MAX_ATTACHMENTS, decode(picked).size)
    }

    @Test
    fun `the base64 has no line breaks`() {
        // Android's default Base64 wraps at 76 characters; Swift's
        // `base64EncodedString()` does not. A wrapped payload is one the backend
        // has to strip before it can decode the image.
        val encoded = decode(listOf(jpegUri(width = 400, height = 400))).single().jpegBase64

        assertFalse(encoded.contains("\n"))
        assertFalse(encoded.contains("\r"))
    }

    @Test
    fun `two picks of the same file get different ids`() {
        // The Uri is not the id. Two rows sharing one makes "remove this
        // screenshot" ambiguous, and the screen removes by id.
        val same = jpegUri()

        val ids = decode(listOf(same, same)).map { it.id }

        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `an item that cannot be read is dropped and the rest still attach`() {
        // Best-effort per item, as iOS is: one screenshot whose read grant has
        // gone must not fail the whole selection. Only the *unreadable* half of
        // that is testable here — Robolectric's BitmapFactory shadow decodes
        // anything, so an undecodable item cannot be simulated (see
        // PickedImageTest).
        val gone = Uri.fromFile(File(context.cacheDir, "no-such-shot.jpg"))

        val decoded = decode(listOf(gone, jpegUri()))

        assertEquals(1, decoded.size)
    }

    @Test
    fun `the preview is the compressed image, at its real size`() {
        // Not just "non-empty": the thumbnail the caregiver checks before
        // sending has to be the picture that is actually going, at the size it
        // is going at. A placeholder would look right and lie. 40x40 is under
        // FEEDBACK_MAX_IMAGE_DIMENSION, so nothing is downscaled here.
        val decoded = decode(listOf(jpegUri(width = 40, height = 40))).single()

        assertEquals(40, decoded.image.width)
        assertEquals(40, decoded.image.height)
    }
}
