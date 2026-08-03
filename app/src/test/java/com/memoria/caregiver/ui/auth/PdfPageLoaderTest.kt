package com.memoria.caregiver.ui.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.memoria.caregiver.legal.LegalConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [PdfPageLoader] against the PDFs that actually ship.
 *
 * This is the file that says the consent gate can be satisfied at all. The gate
 * enables "I agree" only once every document has been scrolled to its end, and a
 * document with no pages has no end — so a loader that finds nothing turns
 * sign-up into a dead end that no screen test would notice, because the screen
 * would be behaving correctly (HARDENING.md H-9, FINDINGS.md F-27).
 *
 * It reads the real `assets/legal/` files rather than a fixture, deliberately:
 * the failure being guarded is "the asset is missing, misnamed, or not a PDF",
 * and a fixture would define exactly that away.
 *
 * ## What this cannot check, and why
 *
 * **Rasterisation itself does not run under Robolectric.** `PdfRenderer` has no
 * working implementation there: on recent SDK levels its constructor dies inside
 * the platform's own code (`NoSuchMethodError: FileDescriptor.getOwnerId$`), and
 * on older ones Robolectric substitutes a stub that reports zero pages. Neither
 * is evidence about the shipped documents, so nothing here asserts a page count
 * — an assertion that always fails in the harness would be deleted by the next
 * person, and one written against the stub would pass for a document that does
 * not exist.
 *
 * What is checked instead is every step the loader owns: that the assets are
 * present under the exact names [LegalConstants] asks for, that they are PDFs,
 * and that each one is staged into the cache directory where `PdfRenderer` can
 * seek it. The remaining step is two lines of platform call. **It still needs
 * confirming on a device or emulator before this ships.**
 */
@RunWith(RobolectricTestRunner::class)
class PdfPageLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loader = PdfPageLoader(context)

    private fun asset(resource: String) =
        context.assets.open("legal/$resource.pdf").use { it.readBytes() }

    /**
     * The names in [LegalConstants] and the files on disk have to agree exactly.
     * A rename on either side is a gate that shows its error state forever.
     */
    @Test
    fun `every listed document is bundled under the name the loader looks for`() {
        for (doc in LegalConstants.documents) {
            val bytes = asset(doc.resource)
            assertTrue(
                "${doc.resource}.pdf is not a PDF",
                bytes.take(5).toByteArray().decodeToString() == "%PDF-",
            )
        }
    }

    /**
     * `PdfRenderer` needs a seekable file and an asset stream is not one, so the
     * loader copies the asset to the cache first. That copy is the part of the
     * pipeline that can go wrong quietly — a wrong path reads as "no pages",
     * which reads as a document that cannot be agreed to.
     */
    @Test
    fun `loading a document stages the real asset where the renderer can seek it`() {
        runTest {
            for (doc in LegalConstants.documents) {
                loader.load(doc.resource)

                val staged = File(context.cacheDir, "legal_${doc.resource}.pdf")
                assertTrue("${doc.resource} was never staged", staged.exists())
                assertArrayEquals(
                    "staged ${doc.resource} is not the bundled document",
                    asset(doc.resource),
                    staged.readBytes(),
                )
            }
        }
    }

    /**
     * A missing document must surface the gate's error state, not crash sign-up
     * — an empty list is how the screen is told the load failed.
     */
    @Test
    fun `a document that is not bundled loads as no pages`() = runTest {
        assertEquals(emptyList<Any>(), loader.load("not-a-document"))
    }
}
