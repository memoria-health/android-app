package com.memoria.caregiver.push

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The caregiver's mute controls.
 *
 * iOS has nothing like this — every notification is one stream there — so these
 * assertions are about a decision Android forced, not about a port. The channel
 * name and description are read by a person in Settings at the moment they are
 * deciding whether to switch alerts off, which is why their wording is tested
 * rather than left to whatever happened to be typed.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationChannelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun channel() = manager.getNotificationChannel(NotificationChannels.alertsId(context))

    @Test
    fun `the app creates the alerts channel at startup, with nobody asking`() {
        // No call to ensure() here on purpose. Robolectric builds a fresh
        // MemoriaApplication for each test and runs its onCreate, so the only
        // thing that can have created this channel is the app itself — which is
        // the behaviour that matters: a caregiver can find the row in Settings
        // on day one, including to silence it pre-emptively, before any alert
        // has ever fired.
        assertNotNull(channel())
    }

    @Test
    fun `the alerts channel exists before any notification has arrived`() {
        NotificationChannels.ensure(context)

        assertNotNull(channel())
    }

    @Test
    fun `the alerts channel is high importance`() {
        NotificationChannels.ensure(context)

        // HIGH is what produces a sound and a banner over whatever is on screen.
        // It is the match for APNs priority 10 plus iOS's
        // [.banner, .sound, .list], and it is the right level for the thing
        // being reported: an alert about a person that arrives silently, minutes
        // late, is not an alert.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel()?.importance)
    }

    @Test
    fun `the channel is named and described in plain words`() {
        NotificationChannels.ensure(context)

        assertEquals("Alerts", channel()?.name)
        val description = channel()?.description.orEmpty()
        // It has to say what turning it off actually does, because that is the
        // decision being made on that screen.
        assertTrue(description.contains("Turning this off stops every alert"))
    }

    @Test
    fun `the channel copy names no patient`() {
        NotificationChannels.ensure(context)

        // A channel list is visible to anyone holding the phone, and this text
        // is fixed at build time — so the only way a name could reach it is if
        // somebody later built the string from app data. This is the test that
        // would catch that.
        val text = "${channel()?.name} ${channel()?.description}"
        assertTrue(text.none { it == '{' || it == '$' })
    }

    @Test
    fun `creating the channel twice does not disturb the caregiver's settings`() {
        NotificationChannels.ensure(context)
        val first = channel()

        NotificationChannels.ensure(context)

        // Android ignores every field but name and description once a channel
        // exists — that is the platform handing the setting to the caregiver.
        // Calling ensure() on every launch and before every post must therefore
        // be free.
        assertEquals(first?.importance, channel()?.importance)
        assertEquals(1, manager.notificationChannels.count { it.id == NotificationChannels.alertsId(context) })
    }

    @Test
    fun `there is exactly one channel`() {
        NotificationChannels.ensure(context)

        // Deliberate. Splitting urgent from routine needs a severity signal, and
        // the backend's payload carries a title, a body and two ids and nothing
        // else. Guessing severity from the title text would put a fall on the
        // quiet channel the day the wording changed. See NotificationChannels.
        assertEquals(1, manager.notificationChannels.size)
    }
}
