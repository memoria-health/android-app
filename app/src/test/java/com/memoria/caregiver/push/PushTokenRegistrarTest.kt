package com.memoria.caregiver.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token lifecycle: arrival, sign-in, sign-out, and the REQ-8 gate.
 *
 * Pure Kotlin — the registrar takes the network calls as functions and the token
 * as a store precisely so this can be exercised with no Firebase, no Android and
 * no backend.
 *
 * The most important test here is the one that asserts **nothing is sent**. The
 * gate is the whole reason this class can be wired up and shipped today, and a
 * gate nobody tests is how a stub quietly becomes a live call.
 */
class PushTokenRegistrarTest {

    private val token = "fcm-token-aaaa"
    private val other = "fcm-token-bbbb"

    private class Calls {
        val registered = mutableListOf<String>()
        val deregistered = mutableListOf<String>()
        val reports = mutableListOf<String>()
    }

    private fun registrar(
        scope: kotlinx.coroutines.CoroutineScope,
        calls: Calls,
        store: PushTokenStore = InMemoryPushTokenStore(),
        enabled: Boolean = true,
        register: suspend (String) -> Unit = { calls.registered += it },
        deregister: suspend (String) -> Unit = { calls.deregistered += it },
    ) = PushTokenRegistrar(
        scope = scope,
        store = store,
        backendAcceptsFcmTokens = enabled,
        register = register,
        deregister = deregister,
        report = { calls.reports += it },
    )

    // -----------------------------------------------------------------------
    // The REQ-8 gate
    // -----------------------------------------------------------------------

    @Test
    fun `nothing is sent while the backend has no contract for an FCM token`() =
        // Unconfined so the launched coroutine actually runs before the
        // assertion. On the default test dispatcher this test would pass
        // whether the gate worked or not, because nothing would have run at
        // all — the same "absence of a failure is not a pass" shape HARNESS.md
        // §4d is about.
        runTest(UnconfinedTestDispatcher()) {
            val calls = Calls()
            val subject = registrar(backgroundScope, calls, enabled = false)

            subject.onToken(token)

            assertEquals(emptyList<String>(), calls.registered)
        }

    @Test
    fun `the skipped registration says so, in words, rather than staying silent`() =
        runTest(UnconfinedTestDispatcher()) {
            val calls = Calls()
            val subject = registrar(backgroundScope, calls, enabled = false)

            subject.onToken(token)

            // Silence would read exactly like a push system that works and has
            // nothing to say. FINDINGS.md F-32.
            assertEquals(1, calls.reports.size)
            assertTrue(calls.reports.single().contains("REQ-8"))
        }

    @Test
    fun `nothing that is reported contains the token`() = runTest(UnconfinedTestDispatcher()) {
        val calls = Calls()
        val subject = registrar(
            backgroundScope,
            calls,
            store = InMemoryPushTokenStore(),
            enabled = false,
        )

        subject.onToken(token)
        subject.onWillSignOut()

        // A registration token identifies this app install. It has no business
        // in logcat, in a crash report, or in a bug-report attachment somebody
        // emails to support.
        assertEquals(2, calls.reports.size)
        assertTrue(calls.reports.none { it.contains(token) })
    }

    @Test
    fun `the token is still stored while registration is switched off`() = runTest {
        val calls = Calls()
        val store = InMemoryPushTokenStore()
        val subject = registrar(backgroundScope, calls, store = store, enabled = false)

        subject.onToken(token)

        // Otherwise flipping the flag would need a token that only arrives when
        // FCM next decides to rotate one, which can be months.
        assertEquals(token, store.read())
        assertEquals(token, subject.currentToken)
    }

    @Test
    fun `flipping the flag is the whole change`() = runTest(UnconfinedTestDispatcher()) {
        val calls = Calls()
        val subject = registrar(backgroundScope, calls, enabled = true)

        subject.onToken(token)

        // Same wiring, same call sites, one boolean. This is the assertion that
        // makes the stub a stub rather than dead code.
        assertEquals(listOf(token), calls.registered)
    }

    // -----------------------------------------------------------------------
    // Arrival
    // -----------------------------------------------------------------------

