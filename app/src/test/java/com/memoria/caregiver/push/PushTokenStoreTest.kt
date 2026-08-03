package com.memoria.caregiver.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Why the token is written to disk at all.
 *
 * iOS keeps the same value in a plain static field, because iOS re-delivers the
 * APNs token on every launch. FCM announces a token only when it is created or
 * rotated, so the same field on Android would be empty on almost every run — and
 * the moment it is most needed is sign-out, which has to send the token to the
 * backend to de-register it.
 */
@RunWith(RobolectricTestRunner::class)
class PushTokenStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a token written in one process is there for the next one`() {
        SharedPrefsPushTokenStore(pushTokenPrefs(context)).write("fcm-token-aaaa")

        // A separate instance over the same preferences file, which is what a
        // push-wakeup process and the signed-in app actually are.
        assertEquals("fcm-token-aaaa", SharedPrefsPushTokenStore(pushTokenPrefs(context)).read())
    }

    @Test
    fun `an untouched store holds nothing`() {
        assertNull(SharedPrefsPushTokenStore(pushTokenPrefs(context)).read())
    }

    @Test
    fun `a rotated token replaces the previous one`() {
        val store = SharedPrefsPushTokenStore(pushTokenPrefs(context))

        store.write("fcm-token-aaaa")
        store.write("fcm-token-bbbb")

        assertEquals("fcm-token-bbbb", store.read())
    }

    @Test
    fun `the service and the app open the same file`() {
        // Two callers name this file: MemoriaMessagingService, which can run in
        // a cold process, and AppEnvironment. Naming it twice by hand is how
        // they would end up with a token each and neither would be the one the
        // backend was told about.
        val fromA = pushTokenPrefs(context)
        val fromB = pushTokenPrefs(context)
        fromA.edit().putString("probe", "x").commit()

        assertEquals("x", fromB.getString("probe", null))
    }
}
