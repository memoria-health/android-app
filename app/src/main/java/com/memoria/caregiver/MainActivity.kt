package com.memoria.caregiver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import com.memoria.caregiver.ui.components.LocalAvatarPhoto
import com.memoria.caregiver.ui.auth.AuthViewModel
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.home.RosterViewModel
import com.memoria.caregiver.data.AppConfig
import com.memoria.caregiver.ui.live.LiveHost
import com.memoria.caregiver.ui.live.LiveViewModel
import com.memoria.caregiver.ui.nav.AuthNavHost
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.key
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
import com.memoria.caregiver.ui.nav.ScopedViewModelStore
import com.memoria.caregiver.ui.nav.ModalOverlay
import com.memoria.caregiver.ui.nav.NotBuiltScreen
import com.memoria.caregiver.ui.cameras.AddCameraHost
import com.memoria.caregiver.ui.cameras.CameraDetailHost
import com.memoria.caregiver.ui.detectors.AddDetectorHost
import com.memoria.caregiver.ui.detectors.DetectorDetailHost
import com.memoria.caregiver.ui.detectors.DetectorLibraryHost
import com.memoria.caregiver.ui.detectors.DetectorLibraryMode
import com.memoria.caregiver.ui.onboarding.FaceEnrollmentHost
import com.memoria.caregiver.ui.onboarding.NewPatientHost
import com.memoria.caregiver.ui.onboarding.rememberEnrollmentPermission
import com.memoria.caregiver.push.EventTap
import com.memoria.caregiver.push.PUSH_KEY_EVENT_ID
import com.memoria.caregiver.push.PUSH_KEY_PATIENT_ID
import com.memoria.caregiver.push.eventPushFrom
import com.memoria.caregiver.ui.permissions.PermissionState
import com.memoria.caregiver.ui.permissions.rememberNotificationPermission
import com.memoria.caregiver.ui.settings.EditProfileHost
import com.memoria.caregiver.ui.settings.rememberPatientPhotoUpload
import com.memoria.caregiver.ui.support.FeedbackHost
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

    /**
     * Held as a field, not a local in [onCreate], because [onNewIntent] needs it:
     * a recovery link tapped while the app is already running arrives there, and
     * there is no composition to reach it through from a callback.
     */
    private lateinit var authViewModel: AuthViewModel

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
        authViewModel = ViewModelProvider(
            owner,
            viewModelFactory { initializer { AuthViewModel(env.auth) } },
        )[AuthViewModel::class.java]

        // Cold start: the app was not running and the recovery link is what
        // launched it. Only on a FIRST create — a configuration change re-runs
        // onCreate with the same intent, and the view model survives that and
        // already has the code, so handing it over again would spend a
        // single-use code twice and turn a working screen into "link expired".
        if (savedInstanceState == null) consumeRecoveryLink(intent)
        // Cold start from a notification tap: the app was not running and this
        // launch IS the tap. Same first-create guard as the recovery link, for a
        // slightly different reason — the router's target is durable, so
        // re-spending the intent after a configuration change would drag the
        // caregiver back to the event they had already navigated away from.
        if (savedInstanceState == null) consumeEventTap(intent)
        val rosterViewModel = ViewModelProvider(
            owner,
            viewModelFactory { initializer { RosterViewModel(env.api, env.auth) } },
        )[RosterViewModel::class.java]
        val settingsViewModel = ViewModelProvider(
            owner,
            viewModelFactory { initializer { AppSettingsViewModel(env.api, env.auth) } },
        )[AppSettingsViewModel::class.java]

        setContent {
            // Composed here, at the root, and NEVER fired here. Composing the
            // gate registers the launcher and prompts nobody; `request()` is
            // called from the taps that mean "start enrollment" — the identity
            // card's Enroll row. See RuntimePermission.kt and DIFFERENCES.md D13.
            val cameraPermission = rememberEnrollmentPermission()
            // Composed at the root, next to the camera gate and for the same
            // reason: the launcher must be registered before anything asks, and
            // registering it inside a screen means an in-flight result can be
            // lost when that screen leaves the composition. WHERE it is fired
            // from is a separate decision and lives in MainShell.
            val notificationPermission = rememberNotificationPermission()
            CompositionLocalProvider(
                // The real sink, from the Application-scoped environment, so the
                // buffer and its flush worker outlive this Activity — a
                // configuration change must not restart a session or drop what
                // is buffered. Every NemaraButton, NemaraToggle and screen in the
                // tree below logs through this.
                LocalAnalytics provides env.analytics,
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
                                // The three destinations the main shell hoists.
                                // Declared INSIDE this lambda, not beside
                                // `showSupport` above it: the lambda leaves the
                                // composition on sign-out, which discards these
                                // with it. Held outside, a caregiver who signed
                                // out with the new-patient form open would hand
                                // the next one that same form on sign-in — the
                                // shape of FINDINGS.md F-24, which `showSupport`
                                // still has.
                                // All four live INSIDE signedInContent, so signing
                                // out discards them. showSupport used to sit at the
                                // root, which meant a sheet left open across a
                                // sign-out would still be open for the next
                                // caregiver — the same shape as F-24.
                                var showSupport by remember { mutableStateOf(false) }
                                var showAddPatient by remember { mutableStateOf(false) }
                                var showEditProfile by remember { mutableStateOf(false) }
                                var showPrivacy by remember { mutableStateOf(false) }
                                MainShell(
                                    rosterViewModel = rosterViewModel,
                                    settingsViewModel = settingsViewModel,
                                    deepLink = env.deepLink,
                                    patientContent = { patientId ->
                                        PatientRoute(
                                            env = env,
                                            patientId = patientId,
                                            resolvePhotoUrl = rosterViewModel::resolvePhotoUrl,
                                            onHome = { env.deepLink.closePatient() },
                                            onContactSupport = { showSupport = true },
                                            cameraPermission = cameraPermission,
                                        )
                                    },
                                    onAddPatient = { showAddPatient = true },
                                    onEditProfile = { showEditProfile = true },
                                    onPrivacy = { showPrivacy = true },
                                    onContactSupport = { showSupport = true },
                                    onAskForNotifications = notificationPermission.request,
                                )
                                if (showAddPatient) ModalOverlay(screen = "new_patient") {
                                    NewPatientHost(
                                        api = env.api,
                                        // Handed down for the enrollment step at
                                        // the end of the post-create flow. The
                                        // gate is composed at the root and fires
                                        // only from a tap.
                                        cameraPermission = cameraPermission,
                                        onDismiss = { showAddPatient = false },
                                    )
                                }
                                if (showEditProfile) ModalOverlay(screen = "edit_profile") {
                                    val me by env.auth.currentCaregiver.collectAsState()
                                    EditProfileHost(
                                        caregiver = me,
                                        api = env.api,
                                        auth = env.auth,
                                        onDismiss = { showEditProfile = false },
                                    )
                                }
                                if (showPrivacy) ModalOverlay(screen = "privacy_policy") {
                                    // The one row in this task with nothing to
                                    // reach. iOS's `PrivacyPolicyView` is 280
                                    // lines of bundled policy text and has no
                                    // Android port at all — FINDINGS.md F-14
                                    // lists it as missing, not as unwired. The
                                    // text is a legal artefact and inventing an
                                    // Android copy of it would be worse than
                                    // saying it is not here yet.
                                    NotBuiltScreen(
                                        title = "Privacy & data",
                                        reason = "The privacy notice has not been " +
                                            "brought across to Android yet. It is " +
                                            "available in full at " +
                                            "dashboard.nemara-health.com/privacy.",
                                        onBack = { showPrivacy = false },
                                    )
                                }
                                if (showSupport) ModalOverlay(screen = "support_contact") { ScopedViewModelStore("support") {
                                    // Scoped, so closing the sheet CLEARS the
                                    // ViewModel. Activity-scoped, its `dismissed`
                                    // flag was never reset, so the second tap on
                                    // "Contact support" re-entered with
                                    // dismissed = true and the sheet closed in
                                    // the same frame — the row did nothing on
                                    // every tap after the first, for the life of
                                    // the process. It also kept the previous
                                    // caregiver's typed message across sign-out.
                                    // FINDINGS.md F-24.
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
                                    // Android's universal exit. Without it Back
                                    // — and the edge swipe, which is how most
                                    // people close a screen — fell through to
                                    // the Activity and QUIT THE APP mid-message.
                                    BackHandler { supportViewModel.cancel() }
                                    SupportContactScreen(
                                        state = supportState,
                                        onSubjectChange = supportViewModel::onSubjectChange,
                                        onMessageChange = supportViewModel::onMessageChange,
                                        onSend = supportViewModel::send,
                                        onCancel = supportViewModel::cancel,
                                    )
                                } }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Warm start: the app was already running and the caregiver tapped the
     * recovery link. Reaches this instance rather than starting a second copy of
     * the app only because the manifest declares `launchMode="singleTop"` — see
     * the comment there.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in step with what was actually delivered. Without
        // this the Activity still reports the intent it was launched with, and
        // anything that reads it later — including this class, after a
        // configuration change — would be reading a stale one.
        setIntent(intent)
        consumeRecoveryLink(intent)
        consumeEventTap(intent)
    }

    /**
     * Hand a notification tap to the router, which is what opens the patient and
     * focuses the event.
     *
     * The ids are stripped out of the intent as they are read, for the same
     * reason the recovery code is: the system keeps an Activity's intent, prints
     * it in bug reports and in `dumpsys activity`, and hands it back to a
     * re-created Activity. These two ids name a household and an incident in it.
     */
    private fun consumeEventTap(intent: Intent?) {
        val tap = eventTapFrom(intent) ?: return
        intent?.removeExtra(PUSH_KEY_EVENT_ID)
        intent?.removeExtra(PUSH_KEY_PATIENT_ID)
        // Read from the Application rather than a field: onNewIntent can run
        // before onCreate's local is in scope, and the environment is
        // process-scoped anyway.
        (application as MemoriaApplication).environment.deepLink.bufferEventTap(
            eventId = tap.eventId,
            patientId = tap.patientId,
        )
    }

    /**
     * Hand a recovery link to [AuthViewModel], which opens the reset screen over
     * whatever is on screen. Anything that is not a recovery link is left alone.
     */
    private fun consumeRecoveryLink(intent: Intent?) {
        val code = recoveryCodeFrom(intent) ?: return
        // Take the code back out of the intent as it is read. The system keeps
        // an Activity's intent and prints it in bug reports and `dumpsys
        // activity`, and a re-created Activity is handed it again — none of
        // which should be true of a credential that has now been consumed.
        intent?.data = null
        authViewModel.onRecoveryLink(code)
    }
}

