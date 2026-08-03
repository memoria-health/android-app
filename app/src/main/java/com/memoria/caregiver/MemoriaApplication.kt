package com.memoria.caregiver

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.memoria.caregiver.push.FirebasePushTokens
import com.memoria.caregiver.push.NotificationChannels
import com.memoria.caregiver.push.PUSH_LOG_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns [AppEnvironment] for the life of the process, and ties it to the app's
 * foreground/background lifecycle.
 *
 * ## Why the environment cannot live in the Activity
 *
 * `AppEnvironment`'s own contract is "one instance, created at process start and
 * living until the process dies", and building it in `MainActivity.onCreate`
 * broke that in a way nothing surfaced. `ComponentActivity`'s `ViewModelStore`
 * survives a configuration change; `onCreate` does not. So changing the system
 * font size, the display size, the locale, or entering split-screen built a
 * **second** environment — new HTTP client, new Supabase Realtime socket, new
 * empty cache — and handed it to `MainShell`, while `ViewModelProvider` returned
 * the RETAINED ViewModels still holding the first one.
 *
 * The caregiver's symptom was being thrown out of a patient back to the roster.
 * Underneath, nothing cancelled the old scope, so each recreation left another
 * Realtime socket and another one-second camera poll running for the rest of the
 * process.
 *
 * iOS has no equivalent because `NemaraCaregiverApp.init` builds the environment
 * once into `@State`.
 *
 * ## Why the lifecycle wiring is here too
 *
 * `NemaraCaregiverApp.swift` drives four calls off `scenePhase`, and none of them
 * had a counterpart. That mattered most for the poll: iOS gets "stop polling" for
 * free because the OS suspends the process, and **Android does not**. The poll
 * was issuing one camera fetch per patient per second, indefinitely, in the
 * background, on cellular.
 */
class MemoriaApplication : Application() {

    /**
     * Cancelled only when the process dies, which is also when this object dies —
     * so there is deliberately no `cancel()` call anywhere. `Application` has no
     * reliable teardown callback (`onTerminate` never fires on a real device).
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val environmentDelegate = lazy {
        AppEnvironment(scope = appScope, appContext = this)
    }

    val environment: AppEnvironment by environmentDelegate

    /**
     * The environment **only if something has already built it**, and null
     * otherwise.
     *
     * Exists for exactly one caller: `MemoriaMessagingService`, which the system
     * can start in a cold process to hand over a push token. Reading
     * [environment] there would construct the Supabase client, the HTTP client,
     * the Realtime socket and the analytics worker in a process that exists for
     * a few hundred milliseconds to write down one string.
     */
    val environmentIfCreated: AppEnvironment?
        get() = if (environmentDelegate.isInitialized()) environmentDelegate.value else null

    override fun onCreate() {
        super.onCreate()
        // Before anything can post a notification, and deliberately before the
        // first one arrives: a caregiver who wants to see — or pre-emptively
        // silence — what this app is allowed to interrupt them with should find
        // the row in Settings on day one. Touches no Firebase and no
        // environment, so it is safe in the one callback that runs before
        // everything else. See NotificationChannels.
        NotificationChannels.ensure(this)
        // Deliberately does NOT touch `environment`. Reading it here would build
        // the Supabase client, the HTTP client and the Realtime socket inside
        // `Application.onCreate`, which is the one callback that runs before
        // anything else is ready — and it takes every Robolectric test down with
        // it, because Robolectric calls onCreate for each one. The first
        // lifecycle event is logged from the observer instead; see below.
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundObserver())
    }

    /**
     * Port of `NemaraCaregiverApp.swift`'s `.onChange(of: scenePhase)`.
     *
     * `ProcessLifecycleOwner` rather than the Activity's lifecycle, because these
     * are process-level concerns: a configuration change destroys and recreates
     * the Activity, and tearing the Realtime channels down and back up on a font
     * size change would be both wasteful and wrong.
     */
    private inner class ForegroundObserver : DefaultLifecycleObserver {

        /**
         * Whether this is the app coming up for the first time in this process,
         * rather than returning from the background.
         */
        private var isFirstForeground = true

        override fun onStart(owner: LifecycleOwner) {
            val env = environment
            // iOS logs app_launch from `NemaraCaregiverApp.init` and
            // app_foreground from the first `scenePhase == .active`, which are
            // milliseconds apart. Both are logged here, in that order, so a cold
            // launch produces the same pair on both platforms and a return from
            // the background produces app_foreground alone.
            //
            // app_launch is the denominator for every funnel in the app: without
            // it, "12 caregivers added a patient" has no "out of how many".
            if (isFirstForeground) {
                isFirstForeground = false
                env.analytics.logLifecycle("app_launch")
                // The counterpart of iOS calling registerForRemoteNotifications()
                // at launch. Needed on top of the service's onNewToken because
                // FCM announces a token only when it is created or rotated — on
                // an ordinary launch it says nothing at all, so this is the only
                // thing that keeps the stored token current.
                //
                // Reports rather than throws when no Firebase project is
                // configured, which is the state of this build today (REQ-7).
                FirebasePushTokens.fetch(
                    context = this@MemoriaApplication,
                    onToken = env.pushTokens::onToken,
                    onUnavailable = { reason -> Log.w(PUSH_LOG_TAG, reason) },
                )
            }
            env.analytics.logLifecycle("app_foreground")
            appScope.launch {
                // Order matters and is the Swift's. Re-read first, because the
                // socket may have been dead for the whole background stretch and
                // Realtime has no replay — anything that changed while away is
                // recovered here or not at all.
                env.api.resync()
                env.realtime.rebuildChannels()
                env.realtime.startStatePolling()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            val env = environment
            // Logged BEFORE the snapshot below, so app_background is itself part
            // of what gets written to disk.
            env.analytics.logLifecycle("app_background")
            // iOS calls this "the last moment before suspension". Here it is the
            // last callback of any kind: a backgrounded Android process can be
            // killed with no further notice, so anything still only in memory
            // when this returns is lost. Both halves are fire-and-forget.
            env.analytics.persistAndFlush()
            // The half iOS gets from process suspension. Without it the poll runs
            // forever in the background.
            env.realtime.stopStatePolling()
        }
    }
}
