package com.memoria.caregiver.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.remote.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the auth screens' state and drives [SupabaseAuthService].
 *
 * The screens are pure composables over `LoginState` / `SignUpState` and know
 * nothing about Supabase (see AuthUiState.kt); this is where those two halves
 * meet. iOS puts the same code in each view's `@State` plus a `submit()` that
 * reaches the service through `@Environment`.
 *
 * Errors are surfaced through [ApiError.errorDescription] **verbatim**. That
 * copy is the product's, pinned by test, and paraphrasing it here would be a
 * silent product change that no screen test would catch.
 */
class AuthViewModel(private val auth: SupabaseAuthService) : ViewModel() {

    private val _login = MutableStateFlow(LoginState())
    val login: StateFlow<LoginState> = _login.asStateFlow()

    private val _signUp = MutableStateFlow(SignUpState())
    val signUp: StateFlow<SignUpState> = _signUp.asStateFlow()

    private val _forgot = MutableStateFlow(ForgotPasswordState())
    val forgot: StateFlow<ForgotPasswordState> = _forgot.asStateFlow()

    /**
     * The open password-recovery link, or `null` when there is none — which is
     * also "do not show the reset screen".
     *
     * Held here rather than in a navigation argument because it carries the
     * recovery code. A Navigation Compose route puts its arguments in the back
     * stack entry, and the back stack is written into the Activity's saved-state
     * Bundle; the code would then outlive its own use, on disk, for a screen
     * whose entire job is to consume it once. A ViewModel field survives a
     * configuration change (which is all it needs to survive) and dies with the
     * process.
     */
    private val _reset = MutableStateFlow<ResetPasswordSession?>(null)
    val reset: StateFlow<ResetPasswordSession?> = _reset.asStateFlow()

    val currentCaregiver = auth.currentCaregiver

    // ---- Login ----

    fun onEmailChange(value: String) {
        _login.value = _login.value.copy(email = value, form = clearedError(_login.value.form))
    }

    fun onPasswordChange(value: String) {
        _login.value = _login.value.copy(password = value, form = clearedError(_login.value.form))
    }

    fun onKeepSignedInChange(value: Boolean) {
        _login.value = _login.value.copy(keepSignedIn = value)
    }

    fun onShowPasswordChange(value: Boolean) {
        _login.value = _login.value.copy(showPassword = value)
    }

    fun submitLogin() {
        val state = _login.value
        // Guard here as well as on the button. The button is disabled, but a
        // stale recomposition or a test could still call this, and a second
        // in-flight sign-in would race the first.
        if (!state.canSubmit) return

        _login.value = state.copy(form = state.form.copy(isBusy = true, errorMessage = null))
        viewModelScope.launch {
            try {
                auth.signIn(email = state.email.trim(), password = state.password)
                _login.value = _login.value.copy(form = _login.value.form.copy(isBusy = false))
            } catch (e: Throwable) {
                _login.value = _login.value.copy(
                    form = _login.value.form.copy(isBusy = false, errorMessage = messageFor(e)),
                )
            }
        }
    }

    // ---- Sign up ----

    fun onSignUpChange(state: SignUpState) {
        _signUp.value = state
    }

    // One setter per field rather than the whole-state [onSignUpChange] above.
    // A caller holding a state object it read earlier — a composition snapshot
    // is one — writes back a copy of a value that may already be stale, and the
    // edit it was carrying wins over anything set in between. Each of these
    // reads the current value at the moment it writes, so no edit can be lost.

    fun onSignUpDisplayNameChange(value: String) {
        _signUp.value = _signUp.value.copy(displayName = value, form = clearedError(_signUp.value.form))
    }

    fun onSignUpEmailChange(value: String) {
        _signUp.value = _signUp.value.copy(email = value, form = clearedError(_signUp.value.form))
    }

    fun onSignUpPhoneChange(value: String) {
        _signUp.value = _signUp.value.copy(phone = value, form = clearedError(_signUp.value.form))
    }

    fun onSignUpCountryChange(value: String) {
        _signUp.value = _signUp.value.copy(countryCode = value)
    }

    fun onSignUpPasswordChange(value: String) {
        _signUp.value = _signUp.value.copy(password = value, form = clearedError(_signUp.value.form))
    }

    /**
     * The caregiver read both legal documents and agreed. Set only from the
     * consent gate's Agree action — never from the form, which is what keeps
     * consent something that was given rather than something assumed.
     */
    fun onSignUpTermsAccepted() {
        _signUp.value = _signUp.value.copy(acceptedTerms = true)
    }

