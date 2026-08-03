package com.memoria.caregiver.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.memoria.caregiver.eventTapFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The round trip: the intent a notification tap fires, read back the way
 * `MainActivity` reads it.
 *
 * The two halves are written in different files and neither can be wrong on its
 * own — the extras only have to agree with each other. This is the test that
 * makes them agree, by taking the real intent from `EventNotifications` and
 * feeding it to the real parser.
 */
@RunWith(RobolectricTestRunner::class)
class EventTapIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val event = "3f1b8b1e-2b7c-4c2e-9c1a-6f0f2d4a8b31"
    private val patient = "9a2c7d40-1f3e-4b55-8a6d-0c5e7b21d904"

    @Test
    fun `an intent written by the notification is read back by the activity`() {
        val intent = EventNotifications.tapIntent(context, EventTap(event, patient))

        assertEquals(EventTap(event, patient), eventTapFrom(intent))
    }

    @Test
    fun `the launcher intent is not a notification tap`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        assertNull(eventTapFrom(launcher))
    }

    @Test
    fun `a recovery link is not a notification tap`() {
        val recovery = Intent(Intent.ACTION_VIEW, Uri.parse("nemara://reset-password?code=abc"))

        // The two deep-link paths share an Activity and must not read each
        // other's intents.
        assertNull(eventTapFrom(recovery))
    }

    @Test
    fun `a null intent is not a notification tap`() {
        assertNull(eventTapFrom(null))
    }

    @Test
    fun `half a target is dropped`() {
        val half = Intent().putExtra(PUSH_KEY_EVENT_ID, event)

        assertNull(eventTapFrom(half))
    }

    @Test
    fun `a fabricated id is rejected rather than routed`() {
        // MainActivity is exported — the recovery link requires it — so any app
        // on the phone can start it with any extras it likes. Re-validating here
        // is what stops a made-up id becoming a route to an empty screen.
        val forged = Intent()
            .putExtra(PUSH_KEY_EVENT_ID, "../../etc/passwd")
            .putExtra(PUSH_KEY_PATIENT_ID, patient)

        assertNull(eventTapFrom(forged))
    }
}
