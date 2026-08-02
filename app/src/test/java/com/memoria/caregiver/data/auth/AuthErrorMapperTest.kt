package com.memoria.caregiver.data.auth

import com.memoria.caregiver.data.remote.ApiError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Supabase failure → [ApiError]. Spec: `SupabaseAuthService.mapAuthError`.
 *
 * The typed-code path needs a real Ktor `HttpResponse` to construct an
 * `AuthRestException`, so it is covered at the integration level rather than
 * here. What IS unit-testable — and what actually protects the caregiver when a
 * Supabase deployment answers without a machine-readable code — is the
 * fallback, which is exactly the branch iOS uses for every case.
 */
class AuthErrorMapperTest {

    @Test fun `an ApiError passes through untouched`() {
        val original = ApiError.Conflict("already_enrolled")
        assertEquals(original, mapAuthError(original))
    }

    @Test fun `invalid login is read as unauthorized`() {
        assertEquals(ApiError.Unauthorized, mapAuthError(RuntimeException("Invalid login credentials")))
    }

    @Test fun `invalid credentials is read as unauthorized`() {
        assertEquals(ApiError.Unauthorized, mapAuthError(RuntimeException("invalid credentials")))
    }

    /** iOS uses localizedCaseInsensitiveContains, so casing must not matter. */
    @Test fun `the credential match is case-insensitive`() {
        assertEquals(ApiError.Unauthorized, mapAuthError(RuntimeException("INVALID LOGIN")))
        assertEquals(ApiError.Unauthorized, mapAuthError(RuntimeException("Invalid Credentials")))
    }

    @Test fun `anything else carries the message through`() {
        assertEquals(
            ApiError.Other("Email rate limit exceeded"),
            mapAuthError(RuntimeException("Email rate limit exceeded")),
        )
    }

    /**
     * Verbatim from the Swift: `error.errorDescription ?? "Sign-in failed."`.
     * A failure with no message must not put an empty sentence on screen.
     */
    @Test fun `a failure with no message falls back to the iOS sentence`() {
        assertEquals(ApiError.Other("Sign-in failed."), mapAuthError(RuntimeException()))
        assertEquals(ApiError.Other("Sign-in failed."), mapAuthError(RuntimeException("")))
    }
}
