package com.memoria.caregiver.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.memoria.caregiver.MainActivity
import com.memoria.caregiver.R

/**
 * Turns a received push into the thing the caregiver actually sees, and into a
 * tap that reaches the event.
 *
 * ## Nothing in here reads app state
 *
 * Every string that can reach the screen comes from the message itself or from
 * `strings.xml`. This class does not look up the patient, the event, the clip or
 * the caregiver, and it logs nothing at all. A notification is displayed on a
 * locked screen to whoever is holding the phone, and the way that goes wrong is
 * not a dramatic bug — it is one convenient lookup added later because the
 * fallback text felt thin.
 *
 * ## Why the tap goes through an Intent and not a direct call
 *
 * The router lives in `AppEnvironment`, which lives in the app process, which
 * may not exist: a push can arrive with the app closed and the process dead. An
 * `Intent` on a `PendingIntent` is the only handoff that survives that — the
 * system holds it, starts the process, and delivers the ids to
 * `MainActivity`. That is also why the ids travel as extras rather than being
 * stashed somewhere for the Activity to pick up: the system keeps them for us.
 */
internal object EventNotifications {

    /**
     * The intent a tap fires. Built here and read back by
     * `MainActivity.eventTapFrom`, which is the other half of this contract.
     *
     * `CLEAR_TOP` together with the manifest's `launchMode="singleTop"` is what
     * makes a tap reach the running app through `onNewIntent` instead of
     * stacking a second copy of it. Without singleTop, `CLEAR_TOP` alone would
     * destroy and recreate the Activity — losing whatever the caregiver had open
     * — so the two are one decision, not two.
     */
    fun tapIntent(context: Context, tap: EventTap): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PUSH_KEY_EVENT_ID, tap.eventId)
            putExtra(PUSH_KEY_PATIENT_ID, tap.patientId)
        }

    /**
     * The notification id, derived from the event id.
     *
     * Per event, so two alerts about two different events sit side by side, and
     * a redelivery of the same event replaces its own notification instead of
     * adding a duplicate. FCM retries, so redelivery is normal, not exceptional.
     */
    fun notificationId(tap: EventTap): Int = tap.eventId.hashCode()

    /** Builds and posts the notification. */
    fun post(context: Context, push: EventPush) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Belt as well as braces: the channel is created at app start, but this
        // service can run in a process where Application.onCreate has taken a
        // different path, and posting to a channel that does not exist is
        // silently dropped on Android 8+.
        NotificationChannels.ensure(context)

        val pending = PendingIntent.getActivity(
            context,
            // A per-event request code, which is load-bearing. PendingIntents
            // are matched WITHOUT comparing extras, so a shared request code
            // makes two notifications share one PendingIntent: with
            // FLAG_UPDATE_CURRENT the second alert overwrites the first one's
            // extras and both taps then open the newest event. The caregiver
            // taps the fall and reads about the kettle.
            notificationId(push.tap),
            tapIntent(context, push.tap),
            // IMMUTABLE because nothing outside this app has any business
            // rewriting the target; required from API 31 regardless.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = push.title ?: context.getString(R.string.push_fallback_title)
        val body = push.body ?: context.getString(R.string.push_fallback_body)

        val notification = Notification.Builder(context, NotificationChannels.alertsId(context))
            .setSmallIcon(R.drawable.ic_push_alert)
            .setColor(context.getColor(R.color.terra))
            .setContentTitle(title)
            .setContentText(body)
            // Narratives run past one line and the useful part is usually at the
            // end. BigTextStyle lets the expanded notification show all of it
            // without the caregiver having to open the app to find out what
            // happened.
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            // Tapping it takes you to the event, so leaving it in the shade
            // afterwards is just clutter.
            .setAutoCancel(true)
            // iOS groups by `aps.thread-id`, which the backend sets to the
            // patient id. This is the same grouping: several alerts about one
            // person collapse together rather than burying everything else.
            .setGroup(push.tap.patientId)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            // `visibility` is deliberately left at the platform default
            // (VISIBILITY_PRIVATE), which defers to the phone's own "sensitive
            // notification content on the lock screen" setting rather than
            // overriding it in either direction. Forcing content to be hidden
            // would diverge from iOS, which shows it; forcing it to be shown
            // would override a choice the caregiver already made for their
            // whole phone. What the server puts in that text is a product
            // question and is recorded in FINDINGS.md, not decided here.
            .build()

        manager.notify(notificationId(push.tap), notification)
    }
}
