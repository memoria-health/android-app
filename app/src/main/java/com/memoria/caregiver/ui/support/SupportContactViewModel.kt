package com.memoria.caregiver.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Produces [SupportContactState] for [SupportContactScreen]. Port of
 * `SupportContactView`'s five `@State` variables and its `send()`.
 *
 * **Nothing here is derived from the client's caches**, unlike `RosterViewModel`
 * or `EventsListViewModel`: a support message is written once and posted, and
 * `POST /v1/support` returns a status string rather than a row. So the single
 * `StateFlow` is a plain [MutableStateFlow] rather than a `combine` — there is
 * no upstream to combine with.
 *
 * There is a view model here at all, where the feedback form has none, because
 * the successful send has a **timed tail**: the backend's sentence sits on
 * screen for [SUCCESS_DWELL] before the sheet closes. That is a coroutine that
 * outlives the tap, and a composable is the wrong owner for one — a
 * recomposition or a dismissal mid-wait would cancel or duplicate it.
 */
class SupportContactViewModel(
    private val api: NemaraApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SupportContactState())

    val state: StateFlow<SupportContactState> = _state.asStateFlow()

    fun onSubjectChange(subject: String) {
        _state.update { it.copy(subject = subject) }
    }

    fun onMessageChange(message: String) {
        _state.update { it.copy(message = message) }
    }

    /** The "Cancel" toolbar button. iOS: `dismiss()`. */
    fun cancel() {
        _state.update { it.copy(dismissed = true) }
    }

    /**
     * Post the message, then close the sheet after the caregiver has had a
     * moment to read the confirmation. Port of `send()`.
     *
     * The guard is what iOS gets from `.disabled(!canSend)`. The screen disables
     * its own send control the same way, so this is only the belt to that
     * braces: two taps landing in the same frame would otherwise send the
     * founders two copies of the same message.
     *
     * **The text to post is read once, before the call.** `sendSupport` is a
     * suspension point, and reading `_state.value` after it would post whatever
     * the caregiver had typed by the time the request came back (FINDINGS.md
     * F-12). Every write afterwards goes through [MutableStateFlow.update], so
     * it composes over whatever the field edits did in the meantime rather than
     * overwriting them.
     */
    fun send() {
        val snapshot = _state.value
        if (!snapshot.canSend) return

        _state.update {
            it.copy(isSending = true, errorMessage = null, successMessage = null)
        }

        viewModelScope.launch {
            try {
                val response = api.sendSupport(
                    subject = snapshot.trimmedSubject,
                    message = snapshot.trimmedMessage,
                )
                // The backend's own words. iOS assigns `response.status`
                // straight to the label; substituting our own sentence here
                // would hide whatever the server chose to say.
                _state.update { it.copy(successMessage = response.status) }
                delay(SUCCESS_DWELL)
                // iOS clears `isSending` in a `defer`, so it stays true across
                // the sleep and is only released as the sheet goes. Matched:
                // the spinner does not flick back to "Send" for the last
                // fraction of a second before the sheet closes.
                _state.update { it.copy(isSending = false, dismissed = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _state.update { it.copy(isSending = false, errorMessage = messageFor(e)) }
            }
        }
    }

    /**
     * Verbatim from [ApiError.errorDescription] — the same sentence Swift's
     * `error.localizedDescription` resolves to. Rewording it would be a silent
     * product change. The fallback is for a throwable that is not an [ApiError]
     * at all, which nothing on this path currently throws.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }

    private companion object {
        /** iOS: `try? await Task.sleep(nanoseconds: 800_000_000)`. */
        val SUCCESS_DWELL = 800.milliseconds
    }
}
