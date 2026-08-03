package com.memoria.caregiver.ui.nav

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.legal.LegalConstants
import com.memoria.caregiver.ui.auth.offlineAuthViewModel
import com.memoria.caregiver.ui.theme.MemoriaTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The signed-out flow, end to end through the real navigation host.
 *
 * This is the regression test for FINDINGS.md F-27: the `SIGN_UP` route used to
 * render `WelcomeScreen` a second time, so "Create an account" led to a screen
 * that looked identical and no Android account could be created at all. A test
 * of `SignUpScreen` would have passed the whole time it was broken, because the
 * screen was fine — it was simply never reached. So the assertion has to start
 * from Welcome and go through the host.
 *
 * It also exercises the **shipped** PDF path rather than a stub, which is the
 * other half of the same bug: the gate was wired to a `pageSource` default that
 * returns nothing, and a gate with no pages can never be satisfied
 * (HARDENING.md H-9).
 */
// Rasterising the real documents needs real Skia.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class AuthNavHostTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp() {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    AuthNavHost(
                        // Offline: there is no session, so `restoreSession` returns
                        // before it touches anything, and nothing here signs in.
                        viewModel = offlineAuthViewModel(Dispatchers.Unconfined),
                        signedInContent = {},
                    )
                }
            }
        }
    }

    @Test
    fun `create an account leads to the sign-up form, not to another welcome screen`() {
        setUp()
        compose.onNodeWithText("The other 99.9% of care.").assertExists()

        compose.onNodeWithText("Create an account").performClick()

        compose.onNodeWithText("Make a Memoria account.").assertExists()
        compose.onNodeWithText("The other 99.9% of care.").assertDoesNotExist()
    }

    /**
     * The gate reached from the real flow asks the real loader for the real
     * documents.
     *
     * The evidence is the loader's own side effect: it stages each asset in the
     * cache directory before handing it to `PdfRenderer`, so a staged
     * `legal_terms.pdf` byte-identical to the bundled one can only mean the
     * production `PdfPageLoader` ran with the resource names [LegalConstants]
     * supplies. Left on `TermsGateScreen`'s `{ emptyList() }` default — the state
     * this flow was in — nothing would be staged at all.
     *
     * The rendered pages cannot be asserted on: `PdfRenderer` does not work under
     * Robolectric (see `PdfPageLoaderTest`), and the page images carry no
     * semantics by design.
     */
    @Test
    fun `the consent gate asks the real loader for the bundled documents`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (doc in LegalConstants.documents) {
            File(context.cacheDir, "legal_${doc.resource}.pdf").delete()
        }
        setUp()
        compose.onNodeWithText("Create an account").performClick()

        compose.onNodeWithText("Review and agree to the Terms and Privacy Notice to continue.")
            .performScrollTo()
            .performClick()

        compose.onNodeWithText("Terms & Privacy").assertExists()
        // The load is real file I/O on a background dispatcher, so it is waited
        // for rather than assumed.
        for (doc in LegalConstants.documents) {
            val staged = File(context.cacheDir, "legal_${doc.resource}.pdf")
            compose.waitUntil(timeoutMillis = 10_000) { staged.exists() }
            assertArrayEquals(
                "the gate staged something other than the bundled ${doc.resource}",
                context.assets.open("legal/${doc.resource}.pdf").use { it.readBytes() },
                staged.readBytes(),
            )
        }
    }
}
