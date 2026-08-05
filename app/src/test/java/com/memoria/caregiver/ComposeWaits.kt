package com.memoria.caregiver

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Waiting for something a network round-trip produces.
 *
 * ## The thing this replaces, and why it flaked
 *
 * `waitForIdle()` waits for **composition and layout** to settle. It does not
 * wait for a coroutine that is suspended on an HTTP call, and Ktor's
 * `MockEngine` genuinely resumes on another thread — so the sequence
 *
 * ```
 * compose.onNodeWithText("Add patient").performClick()
 * compose.waitForIdle()
 * compose.onNodeWithText("Couldn't add that patient.").assertIsDisplayed()
 * ```
 *
 * is a race: the click starts the request, `waitForIdle` returns as soon as the
 * *current* frame is drawn, and whether the error has been written by then
 * depends on which thread won. It passed most of the time and failed about one
 * run in five, which is worse than failing always — a suite that is red at
 * random teaches people to re-run instead of read (FINDINGS.md F-36).
 *
 * `waitUntil` polls the semantics tree until the condition holds, which is the
 * question the test was actually asking.
 *
 * ## When NOT to use this
 *
 * Only for something that should *appear*. Asserting that a node is **absent**
 * this way would pass the instant it is checked and prove nothing — for those,
 * drive the state deterministically and assert directly.
 */
internal const val DEFAULT_WAIT_MILLIS: Long = 5_000

/**
 * Waits for exactly one node with [text] and returns it, or fails naming what it
 * waited for.
 *
 * The failure message matters: `waitUntil`'s own timeout says only "condition
 * never became true", which in a suite this size is not enough to act on.
 */
internal fun ComposeTestRule.awaitText(
    text: String,
    timeoutMillis: Long = DEFAULT_WAIT_MILLIS,
): SemanticsNodeInteraction {
    try {
        waitUntil(timeoutMillis) {
            onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("timed out after ${timeoutMillis}ms waiting for text: \"$text\"")
    }
    return onNode(hasText(text))
}
