package com.memoria.caregiver.ui.settings

import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.model.Patient

/**
 * The state the two app-settings screens render from.
 *
 * Same contract as `ui/auth/AuthUiState.kt`: **screens are pure `@Composable`
 * functions over these types.** No ViewModel, no I/O, no service access. iOS
 * keeps the same values in `@State` on `AppSettingsView` / `EditProfileView` and
 * reaches the caregiver, the patient list and the API through `@Environment`;
 * the shape below is that state, hoisted.
 *
 * Spec: `Features/AppSettings/AppSettingsView.swift`,
 * `Features/AppSettings/EditProfileView.swift`, PARITY.md §28.
 */

// ---------------------------------------------------------------------------
// App settings
// ---------------------------------------------------------------------------

/**
 * `AppSettingsView`. The caregiver card, the patient roster, and the state of
 * the account-deletion flow.
 *
 * `showDeleteConfirm` is deliberately NOT here. It is the visibility of a
 * confirmation alert, it is raised and cleared by the same screen, and nothing
 * outside the screen can act on it — so hoisting it would only give the host a
 * second way to get it wrong. `AppSettingsScreen` owns it, the same way
 * `SignUpScreen` owns its confirm-password field.
 *
 * @param caregiver the signed-in row, or null before bootstrap completes. The
 *   card falls back to placeholder copy when null — see [caregiverDisplayName].
 * @param patients the roster, rendered in the order given. iOS reads
 *   `env.api.patients` directly and does not sort here.
 * @param isDeleting the irreversible delete is in flight. The row is inert while
 *   it is true, so a second tap cannot fire a second delete.
 * @param deleteError the failure sentence from the delete call, or null. Its
 *   non-null-ness IS the error alert's presentation condition, exactly as iOS
 *   binds `deleteError != nil`. Rendered verbatim — never reworded.
 */
data class AppSettingsState(
    val caregiver: Caregiver? = null,
    val patients: List<Patient> = emptyList(),
    val isDeleting: Boolean = false,
    val deleteError: String? = null,
)

/**
 * `env.auth.currentCaregiver?.displayName ?? "Jane Patel"`.
 *
 * The placeholder is real shipping copy on iOS, not a preview fixture — it is
 * what a caregiver sees for the instant between the settings tab appearing and
 * bootstrap landing. Carried verbatim.
 *
 * Note the fallback is on the *caregiver* being absent, not on the name being
 * empty: a row with an empty `display_name` renders empty here, and iOS does the
 * same. Only [caregiverAvatarLetter] falls back on emptiness.
 */
internal val AppSettingsState.caregiverDisplayName: String
    get() = caregiver?.displayName ?: PLACEHOLDER_CAREGIVER_NAME

/** `env.auth.currentCaregiver?.email ?? "jane@patel.co"`. See above. */
internal val AppSettingsState.caregiverEmail: String
    get() = caregiver?.email ?: PLACEHOLDER_CAREGIVER_EMAIL

/**
 * `env.auth.currentCaregiver?.displayName.first.map(String.init) ?? "J"`.
 *
 * Two ways to reach the "J": no caregiver at all, or a caregiver whose display
 * name is empty (`String.first` is nil there). Both are carried.
 *
 * The whole name is handed to `NemaraAvatar` rather than a single character,
 * because the component already takes the first **grapheme cluster** and
 * uppercases it — which is exactly what Swift's `.first` + the component's
 * `prefix(1).uppercased()` do together. Slicing here as well would only add a
 * second place to get surrogate pairs wrong.
 */
internal val AppSettingsState.caregiverAvatarLetter: String
    get() = caregiver?.displayName?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER_AVATAR_LETTER

private const val PLACEHOLDER_CAREGIVER_NAME = "Jane Patel"
private const val PLACEHOLDER_CAREGIVER_EMAIL = "jane@patel.co"
private const val PLACEHOLDER_AVATAR_LETTER = "J"

// ---------------------------------------------------------------------------
// Edit profile
// ---------------------------------------------------------------------------

/**
 * `EditProfileView`. The three editable fields plus the signed-in row they are
 * diffed against.
 *
 * [caregiver] has to be here and not just in the host, because every gate on
 * this screen is a *comparison* against it: iOS's `nameChanged`, `emailChanged`
 * and `phoneChanged` all `guard let me = env.auth.currentCaregiver`, and with no
 * caregiver they all return false — so the Save button is dead. That is not an
 * edge case to design away, it is the behavior.
 *
 * ## Save behavior, carried from the Swift doc comment
 *
 * - Name and phone are written via `PATCH /v1/me`. The returned row replaces
 *   `currentCaregiver` in place so the UI refreshes without a full bootstrap.
 * - Email is changed via Supabase Auth's secure-email-change flow. The new
 *   address only becomes live after the user clicks the link Supabase sends
 *   them, at which point a DB trigger mirrors the new address into
 *   `caregivers.email`. **Until then the displayed email stays the old one
 *   (intentional, not a bug).**
 *
 * @param isHydrated the fields have been filled from [caregiver]. See [hydrated].
 * @param isSaving a save is in flight; Save is inert and shows a spinner.
 * @param errorMessage the failure sentence, rendered verbatim under the fields.
 */