    @Test
    fun `a blank token is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val calls = Calls()
        val store = InMemoryPushTokenStore()
        val subject = registrar(backgroundScope, calls, store = store)

        subject.onToken("")

        assertNull(store.read())
        assertEquals(emptyList<String>(), calls.registered)
    }

    @Test
    fun `a rotated token replaces the one before it`() = runTest(UnconfinedTestDispatcher()) {
        val calls = Calls()
        val store = InMemoryPushTokenStore()
        val subject = registrar(backgroundScope, calls, store = store)

        subject.onToken(token)
        subject.onToken(other)

        assertEquals(other, store.read())
        assertEquals(listOf(token, other), calls.registered)
    }

    @Test
    fun `a failed registration does not propagate`() = runTest(UnconfinedTestDispatcher()) {
        val calls = Calls()
        val subject = registrar(
            backgroundScope,
            calls,
            register = { throw IllegalStateException("offline") },
        )

        subject.onToken(token)

        // Best-effort, exactly as iOS treats it: the next launch or the next
        // sign-in retries, and there is nothing useful to tell the caregiver.
        assertTrue(calls.reports.single().contains("failed"))
    }

    // -----------------------------------------------------------------------
    // Sign-in
    // -----------------------------------------------------------------------

    @Test
    fun `signing in re-binds the token this device already had`() = runTest(UnconfinedTestDispatcher()) {
        val calls = Calls()
        val subject = registrar(
            backgroundScope,
            calls,
            store = InMemoryPushTokenStore(initial = token),
        )

        subject.onSignedIn()

        // A care team sharing one phone is the normal case. FCM announces a
        // token only when it changes, so without this the second caregiver to
        // sign in would get no alerts and the first would keep getting them.
        assertEquals(listOf(token), calls.registered)
    }

    @Test
    fun `signing in before any token exists does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val calls = Calls()
        val subject = registrar(backgroundScope, calls)

        subject.onSignedIn()

        assertEquals(emptyList<String>(), calls.registered)
    }

    // -----------------------------------------------------------------------
    // Sign-out
    // -----------------------------------------------------------------------

    @Test
    fun `signing out de-registers the token`() = runTest {
        val calls = Calls()
        val subject = registrar(
            backgroundScope,
            calls,
            store = InMemoryPushTokenStore(initial = token),
        )

        subject.onWillSignOut()

        assertEquals(listOf(token), calls.deregistered)
    }

    @Test
    fun `signing out keeps the token on the device`() = runTest {
        val calls = Calls()
        val store = InMemoryPushTokenStore(initial = token)
        val subject = registrar(backgroundScope, calls, store = store)

        subject.onWillSignOut()

        // The token belongs to the device; only its association with a caregiver
        // ended. Clearing it would leave the next sign-in on this phone with
        // nothing to re-bind.
        assertEquals(token, store.read())
    }

    @Test
    fun `signing out completes even when de-registration never returns`() = runTest {
        val calls = Calls()
        val subject = registrar(
            backgroundScope,
            calls,
            store = InMemoryPushTokenStore(initial = token),
            // A dead network, which the HTTP client would sit on for a full
            // minute.
            deregister = { CompletableDeferred<Unit>().await() },
        )

        subject.onWillSignOut()

        // Reaching this line at all is the assertion: a caregiver handing their
        // phone to somebody else must not be held on a spinner.
        assertEquals(emptyList<String>(), calls.deregistered)
    }

    @Test
    fun `signing out survives a failing de-registration`() = runTest {
        val calls = Calls()
        val subject = registrar(
            backgroundScope,
            calls,
            store = InMemoryPushTokenStore(initial = token),
            deregister = { throw IllegalStateException("401") },
        )

        subject.onWillSignOut()

        assertEquals(emptyList<String>(), calls.deregistered)
    }

    @Test
    fun `signing out with no token sends nothing`() = runTest {
        val calls = Calls()
        val subject = registrar(backgroundScope, calls)

        subject.onWillSignOut()

        assertEquals(emptyList<String>(), calls.deregistered)
        assertEquals(emptyList<String>(), calls.reports)
    }
}
