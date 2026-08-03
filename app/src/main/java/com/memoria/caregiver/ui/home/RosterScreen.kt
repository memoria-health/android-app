package com.memoria.caregiver.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraAvatar
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import java.time.LocalTime

/** Test handle for the header's `+`, which carries no text to select by. */
const val RosterAddPatientTestTag: String = "roster.addPatient"

/** Test handle for the header avatar, which opens edit-profile. */
const val RosterEditProfileTestTag: String = "roster.editProfile"

/** iOS: `Image(systemName: "plus").font(.system(size: 20, weight: .semibold))`. */
private val HeaderPlusSize = 20.dp

/** iOS: `NemaraAvatar(letter: firstName, size: 28)`. */
private val HeaderAvatarSize = 28.dp

/**
 * The Home tab. Lists every patient the caregiver looks after.
 *
 * Empty state defers to [EmptyRosterScreen]. Tapping a row drills into the
 * patient bundle; tapping `+` opens the new-patient sheet.
 *
 * Pure state-in / callbacks-out, per `ui/auth/AuthUiState.kt`: no ViewModel, no
 * I/O. Three things iOS does inside `RosterView` are reported out instead,
 * because they belong to whoever owns navigation:
 *
 * 1. **The two sheets.** iOS presents `NewPatientView` and `EditProfileView`
 *    with `.sheet(isPresented:)` from this view, along with their
 *    `.trackScreen("new_patient")` / `.trackScreen("edit_profile")` calls.
 *    Here the taps surface as [onAddPatient] and [onEditProfile].
 * 2. **`api.refresh()`.** iOS's `.refreshable` takes a suspending closure and
 *    owns the spinner's lifetime; `PullToRefreshBox` wants the flag, so
 *    [RosterState.isRefreshing] carries it and [onRefresh] starts the work.
 *    The iOS closure swallows the error behind a `#if DEBUG` print, so a failed
 *    refresh is silent on both platforms — pull-to-refresh is an escape hatch
 *    for a dropped Realtime row change, not a primary load path.
 * 3. **Screen tracking.** `App/MainTabView.swift:22` attaches
 *    `.trackScreen("home")` to the tab, not to `RosterView`, so this screen
 *    logs nothing itself.
 *
 * [hourOfDay] is a parameter with a live default rather than a field of
 * [RosterState] because iOS reads `Date()` inside the view body on every render
 * — it is not part of the model there either. The default keeps every call site
 * honest while letting a test pin 3am.
 */
@Composable
fun RosterScreen(
    state: RosterState,
    resolvePhotoUrl: suspend (String) -> String?,
    onOpenPatient: (String) -> Unit,
    onAddPatient: () -> Unit,
    onEditProfile: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    hourOfDay: Int = LocalTime.now().hour,
) {
    if (state.patients.isEmpty()) {
        EmptyRosterScreen(
            onAddPatient = onAddPatient,
            modifier = modifier,
            caregiverDisplayName = state.caregiverDisplayName,
        )
        return
    }

    val firstName = rosterFirstName(state.caregiverDisplayName)

    // Pull-to-refresh escape hatch for when Realtime drops a row change (e.g. a
    // delete done directly in Studio). Refetches the roster + every per-patient
    // sub-collection so a single drag-down restores full sync.
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().background(Paper),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // iOS is a ScrollView over a plain VStack, NOT a List, so the
                // rows are all composed up front. A roster is a handful of
                // patients; making it lazy would change nothing except which
                // rows exist for a test to find.
                .verticalScroll(rememberScrollState()),
        ) {
            RosterLargeTitle(
                title = "Today",
                sub = "${rosterGreeting(hourOfDay)}, $firstName",
                trailing = {
                    Row(
                        modifier = Modifier.alignBy(LastBaseline),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = PlusGlyph,
                            contentDescription = null,
                            tint = Ink,
                            // The glyph IS the tap target, as on iOS, where the
                            // Button's label is the bare Image with no padding.
                            modifier = Modifier
                                .size(HeaderPlusSize)
                                .testTag(RosterAddPatientTestTag)
                                .plainClickable(onAddPatient)
                                // A bare glyph. iOS gets a VoiceOver name from
                                // the SF Symbol "plus"; a drawn vector has none,
                                // so without this the only way to add a patient
                                // announces as an unnamed button. The wording is
                                // the empty-roster button's own label, so the
                                // two entry points to the same sheet say the
                                // same thing.
                                .semantics { contentDescription = "Add a patient" },
                        )
                        NemaraAvatar(
                            // The CAREGIVER's initial, not a patient's — this
                            // is the profile button.
                            letter = firstName,
                            modifier = Modifier
                                .testTag(RosterEditProfileTestTag)
                                .plainClickable(onEditProfile)
                                // The avatar shows an initial, which a screen
                                // reader would read as a single letter and no
                                // more. Named for what tapping it does.
                                .semantics { contentDescription = "Edit profile" },
                            size = HeaderAvatarSize,
                        )
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (patient in state.patients) {
                    PatientRow(
                        patient = patient,
                        events = state.eventsByPatient[patient.id].orEmpty(),
                        resolvePhotoUrl = resolvePhotoUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .plainClickable { onOpenPatient(patient.id) },
                    )
                }
            }
        }
    }
}

/**
 * Tap with no ripple and no indication — SwiftUI's `.buttonStyle(.plain)`,
 * which every button on this screen uses. Same helper LoginScreen.kt defines.
 */
@Composable
private fun Modifier.plainClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        // Every one of these is a SwiftUI `Button` on iOS, and a button tells a
        // screen reader that it is a button. Without a role TalkBack reads the
        // label as ordinary text: the caregiver hears the words and is never
        // told they can be tapped. Set inside the helper rather than at each
        // call site so a new caller cannot be added without it. Controls that
        // are a CHOICE rather than an action use `plainSelectable` or
        // `plainToggleable`, which carry the role their state needs.
        role = Role.Button,
        onClick = onClick,
    )
}
