package com.memoria.caregiver.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.ui.auth.AuthViewModel
import com.memoria.caregiver.ui.auth.ResetPasswordState
import com.memoria.caregiver.ui.theme.MemoriaTheme
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The password-reset screen as the app actually presents it, and the whole path
 * from the recovery link to a signed-in caregiver.
 *
 * This is the regression test for the rest of FINDINGS.md F-26. Every piece of
 * this flow was already built and green while password recovery was impossible
 * on Android — the screen, the exchange, the update — because nothing reached
 * them. So the assertions start from the link arriving, exactly as
 * `AuthNavHostTest` starts from the welcome screen rather than from the sign-up
 * form it is really about.
 */
@RunWith(RobolectricTestRunner::class)
class ResetPasswordDestinationTest {

    // An Android rule, not the plain one: the screen installs a BackHandler, and
    // pressing Back for real means going through the activity's dispatcher.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val analytics = RecordingAnalytics()

    private companion object {
        const val CODE = "recovery-code-from-the-email"
        const val PASSWORD = "correcthorse"
        const val EMAIL = "jane@home.com"
        const val SIGNED_IN = "The signed-in app"

        /**
         * A structurally valid JWT — header, payload, signature — because
         * `SupabaseAuthService` hands every new access token to Realtime, which
         * decodes it. An opaque string fails there before the screen is reached.
         * Unsigned and expiring in 2100; nothing verifies it.
         */
        const val ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJyb2xlIjoiYXV0aGVudGlj" +
            "YXRlZCIsImF1ZCI6ImF1dGhlbnRpY2F0ZWQiLCJleHAiOjQxMDI0NDQ4MDAsImlhdCI6MTc2NzIyNTYwMH0." +
            "c2lnbmF0dXJl"
    }

    private var closes = 0

    // -----------------------------------------------------------------------
    // The two things the destination adds to the screen
    // -----------------------------------------------------------------------

