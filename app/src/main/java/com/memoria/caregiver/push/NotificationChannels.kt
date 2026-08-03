package com.memoria.caregiver.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.memoria.caregiver.R

/**
 * The app's notification channels. **iOS has no counterpart to this file.**
 *
 * On iOS every notification is one undifferentiated stream and the only control
 * the caregiver has is the app-wide switch in Settings. Android makes the app
 * declare its own categories, and each one becomes a separate row the caregiver
 * can silence, demote or turn off. So the channels chosen here ARE the mute
 * controls, and their names and descriptions are read by a person, in Settings,
 * at the moment they are deciding whether to switch alerts off. They are product
 * copy, not identifiers.
 *
 * ## Why exactly one channel
 *
 * The temptation is two — "urgent" for falls and "everyday" for the rest — so a
 * caregiver could mute the routine ones and keep the ones that matter. That is
 * not buildable today: the backend's push payload
 * (`nemara-backend/src/services/push.py:build_event_payload`) carries a title, a
 * body and two ids, and nothing that says how urgent the event is. Splitting on
 * a signal that is not in the payload would mean guessing from the title text,
 * and a fall alert routed to the quiet channel because the wording changed is a
 * worse failure than having no split at all.
 *
 * One channel is therefore the honest shape today, and the thing that would
 * unlock a second is a severity field in the payload, not more client code.
 *
 * ## Why the id lives in strings.xml
 *
 * The manifest has to name the same channel, so that a push sent as an FCM
 * *notification* message — which the system displays without ever calling our
 * service — lands here instead of in Android's unnamed "Miscellaneous" bucket.
 * Two places naming one channel by hand is exactly the kind of pair that drifts
 * silently, so both read one string resource and a test asserts they agree.
 */
internal object NotificationChannels {

    /** The channel every event alert is posted to. */
    fun alertsId(context: Context): String = context.getString(R.string.push_channel_alerts_id)

    /**
     * Creates the channel if it does not exist yet. Cheap and idempotent, so it
     * is called both at app start and again before posting.
     *
     * Called at app start rather than only before the first notification for a
     * plain reason: a caregiver who wants to check what this app is allowed to
     * do — or to pre-emptively silence it — should find the row in Settings on
     * day one, not only after the first alert has already gone off.
     *
     * Android ignores every field but the name and description once the channel
     * exists, so importance and vibration cannot be changed by editing this
     * code; that is the platform handing the setting to the caregiver, and it is
     * why the values below are worth getting right the first time.
     */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = alertsId(context)
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(
            id,
            context.getString(R.string.push_channel_alerts_name),
            // HIGH is what produces a sound and a banner over whatever is on
            // screen. It is the match for what iOS asks for — APNs priority 10
            // plus `[.banner, .sound, .list]` in the foreground — and it is the
            // right level for the thing being reported: an alert about a person
            // that arrives minutes late is not an alert.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.push_channel_alerts_description)
            enableVibration(true)
            // The dot on the launcher icon. Cheap, and it is the only trace left
            // once a caregiver swipes a notification away without opening it.
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
