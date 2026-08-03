package com.memoria.caregiver.ui.auth

import com.memoria.caregiver.data.auth.SupabaseAuthService
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher

/**
 * A [SupabaseAuthService] that never reaches the network, for tests about what
 * happens *before* a call goes out.
 *
 * [AuthViewModel] takes the concrete service and the service takes a real
 * `SupabaseClient`, so there is no seam to fake at. Building the client against
 * a host that does not exist is fine here because no test lets a request start:
 * every one of them either asserts that the view model refused to call, or parks
 * `Dispatchers.Main` so the launched coroutine is queued and never run.
 *
 * The in-memory session manager and verifier cache are required, not a
 * preference — the defaults are backed by `SharedPreferences` through an
 * app-startup initializer that is not running under a unit test, and the client
 * throws while being built without them.
 */
fun offlineAuthService(scope: CoroutineScope): SupabaseAuthService {
    val client = createSupabaseClient(
        supabaseUrl = "https://example.supabase.co",
        supabaseKey = "test-anon-key",
    ) {
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            // Nothing should wake up on its own and start refreshing a session
            // that does not exist.
            autoLoadFromStorage = false
            alwaysAutoRefresh = false
        }
        install(Realtime)
    }
    return SupabaseAuthService(client, scope)
}

/** The same, wrapped in the view model under test. */
fun offlineAuthViewModel(dispatcher: CoroutineDispatcher): AuthViewModel =
    AuthViewModel(offlineAuthService(CoroutineScope(dispatcher)))
