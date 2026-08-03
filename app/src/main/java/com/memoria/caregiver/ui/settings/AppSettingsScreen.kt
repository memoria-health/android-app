package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.components.NemaraAvatar
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra

/**
 * Caregiver-level account settings — the Settings tab of the app shell.
 * Spec: `Features/AppSettings/AppSettingsView.swift`, PARITY.md §28.
 *
 * Lists the caregiver (tapping opens edit profile), their patients, and the
 * account actions: Privacy & data, Contact support, Replay the welcome tour,
 * Sign out, Delete account.
 *
 * Pure over [AppSettingsState] plus callbacks, per `SettingsUiState.kt`. Every
 * destination iOS opens as a `.sheet` — edit profile, support, privacy, new
 * patient — is a callback here, because presenting them is the navigation host's
 * job and three of the four screens belong to other units.
 *
 * ## Account deletion is two steps, and the second one is guarded twice
 *
 * Tapping the row raises a confirmation alert; only its Delete button calls
 * [onDeleteAccount]. While the call is in flight the row is inert
 * ([AppSettingsState.isDeleting]) so a second tap cannot fire a second delete.
 * On success iOS never sees this screen again — `currentCaregiver` becomes nil
 * and the root swaps to the welcome flow — so there is no success state here, by
 * design. On failure the host sets [AppSettingsState.deleteError] and the second
 * alert shows it.
 *
 * ## The outer stack is centre-aligned
 *
 * `VStack(spacing: 14)` defaults to `.center`, which is why the Swift sprinkles
 * `.frame(maxWidth: .infinity, alignment: .leading)` around. That is load-bearing
 * for the version label at the bottom, which has no such frame and therefore sits
 * centred. Reproduced with `Alignment.CenterHorizontally` plus explicit
 * `fillMaxWidth()` / `align(Alignment.Start)` on the children that need it —
 * defaulting the column to `Start` would silently left-align the version label.
 *
 * The Swift wraps groups of children in `Group { }` to apply one horizontal
 * padding to several of them. A `Group` is not a container: its children join the
 * enclosing `VStack` and take its 14pt spacing. So there are no nested columns
 * here either; each child carries its own padding.
 */
@Composable
fun AppSettingsScreen(
    state: AppSettingsState,
    modifier: Modifier = Modifier,
    onEditProfile: () -> Unit,
    onOpenPatient: (String) -> Unit,
    onAddPatient: () -> Unit,
    onPrivacy: () -> Unit,
    onContactSupport: () -> Unit,
    onReplayTour: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDismissDeleteError: () -> Unit,
) {
    // Screen tracking is NOT here. iOS attaches `.trackScreen("settings")` where
    // MainTabView builds this tab, and MainShell now does the same — so this
    // screen firing its own event as well produced TWO `settings` rows for one
    // view, which silently doubled the count of the app's second-most-visited
    // screen. See HARDENING.md H-10.

    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SettingsTitle()

        CaregiverCard(state = state, onClick = onEditProfile)

        NemaraEyebrow(
            text = "Your patients",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        )

        PatientsCard(
            patients = state.patients,
            onOpenPatient = onOpenPatient,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )

        NemaraButton(
            title = "Add another patient",
            // `.frame(maxWidth: .infinity, alignment: .leading)` on a
            // fixed-width child of a centre-aligned VStack. NOT fillMaxWidth,
            // which would stretch the button's tap target across the screen.
            modifier = Modifier
                .align(Alignment.Start)
                .padding(horizontal = Spacing.md),
            variant = NemaraButtonVariant.Ghost,
            size = NemaraButtonSize.Sm,
            icon = PlusIcon,
            onClick = onAddPatient,
        )

        NemaraEyebrow(
            text = "Account",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        )

        AccountCard(
            isDeleting = state.isDeleting,
            onPrivacy = onPrivacy,
            onContactSupport = onContactSupport,
            onReplayTour = onReplayTour,
            onSignOut = onSignOut,
            onDeleteAccount = { showDeleteConfirm = true },
            modifier = Modifier.padding(horizontal = Spacing.md),
        )

        Text(
            text = VERSION_LABEL,
            // NemaraFont.eyebrow with .tracking(0.5) — NOT NemaraEyebrow, which
            // tracks 0.6 and uppercases. The string is already uppercase and the
            // half-point difference is the reason this is a bare Text on iOS too.
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.md),
            style = NemaraType.eyebrow,
            letterSpacing = 0.5.sp,
            color = Ink3,
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(DELETE_CONFIRM_TITLE) },
            text = { Text(DELETE_CONFIRM_MESSAGE) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // A SwiftUI alert dismisses itself when any button is
                        // tapped, before the action runs.
                        showDeleteConfirm = false
                        onDeleteAccount()
                    },
                ) {
                    // `role: .destructive`. iOS paints that in the system red
                    // rather than the app tint; `Danger` is the palette's red and
                    // the nearest token. A raw #FF3B30 would be the only
                    // off-palette colour in the app.
                    Text(text = "Delete", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(text = "Cancel", color = Terra)
                }
            },
        )
    }

    // Presented on `deleteError != nil`, exactly as the Swift binds it.
    state.deleteError?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissDeleteError,
            title = { Text(DELETE_FAILED_TITLE) },
            // The sentence is the thrown error's description and is rendered
            // verbatim. Never reworded here.
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissDeleteError) {
                    Text(text = "OK", color = Terra)
                }
            },
        )
    }
}

