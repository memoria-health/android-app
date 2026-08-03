package com.memoria.caregiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.NoOpAnalytics
import com.memoria.caregiver.ui.auth.AuthViewModel
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.home.RosterViewModel
import com.memoria.caregiver.ui.nav.AuthNavHost
import com.memoria.caregiver.ui.events.EventsListViewModel
import com.memoria.caregiver.ui.nav.MainShell
import com.memoria.caregiver.ui.cameras.CameraListScreen
import com.memoria.caregiver.ui.cameras.CameraListViewModel
import com.memoria.caregiver.ui.patient.PatientShell
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.memoria.caregiver.ui.settings.AppSettingsViewModel
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.MemoriaTheme
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * The app's single activity.
 *
 * Two things here are conventions for every screen, not incidental:
 *
 * 1. **Edge-to-edge is enforced, not chosen.** At targetSdk 35, Android 15+
 *    ignores `android:statusBarColor` and draws under the system bars whether
 *    or not the app opts in. So it opts in explicitly and paints them itself.
 * 2. **Insets are consumed at the root** with `safeDrawingPadding()`, the
 *    Android equivalent of SwiftUI's implicit safe area that every ported
 *    screen assumes. A screen that deliberately bleeds under a bar — live video
 *    — opts out locally. See DECISIONS.md D-010.
 */
class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light scrims on both bars: the app is light-mode only, so dark system
        // icons on Paper is the only correct combination.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
        )
        super.onCreate(savedInstanceState)

        // applicationContext, not `this`: the environment outlives every
        // activity instance, and holding an activity would leak it across a
        // configuration change.
        val env = AppEnvironment(scope = appScope, appContext = applicationContext)
        val owner = this as ViewModelStoreOwner
        val authViewModel = ViewModelProvider(
            owner,
            viewModelFactory { initializer { AuthViewModel(env.auth) } },
        )[AuthViewModel::class.java]
        val rosterViewModel = ViewModelProvider(
            owner,
            viewModelFactory { initializer { RosterViewModel(env.api, env.auth) } },
        )[RosterViewModel::class.java]
        val settingsViewModel = ViewModelProvider(
            owner,
            viewModelFactory { initializer { AppSettingsViewModel(env.api, env.auth) } },
        )[AppSettingsViewModel::class.java]

        setContent {
            // The real analytics transport lands in Phase 8. Until then the
            // no-op sink keeps every component's internal logging call valid
            // without inventing a half-built pipeline.
            CompositionLocalProvider(LocalAnalytics provides NoOpAnalytics) {
                MemoriaTheme {
                    // Paper, not the Material `surface` role. Surface defaults to
                    // colorScheme.surface, which is Card — the RAISED card token.
                    // iOS backs the app with Paper and reserves Card for cards.
                    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                        AuthNavHost(
                            viewModel = authViewModel,
                            modifier = Modifier.safeDrawingPadding(),
                            signedInContent = {
                                MainShell(
                                    rosterViewModel = rosterViewModel,
                                    settingsViewModel = settingsViewModel,
                                    deepLink = env.deepLink,
                                    // The patient bundle lands next; until it
                                    // does this is an explicit placeholder
                                    // rather than a screen that half-works.
                                    patientContent = { patientId ->
                                        PatientRoute(
                                            env = env,
                                            patientId = patientId,
                                            resolvePhotoUrl = rosterViewModel::resolvePhotoUrl,
                                            onHome = { env.deepLink.closePatient() },
                                        )
                                    },
                                    onAddPatient = {},
                                    onEditProfile = {},
                                    onPrivacy = {},
                                    onContactSupport = {},
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resolves a patient id to the row and hosts [PatientShell].
 *
 * The lookup is against the observable cache rather than a fetch, so a Realtime
 * update to the patient reaches the header without this screen doing anything.
 * A null row means the id is not in the roster — a deep link to a patient the
 * caregiver no longer has access to — and the honest response is to leave
 * rather than to render a shell around nothing.
 */
@androidx.compose.runtime.Composable
private fun PatientRoute(
    env: AppEnvironment,
    patientId: String,
    resolvePhotoUrl: suspend (String) -> String?,
    onHome: () -> Unit,
) {
    val patients by env.api.patientsFlow.collectAsState()
    val patient = patients.firstOrNull { it.id == patientId }

    if (patient == null) {
        LaunchedEffect(patientId) { onHome() }
        return
    }

    val eventsViewModel: EventsListViewModel = viewModel(
        // Keyed on the patient, so switching patients does not carry the
        // previous one's feed, open event or poll across.
        key = "events:$patientId",
        factory = viewModelFactory {
            initializer { EventsListViewModel(env.api, patientId, env.deepLink) }
        },
    )

    val openEvent by eventsViewModel.openEvent.collectAsState()

    val camerasViewModel: CameraListViewModel = viewModel(
        key = "cameras:$patientId",
        factory = viewModelFactory { initializer { CameraListViewModel(env.api, patientId) } },
    )

    PatientShell(
        patient = patient,
        resolvePhotoUrl = resolvePhotoUrl,
        eventsViewModel = eventsViewModel,
        onHome = onHome,
        // Feedback, the three remaining tabs and the event detail are the next
        // slice. They are explicit stubs rather than half-built screens: a tab
        // that renders something plausible but inert is the exact failure
        // FINDINGS.md F-10 and H-12 are both about.
        onFeedback = {},
        // The single LiveKit teardown point, and it is deliberately empty
        // rather than absent. LiveKitClient exists and is tested but is not
        // in AppEnvironment yet, because nothing mints a token until the Live
        // tab is built. The SEAM is what matters now: iOS disconnects when
        // the patient cover is dismissed and never on a sub-tab switch, and
        // PatientShell already keys its DisposableEffect that way. When the
        // client lands, the disconnect goes here and nowhere else.
        onLeavePatient = { /* TODO(live-tab): env.liveKit.disconnect() */ },
        liveContent = { TabStub("Live") },
        camerasContent = {
            val cameraState by camerasViewModel.state.collectAsState()
            CameraListScreen(
                state = cameraState,
                // Camera detail and the add-sensor wizard are the next slice.
                // SensorSetupWizard step 2 is separately blocked on QUESTIONS.md
                // Q5 — Android has no equivalent of the captive portal iOS
                // relies on, so that interaction has to be designed, not ported.
                onSelectCamera = {},
                onAddSensor = {},
            )
        },
        // No PatientDetectorsScreen exists — it was never ported (FINDINGS.md
        // F-14). DetectorRow and DetectorDetailSheet are built; nothing composes
        // them into the tab's list.
        detectorsContent = { TabStub("Detectors") },
        settingsContent = { TabStub("Settings") },
    )

    // The detail sheet is not wired yet, so the open-event latch must still be
    // released or the Events feed poll stays paused forever (the ViewModel
    // pauses it while a detail is up).
    if (openEvent != null) {
        LaunchedEffect(openEvent) { eventsViewModel.closeEvent() }
    }
}

/** An unbuilt tab, saying so. */
@androidx.compose.runtime.Composable
private fun TabStub(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NemaraEyebrow(name)
        Text(text = "Not built yet", style = NemaraType.body, color = Ink)
    }
}
