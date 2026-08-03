package com.memoria.caregiver.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the backend's idea of "which device belongs to which caregiver" in step
 * with this device's push token.
 *
 * Port of the two halves iOS spreads across `NemaraCaregiverApp.init` (register
 * on token delivery, re-register on sign-in) and `AppEnvironment.live()`
 * (de-register on sign-out).
 *
 * Pure Kotlin on purpose: it takes the network calls as functions and the token
 * as a [PushTokenStore], so the whole of the behaviour below can be exercised
 * with no Firebase, no Android and no backend.
 *
 * ## The stub, and why it is a flag rather than a missing line
 *
 * [backendAcceptsFcmTokens] is false, and while it is false **nothing is sent**.
 * That is not caution, it is correctness: `POST /v1/me/devices` today takes an
 * `apns_token` plus an `environment` of exactly `sandbox` or `production`
 * (`nemara-backend/src/schemas/devices.py`, and a matching check constraint on
 * the column). Posting an FCM registration token into that field would write a
 * row that `src/services/push.py` then treats as an APNs token — it would POST
 * it to Apple, get a rejection, and on a 410 delete the row. So the "harmless"
 * version of guessing is a device that registers itself and is silently
 * unregistered again, which is indistinguishable from push simply not working.
 *
 * Everything else is wired: the token is fetched, stored, and handed to
 * [register] and [deregister] at exactly the right moments. Flipping the flag —
 * and adjusting the request body where those two functions are supplied, in
 * `AppEnvironment` — is the whole of the change when REQUIREMENTS.md REQ-8 and
 * QUESTIONS.md Q1 land.
 *
 * @param backendAcceptsFcmTokens REQ-8. See above.
 * @param register sends the token to the backend for the signed-in caregiver.
 * @param deregister removes the token from the backend for the signed-in caregiver.
 * @param report a place to say what happened, in words, without ever including
 *   the token. Called for the skipped case so a build with push wired and no
 *   contract cannot be mistaken for a build where push works.
 */
class PushTokenRegistrar(
    private val scope: CoroutineScope,
    private val store: PushTokenStore,
    private val backendAcceptsFcmTokens: Boolean,
    private val register: suspend (token: String) -> Unit,
    private val deregister: suspend (token: String) -> Unit,
    private val report: (String) -> Unit,
) {

    /**
     * The token this device currently has, or null if one has never arrived.
     *
     * The counterpart of `AppDelegate.lastAPNsTokenHex`, and read for the same
     * two reasons: to re-bind on sign-in and to de-register on sign-out.
     */
    val currentToken: String?
        get() = store.read()

    /**
     * A token arrived — either freshly minted by FCM, rotated, or read back at
     * launch. Records it and registers it.
     *
     * Not suspending, because both callers are places that cannot wait: FCM's
     * `onNewToken` runs on a service callback, and the launch fetch completes on
     * a listener.
     */
    fun onToken(token: String) {
        if (token.isBlank()) return
        // Write first, then send. If the send fails or is skipped, the token is
        // still on disk for sign-out and for the next sign-in to retry with.
        store.write(token)
        scope.launch { send(token) }
    }

    /**
     * A caregiver signed in. Re-binds whatever token this device already has to
     * the account that is now signed in.
     *
     * This is not redundant with [onToken]. A push token is per *device* and the
     * backend reassigns it to whoever registers it, but FCM only announces a
     * token when it changes — so on a device where two caregivers share a phone,
     * the second one would inherit no token at all and get no alerts, while the
     * first kept receiving them. iOS solves the same problem by re-requesting
     * registration on `.onChange(of: currentCaregiver?.id)`.
     *
     * Does nothing before a token exists; the launch fetch covers that case when
     * it completes.
     */
    fun onSignedIn() {
        val token = store.read() ?: return
        scope.launch { send(token) }
    }

    /**
     * Sign-out. Removes this device from the caregiver who is leaving.
     *
     * **Suspending and awaited**, unlike everything else here, and that is the
     * point: it has to reach the backend while the caregiver's session is still
     * valid. Firing it off and letting sign-out proceed would cancel it, or send
     * it with a token the server has already invalidated, and the device would
     * stay registered — the next person to pick up this phone would get
     * notifications about a household they have nothing to do with.
     *
     * It never fails sign-out. Failures are swallowed, and the wait is capped:
     * a caregiver handing their phone to someone else must not be held on a
     * spinner by a bad network, and the HTTP client's own timeout is a minute.
     *
     * The stored token is deliberately **kept**. It still belongs to this
     * device; it is only the association with a caregiver that has ended, and
     * the next sign-in needs it to re-bind ([onSignedIn]).
     */
    suspend fun onWillSignOut() {
        val token = store.read() ?: return
        if (!backendAcceptsFcmTokens) {
            report(SKIPPED_DEREGISTER)
            return
        }
        withTimeoutOrNull(SIGN_OUT_BUDGET_MS) {
            runCatching { deregister(token) }
        }
    }

    /**
     * The one place the registration call is made or skipped.
     *
     * Takes the token as a parameter rather than re-reading [store] here: a read
     * before the suspension point and a write inside it is the shape that lost a
     * patient's whole event list in FINDINGS.md F-12. The value is captured once,
     * at the call site, and nothing re-derives it across the boundary.
     */
    private suspend fun send(token: String) {
        if (!backendAcceptsFcmTokens) {
            report(SKIPPED_REGISTER)
            return
        }
        // Best-effort, exactly as iOS treats it: a failed registration is
        // retried by the next launch or the next sign-in, and there is nothing
        // useful to tell the caregiver in the meantime.
        runCatching { register(token) }
            .onFailure { report("push.register failed: ${it::class.java.simpleName}") }
    }

    private companion object {
        /**
         * Deliberately says what is missing and where, because the alternative —
         * silence — reads exactly like a push system that is working and simply
         * has nothing to say. Contains no token.
         */
        const val SKIPPED_REGISTER =
            "push.register SKIPPED — /v1/me/devices has no contract for an FCM token yet " +
                "(REQ-8 / QUESTIONS.md Q1). Token stored on device, nothing sent. " +
                "This device will not receive push notifications."

        const val SKIPPED_DEREGISTER =
            "push.deregister SKIPPED — nothing was ever registered for this device (REQ-8)."

        /**
         * Long enough for a normal round trip on a poor connection, short enough
         * that nobody notices it during a sign-out.
         */
        const val SIGN_OUT_BUDGET_MS = 5_000L
    }
}