/**
 * `roster_largeTitle(title: "Settings")`.
 *
 * That helper lives in the Roster unit and takes optional `sub` and `trailing`
 * slots; this call site passes neither, so its `VStack(spacing: 6)` holds one
 * child and its `HStack` holds the title and a `Spacer`. What remains is a
 * full-width leading title with the helper's own padding, which is what is drawn
 * here rather than a second copy of a shared component.
 */
@Composable
private fun SettingsTitle() {
    Text(
        text = "Settings",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TitleHorizontalPadding)
            .padding(top = Spacing.xxs, bottom = Spacing.md),
        style = NemaraType.largeTitle,
        // iOS: .kerning(-1). Per DECISIONS.md D-008 kerning lands at the call site.
        letterSpacing = (-1).sp,
        color = Ink,
    )
}

/** The caregiver's name, email and avatar. The whole card opens edit profile. */
@Composable
private fun CaregiverCard(state: AppSettingsState, onClick: () -> Unit) {
    NemaraCard(
        // The Swift puts `.padding(.horizontal, 16)` on the Button that wraps
        // the card, so the padding is OUTSIDE the tap target. Ordering the
        // modifier this way keeps that true.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .plainClickable(onClick = onClick),
        padding = Spacing.md,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraAvatar(letter = state.caregiverAvatarLetter, size = CaregiverAvatarSize)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.caregiverDisplayName,
                    style = NemaraType.title2,
                    // iOS: .kerning(-0.3).
                    letterSpacing = (-0.3).sp,
                    color = Ink,
                )
                Text(text = state.caregiverEmail, style = NemaraType.caption, color = Ink3)
            }
            Spacer(Modifier.weight(1f))
            ChevronRightGlyph(size = RowChevronSize, color = Ink3)
        }
    }
}

/**
 * One row per patient, hairline-separated, each opening that patient.
 *
 * `NemaraAvatar` rather than `PatientAvatar`: the Swift asks for the plain
 * initials avatar here, so this list does not mint a signed photo URL per row.
 *
 * With no patients this renders an empty bordered card, which is what the Swift
 * does — the empty roster is handled a screen away, not here.
 */
@Composable
private fun PatientsCard(
    patients: List<Patient>,
    onOpenPatient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NemaraCard(modifier = modifier.fillMaxWidth(), padding = 0.dp) {
        patients.forEachIndexed { index, patient ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Clickable before padding, so the padded band is part of
                    // the tap target — iOS's `.contentShape(Rectangle())`.
                    .plainClickable { onOpenPatient(patient.id) }
                    .padding(Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NemaraAvatar(letter = patient.avatarLetter, size = PatientAvatarSize)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = patient.fullName, style = NemaraType.bodySemibold, color = Ink)
                    Text(
                        text = patient.relation.displayName,
                        style = NemaraType.caption,
                        color = Ink3,
                    )
                }
                Spacer(Modifier.weight(1f))
                ChevronRightGlyph(size = RowChevronSize, color = Ink3)
            }
            if (index < patients.lastIndex) RowSeparator()
        }
    }
}