    fun submitSignUp(confirmPassword: String, tosVersion: String) {
        val state = _signUp.value
        // Same guard as [submitLogin], and the same reason: the button is
        // disabled, but a stale recomposition or a test could still call this,
        // and a second in-flight sign-up would race the first. `isBusy` is one
        // of the terms, so the in-flight form cannot fire again.
        if (!signUpCanSubmit(state, confirmPassword)) return

        _signUp.value = state.copy(form = state.form.copy(isBusy = true, errorMessage = null))
        viewModelScope.launch {
            try {
                auth.signUp(
                    displayName = state.displayName.trim(),
                    email = state.email.trim(),
                    password = state.password,
                    phone = state.phone,
                    tosVersion = tosVersion,
                    country = state.countryCode,
                )
                _signUp.value = _signUp.value.copy(form = _signUp.value.form.copy(isBusy = false))
            } catch (e: Throwable) {
                _signUp.value = _signUp.value.copy(
                    form = _signUp.value.form.copy(isBusy = false, errorMessage = messageFor(e)),
                )
            }
        }
    }

    // ---- Forgot password ----

    fun onForgotEmailChange(value: String) {
        _forgot.value = _forgot.value.copy(email = value, form = clearedError(_forgot.value.form))
    }

    fun submitForgotPassword(redirectTo: String?) {
        val state = _forgot.value
        _forgot.value = state.copy(form = state.form.copy(isBusy = true, errorMessage = null))
        viewModelScope.launch {
            try {
                auth.requestPasswordReset(state.email.trim(), redirectTo)
                // `sent` is set whatever the server said, because Supabase
                // answers success for an unregistered address on purpose and
                // the UI must not reveal which it was.
                _forgot.value = _forgot.value.copy(
                    sent = true,
                    form = _forgot.value.form.copy(isBusy = false),
                )
            } catch (e: Throwable) {
                _forgot.value = _forgot.value.copy(
                    form = _forgot.value.form.copy(isBusy = false, errorMessage = messageFor(e)),
                )
            }
        }
    }

    // ---- Reset password ----

    /**
     * A `nemara://reset-password?code=…` link was opened. Starts step 2, the
     * exchange of the code for a recovery session, exactly as iOS's
     * `ResetPasswordView.task { await exchange() }` does when the cover appears.
     *
     * @param code the recovery code from the link. **Never log this, and never
     *   put it in an analytics label** — it is a credential that signs its
     *   holder in.
     */
    fun onRecoveryLink(code: String) {
        // A link already open is left alone. The same intent can arrive twice —
        // an Activity re-created after a configuration change is handed its
        // original intent again — and a recovery code is single-use, so a second
        // exchange of one already spent would replace a working screen with an
        // "expired link" one.
        if (_reset.value != null) return

        _reset.value = ResetPasswordSession(code = code)
        viewModelScope.launch {
            try {
                val email = auth.exchangeRecoveryCode(code)
                updateSession(code) {
                    it.copy(
                        isLinkValid = true,
                        state = it.state.copy(email = email, isExchangingCode = false),
                    )
                }
            } catch (e: Throwable) {
                updateSession(code) {
                    it.copy(
                        state = it.state.copy(
                            isExchangingCode = false,
                            form = it.state.form.copy(errorMessage = messageFor(e)),
                        ),
                    )
                }
            }
        }
    }

    fun onResetPasswordChange(value: String) {
        _reset.value = _reset.value?.let {
            it.copy(state = it.state.copy(password = value, form = clearedError(it.state.form)))
        }
    }

    fun onResetConfirmChange(value: String) {
        _reset.value = _reset.value?.let {
            it.copy(confirm = value, state = it.state.copy(form = clearedError(it.state.form)))
        }
    }

    /**
     * Step 3. Sets the new password, which also signs the caregiver in —
     * `updatePassword` runs the same `onAuthenticated` provisioning as a normal
     * sign-in, so `currentCaregiver` goes non-null and the host swaps to the
     * signed-in app on its own.
     */
    fun submitPasswordReset() {
        val session = _reset.value ?: return
        // The same second lock as [submitLogin] and [submitSignUp]. The screen's
        // button is disabled on the identical terms, and this is the other side
        // of the same door.
        if (!session.canSubmit) return

        val password = session.state.password
        _reset.value = session.copy(
            state = session.state.copy(
                form = session.state.form.copy(isBusy = true, errorMessage = null),
            ),
        )
        viewModelScope.launch {
            try {
                auth.updatePassword(password)
                // Dropping the session is what erases the recovery code, and it
                // is also the dismissal: iOS calls `dismiss()` here for the same
                // two reasons. Unconditional — the code is spent either way, so
                // it must not linger even if something else opened a link since.
                _reset.value = null
            } catch (e: Throwable) {
                updateSession(session.code) {
                    it.copy(
                        state = it.state.copy(
                            form = it.state.form.copy(isBusy = false, errorMessage = messageFor(e)),
                        ),
                    )
                }
            }
        }
    }

