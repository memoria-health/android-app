package com.memoria.caregiver.ui.patient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.memoria.caregiver.ui.support.SupportButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.nav.DetectorsTabGlyph
import com.memoria.caregiver.ui.nav.EventsTabGlyph
import com.memoria.caregiver.ui.nav.LiveTabGlyph
import com.memoria.caregiver.ui.nav.SensorsTabGlyph
import com.memoria.caregiver.ui.nav.SettingsTabGlyph
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.events.EventsListScreen
import com.memoria.caregiver.ui.events.EventsListViewModel
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim

/**
 * The patient bundle: five tabs under a pinned [PatientHeader]. Port of
 * `Features/Patient/PatientShell.swift`.
 *
 * ## The LiveKit teardown point, which is the thing to get right
 *
 * iOS disconnects the room from `.onDisappear` on the OUTER `TabView`, so it
 * fires when the whole patient cover is dismissed and **not** when the
 * caregiver moves between Live, Events and Sensors. That is what keeps the room
 * alive across sub-tab switches. Compose gives no equivalent for free: a
 * `DisposableEffect` inside a tab would tear the room down on every tab tap,
 * and the caregiver would watch the video reconnect each time they checked the
 * event list.
 *
 * So the teardown is a `DisposableEffect` keyed on the SHELL, not on the tab —
 * see [onLeavePatient] below. Moving it inside the `when` is the single easiest
 * way to break live video here.
 *
 * ## Why the header is outside the tab content
 *
 * iOS builds each tab as `VStack { PHeader; content }` so the header stays put
 * while the content scrolls. Same here: the header is drawn once, above the
 * `when`, rather than by each tab. A tab that forgot to draw it would otherwise
 * lose the only indication of whose data is on screen.
 */
@Composable
fun PatientShell(
    patient: Patient,
    resolvePhotoUrl: suspend (String) -> String?,
    eventsViewModel: EventsListViewModel,
    onHome: () -> Unit,
    onFeedback: () -> Unit,
    /**
     * Disconnects the LiveKit room. Called exactly once, when the caregiver
     * leaves the patient — never on a sub-tab switch.
     */
    onLeavePatient: () -> Unit,
    /**
     * True when the deep-link router is pointing at an event in THIS patient.
     * Steers the shell to the Events tab.
     *
     * Passed as a resolved boolean rather than the router, so the shell stays a
     * pure function of what it is told and can be exercised without one.
     */
    hasFocusedEvent: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * Re-applied here because the patient bundle covers the main shell, which
     * hides that shell's overlay. iOS re-applies `.supportButtonOverlay()` on
     * `PatientShell` for exactly this reason.
     */
    onContactSupport: () -> Unit = {},
    liveContent: @Composable () -> Unit,
    camerasContent: @Composable () -> Unit,
    detectorsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(PatientTab.LIVE) }
    val analytics = LocalAnalytics.current

    // Keyed on Unit, so it survives every tab change and runs only when the
    // whole shell leaves composition. See the class comment.
    DisposableEffect(Unit) {
        onDispose { onLeavePatient() }
    }

    // Android's universal exit, wired to the same place as the Home button.
    // Without it, Back — and the edge swipe most people use to leave a screen —
    // fell through to the Activity and quit the app from inside a patient.
    // iOS has no equivalent gesture, so nothing in the port suggested it was
    // needed. FINDINGS.md F-25.
    BackHandler { onHome() }

    // A notification tap opens the patient AND names an event, so the shell must
    // land on Events rather than on Live. iOS does this with
    // `.onChange(of: focusEventId, initial: true)`; `initial = true` is the cold
    // launch, where the id is already set by the time the shell first appears —
    // which is why this is a LaunchedEffect that also runs on first composition
    // rather than only on change.
    //
    // It steers and nothing more. The events screen is what consumes the id and
    // clears it, so this must not read it as "handled".
    LaunchedEffect(hasFocusedEvent) {
        if (hasFocusedEvent) tab = PatientTab.EVENTS
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Paper,
        bottomBar = {
            NavigationBar(containerColor = Paper, contentColor = Ink) {
                PatientTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = {
                            tab = entry
                            analytics.logScreen(entry.analyticsName)
                        },
                        icon = {
                            Icon(
                                imageVector = entry.glyph,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = { Text(entry.title, style = NemaraType.caption) },
                        colors = NavigationBarItemDefaults.colors(
                            // Terra, because `NemaraCaregiverApp` applies
                            // `.tint(NemaraColor.terra)` and SwiftUI tints the
                            // SELECTED tab item with it. Android was using Ink,
                            // so the active tab was the same colour as the
                            // inactive ones and only the pill distinguished it.
                            selectedIconColor = Terra,
                            selectedTextColor = Terra,
                            unselectedIconColor = Ink3,
                            unselectedTextColor = Ink3,
                            // Transparent: iOS draws no pill behind the active
                            // item, and Material3 adds one by default. The
                            // colour IS the selection indicator on both now.
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            PatientHeader(
                patient = patient,
                resolvePhotoUrl = resolvePhotoUrl,
                onHome = onHome,
                // iOS hides the identity block on Settings, where the profile
                // card immediately below already shows it.
                showsIdentity = tab != PatientTab.SETTINGS,
                onFeedback = onFeedback,
            )

            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    PatientTab.LIVE -> liveContent()

                    PatientTab.EVENTS -> {
                        val state by eventsViewModel.state.collectAsState()

                        // The 20-second quiet poll. Launched HERE rather than in
                        // the ViewModel's init, so it runs only while the Events
                        // tab is on screen — started in init it would poll from
                        // behind the Live tab, which is the reliability net
                        // firing where nobody can see it and the battery cost
                        // landing where nobody asked for it.
                        // Keyed on the ViewModel, not on Unit. A running poll
                        // captures the instance it started with, so a keyed-on-
                        // Unit effect would keep refreshing the PREVIOUS
                        // patient's feed after a switch and never refresh the
                        // new one. Belt and braces with MainShell's key() —
                        // this one also covers a ViewModel swapped under a
                        // shell that did not leave composition.
                        LaunchedEffect(eventsViewModel) { eventsViewModel.runFeedPoll() }

                        EventsListScreen(
                            state = state,
                            onFilterChange = eventsViewModel::setFilter,
                            onEventTap = eventsViewModel::onEventTap,
                            onDismissReviewError = eventsViewModel::dismissReviewError,
                        )
                    }

                    PatientTab.CAMERAS -> camerasContent()
                    PatientTab.DETECTORS -> detectorsContent()
                    PatientTab.SETTINGS -> settingsContent()
                }

                SupportButton(
                    onClick = onContactSupport,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 90.dp),
                )
            }
        }
    }
}

/**
 * The five patient tabs, in iOS's order.
 *
 * "Sensors" rather than "Cameras" in the bar: the product calls the hardware a
 * sensor to the caregiver, and only the code calls it a camera.
 */
enum class PatientTab(
    val title: String,
    val analyticsName: String,
    val glyph: ImageVector,
) {
    LIVE("Live", "patient.live", LiveTabGlyph),
    EVENTS("Events", "patient.events", EventsTabGlyph),
    CAMERAS("Sensors", "patient.sensors", SensorsTabGlyph),
    DETECTORS("Detectors", "patient.detectors", DetectorsTabGlyph),
    SETTINGS("Settings", "patient.settings", SettingsTabGlyph),
}
