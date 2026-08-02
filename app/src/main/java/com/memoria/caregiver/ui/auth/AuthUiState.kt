package com.memoria.caregiver.ui.auth

/**
 * The state every auth screen renders from, and the callbacks it fires.
 *
 * **Screens are pure `@Composable` functions over these types.** They hold no
 * `ViewModel`, do no I/O, and know nothing about Supabase. That makes each one
 * testable with Robolectric by handing it a state object, and it keeps the six
 * screens from each inventing their own way to talk to the auth service — which
 * is exactly how three incompatible date codecs appeared in the model layer.
 *
 * iOS keeps this state in `@State` on each view and reaches the auth service
 * through `@Environment`. The shape below is the same state, hoisted.
 */

/**
 * Shared across all auth screens.
 *
 * @param isBusy a sign-in / sign-up / reset call is in flight. Every submit
 *   button disables on this, so a double tap cannot fire two sign-ups.
 * @param errorMessage the user-facing sentence from `ApiError.errorDescription`,
 *   or null. Screens render it verbatim — never reword it, and never substitute
 *   a friendlier one, because the copy is the product's and it is pinned by test.
 */
data class AuthFormState(
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

/** Login. iOS: `LoginView` — email, password, keepSignedIn, showPassword. */
data class LoginState(
    val email: String = "",
    val password: String = "",
    /**
     * iOS defaults this to true. It is presented to the caregiver as a choice
     * and is the more forgiving default for someone checking on a parent at
     * 3am, so it must stay true.
     */
    val keepSignedIn: Boolean = true,
    val showPassword: Boolean = false,
    val form: AuthFormState = AuthFormState(),
) {
    /** iOS gates the submit button on both fields being non-empty. */
    val canSubmit: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && !form.isBusy
}

/** Sign up. iOS: `SignUpView`. */
data class SignUpState(
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val phone: String = "",
    val countryCode: String = "US",
    val showPassword: Boolean = false,
    /**
     * Consent is collected inline on the sign-up form, not on a later screen.
     * The submit button is gated on it.
     */
    val acceptedTerms: Boolean = false,
    val form: AuthFormState = AuthFormState(),
)

/** Forgot password, step 1. iOS: `ForgotPasswordView`. */
data class ForgotPasswordState(
    val email: String = "",
    /**
     * Supabase returns success whether or not the address is registered, and
     * the UI mirrors that. **Never reveal whether an account exists** — the
     * confirmation copy is identical either way.
     */
    val sent: Boolean = false,
    val form: AuthFormState = AuthFormState(),
)

/** Reset password, steps 2 and 3. iOS: `ResetPasswordView`. */
data class ResetPasswordState(
    /** Shown as "Reset password for jane@…" once the code is exchanged. */
    val email: String? = null,
    val password: String = "",
    val showPassword: Boolean = false,
    val isExchangingCode: Boolean = true,
    val form: AuthFormState = AuthFormState(),
)

/**
 * The terms gate. iOS: `TermsConsentView`.
 *
 * @param canAccept only true once the document has been scrolled to the end.
 *   This is the whole point of the screen: consent that was never scrolled is
 *   not consent, and the button stays disabled until it is.
 */
data class TermsGateState(
    val canAccept: Boolean = false,
    val form: AuthFormState = AuthFormState(),
)
