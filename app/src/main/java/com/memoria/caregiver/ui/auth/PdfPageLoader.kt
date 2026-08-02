package com.memoria.caregiver.ui.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rasterises a bundled legal PDF into page bitmaps, for [TermsGateScreen].
 *
 * iOS renders the bundled PDF with PDFKit inside the view. Android's
 * [PdfRenderer] needs a `Context`, a `ParcelFileDescriptor` and a **seekable
 * file on disk** — an asset stream is not seekable, so the asset is copied to
 * the cache directory first. That is why this is I/O and why it lives behind
 * the screen's `pageSource` seam rather than inside the composable.
 *
 * **Without this the consent gate is a dead end.** The gate enables its Agree
 * button only when every document has been scrolled to the end, so a screen
 * with no pages can never be satisfied and sign-up cannot complete.
 *
 * Pages render at [TARGET_WIDTH_PX] wide, which is comfortably above a phone's
 * logical width so text stays sharp when scaled to fit, and low enough that two
 * short documents do not push a mid-range device into an OOM.
 */
class PdfPageLoader(private val context: Context) {

    suspend fun load(resource: String): List<ImageBitmap> = withContext(Dispatchers.IO) {
        runCatching { render(resource) }.getOrElse {
            // An empty list is the screen's "could not load" signal, and it
            // takes the same branch iOS takes when the bundle resource is
            // missing. Deliberately not a throw: a failed document must show an
            // error state, not crash the sign-up flow.
            emptyList()
        }
    }

    private fun render(resource: String): List<ImageBitmap> {
        val cached = File(context.cacheDir, "legal_$resource.pdf")
        if (!cached.exists() || cached.length() == 0L) {
            context.assets.open("$ASSET_DIR/$resource.pdf").use { input ->
                cached.outputStream().use(input::copyTo)
            }
        }

        ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                return (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale = TARGET_WIDTH_PX.toFloat() / page.width
                        val bitmap = Bitmap.createBitmap(
                            TARGET_WIDTH_PX,
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        // PdfRenderer composites onto whatever is already in the
                        // bitmap and a fresh one is transparent, so text would
                        // render onto nothing and read as invisible on a dark
                        // surface. Fill white first, which is the page.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }
            }
        }
    }

    private companion object {
        const val ASSET_DIR = "legal"
        const val TARGET_WIDTH_PX = 1080
    }
}