data class EditProfileState(
    val caregiver: Caregiver? = null,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val isHydrated: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * iOS's `hydrate()`, which runs from `.onAppear` — fill the three fields from
 * the signed-in row, **once**.
 *
 * `EditProfileScreen` reports the appear through its `onAppear` callback and the
 * host applies this, because a pure screen may not rewrite the state it is
 * handed. Both guards are the Swift's: already hydrated is a no-op, and no
 * caregiver is a no-op that leaves `isHydrated` false — so a caregiver that
 * arrives *after* the screen appeared never populates the fields. That is iOS's
 * behavior; the flag is only set inside the guarded body.
 */
internal fun EditProfileState.hydrated(): EditProfileState {
    if (isHydrated) return this
    val me = caregiver ?: return this
    return copy(
        name = me.displayName,
        email = me.email,
        phone = me.phone ?: "",
        isHydrated = true,
    )
}

internal val EditProfileState.trimmedName: String get() = name.trimmingWhitespaces()
internal val EditProfileState.trimmedEmail: String get() = email.trimmingWhitespaces()
internal val EditProfileState.trimmedPhone: String get() = phone.trimmingWhitespaces()

internal val EditProfileState.nameChanged: Boolean
    get() {
        val me = caregiver ?: return false
        return trimmedName != me.displayName
    }

/**
 * Case-insensitive, matching `trimmedEmail.lowercased() != me.email.lowercased()`.
 *
 * `lowercase()` with no locale is used on purpose. `lowercase(Locale.getDefault())`
 * would lower-case a dotted capital I to a dotless one under a Turkish locale, so
 * a Turkish caregiver merely opening this sheet would see the email-change hint
 * and, on save, be sent a confirmation mail for an address that only differs by
 * an invisible locale rule. Swift's `lowercased()` is locale-independent.
 */
internal val EditProfileState.emailChanged: Boolean
    get() {
        val me = caregiver ?: return false
        return trimmedEmail.lowercase() != me.email.lowercase()
    }

internal val EditProfileState.phoneChanged: Boolean
    get() {
        val me = caregiver ?: return false
        return trimmedPhone != (me.phone ?: "")
    }

internal val EditProfileState.hasChanges: Boolean
    get() = nameChanged || emailChanged || phoneChanged

/**
 * Phone is required here, mirroring signup: at least 10 digits once non-digit
 * characters are stripped, so support can text the caregiver back. The backend
 * normalizes the format to E.164.
 *
 * `Char.isDigit()` is the Kotlin counterpart of Swift's `Character.isNumber` —
 * the same call `isSignUpPhoneValid` makes for the identical rule on the sign-up
 * form. (`isNumber` is the wider of the two: it also admits Unicode numerals
 * like `½` that `isDigit()` rejects. Ten fraction glyphs are not a phone number
 * on either platform, and the rule is duplicated in both Swift views rather than
 * shared, so it is duplicated here too rather than reaching into `ui.auth`.)
 */
internal val EditProfileState.phoneValid: Boolean
    get() = trimmedPhone.count { it.isDigit() } >= MIN_PHONE_DIGITS

/**
 * `!trimmedName.isEmpty && trimmedEmail.contains("@") && trimmedEmail.contains(".") && phoneValid`.
 *
 * Deliberately NOT a real address validator — iOS accepts any string with an "@"
 * and a "." anywhere in it, in either order, so tightening it here would reject
 * addresses iOS lets through.
 */
internal val EditProfileState.isValid: Boolean
    get() = trimmedName.isNotEmpty() &&
        trimmedEmail.contains("@") &&
        trimmedEmail.contains(".") &&
        phoneValid

/** `isHydrated && hasChanges && isValid && !isSaving`. */
internal val EditProfileState.canSave: Boolean
    get() = isHydrated && hasChanges && isValid && !isSaving

private const val MIN_PHONE_DIGITS = 10

/**
 * Swift's `trimmingCharacters(in: .whitespaces)`, which is Unicode general
 * category **Zs plus CHARACTER TABULATION (U+0009)** — and notably *not*
 * newlines.
 *
 * Kotlin's `String.trim()` uses `Char.isWhitespace()`, which does include `\n`
 * and `\r`. The difference is only reachable by pasting, but it is reachable:
 * a name pasted with a trailing newline is "changed" on iOS (so Save enables and
 * the newline is sent) and unchanged on Android. Matching iOS is the point of
 * the port even where iOS is the worse of the two.
 */
internal fun String.trimmingWhitespaces(): String = trim(Char::isSwiftWhitespace)

private fun Char.isSwiftWhitespace(): Boolean =
    this == '\t' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()