/**
 * The event a notification tap is aimed at, or null for every other intent.
 *
 * The other half of the contract `EventNotifications.tapIntent` writes, kept as
 * a pure function of the intent for the same two reasons `recoveryCodeFrom`
 * below is: it can be tested without an Activity, and the one place that decides
 * "this is a notification tap" is readable in full.
 *
 * The ids are re-validated here rather than trusted. They came back through the
 * system, and any app can start an exported Activity with any extras it likes —
 * this one is exported, because the recovery link requires it. A malformed id
 * would otherwise be routed as a real target and land the caregiver on an empty
 * screen.
 */
internal fun eventTapFrom(intent: Intent?): EventTap? {
    val extras = intent?.extras ?: return null
    return eventPushFrom(
        mapOf(
            PUSH_KEY_EVENT_ID to (extras.getString(PUSH_KEY_EVENT_ID) ?: return null),
            PUSH_KEY_PATIENT_ID to (extras.getString(PUSH_KEY_PATIENT_ID) ?: return null),
        ),
    )?.tap
}

/**
 * The recovery code carried by a `nemara://reset-password?code=…` link, or null
 * for every other intent.
 *
 * A pure function of the intent so the parsing can be tested without an
 * Activity, and so the one place that decides "this is a recovery link" is
 * readable in full. iOS does the same three checks in
 * `NemaraCaregiverApp.handleIncomingURL`: scheme, host, then the `code` query
 * item, dropping the open when there is no code at all.
 *
 * An empty `?code=` is passed through rather than dropped, matching iOS. The
 * exchange then fails and the screen says the link is invalid, which is a truer
 * answer to a broken link than a tap that appears to do nothing.
 */
