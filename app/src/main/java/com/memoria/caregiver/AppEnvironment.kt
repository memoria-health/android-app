package com.memoria.caregiver

import com.memoria.caregiver.data.AppConfig
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope

/**
 * Object graph for the whole app. Port of `App/AppEnvironment.swift`.
 *
 * One instance, created at process start and living until the process dies.
 * It exists to wire the auth service to the API client **through closures**
 * rather than a direct reference, which is the structure iOS chose so that
 * `SupabaseAuthService` never imports the API client. Keeping that seam means
 * auth can be exercised without a backend.
 */
class AppEnvironment(
    scope: CoroutineScope,
) {

    init {
        // iOS fatalErrors in SupabaseAuthService.init when Supabase is not
        // configured, with a message naming the two variables. Same contract:
        // fail loudly at construction rather than at the first sign-in, where
        // it would read as a network problem.
        require(AppConfig.isSupabaseConfigured) {
            "Supabase isn't configured. Set SUPABASE_URL + SUPABASE_ANON_KEY " +
                "(or run a debug build with the bundled local-stack defaults)."
        }
    }

    val httpClient: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(NemaraJson.json) }
        install(HttpTimeout) {
            // iOS relies on URLSession's defaults (60s request, 7 days
            // resource). Stated explicitly here because Ktor's default is NO
            // timeout at all, which would hang a screen forever on a dead
            // backend instead of surfacing ApiError.Offline.
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    val supabase = createSupabaseClient(
        // Non-null by the require() above.
        supabaseUrl = AppConfig.supabaseUrl!!,
        supabaseKey = AppConfig.supabaseAnonKey!!,
    ) {
        install(Auth)
        install(Realtime)
    }

    val auth = SupabaseAuthService(client = supabase, scope = scope)

    val api = NemaraApiClient(
        HttpTransport(
            client = httpClient,
            baseUrl = AppConfig.backendUrl,
            // Read per request, never cached: Supabase refreshes the JWT
            // underneath us and a held token would go stale.
            tokenProvider = { auth.currentAccessToken() },
        ),
    )

    init {
        // Sign-up passes a display name and provisions the row; sign-in and
        // restore fetch the existing one. Both then bootstrap. This is the only
        // place the two services meet.
        auth.onAuthenticated = { displayName, phone, tosVersion, country ->
            val caregiver = if (displayName != null) {
                api.provisionMe(
                    displayName = displayName,
                    phone = phone,
                    tosVersion = tosVersion,
                    country = country,
                )
                // iOS also marks a new-account flag here so the welcome tour
                // auto-starts. That lands with the tour in Phase 8.
            } else {
                api.me()
            }
            // TODO(phase-3): api.bootstrap() — the initial roster load. There is
            // nothing to load until the roster exists, and calling a stub here
            // would make sign-in look complete when it is not.
            caregiver
        }

        auth.onWillSignOut = {
            // TODO(REQ-8, phase-7): de-register this device's push token while
            // the session is still valid, so a signed-out caregiver stops
            // receiving pushes on a device they left. Best-effort by design —
            // it must never block sign-out.
        }

        auth.onSignedOut = {
            // TODO(phase-3): api.teardown() — clear the cached roster and close
            // Realtime channels. Nothing is cached yet.
        }

        auth.onDeleteAccount = { api.deleteAccount() }
    }

    /** Releases the HTTP client. Process-lifetime object, so this is for tests. */
    fun close() {
        httpClient.close()
    }
}
