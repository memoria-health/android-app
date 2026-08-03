package com.memoria.caregiver.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.memoria.caregiver.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What a caregiver sees, and what a tap on it carries.
 *
 * Two things are being pinned here. The first is that a tap reaches the event —
 * the ids have to survive the round trip through the system, and the intent is
 * the only thing that survives the app's process being dead. The second is
 * privacy: nothing in a notification may be looked up from app state, so the
 * assertions check what is NOT there as much as what is.
 */
@RunWith(RobolectricTestRunner::class)
class EventNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val eventA = "3f1b8b1e-2b7c-4c2e-9c1a-6f0f2d4a8b31"
    private val eventB = "7c5d9e21-4a8b-4f10-b2c3-1d6e8f0a9b42"
    private val patient = "9a2c7d40-1f3e-4b55-8a6d-0c5e7b21d904"

    private fun push(
        eventId: String = eventA,
        title: String? = "Fall detected",
        body: String? = "A fall was detected",
    ) = EventPush(EventTap(eventId = eventId, patientId = patient), title, body)

    private fun posted() = shadowOf(manager).allNotifications

    // -----------------------------------------------------------------------
    // The tap
    // -----------------------------------------------------------------------

    @Test
    fun `the tap intent carries both ids to the activity`() {
        val intent = EventNotifications.tapIntent(context, EventTap(eventA, patient))

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(eventA, intent.getStringExtra(PUSH_KEY_EVENT_ID))
        assertEquals(patient, intent.getStringExtra(PUSH_KEY_PATIENT_ID))
    }

    @Test
    fun `the tap intent reaches the running app instead of stacking a second copy of it`() {
        val intent = EventNotifications.tapIntent(context, EventTap(eventA, patient))

        // CLEAR_TOP with the manifest's launchMode="singleTop" is what routes a
        // tap into onNewIntent on the instance already running. Without it the
        // caregiver taps an alert and lands in a duplicate app on top of the one
        // they were using — the same defect the recovery link had.
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `two events do not share one pending intent`() {
        EventNotifications.post(context, push(eventId = eventA))
        EventNotifications.post(context, push(eventId = eventB))

        // PendingIntents are matched WITHOUT comparing extras, so a shared
        // request code makes the second alert overwrite the first one's target
        // and both taps open the newest event: the caregiver taps the fall and
        // reads about the kettle.
        assertNotEquals(
            EventNotifications.notificationId(EventTap(eventA, patient)),
            EventNotifications.notificationId(EventTap(eventB, patient)),
        )
        assertEquals(2, posted().size)
    }

    @Test
    fun `a redelivery of the same event replaces its own notification`() {
        EventNotifications.post(context, push(eventId = eventA))
        EventNotifications.post(context, push(eventId = eventA))

        // FCM retries. A duplicate alert about one fall reads as two falls.
        assertEquals(1, posted().size)
    }

    // -----------------------------------------------------------------------
    // What is shown
    // -----------------------------------------------------------------------

    @Test
    fun `the server's own text is what appears`() {
        EventNotifications.post(context, push(title = "Fall detected", body = "In the kitchen"))

        val extras = posted().single().extras
        assertEquals("Fall detected", extras.getString(Notification.EXTRA_TITLE))
        assertEquals("In the kitchen", extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a push with no text of its own says nothing about anyone`() {
        EventNotifications.post(context, push(title = null, body = null))

        val extras = posted().single().extras
        // Fixed copy from strings.xml. The alternative — looking the patient up
        // in the cache to write a nicer line — is exactly the change that would
        // put a name on a lock screen, and it would be added by somebody trying
        // to be helpful.
        assertEquals("Memoria alert", extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Something needs your attention. Open Memoria to see it.",
            extras.getString(Notification.EXTRA_TEXT),
        )
    }

    @Test
    fun `no identifier reaches the visible text`() {
        EventNotifications.post(context, push(title = null, body = null))

        val extras = posted().single().extras
        val shown = "${extras.getString(Notification.EXTRA_TITLE)} " +
            "${extras.getString(Notification.EXTRA_TEXT)}"
        // The ids route the tap and are not something to read off a lock screen.
        assertTrue(shown.none { it.isDigit() })
        assertTrue(!shown.contains(eventA) && !shown.contains(patient))
    }

    @Test
    fun `the whole narrative is readable without opening the app`() {
        val long = "A fall was detected in the kitchen at 4:15 in the afternoon, " +
            "and there has been no movement since."
        EventNotifications.post(context, push(body = long))

        // Narratives run past one line and the useful part is usually at the
        // end. Without BigTextStyle the caregiver has to open the app to find
        // out what happened.
        assertEquals(long, posted().single().extras.getString(Notification.EXTRA_BIG_TEXT))
    }

    // -----------------------------------------------------------------------
    // How it behaves in the shade
    // -----------------------------------------------------------------------

    @Test
    fun `alerts are posted to the caregiver's alerts channel`() {
        EventNotifications.post(context, push())

        // Not the default channel. This is the row the caregiver can mute, and a
        // notification posted anywhere else escapes their own setting.
        assertEquals(NotificationChannels.alertsId(context), posted().single().channelId)
    }

    @Test
    fun `alerts about one person group together`() {
        EventNotifications.post(context, push(eventId = eventA))
        EventNotifications.post(context, push(eventId = eventB))

        // iOS groups by aps.thread-id, which the backend sets to the patient id.
        // Several alerts about one person collapse rather than burying
        // everything else in the shade.
        assertTrue(posted().all { it.group == patient })
    }

    @Test
    fun `tapping an alert clears it`() {
        EventNotifications.post(context, push())

        assertTrue(posted().single().flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `the small icon is the silhouette one, not the brand photo`() {
        EventNotifications.post(context, push())

        // Android throws away everything but the alpha channel here. The brand
        // mark PNG would come out as a solid white blob in the status bar.
        assertEquals(
            com.memoria.caregiver.R.drawable.ic_push_alert,
            posted().single().smallIcon.resId,
        )
    }

    @Test
    fun `posting works even if nothing created the channel first`() {
        manager.deleteNotificationChannel(NotificationChannels.alertsId(context))

        EventNotifications.post(context, push())

        // A notification posted to a channel that does not exist is silently
        // dropped on Android 8 and later. The service can run in a process where
        // Application.onCreate took a different path, so posting has to be able
        // to stand the channel up itself.
        assertNotNull(manager.getNotificationChannel(NotificationChannels.alertsId(context)))
        assertEquals(1, posted().size)
    }
}
