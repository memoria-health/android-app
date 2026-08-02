package com.memoria.caregiver.data.auth

import com.memoria.caregiver.data.remote.ApiError
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException

/**
 * Supabase auth failure → [ApiError]. Port of `SupabaseAuthService.mapAuthError`.
 *
 * **iOS matches on English substrings of the SDK's error text** — it asks
 * whether the description contains `"invalid login"` or `"invalid credentials"`
 * and returns `.unauthorized` if so. That works until Supabase rewords a
 * message, ships a localized one, or the two SDKs word it differently, at which
 * point a wrong password silently starts showing the generic failure copy
 * instead of a sign-in error.
 *
 * The Kotlin SDK exposes a **typed** [AuthErrorCode] on [AuthRestException],
 * which the Swift SDK does not. So this matches on the code. The observable
 * behavior is what iOS intends — bad credentials read as unauthorized — it just
 * cannot drift. See DIFFERENCES.md D12.
 *
 * The substring test is kept as a fallback for older Supabase deployments that
 * answer without a machine-readable code, so behavior degrades to iOS's rather
 * than to nothing.
 */
internal fun mapAuthError(error: Throwable): ApiError {
    if (error is ApiError) return error

    if (error is AuthRestException) {
        return when (error.errorCode) {
            AuthErrorCode.InvalidCredentials -> ApiError.Unauthorized
            else -> ApiError.Other(error.errorDescription.ifBlank { FALLBACK_MESSAGE })
        }
    }

    // No typed code available. This is iOS's exact test, kept as the fallback.
    val description = error.message.orEmpty()
    val looksLikeBadCredentials = INVALID_CREDENTIAL_HINTS.any {
        description.contains(it, ignoreCase = true)
    }
    return if (looksLikeBadCredentials) {
        ApiError.Unauthorized
    } else {
        ApiError.Other(description.ifBlank { FALLBACK_MESSAGE })
    }
}

/** Verbatim from `SupabaseAuthService.mapAuthError`. */
private const val FALLBACK_MESSAGE = "Sign-in failed."

private val INVALID_CREDENTIAL_HINTS = listOf("invalid login", "invalid credentials")
