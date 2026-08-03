package com.memoria.caregiver.ui.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `decodePickedImage` — the Android half of "the caregiver picked a photo".
 *
 * ## What this harness can and cannot see
 *
 * Robolectric shadows `BitmapFactory`, and **its shadow decodes anything** — it
 * returns a bitmap for a text file and for zero bytes alike. So the
 * [PickedImage.Unsupported] branch cannot be exercised here at all; a test that
 * appeared to cover it would only be asserting the shadow's behavior. It is left
 * uncovered and said so, rather than covered falsely. The [PickedImage.Unreadable]
 * branch IS reachable, because a `file://` Uri goes to the real filesystem.
 */
@RunWith(RobolectricTestRunner::class)
class PickedImageTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val resolver: ContentResolver get() = context.contentResolver

    /** A real JPEG on disk, addressed by a `file://` Uri the resolver can open. */
    private fun jpegUri(width: Int = 8, height: Int = 4): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
        val file = File.createTempFile("photo", ".jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    @Test
    fun `a readable image decodes`() {
        val result = decodePickedImage(resolver, jpegUri())

        assertTrue(result is PickedImage.Decoded)
    }

    @Test
    fun `the decoded bitmap keeps the source dimensions`() {
        // Not a tautology: the downscale belongs to the caller, so anything that
        // resampled here would silently change what the two compressors are
        // handed and break their own size maths.
        val result = decodePickedImage(resolver, jpegUri(width = 8, height = 4))

        val decoded = result as PickedImage.Decoded
        assertEquals(8, decoded.bitmap.width)
        assertEquals(4, decoded.bitmap.height)
    }

    @Test
    fun `a file that is not there is unreadable`() {
        // The picker's read grant is per-item and short-lived: by the time a
        // slow decode starts, the grant can already be gone. That must read as
        // "couldn't read the selected photo", not as a crash.
        val gone = Uri.fromFile(File(context.cacheDir, "no-such-photo.jpg"))

        assertEquals(PickedImage.Unreadable, decodePickedImage(resolver, gone))
    }

    @Test
    fun `a directory is unreadable rather than a crash`() {
        val directory = Uri.fromFile(context.cacheDir)

        assertEquals(PickedImage.Unreadable, decodePickedImage(resolver, directory))
    }
}
