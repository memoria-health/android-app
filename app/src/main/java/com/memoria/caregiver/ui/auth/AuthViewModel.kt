package com.memoria.caregiver.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.remote.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun submitSignUp(confirmPassword: String, tosVersion: String) {
        val state = _signUp.value
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
