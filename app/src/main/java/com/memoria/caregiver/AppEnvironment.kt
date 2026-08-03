package com.memoria.caregiver

import android.content.Context
import com.memoria.caregiver.data.AppConfig
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import com.memoria.caregiver.data.realtime.CameraLiveState
import com.memoria.caregiver.data.realtime.InMemoryEverOnlineStore
import com.memoria.caregiver.data.realtime.RealtimeStore
import com.memoria.caregiver.data.realtime.SharedPrefsEverOnlineStore
import com.memoria.caregiver.ui.nav.DeepLinkRouter
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
    /**
     * Application context, used for exactly one thing: the SharedPreferences
     * behind [CameraLiveState]'s "has this camera ever been online" set. That
     * set is persisted on iOS too (`UserDefaults`), and it is what keeps an
     * offline camera from regressing to "Preparing…" after a relaunch.
     */
    appContext: Context? = null,
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

    /**
     * Shared between the API client and the Realtime store, because the three
     * writers of the reconnect window live in both (PARITY.md §10). One
     * instance, or the Live tile's "Connecting…" state depends on which half
     * of the app last touched it.
     */
    val live = CameraLiveState(
        store = appContext
            ?.getSharedPreferences(EVER_ONLINE_PREFS, Context.MODE_PRIVATE)
            ?.let { SharedPrefsEverOnlineStore(it) }
            ?: InMemoryEverOnlineStore(),
    )

    val api = NemaraApiClient(
        HttpTransport(
            client = httpClient,
            baseUrl = AppConfig.backendUrl,
            // Read per request, never cached: Supabase refreshes the JWT
            // underneath us and a held token would go stale.
            tokenProvider = { auth.currentAccessToken() },
        ),
        live = live,
    )

    /**
     * Routing target for "which patient is open". Every way into a patient —
     * a roster tap, a settings row, a notification — writes here and nothing
     * else opens one, which is the rule iOS adopted after two presenters
     * fought over one full-screen cover.
     */
    val deepLink = DeepLinkRouter()

    /**
     * The four Realtime channels and the 1-second poll.
     *
     * It takes [api] twice, as its cache and as its camera fetcher, and [api]
     * holds it back — a cycle that exists only because iOS puts both halves in
     * one type and has nothing to break. Constructed after [api] and handed
     * back to it below.
     */
    val realtime = RealtimeStore(
        supabase = supabase,
        cache = api,
        fetcher = api,
        live = live,
        scope = scope,
    )

    init {
        api.realtime = realtime

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
            // Roster, then cameras/detectors/events for every patient, then
            // Realtime detached and the poll started. Sign-in is not complete
            // until this returns, because landing on an empty roster that fills
            // in a second later reads as "my patients are gone".
            api.bootstrap()
            caregiver
        }

        auth.onWillSignOut = {
            // TODO(REQ-8, phase-7): de-register this device's push token while
            // the session is still valid, so a signed-out caregiver stops
            // receiving pushes on a device they left. Best-effort by design —
            // it must never block sign-out.
        }

        auth.onSignedOut = {
            // Stops the poll, closes the channels and empties all four cached
            // collections. The clear is a privacy boundary, not housekeeping:
            // a care team sharing one device is the normal case, and without it
            // the next person to sign in is one recomposition away from seeing
            // a household they have no relationship to.
            api.teardown()
            deepLink.closePatient()
        }

        auth.onDeleteAccount = { api.deleteAccount() }
    }

    /** Releases the HTTP client. Process-lifetime object, so this is for tests. */
    fun close() {
        httpClient.close()
    }

    private companion object {
        /**
         * iOS stores the same set under `co.sunny.nemara.camerasEverOnline`.
         * The file name is Android-only; the key inside matches.
         */
        const val EVER_ONLINE_PREFS = "com.memoria.caregiver.live"
    }
}
