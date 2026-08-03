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
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.memoria.caregiver.analytics.NoOpAnalytics
import com.memoria.caregiver.ui.components.LocalAvatarPhoto
import com.memoria.caregiver.ui.auth.AuthViewModel
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.home.RosterViewModel
import com.memoria.caregiver.ui.nav.AuthNavHost
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.memoria.caregiver.ui.events.EventDetailScreen
import com.memoria.caregiver.ui.events.EventDetailViewModel
import com.memoria.caregiver.ui.events.EventReviewScreen
import com.memoria.caregiver.ui.events.EventReviewState
import com.memoria.caregiver.ui.events.EventsListViewModel
import com.memoria.caregiver.ui.nav.MainShell
import com.memoria.caregiver.ui.cameras.CameraListScreen
import com.memoria.caregiver.ui.cameras.CameraListViewModel
import com.memoria.caregiver.ui.detectors.PatientDetectorsScreen
import com.memoria.caregiver.ui.detectors.PatientDetectorsViewModel
import com.memoria.caregiver.ui.patient.PatientShell
import com.memoria.caregiver.ui.patient.PatientViewModelScope
import androidx.compose.material3.ModalBottomSheet
import com.memoria.caregiver.ui.settings.DataCollectionConsentSheet
import com.memoria.caregiver.ui.settings.patientDisplayName
import com.memoria.caregiver.ui.settings.PatientSettingsScreen
import com.memoria.caregiver.ui.support.SupportContactScreen
import com.memoria.caregiver.ui.support.SupportContactViewModel
import com.memoria.caregiver.ui.settings.PatientSettingsViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.memoria.caregiver.ui.settings.AppSettingsViewModel
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.MemoriaTheme
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import androidx.compose.runtime.CompositionLocalProvider

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

    // ModalBottomSheet is still marked experimental in material3. Opted in at
    // the one place it is used (the data-collection consent sheet) rather than
    // module-wide, so the next experimental API someone reaches for still has to
    // be a deliberate choice.
    @androidx.compose.material3.ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light scrims on both bars: the app is light-mode only, so dark system
        // icons on Paper is the only correct combination.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
        )
        super.onCreate(savedInstanceState)

        // From the Application, NOT built here. A configuration change recreates
        // this Activity but retains the ViewModelStore, so building the
        // environment in onCreate handed the shells a fresh, empty environment
        // while the retained ViewModels kept the old one — and left the previous
        // Realtime socket and camera poll running. See MemoriaApplication.
        val env = (application as MemoriaApplication).environment
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
            // Contact support is a full-surface sheet over the signed-in app, so
            // the flag lives above both shells rather than inside either.
            var showSupport by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            // The real analytics transport lands in Phase 8. Until then the
            // no-op sink keeps every component's internal logging call valid
            // without inventing a half-built pipeline.
            CompositionLocalProvider(
                LocalAnalytics provides NoOpAnalytics,
                // REQ-22 / DIFFERENCES.md D8. The design system deliberately owns
                // no image dependency, so the one renderer is provided here, once,
                // and every avatar in the app picks it up. Until this existed,
                // every avatar showed its serif initial and no patient photo
                // rendered anywhere.
                LocalAvatarPhoto provides { url ->
                    AsyncImage(
                        model = url,
                        // Null rather than a description: the avatar's own
                        // semantics already name the patient, and "photo of
                        // Margaret Doyle" announced twice is worse than once.
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // iOS's .scaledToFill() — fill the circle and crop, never
                        // letterbox a face.
                        contentScale = ContentScale.Crop,
                    )
                },
            ) {
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
                                            onContactSupport = { showSupport = true },
                                        )
                                    },
                                    onAddPatient = {},
                                    onEditProfile = {},
                                    onPrivacy = {},
                                    onContactSupport = { showSupport = true },
                                )
                                if (showSupport) {
                                    val supportViewModel: SupportContactViewModel = viewModel(
                                        factory = viewModelFactory {
                                            initializer { SupportContactViewModel(env.api) }
                                        },
                                    )
                                    val supportState by supportViewModel.state.collectAsState()
                                    // `dismissed` covers both exits: Cancel, and
                                    // the short dwell after a successful send that
                                    // lets the caregiver read the confirmation.
                                    LaunchedEffect(supportState.dismissed) {
                                        if (supportState.dismissed) showSupport = false
                                    }
                                    SupportContactScreen(
                                        state = supportState,
                                        onSubjectChange = supportViewModel::onSubjectChange,
                                        onMessageChange = supportViewModel::onMessageChange,
                                        onSend = supportViewModel::send,
                                        onCancel = supportViewModel::cancel,
                                    )
                                }
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
@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
private fun PatientRoute(
    env: AppEnvironment,
    patientId: String,
    resolvePhotoUrl: suspend (String) -> String?,
    onHome: () -> Unit,
    onContactSupport: () -> Unit,
) {
    val patients by env.api.patientsFlow.collectAsState()
    val patient = patients.firstOrNull { it.id == patientId }

    if (patient == null) {
        LaunchedEffect(patientId) { onHome() }
        return
    }

    PatientViewModelScope(patientId) {
        PatientTabs(env, patient, patientId, resolvePhotoUrl, onHome, onContactSupport)
    }
}

