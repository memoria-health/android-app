package com.memoria.caregiver.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.memoria.caregiver.MemoriaApplication

/**
 * Where FCM hands the app a token and a message. Android's counterpart to the
 * APNs callbacks on `AppDelegate`.
 *
 * The class is thin on purpose — it unpacks what Firebase gives it and delegates
 * — because it is the one piece of the push path that cannot run in a unit test
 * (it is instantiated by Firebase, in a process this suite never starts). The
 * parsing, the notification and the registration all live in plain classes
 * beside it, where they are tested.
 *
 * ## Why this deliberately avoids building the app's object graph
 *
 * A push can start this service in a cold process with nothing else running.
 * Touching `MemoriaApplication.environment` there would construct the Supabase
 * client, the HTTP client, the Realtime socket and the analytics worker just to
 * write down a string — in a process the system started for a few hundred
 * milliseconds and will kill again. So the token is written straight to its
 * store, and the live object graph is used only if it happens to already exist.
 */
class MemoriaMessagingService : FirebaseMessagingService() {

    /**
     * FCM issued or rotated this device's token.
     *
     * Fires when the token is first created, when app data is cleared, and on
     * reinstall. It does NOT fire on ordinary launches, which is why
     * `FirebasePushTokens.fetch` also runs at startup.
     *
     * Deprecated in favour of `onRegistered`, and deliberately not moved: that
     * callback delivers a Firebase Installation ID rather than an FCM
     * registration token, under a manifest opt-in, for a different send API.
     * The reasoning is written out once in `FirebasePushTokens.fetch`.
     */
    @Deprecated("Paired with FirebaseMessaging.getToken(); see FirebasePushTokens.fetch.")
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = application as? MemoriaApplication
        val live = app?.environmentIfCreated?.pushTokens
        if (live != null) {
            // The app is up: store it and try to register it in one step.
            live.onToken(token)
        } else {
            // Cold push-wakeup. Record it and stop; the next foreground reads it
            // back and registers it against whoever is signed in then.
            SharedPrefsPushTokenStore(pushTokenPrefs(applicationContext)).write(token)
        }
    }

    /**
     * A message arrived.
     *
     * Called for every data message, foreground or background. It is NOT called
     * for a background message that carries a `notification` block — the system
     * draws those itself, which is why the manifest also declares the default
     * channel, icon and colour: that path never reaches this code and would
     * otherwise land in Android's unnamed "Miscellaneous" channel.
     *
     * A message with no usable ids is dropped in silence. That matches iOS,
     * which requires both ids before it does anything, and it is the right
     * answer here too: a notification the caregiver can tap that goes nowhere is
     * worse than one that never appeared.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val push = eventPushFrom(
            data = message.data,
            title = message.notification?.title,
            body = message.notification?.body,
        ) ?: return
        EventNotifications.post(applicationContext, push)
    }
}

/**
 * The app's private preferences file for the push token.
 *
 * A named function rather than a constant in two places, because the service can
 * open it in a process where nothing else has, and it must be the same file the
 * signed-in app reads.
 */
internal fun pushTokenPrefs(context: Context) =
    context.getSharedPreferences("com.memoria.caregiver.push", Context.MODE_PRIVATE)