    /**
     * The caregiver closed the reset screen — "Close", or the system Back
     * button. Drops the recovery code with it, so nothing is left holding a
     * credential for a screen that is gone.
     */
    fun closePasswordReset() {
        _reset.value = null
    }

    /**
     * Write back to the session that is open **now**, and only if it is still
     * the one whose result this is.
     *
     * Every caller here resumes after a suspending call, so the value read
     * before the call may be stale by the time the answer arrives — the caregiver
     * may have closed the screen, or opened a second link. Reading inside
     * `update` rather than reusing the earlier snapshot is what keeps this from
     * being a read-modify-write across a suspension point (FINDINGS.md F-12);
     * matching on [code] is what stops one link's answer landing on another's
     * screen. A session that has gone away stays gone: no `code` matches `null`,
     * so a late answer cannot resurrect a credential that was cleared.
     */
    private fun updateSession(
        code: String,
        transform: (ResetPasswordSession) -> ResetPasswordSession,
    ) {
        _reset.update { current ->
            if (current == null || current.code != code) current else transform(current)
        }
    }

    fun restoreSession() {
        viewModelScope.launch { auth.restoreSession() }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    /**
     * Verbatim from [ApiError.errorDescription], with the same last-resort
     * sentence the auth service uses when a failure carries no message at all.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Sign-in failed."
    }

    /** Typing clears a stale error, so a fixed field stops shouting. */
    private fun clearedError(form: AuthFormState) =
        if (form.errorMessage == null) form else form.copy(errorMessage = null)
}

/**
 * One open password-recovery link: the code it carried, and everything the reset
 * screen renders from.
 *
 * Deliberately **not** in `AuthUiState.kt` with the other screen states, and
 * deliberately not merged into `ResetPasswordState`. Two reasons, and they point
 * the same way:
 *
 * 1. It holds a credential. `ResetPasswordState` is handed to a composable and
 *    is the shape a screen test builds by hand; the recovery code has no
 *    business in either. Only [AuthViewModel] ever sees this type, and the code
 *    never reaches the composition — the host passes the three rendered pieces.
 * 2. `confirm` and `isLinkValid` are the two values `ResetPasswordScreen`
 *    documents as missing from its state. They live here, on the object the
 *    orchestrator owns, until they land on the state itself.
 *
 * Existing at all is the signal: `null` means no link is open, so clearing the
 * field and forgetting the code are the same act, and there is no second place
 * to remember to wipe.
 *
 * @param code the single-use recovery code from the link. Never logged.
 * @param confirm the "Confirm password" field, which is only ever compared with
 *   [ResetPasswordState.password] and is never sent anywhere.
 * @param isLinkValid the code was exchanged for a recovery session. Not the same
 *   as "no error": a failed *submit* also sets an error, and the form must stay
 *   up in that case rather than flipping to the invalid-link body.
 */
data class ResetPasswordSession(
    val code: String,
    val state: ResetPasswordState = ResetPasswordState(),
    val confirm: String = "",
    val isLinkValid: Boolean = false,
) {
    /**
     * The submit gate, matching the one `ResetPasswordScreen` puts on its
     * button: `passwordValid && password == confirm && !submitting &&
     * !exchanging && linkValid` (`ResetPasswordView.swift:35`).
     *
     * Duplicated rather than shared because the screen owns its button's copy
     * and this file may not change the screen. That is survivable only because
     * both are pinned by test: if the two ever disagree the button and the call
     * disagree, which is a silent dead tap.
     */
    val canSubmit: Boolean
        get() = state.password.length >= MIN_RESET_PASSWORD_LENGTH &&
            state.password == confirm &&
            !state.form.isBusy &&
            !state.isExchangingCode &&
            isLinkValid

    /**
     * A data class prints every field it has, so the generated `toString` would
     * put the recovery code — and both copies of the new password — into
     * whatever printed it: a log line, a crash report, an exception message from
     * a failed assertion. There is no version of that which is acceptable for a
     * credential, and the cheapest way to make it impossible is to have nothing
     * to print. `equals`/`hashCode` are untouched, so tests can still compare
     * whole sessions.
     */
    override fun toString(): String = "ResetPasswordSession(redacted)"
}

/** iOS gates on `password.count >= 8`, as does `ResetPasswordScreen`. */
private const val MIN_RESET_PASSWORD_LENGTH = 8