/**
 * The five tabs and their ViewModels, inside the patient's own ViewModel store.
 *
 * Split out from [PatientRoute] only so `PatientViewModelScope` can provide the
 * store owner ABOVE every `viewModel(...)` call — a CompositionLocal only
 * reaches composables nested inside the provider.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
private fun PatientTabs(
    env: AppEnvironment,
    patient: com.memoria.caregiver.data.model.Patient,
    patientId: String,
    resolvePhotoUrl: suspend (String) -> String?,
    onHome: () -> Unit,
    onContactSupport: () -> Unit,
) {
    val eventsViewModel: EventsListViewModel = viewModel(
        // Keyed on the patient, so switching patients does not carry the
        // previous one's feed, open event or poll across.
        key = "events:$patientId",
        factory = viewModelFactory {
            initializer { EventsListViewModel(env.api, patientId, env.deepLink) }
        },
    )

    val openEvent by eventsViewModel.openEvent.collectAsState()
    val link by env.deepLink.state.collectAsState()

    val camerasViewModel: CameraListViewModel = viewModel(
        key = "cameras:$patientId",
        factory = viewModelFactory { initializer { CameraListViewModel(env.api, patientId) } },
    )
    val detectorsViewModel: PatientDetectorsViewModel = viewModel(
        key = "detectors:$patientId",
        factory = viewModelFactory { initializer { PatientDetectorsViewModel(env.api, patientId) } },
    )
    val patientSettingsViewModel: PatientSettingsViewModel = viewModel(
        key = "patientSettings:$patientId",
        factory = viewModelFactory {
            initializer {
                PatientSettingsViewModel(
                    api = env.api,
                    patientId = patientId,
                    auth = env.auth,
                    peopleBlur = env.peopleBlur,
                    onDeleted = onHome,
                )
            }
        },
    )

    // First-open data-collection consent. Presented from the shell, as iOS does
    // from PatientShell — not from the Settings tab — because it must appear
    // when the caregiver first opens the patient, wherever they land.
    val showConsent by patientSettingsViewModel.showDataCollectionConsent.collectAsState()
    val settingsStateForConsent by patientSettingsViewModel.state.collectAsState()
    if (showConsent) {
        ModalBottomSheet(
            // NOT onDecide(false). A swipe-away is the ABSENCE of an answer, and
            // recording "no" for someone who only dismissed the sheet is still
            // recording a decision they did not make. iOS sends nothing here
            // either: decided_at stays null and the server default stays off.
            onDismissRequest = patientSettingsViewModel::dismissDataCollectionConsent,
            containerColor = Paper,
            // The sheet draws the Swift's own capsule.
            dragHandle = null,
        ) {
            DataCollectionConsentSheet(
                patientName = patientDisplayName(settingsStateForConsent.patient),
                mutation = settingsStateForConsent.dataCollection,
                onDecide = patientSettingsViewModel::setDataCollectionEnabled,
            )
        }
    }

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
        onContactSupport = onContactSupport,
        // The single LiveKit teardown point, and it is deliberately empty
        // rather than absent. LiveKitClient exists and is tested but is not
        // in AppEnvironment yet, because nothing mints a token until the Live
        // tab is built. The SEAM is what matters now: iOS disconnects when
        // the patient cover is dismissed and never on a sub-tab switch, and
        // PatientShell already keys its DisposableEffect that way. When the
        // client lands, the disconnect goes here and nowhere else.
        onLeavePatient = { /* TODO(live-tab): env.liveKit.disconnect() */ },
        // Only when the router names an event for THIS patient. Without the
        // patient check, a stale focus id from another household would drag
        // this shell onto Events for no reason.
        hasFocusedEvent = link.openPatientId == patientId && link.focusEventId != null,
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
        detectorsContent = {
            val detectorState by detectorsViewModel.state.collectAsState()
            // The wandering-door configs are fetched on entry, not in init:
            // the tab may never be opened, and the request is per-camera.
            LaunchedEffect(detectorsViewModel) { detectorsViewModel.loadDoorConfigs() }
            PatientDetectorsScreen(
                state = detectorState,
                onToggleDetector = detectorsViewModel::toggleDetector,
                onDeleteDetector = detectorsViewModel::deleteDetector,
                onLoadDoorConfigs = detectorsViewModel::loadDoorConfigs,
                // Authoring and the library are presented flows; their
                // ViewModels exist and tested. Wiring them is the next slice.
                onAddCustomDetector = {},
                onOpenLibrary = {},
                onEditDetector = {},
            )
        },
        settingsContent = {
            val settingsState by patientSettingsViewModel.state.collectAsState()
            // The enrollment summary poll, run from the tab so it stops when the
            // caregiver leaves it — same reasoning as the events feed poll.
            LaunchedEffect(patientSettingsViewModel) {
                patientSettingsViewModel.runEnrollmentPoll()
            }
            PatientSettingsScreen(
                state = settingsState,
                resolvePhotoUrl = patientSettingsViewModel::resolvePhotoUrl,
                onRemovePhoto = patientSettingsViewModel::removePhoto,
                onEmailAlertsEnabledChange = patientSettingsViewModel::setEmailAlertsEnabled,
                onEmailDraftChange = patientSettingsViewModel::setEmailDraft,
                onEmailRecipientsChange = patientSettingsViewModel::setEmailRecipients,
                onEmailValidationError = patientSettingsViewModel::showEmailValidationError,
                onSendTestEmail = patientSettingsViewModel::sendTestEmail,
                onPeopleBlurChange = patientSettingsViewModel::setPeopleBlur,
                onAudioCaptureChange = patientSettingsViewModel::setAudioCapture,
                onDataCollectionChange = patientSettingsViewModel::setDataCollectionEnabled,
                onRemovePatient = patientSettingsViewModel::deletePatient,
                // Each of these PRESENTS something that is not built yet: a
                // photo picker, the enrollment flow, the feedback sheet.
                onPickPhoto = {},
                onEnroll = {},
                onOpenFeedback = {},
            )
        },
    )

    // The event stack: list -> detail -> review. Rendered over the shell rather
    // than as tabs, mirroring iOS's sheet from PatientEventsView.
    //
    // `closeEvent()` is what releases the events feed poll, which the ViewModel
    // pauses while a detail is up. Every exit from this stack goes through it.
    val event = openEvent
    if (event != null) {
        var reviewing by remember(event.id) { mutableStateOf(false) }

        if (reviewing) {
            var review by remember(event.id) { mutableStateOf(EventReviewState(event)) }
            EventReviewScreen(
                state = review,
                onChoiceChange = { review = review.copy(choice = it) },
                onNotesChange = { review = review.copy(notes = it) },
                onSubmit = {
                    // submitReview writes optimistically and nulls openEvent
                    // itself, so the whole stack closes and the poll resumes.
                    eventsViewModel.submitReview(event, review.choice, review.notes)
                },
                onBack = { reviewing = false },
            )
        } else {
            val detailViewModel: EventDetailViewModel = viewModel(
                key = "eventDetail:${'$'}{event.id}",
                factory = viewModelFactory {
                    initializer { EventDetailViewModel(env.api, patientId, event) }
                },
            )
            val detailState by detailViewModel.state.collectAsState()
            EventDetailScreen(
                state = detailState,
                onBack = eventsViewModel::closeEvent,
                onReviewEvent = { reviewing = true },
                // No video player is built yet. An explicit placeholder rather
                // than a blank box, so "this build cannot play clips" cannot be
                // mistaken for "this event has no clip" — the detail screen has
                // a real, separate empty state for the latter.
                clipPlayer = { ClipPlayerStub() },
            )
        }
    }
}

/** Stands in for the clip player until one is built. */
@androidx.compose.runtime.Composable
private fun ClipPlayerStub() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NemaraEyebrow("Clip")
        Text(text = "Playback is not built yet", style = NemaraType.body, color = Ink)
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
