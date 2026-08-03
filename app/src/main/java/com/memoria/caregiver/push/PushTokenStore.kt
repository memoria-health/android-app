package com.memoria.caregiver.push

import android.content.SharedPreferences

/**
 * Remembers the push token this device was last given.
 *
 * ## Why this exists at all, when iOS gets it for free
 *
 * iOS holds the equivalent in a plain static field, `AppDelegate.lastAPNsTokenHex`,
 * and that is enough because iOS re-delivers the APNs token to
 * `didRegisterForRemoteNotificationsWithDeviceToken` on **every** launch. A field
 * that lives only as long as the process is always refilled before anything needs
 * it.
 *
 * FCM does not work that way. `onNewToken` fires when the token is first created
 * and when it rotates, and on no other launch. So the same in-memory field on
 * Android would be empty on almost every run — and the one moment it is most
 * needed is sign-out, which must send the token to the backend to de-register it.
 * A caregiver signing out on a launch where FCM said nothing would leave their
 * token registered and keep receiving another household's alerts.
 *
 * Hence a stored value rather than a field. The asynchronous fetch at launch
 * (`FirebasePushTokens.fetch`) refreshes it when it can; this is what is there
 * when it cannot.
 *
 * ## What is stored
 *
 * An FCM registration token, and nothing else. It identifies this app install to
 * Google's servers. It is not a credential for our backend and it carries no
 * patient data, but it is still an identifier, so it goes in the app's private
 * preferences and is never written to a log.
 */
interface PushTokenStore {
    /** The last token this device was given, or null if none ever arrived. */
    fun read(): String?

    /** Records a token. */
    fun write(token: String)
}

/** The real one. */
class SharedPrefsPushTokenStore(private val prefs: SharedPreferences) : PushTokenStore {

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(token: String) {
        // apply(), not commit(): this can be called from FirebaseMessagingService
        // on the main thread, and a token that lands on disk a few milliseconds
        // later is not worth a synchronous write.
        prefs.edit().putString(KEY, token).apply()
    }

    private companion object {
        const val KEY = "last_push_token"
    }
}

/** For tests and for any environment built without a Context. */
class InMemoryPushTokenStore(initial: String? = null) : PushTokenStore {
    private var token: String? = initial
    override fun read(): String? = token
    override fun write(token: String) {
        this.token = token
    }
}