    private fun setUpDestination(
        state: ResetPasswordState = ResetPasswordState(isExchangingCode = false),
        isLinkValid: Boolean = true,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    ResetPasswordDestination(
                        state = state,
                        confirm = state.password,
                        isLinkValid = isLinkValid,
                        onPasswordChange = {},
                        onConfirmChange = {},
                        onSubmit = {},
                        onClose = { closes++ },
                    )
                }
            }
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /**
     * Back is Close. This screen is drawn over the app rather than navigated to,
     * so nothing else handles the system Back button or the edge swipe: without
     * the handler both fall through to the navigation host underneath and pop the
     * welcome screen off, quitting the app from the middle of a password reset
     * (FINDINGS.md F-25).
     */
    @Test
    fun `the system Back button closes the screen`() {
        setUpDestination()

        pressBack()

        assertEquals("Back must close the screen, not leave the app", 1, closes)
    }

    /** The toolbar's Close is the same exit, and iOS offers no other. */
    @Test
    fun `the toolbar Close closes the screen`() {
        setUpDestination()

        compose.onNodeWithText("Close").performClick()

        assertEquals(1, closes)
    }

    /**
     * iOS attaches `.trackScreen("reset_password")` to the cover, so the
     * presenter logs it. The label is a constant: the code this screen was
     * reached with is a credential and must never reach an analytics event.
     */
    @Test
    fun `the screen is reported to analytics under a fixed name`() {
        setUpDestination(ResetPasswordState(isExchangingCode = true), isLinkValid = false)

        assertEquals(listOf("reset_password"), analytics.names("screen"))
        assertFalse(
            "the recovery code must not reach analytics",
            analytics.trace.any { it.contains(CODE) },
        )
    }

    // -----------------------------------------------------------------------
    // The whole path, through the real host
    // -----------------------------------------------------------------------

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun userJson() = """
        {
          "id": "00000000-0000-0000-0000-000000000001",
          "aud": "authenticated",
          "role": "authenticated",
          "email": "$EMAIL",
          "app_metadata": {},
          "user_metadata": {},
          "created_at": "2026-01-01T00:00:00Z",
          "updated_at": "2026-01-01T00:00:00Z"
        }
    """.trimIndent()

    /** GoTrue with its network replaced: the code exchange, then the password update. */
    private fun authService(): SupabaseAuthService {
        val client = createSupabaseClient(
            supabaseUrl = "https://example.supabase.co",
            supabaseKey = "test-anon-key",
        ) {
            httpEngine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/token")) {
                    respond(
                        """
                        {
                          "access_token": "$ACCESS_TOKEN",
                          "refresh_token": "test-refresh-token",
                          "token_type": "bearer",
                          "expires_in": 3600,
                          "user": ${userJson()}
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        json,
                    )
                } else {
                    respond(userJson(), HttpStatusCode.OK, json)
                }
            }
            install(Auth) {
                sessionManager = MemorySessionManager()
                // Pre-loaded: an exchange with no PKCE verifier stored fails
                // before it reaches the network. In the app it is written by
                // `requestPasswordReset` when the email is sent.
                codeVerifierCache = MemoryCodeVerifierCache("test-code-verifier")
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
            }
            install(Realtime)
        }
        return SupabaseAuthService(client, CoroutineScope(Dispatchers.Main)).apply {
            // What the real one does after any successful authentication: provision
            // the caregiver row and hand it back. `updatePassword` runs it too,
            // which is why setting a new password lands on the signed-in app
            // rather than back on the sign-in form.
            onAuthenticated = { _, _, _, _ ->
                Caregiver(
                    id = "c1",
                    displayName = "Jane Patel",
                    email = EMAIL,
                    createdAt = Instant.fromEpochSeconds(0),
                    updatedAt = Instant.fromEpochSeconds(0),
                )
            }
        }
    }

    private fun setUpHost(): AuthViewModel {
        val viewModel = AuthViewModel(authService())
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    AuthNavHost(
                        viewModel = viewModel,
                        signedInContent = { Text(SIGNED_IN) },
                    )
                }
            }
        }
        compose.onNodeWithText("The other 99.9% of care.").assertExists()
        return viewModel
    }

    /** What `MainActivity` does when the recovery link arrives, cold or warm. */
    private fun openRecoveryLink(viewModel: AuthViewModel) {
        compose.runOnUiThread { viewModel.onRecoveryLink(CODE) }
        compose.waitForIdle()
    }

    /**
     * The link now opens something. Before this change the email sent, the tap
     * resolved to no app, and there was no way to recover a password at all.
     */
    @Test
    fun `a recovery link opens the reset screen over the app`() {
        val viewModel = setUpHost()

        openRecoveryLink(viewModel)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTextCount("Set a new password.") == 1
        }
        compose.onNodeWithText("Resetting password for jane@home.com.").assertExists()
    }

    /**
     * The whole point, end to end: link → the app → the reset screen → a new
     * password → signed in. `updatePassword` runs the same provisioning a normal
     * sign-in runs, so the host swaps to the signed-in app by itself.
     */
    @Test
    fun `setting a new password from the link signs the caregiver in`() {
        val viewModel = setUpHost()
        openRecoveryLink(viewModel)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTextCount("Set a new password.") == 1
        }

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(PASSWORD)
        fields[1].performTextInput(PASSWORD)
        compose.onNodeWithText("Set new password").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTextCount(SIGNED_IN) == 1
        }
        compose.onNodeWithText("Set a new password.").assertDoesNotExist()
    }

    /**
     * Back closes the reset screen and leaves the caregiver where they were,
     * rather than closing the app. What is underneath stays alive throughout —
     * iOS covers RootView rather than replacing it.
     */
    @Test
    fun `Back from the reset screen returns to the app it covered`() {
        val viewModel = setUpHost()
        openRecoveryLink(viewModel)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTextCount("Set a new password.") == 1
        }

        pressBack()

        compose.onNodeWithText("Set a new password.").assertDoesNotExist()
        compose.onNodeWithText("The other 99.9% of care.").assertExists()
        compose.onNodeWithText("Create an account").performClick()
        compose.onNodeWithText("Make a Memoria account.").assertExists()
    }

    /** Counting matcher, so `waitUntil` can poll without throwing on "not there yet". */
    private fun ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
}
