package com.memoria.caregiver.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontWeight
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DataCollectionConsentSheet] behaviour. Spec:
 * `DataCollectionConsentSheet.swift`, PARITY.md §24, FINDINGS.md F-14 / I-5.
 *
 * **The copy is asserted verbatim here on purpose.** This sheet is the record of
 * what a caregiver agreed to before Memoria stores video and audio of a person
 * with dementia in their own home, so the sentences are the contract, not
 * decoration — a reworded line is a different consent. The rest of the file
 * pins the properties a refactor could quietly invert: that each button records
 * its own answer, that neither answer is recorded on the caregiver's behalf, that
 * a failure leaves the question standing, and that nothing about the patient
 * reaches analytics. Layout is judged by screenshot per DECISIONS.md D-004, so it
 * is not asserted.
 */
@RunWith(RobolectricTestRunner::class)
class DataCollectionConsentSheetTest {

    @get:Rule val compose = createComposeRule()

    private val decisions = mutableListOf<Boolean>()

    private fun body(name: String) =
        "With your permission, Memoria will securely store video and audio clips of " +
            "$name to help improve our understanding of elderly health and disease.\n\n" +
            "This is optional, never required, and you can turn it off anytime in Settings."

    private fun setUp(
        patientName: String = "Ruth",
        mutation: CardMutationState = CardMutationState(),
        analytics: RecordingAnalytics = RecordingAnalytics(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    DataCollectionConsentSheet(
                        patientName = patientName,
                        mutation = mutation,
                        onDecide = { decisions += it },
                    )
                }
            }
        }
    }

    // ---- The copy ----------------------------------------------------------

    @Test
    fun `the sheet says who is asking, for what, and on what terms`() {
        setUp()

        compose.onNodeWithText("DATA COLLECTION").assertIsDisplayed()
        compose.onNodeWithText("Help improve care for everyone.").assertIsDisplayed()
        compose.onNodeWithText(body("Ruth")).assertIsDisplayed()
    }

    /**
     * The bold run is the disclosure itself — what is stored, and about whom. iOS
     * renders it from markdown; if the Compose spans were dropped the sentence
     * would still read, which is exactly why it needs pinning.
     */
    @Test
    fun `the sentence naming what gets stored is the emphasised one`() {
        setUp()

        val rendered = compose.onNodeWithText(body("Ruth"))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
        val bold = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }

        assertEquals(1, bold.size)
        assertEquals(
            "securely store video and audio clips of Ruth",
            rendered.text.substring(bold.single().start, bold.single().end),
        )
    }

    /** The consent names one person, and it is the one the caller passed. */
    @Test
    fun `the copy names this patient and no one else`() {
        setUp(patientName = "Sunny")

        compose.onNodeWithText(body("Sunny")).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(body("Ruth")).fetchSemanticsNodes().size)
    }

    // ---- The two answers ---------------------------------------------------

    @Test
    fun `share recordings records a yes`() {
        val analytics = RecordingAnalytics()
        setUp(analytics = analytics)

        compose.onNodeWithText("Share recordings").performScrollTo().performClick()

        assertEquals(listOf(true), decisions)
        assertEquals(listOf("data_collection.consent_share"), analytics.names("button"))
    }

    @Test
    fun `not now records a no`() {
        val analytics = RecordingAnalytics()
        setUp(analytics = analytics)

        compose.onNodeWithText("Not now").performScrollTo().performClick()

        assertEquals(listOf(false), decisions)
        assertEquals(listOf("data_collection.consent_decline"), analytics.names("button"))
    }

    /**
     * A swipe-away is a dismissal, not an answer — iOS's sheet carries no
     * `.interactiveDismissDisabled()`, and the host's dismiss sends nothing. The
     * sheet must therefore never decide for the caregiver just by being on
     * screen, and it must not offer a third exit that would look like one.
     */
    @Test
    fun `being on screen decides nothing`() {
        val analytics = RecordingAnalytics()
        setUp(analytics = analytics)

        compose.waitForIdle()

        assertEquals(emptyList<Boolean>(), decisions)
        assertEquals(emptyList<RecordingAnalytics.Entry>(), analytics.entries)
        for (exit in listOf("Close", "Cancel", "Skip", "Done", "Maybe later")) {
            assertEquals(0, compose.onAllNodesWithText(exit).fetchSemanticsNodes().size)
        }
    }

    /** `.disabled(busy)` — a second tap must not queue a conflicting decision. */
    @Test
    fun `a decision already in flight cannot be sent twice`() {
        setUp(mutation = CardMutationState(busy = true))

        compose.onNodeWithText("Share recordings").performScrollTo().performClick()
        compose.onNodeWithText("Not now").performScrollTo().performClick()

        assertEquals(emptyList<Boolean>(), decisions)
    }

    // ---- Failure -----------------------------------------------------------

    /**
     * A failed PATCH recorded nothing, so the question is still open: the message
     * appears *and* both answers stay tappable. Swapping the copy out for the
     * error — which is what `AudioCaptureCard` does — would leave a caregiver
     * agreeing to a sentence they can no longer read.
     */
    @Test
    fun `a failed decision shows why and leaves the question standing`() {
        setUp(mutation = CardMutationState(errorText = "The network connection was lost."))

        compose.onNodeWithText("The network connection was lost.").assertIsDisplayed()
        compose.onNodeWithText(body("Ruth")).assertIsDisplayed()

        compose.onNodeWithText("Not now").performScrollTo().performClick()
        assertEquals(listOf(false), decisions)
    }

    @Test
    fun `nothing stands in for the error before one happens`() {
        setUp()

        val allText = compose.onAllNodesWithText("", substring = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
            }

        assertEquals(
            setOf(
                "DATA COLLECTION",
                "Help improve care for everyone.",
                body("Ruth"),
                "Share recordings",
                "Not now",
            ),
            allText.toSet(),
        )
    }
}
