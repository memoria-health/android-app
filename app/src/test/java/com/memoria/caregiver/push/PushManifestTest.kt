package com.memoria.caregiver.push

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.memoria.caregiver.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The manifest half of push, asserted through `PackageManager` — the same way
 * `RecoveryLinkTest` asserts the deep link.
 *
 * This is the tier of test that would actually have failed while FINDINGS.md
 * F-26's push half was missing. Everything downstream of a delivered message can
 * be tested without any of this being declared, and was: `DeepLinkRouter` was
 * fully green with zero production callers. What could not be faked is whether
 * the operating system can route a message to this app at all.
 */
@RunWith(RobolectricTestRunner::class)
class PushManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The action Firebase's own library binds to. Written out rather than
     * imported so that a rename in the SDK shows up here as a failure rather
     * than as silence.
     */
    private val messagingEvent = "com.google.firebase.MESSAGING_EVENT"

    // -----------------------------------------------------------------------
    // The service
    // -----------------------------------------------------------------------

    @Test
    fun `a delivered message resolves to this app's messaging service`() {
        val matches = context.packageManager.queryIntentServices(
            Intent(messagingEvent).setPackage(context.packageName),
            0,
        )

        // firebase-messaging declares its own base service at priority -500 as a
        // fallback, so more than one can match. What decides delivery is which
        // one comes first.
        assertTrue("no service handles $messagingEvent", matches.isNotEmpty())
        assertEquals(
            MemoriaMessagingService::class.java.name,
            matches.first().serviceInfo.name,
        )
    }

    @Test
    fun `the messaging service is not exported`() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, MemoriaMessagingService::class.java),
            0,
        )

        // Only the system may start it. An exported push endpoint lets any app
        // on the phone fabricate an alert about a household it has nothing to do
        // with.
        assertFalse(info.exported)
    }

    // -----------------------------------------------------------------------
    // The permission
    // -----------------------------------------------------------------------

    @Test
    fun `the app declares the notification permission itself`() {
        // Asserted against the app's OWN manifest, not the merged one, and that
        // distinction is the point: firebase-messaging happens to declare
        // POST_NOTIFICATIONS too, so the merged manifest would still carry it
        // if this app's line were deleted — right up until the day the SDK is
        // swapped, upgraded or dropped, and notifications silently stop
        // appearing on every phone running Android 13 or later.
        val source = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            "app/src/main/AndroidManifest.xml must declare POST_NOTIFICATIONS itself",
            source.contains("android.permission.POST_NOTIFICATIONS"),
        )
    }

    @Test
    fun `the built package requests the notification permission`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertTrue(requested.contains("android.permission.POST_NOTIFICATIONS"))
    }

    // -----------------------------------------------------------------------
    // The default channel
    // -----------------------------------------------------------------------

    @Test
    fun `the channel Firebase is told to use is the channel this app creates`() {
        val meta = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData

        val declared = meta.getString("com.google.firebase.messaging.default_notification_channel_id")
            ?: context.getString(meta.getInt("com.google.firebase.messaging.default_notification_channel_id"))

        // These two are written in different languages in different files and
        // nothing but a test connects them. Let them drift and a backgrounded
        // notification message lands in Android's unnamed "Miscellaneous"
        // channel, where the caregiver's own Alerts setting does not reach it.
        assertEquals(NotificationChannels.alertsId(context), declared)
    }

    @Test
    fun `the notification icon and colour are declared for the path our code never sees`() {
        val meta = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData

        assertEquals(
            R.drawable.ic_push_alert,
            meta.getInt("com.google.firebase.messaging.default_notification_icon"),
        )
        assertEquals(
            R.color.terra,
            meta.getInt("com.google.firebase.messaging.default_notification_color"),
        )
    }
}
