package com.memoria.caregiver.data.auth

import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.remote.ApiError
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Supabase Auth — the only auth implementation in the app.
 * Port of `Services/SupabaseAuthService.swift`.
 *
 * Owns one [SupabaseClient], used here for auth and shared with the API client
 * for Realtime. iOS exposes `currentCaregiver` / `isAuthenticating` for SwiftUI
 * to observe; the Compose equivalent is a pair of `StateFlow`s.
 *
 * Provisioning the caregiver row (`POST /v1/me`) and the initial data load are
 * deliberately NOT here — they arrive through [onAuthenticated], exactly as on
 * iOS, so the API client stays out of this class's dependency graph.
 */
class SupabaseAuthService(
    val client: SupabaseClient,
    private val scope: CoroutineScope,
) {

    private val _currentCaregiver = MutableStateFlow<Caregiver?>(null)

    /** `null` ⇒ the welcome / sign-in flow renders; non-null ⇒ the main app. */
    val currentCaregiver: StateFlow<Caregiver?> = _currentCaregiver.asStateFlow()

    private val _isAuthenticating = MutableStateFlow(false)

    /** True while a sign-up / sign-in / restore call is in flight. */
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating.asStateFlow()

    /**
     * Runs once a Supabase session is live.
     *
     * Sign-up passes a display name: `POST /v1/me`, then bootstrap.
     * Sign-in and restore pass nulls: `GET /v1/me`, then bootstrap.
     * Returns the caregiver row, which this service caches.
     */
    var onAuthenticated: (suspend (
        signUpDisplayName: String?,
        signUpPhone: String?,
        signUpTosVersion: String?,
        signUpCountry: String?,
    ) -> Caregiver)? = null

    /**
     * Runs at the START of sign-out, while the session is still valid so authed
     * calls still work. Wired to de-registering this device's push token, so a
     * caregiver who signs out stops receiving pushes on a device they left.
     */
    var onWillSignOut: (suspend () -> Unit)? = null

    /** Runs after sign-out. Wired to the API client's teardown. */
    var onSignedOut: (suspend () -> Unit)? = null

    /** Wired to `DELETE /v1/me`. Kept out of this class's graph like the rest. */
    var onDeleteAccount: (suspend () -> Unit)? = null

    init {
        // Forward every session change into the Realtime client so subscribed
        // channels carry the user JWT.
        //
        // THIS IS THE ONE THAT FAILS SILENTLY. Without it, RLS policies evaluate
        // auth.uid() as NULL and Realtime drops every row gated on the
        // caregiver — events, cameras, detectors. No error, no crash: the app
        // simply never receives an update, and the 1s poll masks it well enough
        // that it looks like ordinary latency.
        //
        // sessionStatus is a StateFlow that replays its current value, and the
        // SDK emits on cold start with the restored session, so this covers the
        // restore path without a separate call.
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                val token = (status as? SessionStatus.Authenticated)?.session?.accessToken
                client.realtime.setAuth(token)
            }
        }
    }

    // ---- Sign up ----

    suspend fun signUp(
        displayName: String,
        email: String,
        password: String,
        phone: String,
        tosVersion: String,
        country: String,
    ) {
        authenticating {
            try {
                client.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                }
                // Projects with email confirmation on return no session here.
                // iOS detects that by the session being nil and surfaces this
                // exact sentence rather than dropping the user on a blank app.
                if (currentSession() == null) {
                    throw ApiError.Other("Check your email to confirm your account.")
                }
                _currentCaregiver.value =
                    onAuthenticated?.invoke(displayName, phone, tosVersion, country)
            } catch (e: Throwable) {
                throw mapAuthError(e)
            }
        }
    }

    // ---- Sign in ----

    suspend fun signIn(email: String, password: String) {
        authenticating {
            try {
                client.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                // A backend failure AFTER Supabase succeeded is surfaced, not
                // swallowed: the user IS signed in server-side, so silently
                // returning would leave them staring at an empty app.
                _currentCaregiver.value = onAuthenticated?.invoke(null, null, null, null)
            } catch (e: Throwable) {
                throw mapAuthError(e)
            }
        }
    }

    // ---- Password reset ----

    /**
     * Step 1. Supabase returns success whether or not the address is
     * registered, and the UI mirrors that — never reveal whether an account
     * exists.
     *
     * The redirect must be on the project's Auth URL allow-list or Supabase
     * silently drops the link.
     */
    suspend fun requestPasswordReset(email: String, redirectTo: String?) {
        try {
            client.auth.resetPasswordForEmail(email, redirectTo)
        } catch (e: Throwable) {
            throw mapAuthError(e)
        }
    }

    /**
     * Step 2. Trades the `?code=` from the recovery deep link for a recovery
     * session, and returns the account's email so the UI can confirm which one
     * is being reset.
     */
    suspend fun exchangeRecoveryCode(code: String): String? = try {
        client.auth.exchangeCodeForSession(code)
        client.auth.currentUserOrNull()?.email
    } catch (e: Throwable) {
        throw mapAuthError(e)
    }

    /** Step 3. After this resolves the recovery session is a full session. */
    suspend fun updatePassword(newPassword: String) {
        authenticating {
            try {
                client.auth.updateUser { password = newPassword }
                _currentCaregiver.value = onAuthenticated?.invoke(null, null, null, null)
            } catch (e: Throwable) {
                throw mapAuthError(e)
            }
        }
    }

    // ---- Email change ----

    /**
     * Triggers a confirmation link to the new address. Until it is clicked,
     * `auth.users.email` is unchanged and the DB trigger that mirrors into
     * `caregivers.email` does not fire — so the displayed email keeps returning
     * the old value, which is the correct thing to show.
     */
    suspend fun requestEmailChange(newEmail: String) {
        try {
            client.auth.updateUser { email = newEmail }
        } catch (e: Throwable) {
            throw mapAuthError(e)
        }
    }

    // ---- Sign out ----

    suspend fun signOut() {
        // De-register the push token FIRST, while the session is still valid —
        // the authed DELETE needs a live token. Best effort.
        onWillSignOut?.invoke()
        runCatching { client.auth.signOut() }
        _currentCaregiver.value = null
        onSignedOut?.invoke()
    }

    // ---- Delete account ----

    /**
     * Backend delete first, then tear down the now-dead local session exactly
     * like sign-out. The Supabase sign-out is best-effort because the auth user
     * no longer exists once the backend call succeeds. **Throws if the backend
     * delete fails**, so the caller keeps the user signed in and shows the error
     * rather than leaving a half-deleted account.
     */
    suspend fun deleteAccount() {
        authenticating {
            onDeleteAccount?.invoke()
            runCatching { client.auth.signOut() }
            _currentCaregiver.value = null
            onSignedOut?.invoke()
        }
    }

    // ---- Restore ----

    /**
     * Called once at launch. The SDK restores any cached session itself; this
     * just checks whether one is alive and, if so, runs the same
     * [onAuthenticated] path so a cold start lands on a populated roster.
     */
    suspend fun restoreSession() {
        runCatching {
            if (currentSession() == null) return
            _currentCaregiver.value = onAuthenticated?.invoke(null, null, null, null)
        }
    }

    // ---- Token provider ----

    /**
     * The current access JWT, read before every authenticated request. Null
     * when there is no session.
     */
    suspend fun currentAccessToken(): String? = currentSession()?.accessToken

    private fun currentSession() = client.auth.currentSessionOrNull()

    private suspend fun <T> authenticating(block: suspend () -> T): T {
        _isAuthenticating.value = true
        try {
            return block()
        } finally {
            _isAuthenticating.value = false
        }
    }
}
