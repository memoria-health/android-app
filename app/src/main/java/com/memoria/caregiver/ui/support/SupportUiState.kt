package com.memoria.caregiver.ui.support

import androidx.compose.ui.graphics.ImageBitmap

/**
 * State for the two founder-facing surfaces — the feedback form and the
 * perpetual help button's chat sheet.
 *
 * Same contract as `ui/auth/AuthUiState.kt`: **the screens are pure
 * `@Composable` functions over these types.** No ViewModel, no I/O, no direct
 * service access. iOS keeps each of these values in `@State` on the view and
 * reaches the API through `@Environment`; the shape below is that same state,
 * hoisted so the host owns the network call.
 *
 * Spec: `Features/Feedback/FeedbackView.swift`,
 * `Features/Support/SupportButton.swift`, PARITY.md §28.
 */

// ---------------------------------------------------------------------------
// Swift string trimming
// ---------------------------------------------------------------------------

/**
 * True when [this] is a whitespace or newline character **as Swift's
 * `CharacterSet.whitespacesAndNewlines` defines it**, which is what both
 * surfaces trim with before deciding whether a field is empty.
 *
 * Not `Char.isWhitespace()`. Kotlin's predicate is
 * `Character.isWhitespace() || Character.isSpaceChar()`, which misses U+0085
 * (NEXT LINE) — a control character to Java and a newline to Foundation. A
 * message consisting only of a NEL would therefore be sendable on Android and
 * not on iOS. The set is written out instead:
 *
 * - `.whitespaces` = horizontal tab plus Unicode general category `Zs`
 *   (space, NBSP, ogham space, en/em quad through hair space, narrow NBSP,
 *   medium mathematical space, ideographic space).
 * - `.newlines` = U+000A..U+000D, U+0085, U+2028 (line separator) and
 *   U+2029 (paragraph separator).
 */
internal fun Char.isSwiftWhitespaceOrNewline(): Boolean =
    this == '\u0009' ||
        this in '\u000A'..'\u000D' ||
        this == '\u0085' ||
        this == '\u2028' ||
        this == '\u2029' ||
        Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

/** Swift's `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimmingWhitespaceAndNewlines(): String =
    trim { it.isSwiftWhitespaceOrNewline() }

// ---------------------------------------------------------------------------
// Feedback
// ---------------------------------------------------------------------------

/**
 * One screenshot the caregiver attached. Port of the Swift's private
 * `FeedbackAttachment`: the decoded image for the on-screen thumbnail, plus the
 * JPEG bytes the host base64-encodes and uploads.
 *
 * [id] is per-pick so thumbnails diff cleanly — the Swift uses a fresh `UUID()`.
 *
 * The screen reads only [id] and [image]; [jpegBase64] rides along because it is
 * what the host puts in `FeedbackRequest.attachments`, and splitting the bytes
 * into a second collection keyed by id would let the two drift.
 */
data class FeedbackAttachment(
    val id: String,
    val image: ImageBitmap,
    /** Base64 of the compressed JPEG, no `data:` prefix. See FeedbackAttachments.kt. */
    val jpegBase64: String,
)

/**
 * Everything `FeedbackView` holds in `@State`.
 *
 * @param rating 1-5, or **0 for "not rated"**. The Swift sends `nil` when it is
 *   0; that mapping belongs to the host, not the screen.
 * @param isLoadingAttachments a pick is being decoded and compressed. While
 *   true the screen shows spinner tiles instead of thumbnails, so the caregiver
 *   sees the pick register immediately rather than a dead pause.
 * @param pendingPickCount how many items the caregiver just picked. Only used
 *   to decide how many spinner tiles to draw; iOS reads `pickerItems.count`.
 */
data class FeedbackState(
    val rating: Int = 0,
    val whatsLoved: String = "",
    val whatsNotWorking: String = "",
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val submitError: String? = null,
    val attachments: List<FeedbackAttachment> = emptyList(),
    val isLoadingAttachments: Boolean = false,
    val pendingPickCount: Int = 0,
) {
    /**
     * `FeedbackView.swift:31-33`. **"What's not working" is the only required
     * field** — the rating and "what do you like" are both optional and both
     * labelled as such on screen.
     *
     * Deliberately does NOT include [isSubmitting]. The Swift keeps the two
     * apart, gating the button on `isSubmitting || !canSubmit`, and `submit()`
     * re-checks `canSubmit` separately.
     */
    val canSubmit: Boolean
        get() = whatsNotWorking.trimmingWhitespaceAndNewlines().isNotEmpty()

    /**
     * How many spinner placeholders to show mid-load — the count just picked,
     * capped at the limit, floored at 1 so something always shows.
     * `FeedbackView.swift:381-383`.
     */
    val placeholderCount: Int
        get() = maxOf(1, minOf(pendingPickCount, FEEDBACK_MAX_ATTACHMENTS))
}

/**
 * The failure sentence the form shows when the POST throws.
 * `FeedbackView.swift:56`: `"Couldn't send feedback. \(error.localizedDescription)"`.
 *
 * It lives here rather than in the host because it is this surface's product
 * copy, and the host has no other reason to know the wording. Pass
 * `ApiError.errorDescription` (or the platform's message) as [description].
 */
fun feedbackSubmitErrorMessage(description: String): String =
    "Couldn't send feedback. $description"

// ---------------------------------------------------------------------------
// Support chat
// ---------------------------------------------------------------------------

/**
 * The subject line every in-app help message is posted under.
 * `SupportButton.swift:147`: `sendSupport(subject: "In-app help", message: text)`.
 */
const val SUPPORT_SUBJECT: String = "In-app help"

/**
 * Everything `HelpChatSheet` holds in `@State`, plus the sheet's own presented
 * flag (iOS keeps that one on `SupportButton` instead).
 *
 * @param sentText the message as it was sent, echoed back as an outgoing
 *   bubble. Held separately from [message] because [message] is cleared on
 *   success so the composer empties.
 * @param didSend one message has landed. The composer locks and the toolbar
 *   button relabels to "Done" — this sheet takes one message and then it is
 *   done, it is not a running conversation.
 * @param errorMessage the platform's `localizedDescription`, rendered verbatim.
 */
data class HelpChatState(
    val isPresented: Boolean = false,
    val message: String = "",
    val sentText: String = "",
    val isSending: Boolean = false,
    val didSend: Boolean = false,
    val errorMessage: String? = null,
) {
    /** The trimmed message — what actually gets posted. `SupportButton.swift:45`. */
    val trimmedMessage: String
        get() = message.trimmingWhitespaceAndNewlines()

    /** `SupportButton.swift:46`. */
    val canSend: Boolean
        get() = trimmedMessage.isNotEmpty() && !isSending && !didSend
}
