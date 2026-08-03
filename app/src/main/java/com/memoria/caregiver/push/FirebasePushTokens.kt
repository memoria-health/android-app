package com.memoria.caregiver.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * One tag for everything push says, so `adb logcat -s MemoriaPush` shows the
 * whole story — including the sentences explaining why nothing is arriving.
 *
 * Nothing logged under it ever contains a token, a patient name, an event
 * narrative or a URL.
 */
internal const val PUSH_LOG_TAG: String = "MemoriaPush"

/**
 * The only place the app asks Firebase for this device's push token, and the
 * only place that reports Firebase not being configured.
 *
 * ## The stub is here, and it is loud
 *
 * `google-services.json` does not exist in this repository (REQUIREMENTS.md
 * REQ-7). Without it the `google-services` Gradle plugin is not applied, no
 * Firebase configuration resources are generated, and `FirebaseInitProvider`
 * finds nothing to initialise — so there is no default `FirebaseApp` and
 * `FirebaseMessaging.getInstance()` throws.
 *
 * The failure mode that must not happen is the quiet one: an app that looks
 * fully wired, never crashes, and simply never notifies anybody, with nothing
 * anywhere saying why. That is FINDINGS.md F-32's lesson — a control that is
 * present and does nothing is worse than one that is plainly absent. So the
 * unconfigured case is detected explicitly rather than caught as an exception,
 * and it is reported in a sentence that names the missing file.
 *
 * Nothing here logs a token.
 */
internal object FirebasePushTokens {

    /**
     * Whether a Firebase project is configured in this build.
     *
     * `FirebaseApp.getApps` returns an empty list rather than throwing, which is
     * why it is the check: asking `FirebaseApp.getInstance()` and catching
     * `IllegalStateException` would work too, but "no exception was thrown" is a
     * poor way to learn something this important, and it would also swallow a
     * genuinely broken configuration.
     */
    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /**
     * Asks FCM for the current registration token.
     *
     * Called at launch, which is the counterpart of iOS calling
     * `registerForRemoteNotifications()` at launch. It is needed *in addition*
     * to `MemoriaMessagingService.onNewToken` because FCM announces a token only
     * when it is created or rotated — on an ordinary launch it says nothing, and
     * this is what closes that gap.
     *
     * **Not gated on the notification permission**, and this is a deliberate
     * divergence from iOS, which returns early when authorization is refused.
     * The two things are independent on Android: the token exists regardless,
     * and POST_NOTIFICATIONS controls only whether anything is displayed. A
     * caregiver who declines the prompt and later turns alerts on from Settings
     * gives the app no callback at all — so if the token had never been
     * registered, they would enable notifications and still receive nothing,
     * permanently. Registering regardless is what makes that recoverable.
     *
     * ## Why the deprecated call
     *
     * `FirebaseMessaging.getToken()` is marked deprecated in favour of
     * `register()`, and that is NOT a like-for-like swap. `register()` is opt-in
     * behind a manifest flag (`firebase_messaging_installation_id_enabled`), and
     * what it hands back through `FirebaseMessagingService.onRegistered` is a
     * Firebase Installation ID — a different identifier, for Firebase's
     * direct-send API. What our backend will need is an FCM *registration
     * token*, which is what this returns. Moving to the other one would change
     * what the server has to be given, so it is a backend decision (REQ-8) and
     * not something to slip in while porting.
     *
     * @param onToken called with the token, on the main thread.
     * @param onUnavailable called with a human-readable reason, never a token.
     */
    @Suppress("DEPRECATION")
    fun fetch(
        context: Context,
        onToken: (String) -> Unit,
        onUnavailable: (String) -> Unit = { Log.w(PUSH_LOG_TAG, it) },
    ) {
        if (!isConfigured(context)) {
            onUnavailable(NOT_CONFIGURED)
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNullOrBlank()) {
                    onUnavailable("push.token EMPTY — FCM returned no registration token.")
                } else {
                    onToken(token)
                }
            }
            .addOnFailureListener { error ->
                // The class name, not the message: Play Services error strings
                // have historically included identifiers.
                onUnavailable("push.token FAILED — ${error::class.java.simpleName}")
            }
    }

    /**
     * Said out loud at launch, once, so a build without push cannot be mistaken
     * for a build where push works and nothing has happened yet.
     */
    const val NOT_CONFIGURED: String =
        "PUSH DISABLED — no Firebase project is configured in this build " +
            "(app/google-services.json is missing, REQ-7). No token will be issued and " +
            "no notification can arrive. Everything else in the push path is wired."
}
