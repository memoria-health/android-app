package com.memoria.caregiver.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.memoria.caregiver.ui.support.SupportButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.home.EmptyRosterScreen
import com.memoria.caregiver.ui.home.RosterScreen
import com.memoria.caregiver.ui.home.RosterViewModel
import com.memoria.caregiver.ui.settings.AppSettingsScreen
import com.memoria.caregiver.ui.settings.AppSettingsViewModel
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import kotlinx.coroutines.launch

/**
 * The signed-in surface: Home and Settings. Port of `App/MainTabView.swift`.
 *
 * ## One presenter for the patient, and only one
 *
 * A roster row, a settings row and a notification tap all reach the same
 * patient, and on iOS all three funnel through `deepLink.openPatientId` because
 * two independent presenters once fought over one full-screen cover. The same
 * rule holds here: [openPatient] writes the router and **nothing else opens a
 * patient**. That is why the roster's callback routes rather than navigating.
 *
 * ## Why this is not a NavHost
 *
 * The patient bundle replaces the whole surface, tab bar included — iOS
 * presents it as a `fullScreenCover` precisely so two tab bars never stack.
 * Modelling it as a route inside the same graph would leave this bar visible
 * underneath. So the patient shell is a branch here, above the `Scaffold`.
 */
@Composable
fun MainShell(
    rosterViewModel: RosterViewModel,
    settingsViewModel: AppSettingsViewModel,
    deepLink: DeepLinkRouter,
    patientContent: @Composable (patientId: String) -> Unit,
    /**
     * The destinations that are plain local navigation, not deep-link targets.
     * Hoisted rather than pushed through [DeepLinkRouter]: that router exists to
     * make "which patient is open" single-valued, and widening it into a general
     * navigation bus would dissolve the one property it is there to guarantee.
     */
    onAddPatient: () -> Unit,
    onEditProfile: () -> Unit,
    onPrivacy: () -> Unit,
    onContactSupport: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * iOS applies `.supportButtonOverlay()` on RootView AND again on
     * PatientShell, because the patient cover would otherwise hide the
     * presenter-level overlay. Same here: this shell draws it, and the patient
     * shell draws its own.
     */
    showSupportButton: Boolean = true,
) {
    val link by deepLink.state.collectAsState()

    // Declared ABOVE the early return on purpose. Below it, opening a patient
    // takes the whole Scaffold out of composition, `rememberSaveable`
    // unregisters, and the caregiver who went Settings -> a patient -> Home
    // lands back on Home rather than on Settings. iOS keeps its `@State
    // selection` because the patient is a cover over a still-live TabView.
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val analytics = LocalAnalytics.current

    // The patient bundle takes over completely, including the tab bar.
    val openPatientId = link.openPatientId
    if (openPatientId != null) {
        Box(modifier.fillMaxSize()) {
            // key() on the patient id, which is load-bearing. The call site is
            // fixed, so switching patient A -> B would otherwise RECOMPOSE the
            // same shell rather than replace it: A's DisposableEffect would
            // never fire (so A's LiveKit room would stay connected inside B's
            // shell — one household's live video under another household's
            // name), A's feed poll would keep running, and A's selected tab
            // would carry across. iOS got the teardown from the cover being
            // genuinely re-presented; DIFFERENCES.md D1 dropped that dance, and
            // this is what replaces it.
            key(openPatientId) { patientContent(openPatientId) }
        }
        return
    }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Paper,
        bottomBar = {
            NavigationBar(containerColor = Paper, contentColor = Ink) {
                MainTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        // Selection only. The screen event fires where the tab's
                        // content is drawn, not here — see the LaunchedEffect
                        // below.
                        onClick = { tab = entry },
                        // Icon AND label, as iOS's `Label(title, systemImage:)`
                        // renders. It was text-only in the icon slot (F-13).
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
        // iOS's `.trackScreen()` is an `onAppear`, so it fires for whatever tab
        // is showing — including the one the session starts on, and any tab the
        // app selects for itself. Logging from the tab bar's onClick instead
        // meant neither was ever recorded: every session appeared to begin
        // nowhere, and Home was undercounted by exactly the sessions that never
        // left it. Firing here, where the content is drawn, is the port of
        // onAppear and is also the only instrumentation point for these two
        // screens, so a tab added to the enum cannot go untracked
        // (HARDENING.md H-10).
        LaunchedEffect(tab) { analytics.logScreen(tab.analyticsName) }
        Box(Modifier.padding(insets).fillMaxSize()) {
            when (tab) {
                MainTab.HOME -> {
                    val state by rosterViewModel.state.collectAsState()
                    // iOS switches on `patients.isEmpty` alone — a roster that is
                    // still loading shows the empty state on both platforms.
                    if (state.patients.isEmpty()) {
                        EmptyRosterScreen(
                            caregiverDisplayName = state.caregiverDisplayName,
                            onAddPatient = onAddPatient,
                        )
                    } else {
                        RosterScreen(
                            state = state,
                            resolvePhotoUrl = rosterViewModel::resolvePhotoUrl,
                            onOpenPatient = { deepLink.openPatient(it) },
                            onAddPatient = onAddPatient,
                            onEditProfile = onEditProfile,
                            onRefresh = rosterViewModel::refresh,
                        )
                    }
                }

                MainTab.SETTINGS -> {
                    val state by settingsViewModel.state.collectAsState()
                    AppSettingsScreen(
                        state = state,
                        onEditProfile = onEditProfile,
                        onOpenPatient = { deepLink.openPatient(it) },
                        onAddPatient = onAddPatient,
                        onPrivacy = onPrivacy,
                        onContactSupport = onContactSupport,
                        // No Android tour yet, so this is deliberately inert
                        // rather than pretending. Tracked in the plan.
                        onReplayTour = {},
                        onSignOut = { scope.launch { settingsViewModel.signOut() } },
                        onDeleteAccount = { scope.launch { settingsViewModel.deleteAccount() } },
                        onDismissDeleteError = settingsViewModel::dismissDeleteError,
                    )
                }
            }

            if (showSupportButton) {
                SupportButton(
                    onClick = onContactSupport,
                    // iOS: `.padding(.trailing, 18).padding(.bottom, 90)` in
                    // `supportButtonOverlay()`. The 90 clears the tab bar; here
                    // the Scaffold has already inset for it, so this sits
                    // relative to the content area the same way.
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 90.dp),
                )
            }
        }
    }
}

/** The two top-level tabs. iOS: `MainTabView.AppTab`. */
enum class MainTab(
    val title: String,
    val analyticsName: String,
    val glyph: ImageVector,
) {
    HOME("Home", "home", HomeTabGlyph),
    SETTINGS("Settings", "settings", SettingsTabGlyph),
}
