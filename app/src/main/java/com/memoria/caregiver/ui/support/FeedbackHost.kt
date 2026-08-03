package com.memoria.caregiver.ui.support

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.media.PickedImage
import com.memoria.caregiver.ui.media.decodePickedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts [FeedbackScreen]: owns the form state, decodes the picked screenshots
 * and runs the POST.
 *
 * Spec: `Features/Feedback/FeedbackView.swift`. iOS reaches this from two
 * places with two different presentations — the patient header's Feedback pill
 * shows it as a sheet (`PatientShell.swift:125`), the patient Settings row
 * pushes it (`PatientSettingsView.swift:90`) — but the screen and its behaviour
 * are identical, so there is one host.
 *
 * No ViewModel: iOS keeps all nine values in `@State` and none of them outlive
 * the screen. The submitted confirmation is deliberately part of that state
 * rather than an exit — **a successful send does not close the screen**, it
 * swaps the form for a "Feedback sent" panel with its own Done button.
 */
@Composable
fun FeedbackHost(
    api: NemaraApiClient,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    var state by remember { mutableStateOf(FeedbackState()) }

    // A pick REPLACES the attachment set rather than appending to it, which is
    // what `loadAttachments` does on iOS (`attachments = loaded`). Appending
    // would walk straight past the four-item cap.
    val pickScreenshots = rememberFeedbackScreenshotPicker { uris ->
        if (uris.isEmpty()) {
            // iOS treats an empty selection as "clear", not as "no change".
            state = state.copy(attachments = emptyList(), pendingPickCount = 0)
            return@rememberFeedbackScreenshotPicker
        }
        state = state.copy(isLoadingAttachments = true, pendingPickCount = uris.size)
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { decodeAttachments(resolver, uris) }
            // Read-modify-write across the decode would clobber anything typed
            // while it ran, so only the three fields this pick owns are written
            // and the rest of the state is taken as it is NOW, not as it was
            // before the suspension — FINDINGS.md F-12.
            state = state.copy(
                attachments = loaded,
                isLoadingAttachments = false,
                pendingPickCount = 0,
            )
        }
    }

    // Back and the nav bar's chevron mean the same thing. Without this, Back —
    // and the edge swipe — fell through to the Activity and quit the app on top
    // of a half-written message. FINDINGS.md F-25.
    BackHandler(onBack = onDismiss)

    FeedbackScreen(
        state = state,
        modifier = modifier,
        onRatingChange = { state = state.copy(rating = it) },
        onWhatsLovedChange = { state = state.copy(whatsLoved = it) },
        onWhatsNotWorkingChange = { state = state.copy(whatsNotWorking = it) },
        onAddScreenshots = pickScreenshots,
        onRemoveAttachment = { attachment ->
            state = state.copy(attachments = state.attachments.filterNot { it.id == attachment.id })
        },
        onSubmit = {
            // iOS re-checks both guards inside `submit()` rather than trusting
            // the disabled button, because the callback is reachable regardless.
            if (!state.canSubmit || state.isSubmitting) return@FeedbackScreen
            state = state.copy(isSubmitting = true, submitError = null)
            scope.launch {
                // Snapshotted before the request so the body is what the form
                // showed at the tap, not what it holds when the call returns.
                val submitted = state
                try {
                    api.enrollment.submitFeedback(
                        // 0 is "not rated" and is sent as an absent field, not
                        // as a zero score.
                        rating = submitted.rating.takeIf { it != 0 },
                        whatsWorking = submitted.whatsLoved.nonBlankOrNull(),
                        whatsFrustrating = submitted.whatsNotWorking.nonBlankOrNull(),
                        category = null,
                        attachments = submitted.attachments
                            .map { it.jpegBase64 }
                            .takeIf { it.isNotEmpty() },
                    )
                    // NOT a dismiss. The screen swaps to its confirmation panel
                    // and the caregiver leaves through its own Done button.
                    state = state.copy(submitted = true)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Throwable) {
                    state = state.copy(
                        submitError = feedbackSubmitErrorMessage(feedbackErrorFor(e)),
                    )
                } finally {
                    state = state.copy(isSubmitting = false)
                }
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * Decode, downscale and base64 the picked screenshots. **Blocking — runs on IO.**
 *
 * Best-effort per item, as iOS is: one screenshot that will not decode is
 * dropped and the rest of the selection still attaches, rather than failing the
 * whole pick. The cap is applied before any decoding so a fifty-item selection
 * costs four decodes, not fifty.
 *
 * `Base64.NO_WRAP` matters: `Data.base64EncodedString()` emits one unbroken
 * line, while Android's default inserts newlines every 76 characters, which the
 * backend would have to strip before it could decode the image.
 */
internal fun decodeAttachments(
    resolver: ContentResolver,
    uris: List<Uri>,
): List<FeedbackAttachment> = uris.take(FEEDBACK_MAX_ATTACHMENTS).mapNotNull { uri ->
    val decoded = decodePickedImage(resolver, uri) as? PickedImage.Decoded ?: return@mapNotNull null
    val jpeg = compressFeedbackJpeg(decoded.bitmap)
    // The full-size decode is dead the moment it has been re-encoded. Four
    // 12-megapixel screenshots held alive at once is ~200 MB of heap on a device
    // that may have a fifth of that to spare.
    decoded.bitmap.recycle()
    val preview = android.graphics.BitmapFactory
        .decodeByteArray(jpeg, 0, jpeg.size)
        ?: return@mapNotNull null
    FeedbackAttachment(
        // The `Uri` is not the id: the same image picked twice is the same
        // `Uri`, and two rows sharing an id makes the remove button ambiguous.
        id = java.util.UUID.randomUUID().toString(),
        image = preview.asImageBitmap(),
        jpegBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
    )
}

/** Swift's `nonBlank(_:)` inside `submit()` — `.whitespacesAndNewlines`, then empty is nil. */
private fun String.nonBlankOrNull(): String? =
    trimmingWhitespaceAndNewlines().takeIf { it.isNotEmpty() }

/**
 * Verbatim from [ApiError.errorDescription] — the string this surface then wraps
 * with [feedbackSubmitErrorMessage], which is where the "Couldn't send feedback."
 * prefix lives.
 */
internal fun feedbackErrorFor(e: Throwable): String = when (e) {
    is ApiError -> e.errorDescription
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
