package com.memoria.caregiver.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.auth.SupabaseAuthService
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [AuthViewModel]'s password-recovery half. Spec: `Features/Auth/ResetPasswordView.swift`
 * and the `.onOpenURL` handler in `App/NemaraCaregiverApp.swift`.
 *
 * The regression this defends is FINDINGS.md F-26: `exchangeRecoveryCode` and
 * `updatePassword` were both built and tested, and `ResetPasswordScreen` was
 * 292 lines, while password recovery was impossible — because nothing joined
 * them. So the assertions here are about the joining: that a link opens a
 * session, that the session carries the exchange's answer, that a spent code is
 * dropped, and that nothing submits before the link is known good.
 *
 * Unlike `AuthViewModelTest`, this one lets calls actually run, against a Ktor
 * [MockEngine] standing in for GoTrue — the same harness the API view-model
 * tests use. It has to: `isLinkValid` is set by a successful exchange and
 * nothing else, so a test that never lets a call complete could only ever assert
 * refusals, and "the submit never fires at all" would look identical to "the
 * submit is correctly gated".
 */
// Robolectric: building a Supabase client pulls in Android framework classes.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ResetPasswordViewModelTest {

    private companion object {
        const val CODE = "recovery-code-from-the-email"
        const val PASSWORD = "Password1!"
        const val EMAIL = "jane@home.com"

        /** The PKCE verifier `requestPasswordReset` stores when it sends the email. */
        const val VERIFIER = "test-code-verifier"

        /**
         * A structurally valid JWT — header, payload, signature — because
         * `SupabaseAuthService` hands every new access token to Realtime, which
         * decodes it. An opaque string fails there before any assertion in this
         * file is reached. Unsigned and expiring in 2100; nothing verifies it.
         */
        const val ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJyb2xlIjoiYXV0aGVudGlj" +
            "YXRlZCIsImF1ZCI6ImF1dGhlbnRpY2F0ZWQiLCJleHAiOjQxMDI0NDQ4MDAsImlhdCI6MTc2NzIyNTYwMH0." +
            "c2lnbmF0dXJl"
    }

    /**
     * What GoTrue answers a `token?grant_type=pkce` exchange with: a session,
     * and the user whose address the screen shows as "Resetting password for …".
     */
    private fun sessionJson(email: String = EMAIL) = """
        {
          "access_token": "$ACCESS_TOKEN",
          "refresh_token": "test-refresh-token",
          "token_type": "bearer",
          "expires_in": 3600,
          "user": ${userJson(email)}
        }
    """.trimIndent()

    private fun userJson(email: String = EMAIL) = """
        {
          "id": "00000000-0000-0000-0000-000000000001",
          "aud": "authenticated",
          "role": "authenticated",
          "email": "$email",
          "app_metadata": {},
          "user_metadata": {},
          "created_at": "2026-01-01T00:00:00Z",
          "updated_at": "2026-01-01T00:00:00Z"
        }
    """.trimIndent()

    /** Every auth endpoint answers well unless a test says otherwise. */
    private var exchangeFails = false
    private var updateFails = false

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    /** Every request that left, so a test can assert what was actually sent. */
    private val sent = mutableListOf<Pair<String, String>>()

    private fun engine() = MockEngine { request ->
        val path = request.url.encodedPath
        sent += path to ((request.body as? TextContent)?.text ?: "")
        when {
            path.endsWith("/token") && exchangeFails ->
                respondError(HttpStatusCode.BadRequest, """{"error":"invalid_grant"}""", json)
            path.endsWith("/token") -> respond(sessionJson(), HttpStatusCode.OK, json)
            path.endsWith("/user") && updateFails ->
                respondError(HttpStatusCode.UnprocessableEntity, """{"msg":"weak password"}""", json)
            path.endsWith("/user") -> respond(userJson(), HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.OK, json)
        }
    }

    /**
     * The real service and the real client, with the network replaced. The
     * in-memory session manager and verifier cache are required rather than
     * preferred, for the reason `OfflineAuth` gives: the defaults are backed by
     * `SharedPreferences` through an app-startup initializer that is not running.
     */
    private fun viewModel(): AuthViewModel {
        val client = createSupabaseClient(
            supabaseUrl = "https://example.supabase.co",
            supabaseKey = "test-anon-key",
        ) {
            httpEngine = engine()
            install(Auth) {
                sessionManager = MemorySessionManager()
                // Pre-loaded, because an exchange with no verifier stored fails
                // before it reaches the network. In the app the verifier is
                // written by `requestPasswordReset` — the half of this flow that
                // already worked — when the email is sent, and read back here
                // when the link comes home.
                codeVerifierCache = MemoryCodeVerifierCache(VERIFIER)
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
            }
            install(Realtime)
        }
        return AuthViewModel(SupabaseAuthService(client, CoroutineScope(mainDispatcher)))
            .also { viewModels += it }
    }

    /**
     * One dispatcher for `Dispatchers.Main` and for `runTest`, so there is
     * exactly one scheduler. `StandardTestDispatcher` rather than an unconfined
     * one for the reason `CameraDetailViewModelTest` documents: `MockEngine`
     * really does hop threads, and an unconfined dispatcher resumes on whichever
     * thread finished the call, through an event loop this test cannot drain.
     */
    private val mainDispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<AuthViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    /** Cancel before [resetMain], or the children resume on a torn-down dispatcher. */
    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    /**
     * Wait for every coroutine an action started, and nothing else. Draining the
     * scheduler alone does not finish a call that is parked on `MockEngine`'s
     * thread; joining alone does not deliver its resumption to Main.
     */
    private suspend fun ViewModel.settle() {
        while (true) {
            mainDispatcher.scheduler.advanceUntilIdle()
            val pending = viewModelScope.coroutineContext.job.children.toList()
            if (pending.isEmpty()) break
            pending.joinAll()
        }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    /** A view model with a verified link open and a matching password typed in. */
    private suspend fun readyToSubmit(): AuthViewModel {
        val vm = viewModel()
        vm.onRecoveryLink(CODE)
        vm.settle()
        vm.onResetPasswordChange(PASSWORD)
        vm.onResetConfirmChange(PASSWORD)
        return vm
    }

    // -----------------------------------------------------------------------
    // Opening the link
    // -----------------------------------------------------------------------

    /**
     * The screen is shown by the session existing, so this is the whole of
     * "the link now opens something". Before it, tapping the email button
     * reached no app at all.
     */
    @Test fun `a recovery link opens the reset screen, verifying`() = runTest(mainDispatcher) {
        val vm = viewModel()

        vm.onRecoveryLink(CODE)

        val session = vm.reset.value
        assertNotNull("the link must open the reset screen", session)
        assertTrue("iOS shows 'Verifying link…' first", session!!.state.isExchangingCode)
        assertFalse("nothing is valid before the exchange answers", session.isLinkValid)
    }

    /**
     * A good code comes back with the account's address, which the form shows as
     * "Resetting password for …" so the caregiver can see which account they are
     * about to change.
     */
    @Test fun `a good code verifies the link and names the account`() = runTest(mainDispatcher) {
        val vm = viewModel()

        vm.onRecoveryLink(CODE)
        vm.settle()

        val session = vm.reset.value!!
        assertTrue(session.isLinkValid)
        assertFalse(session.state.isExchangingCode)
        assertEquals(EMAIL, session.state.email)
        assertNull(session.state.form.errorMessage)
    }

    /**
     * The state a real caregiver hits: a link that has expired or was already
     * used. The screen must fall to its invalid body rather than offering a form
     * that can never save.
     */
    @Test fun `a spent code leaves the link invalid, with the server's reason`() = runTest(mainDispatcher) {
        exchangeFails = true
        val vm = viewModel()

        vm.onRecoveryLink(CODE)
        vm.settle()

        val session = vm.reset.value!!
        assertFalse("an unusable link must not show the form", session.isLinkValid)
        assertFalse("and must stop saying it is verifying", session.state.isExchangingCode)
        assertNotNull("with something to explain it", session.state.form.errorMessage)
    }

    /**
     * A recovery code is single-use. The same intent can be delivered twice — a
     * configuration change hands the Activity its original intent again — and
     * re-exchanging would replace a working screen with an expired-link one.
     */
    @Test fun `the same link arriving twice does not restart the exchange`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onRecoveryLink(CODE)
        vm.settle()
        val verified = vm.reset.value

        vm.onRecoveryLink(CODE)
        vm.settle()

        assertEquals("the verified session must be left alone", verified, vm.reset.value)
        assertTrue(vm.reset.value!!.isLinkValid)
    }

    // -----------------------------------------------------------------------
    // The submit gate
    // -----------------------------------------------------------------------

    @Test fun `setting the password is refused while the link is still being verified`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            vm.onRecoveryLink(CODE)
            // Deliberately NOT settled: the exchange is still in flight.
            vm.onResetPasswordChange(PASSWORD)
            vm.onResetConfirmChange(PASSWORD)

            vm.submitPasswordReset()

            assertFalse("no call may start", vm.reset.value!!.state.form.isBusy)
        }

    @Test fun `setting the password is refused when the link is invalid`() = runTest(mainDispatcher) {
        exchangeFails = true
        val vm = readyToSubmit()

        vm.submitPasswordReset()

        assertFalse(vm.reset.value!!.state.form.isBusy)
    }

    @Test fun `setting the password is refused when the confirmation does not match`() =
        runTest(mainDispatcher) {
            val vm = readyToSubmit()
            vm.onResetConfirmChange("Password2!")

            vm.submitPasswordReset()

            assertFalse(vm.reset.value!!.state.form.isBusy)
        }

    @Test fun `setting the password is refused when it is too short`() = runTest(mainDispatcher) {
        val vm = readyToSubmit()
        vm.onResetPasswordChange("Pass1!a")
        vm.onResetConfirmChange("Pass1!a")

        vm.submitPasswordReset()

        assertFalse(vm.reset.value!!.state.form.isBusy)
    }

    /** A double tap must not send two updates. */
    @Test fun `a second submit while one is in flight is refused`() = runTest(mainDispatcher) {
        val vm = readyToSubmit()

        vm.submitPasswordReset()
        val afterFirst = vm.reset.value
        vm.submitPasswordReset()

        assertTrue("the first call is the one that runs", afterFirst!!.state.form.isBusy)
        assertEquals(afterFirst, vm.reset.value)
    }

    // -----------------------------------------------------------------------
    // Setting the password
    // -----------------------------------------------------------------------

    /**
     * The end of the flow. `updatePassword` runs the same `onAuthenticated`
     * provisioning as a normal sign-in, so the host swaps to the signed-in app on
     * its own; dropping the session is what dismisses this screen — and is also
     * the only thing that erases the recovery code.
     */
    @Test fun `a verified link and a good password complete the reset`() = runTest(mainDispatcher) {
        val vm = readyToSubmit()

        vm.submitPasswordReset()
        vm.settle()

        assertNull("the screen dismisses and the code is gone with it", vm.reset.value)
    }

    /**
     * The password the caregiver typed is the one that leaves. Asserted on the
     * wire rather than on state, because dismissing the screen looks exactly the
     * same whether the update was sent or silently skipped — a reset that
     * "succeeds" without changing anything locks the caregiver out for good.
     */
    @Test fun `the typed password is what is sent`() = runTest(mainDispatcher) {
        val vm = readyToSubmit()

        vm.submitPasswordReset()
        vm.settle()

        val update = sent.lastOrNull { it.first.endsWith("/user") }
        assertNotNull("the password update never left", update)
        // Compared exactly, not by "contains": a password that merely contains
        // the typed one is a different password, and the caregiver would be
        // locked out by the very thing they used to get back in.
        assertEquals(
            JsonPrimitive(PASSWORD),
            Json.parseToJsonElement(update!!.second).jsonObject["password"],
        )
    }

    /**
     * A rejected password leaves the form up with the reason, rather than
     * flipping to the invalid-link body — the link is still fine, the password
     * was not. iOS keeps showing the form here too.
     */
    @Test fun `a rejected password leaves the form up with the reason`() = runTest(mainDispatcher) {
        updateFails = true
        val vm = readyToSubmit()

        vm.submitPasswordReset()
        vm.settle()

        val session = vm.reset.value
        assertNotNull("the screen must not dismiss on a failure", session)
        assertTrue("the link is still good", session!!.isLinkValid)
        assertFalse(session.state.form.isBusy)
        assertNotNull(session.state.form.errorMessage)
    }

    // -----------------------------------------------------------------------
    // Closing
    // -----------------------------------------------------------------------

    /**
     * Close — from the toolbar, the invalid-link button, or the system Back
     * button — takes the recovery code with it. There is nowhere else it is
     * kept, which is the point of holding it here rather than in a navigation
     * argument.
     */
    @Test fun `closing the screen drops the recovery code`() = runTest(mainDispatcher) {
        val vm = readyToSubmit()

        vm.closePasswordReset()

        assertNull(vm.reset.value)
    }

    /**
     * An exchange that answers after the caregiver has closed the screen must
     * not put it back up — and must not put the code back with it.
     */
    @Test fun `an exchange that lands after the screen closed does not reopen it`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            vm.onRecoveryLink(CODE)

            vm.closePasswordReset()
            vm.settle()

            assertNull(vm.reset.value)
        }

    // -----------------------------------------------------------------------
    // Typing
    // -----------------------------------------------------------------------

    /**
     * Each field is written on its own. A setter that rebuilt the whole session
     * from a snapshot would lose whichever edit landed in between — the
     * read-modify-write hazard the sign-up setters were split up to avoid.
     */
    @Test fun `the password and the confirmation are edited independently`() = runTest(mainDispatcher) {
        val vm = readyToSubmit()

        vm.onResetPasswordChange("first")
        vm.onResetConfirmChange("second")

        assertEquals("first", vm.reset.value!!.state.password)
        assertEquals("second", vm.reset.value!!.confirm)
    }

    /** Typing clears a stale error, as it does on every other form in this class. */
    @Test fun `editing a field clears a stale error`() = runTest(mainDispatcher) {
        updateFails = true
        val vm = readyToSubmit()
        vm.submitPasswordReset()
        vm.settle()
        assertNotNull(vm.reset.value!!.state.form.errorMessage)

        vm.onResetPasswordChange("${PASSWORD}x")

        assertNull(vm.reset.value!!.state.form.errorMessage)
    }

    /**
     * Every entry point is safe with no link open. These are wired to a screen
     * that is not on screen in that state, but a stale recomposition can still
     * call them, and none of them may invent a session — a session with no code
     * is a reset screen with nothing to exchange.
     */
    @Test fun `nothing creates a session when no link is open`() = runTest(mainDispatcher) {
        val vm = viewModel()

        vm.onResetPasswordChange(PASSWORD)
        vm.onResetConfirmChange(PASSWORD)
        vm.submitPasswordReset()
        vm.closePasswordReset()
        vm.settle()

        assertNull(vm.reset.value)
    }
}