/** Privacy, support, tutorial replay, sign out, delete. */
@Composable
private fun AccountCard(
    isDeleting: Boolean,
    onPrivacy: () -> Unit,
    onContactSupport: () -> Unit,
    onReplayTour: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NemaraCard(modifier = modifier.fillMaxWidth(), padding = 0.dp) {
        AccountRow(title = "Privacy & data", glyph = { s, c -> LockShieldGlyph(s, c) }, onClick = onPrivacy)
        RowSeparator()
        AccountRow(title = "Contact support", glyph = { s, c -> EnvelopeGlyph(s, c) }, onClick = onContactSupport)
        RowSeparator()
        AccountRow(title = "Replay the welcome tour", glyph = { s, c -> SparklesGlyph(s, c) }, onClick = onReplayTour)
        RowSeparator()
        AccountRow(title = "Sign out", glyph = { s, c -> SignOutGlyph(s, c) }, onClick = onSignOut)
        RowSeparator()
        AccountRow(
            title = "Delete account",
            glyph = { s, c -> TrashGlyph(s, c) },
            titleColor = Danger,
            // `.disabled(isDeleting)` — the row cannot fire a second delete.
            enabled = !isDeleting,
            trailing = {
                if (isDeleting) {
                    CircularProgressIndicator(Modifier.size(RowSpinnerSize), strokeWidth = RowSpinnerStroke)
                } else {
                    ChevronRightGlyph(size = RowChevronSize, color = Ink3)
                }
            },
            onClick = onDeleteAccount,
        )
    }
}

/**
 * `rowChevron(icon:title:)` — icon tile, title, spacer, chevron, 14pt padding.
 *
 * The Swift has two near-copies of this shape: the three simple rows go through
 * `rowChevron`, while Sign out and Delete account are written out inline so they
 * can change the title colour and swap the chevron for a spinner. One function
 * with defaults covers both without inventing anything: the defaults reproduce
 * `rowChevron` exactly.
 */
@Composable
private fun AccountRow(
    title: String,
    glyph: @Composable (Dp, Color) -> Unit,
    onClick: () -> Unit,
    titleColor: Color = Ink,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = { ChevronRightGlyph(size = RowChevronSize, color = Ink3) },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(AccountRowPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(glyph)
        Text(text = title, style = NemaraType.body, color = titleColor)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// ---------------------------------------------------------------------------
// Copy and tuning constants, carried verbatim from AppSettingsView.swift
// ---------------------------------------------------------------------------

internal const val VERSION_LABEL = "MEMORIA v0.1"
internal const val DELETE_CONFIRM_TITLE = "Delete your account?"
internal const val DELETE_CONFIRM_MESSAGE =
    "This permanently deletes your account, your loved ones' profiles, sensors, " +
        "and all data. This can't be undone."
internal const val DELETE_FAILED_TITLE = "Couldn't delete account"

/** `VStack(spacing: 14)`. Not on the spacing scale; carried verbatim. */
private val SectionSpacing = 14.dp

/** `roster_largeTitle`'s `.padding(.horizontal, 20)` — Spacing.lg. */
private val TitleHorizontalPadding = Spacing.lg

/** `NemaraAvatar(size: 56)` on the caregiver card. */
private val CaregiverAvatarSize = 56.dp

/** `NemaraAvatar(size: 40)` on a patient row — the component's own default. */
private val PatientAvatarSize = 40.dp

/** Account rows are `.padding(14)`; patient rows are `.padding(12)` (Spacing.sm). */
private val AccountRowPadding = 14.dp

/**
 * `ProgressView()` in the delete row. iOS's default circular indicator is ~20pt;
 * the stroke is judgement, in the same category as the nav-bar metrics.
 */
private val RowSpinnerSize = 20.dp
private val RowSpinnerStroke = 2.dp
