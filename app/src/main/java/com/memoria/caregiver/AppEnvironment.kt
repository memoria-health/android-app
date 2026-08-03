package com.memoria.caregiver

import android.content.Context
import android.os.Build
import com.memoria.caregiver.analytics.Analytics
import com.memoria.caregiver.analytics.BufferedAnalytics
import com.memoria.caregiver.analytics.DeviceContext
import com.memoria.caregiver.analytics.FileAnalyticsStore
import com.memoria.caregiver.analytics.InMemoryAnalyticsStore
import com.memoria.caregiver.analytics.SupabaseAnalyticsUploader
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
import com.memoria.caregiver.ui.live.PeopleBlurStore
import com.memoria.caregiver.ui.nav.DeepLinkRouter
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
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
        // Analytics rows go straight into `ui_events` with the caregiver's own
        // session — there is no backend endpoint for them, because the table's
        // row-level-security policy already scopes every insert to the caller.
        install(Postgrest)
    }

    val auth = SupabaseAuthService(client = supabase, scope = scope)

    /**
     * UI analytics. Port of `Analytics.shared`, which iOS configures from
     * `AppEnvironment.live()` for the same reason it is built here: it needs the
     * Supabase client and a way to read the current caregiver, and nothing else
     * in the app should have to thread either of those to a button.
     *
     * `caregivers.id` is the Supabase auth user id, so reading it here is
     * exactly the `auth.uid()` the `ui_events` policy checks against.
     *
     * [BufferedAnalytics.start] loads whatever a previous run left on disk and
     * begins the hourly worker. Called here, at construction, so a buffer that
     * survived a process kill goes out at launch instead of an hour later.
     */
    val analytics: Analytics = BufferedAnalytics(
        scope = scope,
        // No Context means no private files directory — previews and any test
        // that builds the environment. Keeping the buffer in memory there is
        // better than making the whole environment unconstructible for it.
        store = appContext
            ?.let { FileAnalyticsStore(java.io.File(it.filesDir, ANALYTICS_BUFFER_FILE)) }
            ?: InMemoryAnalyticsStore(),
        uploader = SupabaseAnalyticsUploader(supabase),
        caregiverId = { auth.currentCaregiver.value?.id },
        device = DeviceContext(
            appBuild = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            osVersion = Build.VERSION.RELEASE,
            deviceModel = Build.MODEL,
        ),
    ).also { it.start() }

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

    /**
     * Per-device people-blur preference. Device-local by design: iOS keeps it in
     * `UserDefaults` rather than on the patient row, because it is a viewing
     * choice made by this caregiver on this phone, not a property of the
     * household.
     *
     * H-12 still applies — the toggle currently blurs nothing, because no person
     * detector is vendored (REQ-23).
     */
    val peopleBlur = PeopleBlurStore(
        prefs = requireNotNull(appContext) {
            "PeopleBlurStore needs a Context; AppEnvironment was built without one."
        }.getSharedPreferences(PEOPLE_BLUR_PREFS, Context.MODE_PRIVATE),
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
            // BEFORE the Supabase sign-out, and the ordering is the whole point.
            //
            // supabase-kt forwards every auth change to Realtime, and its
            // `setAuth` iterates the open channels calling `updateAuth`, which
            // calls `getWebsocket()`. By sign-out time the socket is gone, so it
            // throws `IllegalStateException: Websocket not yet initialized`
            // inside the SDK's own coroutine on Dispatchers.IO — where nothing
            // of ours can catch it. **That crashed the app on every sign-out.**
            // Closing the channels first leaves `setAuth` nothing to iterate.
            //
            // Only a device found this: the crash is in a third-party coroutine
            // and no unit test constructs a live Realtime client.
            // FINDINGS.md F-33.
            api.teardown()

            // TODO(REQ-8, phase-7): de-register this device's push token while
            // the session is still valid, so a signed-out caregiver stops
            // receiving pushes on a device they left. Best-effort by design —
            // it must never block sign-out.
        }

        auth.onSignedOut = {
            // The cache clear already happened in onWillSignOut, above — it is a
            // privacy boundary and running it earlier is strictly better: a care
            // team sharing one device is the normal case, and the next person to
            // sign in must not be one recomposition away from a household they
            // have no relationship to.
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

        /** iOS keys the same preference off `UserDefaults` directly. */
        const val PEOPLE_BLUR_PREFS = "com.memoria.caregiver.peopleblur"

        /**
         * Same file name iOS uses, in the app's private files directory. It
         * holds buffered interaction events between runs and nothing else — no
         * patient data ever reaches it.
         */
        const val ANALYTICS_BUFFER_FILE = "ui_events_buffer.json"
    }
}
