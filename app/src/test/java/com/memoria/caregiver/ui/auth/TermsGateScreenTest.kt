package com.memoria.caregiver.ui.auth

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The legal gate's behavior. Spec: `Features/Legal/TermsConsentView.swift`,
 * PARITY.md §16.
 *
 * The product requirement this file exists to defend is "there is no
 * accept-without-reading path". Two halves of it are tested separately, because
 * they can break independently:
 *
 * 1. **The button obeys `canAccept`.** A regression here ships consent that was
 *    never read.
 * 2. **`onAllDocumentsReviewed` fires only when both documents actually reach
 *    their end.** A regression here — most plausibly by treating "the lazy list
 *    composed the item" as "the reader saw it" — makes `canAccept` true early,
 *    and the button obeying it faithfully is then no defense at all.
 *
 * Layout is not asserted anywhere; the screenshot pass owns that.
 */
// Robolectric's default LEGACY graphics returns a null Bitmap from
// Bitmap.createBitmap, so any composable that rasterises — this screen
// renders document pages — dies with an NPE inside Compose rather than a
// meaningful failure. NATIVE runs real Skia.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class TermsGateScreenTest {

    @get:Rule val compose = createComposeRule()

    /** Renders a page or so tall — its end sentinel is on screen immediately. */
    private fun shortPages() = listOf(ImageBitmap(400, 20))

    /** Far taller than any test viewport — the end can only be reached by scrolling. */
    private fun tallPages() = listOf(ImageBitmap(40, 1000))

    private val scrollPrompt = "Scroll to the end of each document to continue."

    // -----------------------------------------------------------------------
    // Submit gating
    // -----------------------------------------------------------------------

    @Test
    fun `agreeing fires once the documents have been reviewed`() {
        var agreed = 0
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(canAccept = true),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = { agreed++ },
                )
            }
        }

        compose.onNodeWithText("I agree").performClick()

        assertEquals(1, agreed)
    }

    /**
     * The gate itself. `canAccept` is false until both documents have been read,
     * and the button must be inert — not merely dimmed.
     */
    @Test
    fun `agreeing does nothing while the documents are unread`() {
        var agreed = 0
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(canAccept = false),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = { agreed++ },
                )
            }
        }

        compose.onNodeWithText("I agree").performClick()

        assertEquals(0, agreed)
    }

    /**
     * `isBusy` has no counterpart in the Swift, where agreeing is a local state
     * flip. It is in `AuthFormState`, and without it a second tap while the host
     * is mid-signup fires a second agreement.
     */
    @Test
    fun `a busy form cannot agree`() {
        var agreed = 0
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(
                        canAccept = true,
                        form = AuthFormState(isBusy = true),
                    ),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = { agreed++ },
                )
            }
        }

        compose.onNodeWithText("I agree").performClick()
        compose.onNodeWithText("I agree").performClick()

        assertEquals(0, agreed)
    }

    /** Cancel returns to the signup form without agreeing, leaving signup blocked. */
    @Test
    fun `cancel reports without agreeing`() {
        var cancelled = 0
        var agreed = 0
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(canAccept = true),
                    onCancel = { cancelled++ },
                    onAllDocumentsReviewed = {},
                    onAgree = { agreed++ },
                )
            }
        }

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancelled)
        assertEquals(0, agreed)
    }

    // -----------------------------------------------------------------------
    // Scroll-to-end detection
    // -----------------------------------------------------------------------

    /**
     * Both documents are short enough that each end sentinel is on screen from
     * the first frame, so both are reviewed without scrolling — and the screen
     * reports that exactly once, not once per document.
     *
     * The unselected document counts too: iOS keeps every document mounted and
     * only hides it with opacity, so its sentinel appears on the same terms.
     */
    @Test
    fun `reviewing every document is reported once`() {
        var reviewed = 0
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(),
                    onCancel = {},
                    onAllDocumentsReviewed = { reviewed++ },
                    onAgree = {},
                    pageSource = { shortPages() },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) { reviewed == 1 }
        compose.waitForIdle()

        assertEquals(1, reviewed)
    }

    /**
     * The regression this guards: a lazy list composes a prefetch buffer beyond
     * the viewport, so "the sentinel item was composed" is not "the reader
     * reached the end". With pages this tall, nothing but a real scroll can get
     * there.
     */
    @Test
    fun `an unscrolled document is not reported as reviewed`() {
        var reviewed = 0
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(),
                    onCancel = {},
                    onAllDocumentsReviewed = { reviewed++ },
                    onAgree = {},
                    pageSource = { tallPages() },
                )
            }
        }

        compose.waitForIdle()

        assertEquals(0, reviewed)
    }

    /** iOS appends a literal "✓" to a reviewed document's tab label. */
    @Test
    fun `a reviewed document is checkmarked in the picker`() {
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = {},
                    pageSource = { shortPages() },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Terms of Service  ✓").fetchSemanticsNodes().size == 1
        }

        compose.onNodeWithText("Privacy Notice  ✓").assertExists()
    }

    // -----------------------------------------------------------------------
    // Copy
    // -----------------------------------------------------------------------

    @Test
    fun `the error sentence renders verbatim`() {
        val sentence = "Your session expired. Please sign in again."
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(form = AuthFormState(errorMessage = sentence)),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = {},
                )
            }
        }

        compose.onNodeWithText(sentence).assertExists()
    }

    /** The helper line is shown only while the gate is still closed. */
    @Test
    fun `the scroll prompt is hidden once accepting is unlocked`() {
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(canAccept = true),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = {},
                )
            }
        }

        compose.onNodeWithText(scrollPrompt).assertDoesNotExist()
    }

    @Test
    fun `the scroll prompt is shown while the gate is closed`() {
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(canAccept = false),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = {},
                )
            }
        }

        compose.onNodeWithText(scrollPrompt).assertExists()
    }

    /**
     * A document with no pages is the failed load, and both documents are
     * mounted — so the failure copy appears twice, which is also the evidence
     * that every document stays mounted rather than being swapped in on select.
     */
    @Test
    fun `a document that cannot be loaded shows the retry copy`() {
        compose.setContent {
            MemoriaTheme {
                TermsGateScreen(
                    state = TermsGateState(),
                    onCancel = {},
                    onAllDocumentsReviewed = {},
                    onAgree = {},
                    pageSource = { emptyList() },
                )
            }
        }

        compose.waitForIdle()

        compose.onAllNodesWithText("Couldn't load this document. Pull down to retry.")
            .assertCountEquals(2)
    }
}