internal fun recoveryCodeFrom(intent: Intent?): String? {
    val data = intent?.data ?: return null
    if (data.scheme != "nemara" || data.host != "reset-password") return null
    return data.getQueryParameter("code")
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
    /**
     * The camera gate for face enrollment, composed once at the root. Passed
     * down rather than re-composed here so a patient switch cannot lose an
     * in-flight permission result.
     */
    cameraPermission: PermissionState,
) {
    val patients by env.api.patientsFlow.collectAsState()
    val patient = patients.firstOrNull { it.id == patientId }

    if (patient == null) {
        LaunchedEffect(patientId) { onHome() }
        return
    }

    ScopedViewModelStore("patient:" + patientId) {
        PatientTabs(
            env = env,
            patient = patient,
            patientId = patientId,
            resolvePhotoUrl = resolvePhotoUrl,
            onHome = onHome,
            onContactSupport = onContactSupport,
            cameraPermission = cameraPermission,
        )
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
    cameraPermission: PermissionState,
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

    val liveViewModel: LiveViewModel = viewModel(
        // Keyed on the patient, like every other patient-scoped ViewModel here.
        // A constant key would hand patient B the ViewModel that is still
        // connected to patient A's room — audio from one household playing under
        // another household's tiles. FINDINGS.md F-23 is the same mistake made
        // with a string template that did not interpolate.
        key = "live:$patientId",
        factory = viewModelFactory {
            initializer {
                LiveViewModel(
                    patientId = patientId,
                    api = env.api,
                    liveKit = env.liveKit,
                    // Read at mint time, not captured: a token minted for a
                    // caregiver who has since signed out is a token for the
                    // wrong household.
                    caregiverId = { env.auth.currentCaregiver.value?.id },
                    ackStore = env.sensitiveRoomAcks,
                    peopleBlur = env.peopleBlur,
                    clipCaptureEnabled = AppConfig.enableClipCapture,
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
        // iOS: `.trackScreen("data_collection_consent")` on the sheet in
        // PatientShell. Tracked here, at the presentation point, rather than
        // inside the sheet — same rule as ModalOverlay (HARDENING.md H-10). It
        // was missing entirely, which meant we could see how many caregivers
        // answered the consent prompt but not how many were ever shown it
        // (FINDINGS.md F-28). For a consent step under DPDP that gap is the
        // difference between "declined" and "never asked".
        //
        // The label is a fixed string. The patient this consent is about is
        // named on the sheet but never in the event.
        val consentAnalytics = LocalAnalytics.current
        LaunchedEffect(Unit) { consentAnalytics.logScreen("data_collection_consent") }
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

    // The patient-scoped destinations, all of them presented over the shell as
    // iOS presents them over its own. One value rather than six booleans: they
    // are mutually exclusive, and six flags admit states that cannot be drawn.
    var destination by remember(patientId) {
        mutableStateOf<PatientDestination>(PatientDestination.None)
    }

    PatientShell(
        patient = patient,
        resolvePhotoUrl = resolvePhotoUrl,
        eventsViewModel = eventsViewModel,
        onHome = onHome,
        // The header pill, on every one of the five sub-tabs. iOS keeps a single
        // presentation point on PatientShell for exactly the same reason.
        onFeedback = { destination = PatientDestination.Feedback },
        onContactSupport = onContactSupport,
        // The single LiveKit teardown point. iOS disconnects when the patient
        // cover is dismissed and never on a sub-tab switch, and PatientShell
        // keys its DisposableEffect that way — which is what makes
        // Live → Events → Live resume instantly instead of re-joining the room
        // every time. The call is ended earlier and separately, by LiveHost;
        // see LiveViewModel's class comment for why those are two moments.
        onLeavePatient = liveViewModel::onClosePatient,
        // Only when the router names an event for THIS patient. Without the
        // patient check, a stale focus id from another household would drag
        // this shell onto Events for no reason.
        hasFocusedEvent = link.openPatientId == patientId && link.focusEventId != null,
        liveContent = {
            LiveHost(
                viewModel = liveViewModel,
                // The same destination the Sensors tab pushes, so a camera
                // opened from a tile and one opened from the list are one
                // screen with one state.
                onOpenCameraDetail = { destination = PatientDestination.Camera(it) },
            )
        },
        camerasContent = {
            val cameraState by camerasViewModel.state.collectAsState()
            CameraListScreen(
                state = cameraState,
                onSelectCamera = { destination = PatientDestination.Camera(it) },
                // The sheet is reachable and its phone tab is complete. Its
                // sensor tab's step 2 is still blocked on QUESTIONS.md Q5 —
                // Android has no equivalent of the captive portal iOS relies on
                // — and says so on screen rather than rendering an inert form.
                onAddSensor = { destination = PatientDestination.AddSensor },
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
                onAddCustomDetector = { destination = PatientDestination.AddDetector },
                onOpenLibrary = { destination = PatientDestination.DetectorLibrary },
                onEditDetector = { destination = PatientDestination.EditDetector(it) },
            )
        },
        settingsContent = {
            val settingsState by patientSettingsViewModel.state.collectAsState()
            // The enrollment summary poll, run from the tab so it stops when the
            // caregiver leaves it — same reasoning as the events feed poll.
            LaunchedEffect(patientSettingsViewModel) {
                patientSettingsViewModel.runEnrollmentPoll()
            }
            val pickPhoto = rememberPatientPhotoUpload(patientSettingsViewModel)
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
                onPickPhoto = pickPhoto,
                onEnroll = {
                    // The tap IS the deliberate action the permission gate waits
                    // for, so the request goes here and not inside the enrollment
                    // screen. Composing that screen instead would ambush anyone
                    // who merely reached it. DIFFERENCES.md D13.
                    cameraPermission.request()
                    destination = PatientDestination.Enroll
                },
                onOpenFeedback = { destination = PatientDestination.Feedback },
            )
        },
    )

    // The patient-scoped stack, drawn over the shell exactly as iOS's sheets and
    // covers are. Every branch owns a BackHandler through its host or through
    // NotBuiltScreen; none of them may let Back reach the Activity.
    when (val open = destination) {
        PatientDestination.None -> Unit

        PatientDestination.Feedback -> ModalOverlay(screen = "feedback") {
            FeedbackHost(
                api = env.api,
                onDismiss = { destination = PatientDestination.None },
            )
        }

        is PatientDestination.Camera -> ModalOverlay(screen = "camera_detail") {
            CameraDetailHost(
                api = env.api,
                patientId = patientId,
                cameraId = open.cameraId,
                onBack = { destination = PatientDestination.None },
                // The app's single room instance. The door picker reuses it
                // when the Live tab already has it up, rather than opening a
                // second connection to the same room.
                liveKit = env.liveKit,
                caregiverId = { env.auth.currentCaregiver.value?.id },
            )
        }

        PatientDestination.AddSensor -> ModalOverlay(screen = "add_camera") {
            AddCameraHost(
                api = env.api,
                patientId = patientId,
                onDismiss = { destination = PatientDestination.None },
            )
        }

        PatientDestination.AddDetector -> ModalOverlay(screen = "add_detector") {
            AddDetectorHost(
                api = env.api,
                patientId = patientId,
                onDismiss = { destination = PatientDestination.None },
            )
        }

        PatientDestination.DetectorLibrary -> ModalOverlay(screen = "detector_library") {
            DetectorLibraryHost(
                api = env.api,
                patientId = patientId,
                mode = DetectorLibraryMode.Add,
                onFinished = { destination = PatientDestination.None },
                // Add-mode's leading button is Cancel, which just closes.
                onLeading = { destination = PatientDestination.None },
            )
        }

        is PatientDestination.EditDetector -> ModalOverlay(screen = "detector_detail") {
            DetectorDetailHost(
                api = env.api,
                patientId = patientId,
                detector = open.detector,
                onDismiss = { destination = PatientDestination.None },
            )
        }

        PatientDestination.Enroll -> ModalOverlay(screen = "face_enrollment") {
            FaceEnrollmentHost(
                api = env.api,
                patientId = patientId,
                permission = cameraPermission,
                // Skip, Done and a successful upload all land here, as iOS's
                // single `onComplete` does. The identity card behind refreshes
                // itself: the summary poll restarts when the tab comes back.
                onComplete = { destination = PatientDestination.None },
            )
        }
    }

    // The event stack: list -> detail -> review. Rendered over the shell rather
    // than as tabs, mirroring iOS's sheet from PatientEventsView.
    //
    // `closeEvent()` is what releases the events feed poll, which the ViewModel
    // pauses while a detail is up. Every exit from this stack goes through it.
    val event = openEvent
    // key() on the event id so that swapping the presented event in place — a
    // notification tap arriving while a detail is already open — is a NEW
    // composition rather than a recomposition of the same one. Without it the
    // overlay's screen tracking, which fires once per composition, would log the
    // first event opened and then go quiet for the rest of the session.
    if (event != null) key(event.id) {
        var reviewing by remember(event.id) { mutableStateOf(false) }

        if (reviewing) {
            var review by remember(event.id) { mutableStateOf(EventReviewState(event)) }
            // Back returns to the detail, not out of the app. Typed notes
            // survive it, and survive a configuration change.
            BackHandler { reviewing = false }
            ModalOverlay(screen = "event_review") { EventReviewScreen(
                state = review,
                onChoiceChange = { review = review.copy(choice = it) },
                onNotesChange = { review = review.copy(notes = it) },
                onSubmit = {
                    // submitReview writes optimistically and nulls openEvent
                    // itself, so the whole stack closes and the poll resumes.
                    eventsViewModel.submitReview(event, review.choice, review.notes)
                },
                onBack = { reviewing = false },
            ) }
        } else {
            val detailViewModel: EventDetailViewModel = viewModel(
                // Concatenated, not interpolated. This was written as
                // "eventDetail:${'$'}{event.id}" — a literal dollar followed by
                // literal text, so the key was a CONSTANT and every event after
                // the first got the first event's ViewModel: the caregiver read
                // one event's narrative and clip while filing a review against
                // another. FINDINGS.md F-23.
                key = "eventDetail:" + event.id,
                factory = viewModelFactory {
                    initializer { EventDetailViewModel(env.api, patientId, event) }
                },
            )
            val detailState by detailViewModel.state.collectAsState()
            BackHandler { eventsViewModel.closeEvent() }
            ModalOverlay(screen = "event_detail") { EventDetailScreen(
                state = detailState,
                onBack = eventsViewModel::closeEvent,
                onReviewEvent = { reviewing = true },
                // No video player is built yet. An explicit placeholder rather
                // than a blank box, so "this build cannot play clips" cannot be
                // mistaken for "this event has no clip" — the detail screen has
                // a real, separate empty state for the latter.
                clipPlayer = { ClipPlayerStub() },
            ) }
        }
    }
}

/**
 * Where the patient bundle is, on top of its five tabs.
 *
 * One value rather than six booleans. iOS can afford six `@State` flags because
 * SwiftUI serialises its own presentations; here two flags true at once would
 * draw two full-surface screens over each other, and the type makes that
 * unrepresentable.
 *
 * The event stack is deliberately NOT in here: it is driven by
 * `EventsListViewModel.openEvent`, which the deep-link router can set from
 * outside, and folding it in would give that router a second way in.
 */
private sealed interface PatientDestination {

    data object None : PatientDestination

    /** The feedback form. Reached from the header pill and the Settings row. */
    data object Feedback : PatientDestination

    /** One sensor's detail. */
    data class Camera(val cameraId: String) : PatientDestination

    /** The add-a-sensor sheet. */
    data object AddSensor : PatientDestination

    data object AddDetector : PatientDestination

    data object DetectorLibrary : PatientDestination

    /**
     * The detector editor. Carries the row rather than its id: the sheet opens
     * on the detector the caregiver tapped and re-reads the cache itself, so
     * looking the id back up here would only add a way to fail.
     */
    data class EditDetector(
        val detector: com.memoria.caregiver.data.model.Detector,
    ) : PatientDestination

    /** Face enrollment. No consent step — that one belongs to a new patient. */
    data object Enroll : PatientDestination
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
