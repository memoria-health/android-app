package com.memoria.caregiver.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edit-profile gates, tested without composing anything.
 *
 * Every rule here decides whether a caregiver's edit reaches the backend, and
 * each one is a place a port silently loosens or tightens the Swift: the
 * case-insensitive email compare, the ten-digit phone floor, the deliberately
 * naive address check, and the hydration guards.
 */
class SettingsUiStateTest {

    private fun hydratedState(
        displayName: String = "Aryaman Darda",
        email: String = "aryaman@memoria.care",
        phone: String? = "+15551234567",
    ): EditProfileState =
        EditProfileState(caregiver = caregiver(displayName, email, phone)).hydrated()

    // -----------------------------------------------------------------------
    // Hydration
    // -----------------------------------------------------------------------

    @Test
    fun `hydrating fills the three fields from the signed-in row`() {
        val state = hydratedState()

        assertEquals("Aryaman Darda", state.name)
        assertEquals("aryaman@memoria.care", state.email)
        assertEquals("+15551234567", state.phone)
        assertTrue(state.isHydrated)
    }

    /** A caregiver with no phone on file gets an empty field, not "null". */
    @Test
    fun `a missing phone hydrates to an empty field`() {
        assertEquals("", hydratedState(phone = null).phone)
    }

    /**
     * `guard !isHydrated` — a second appear must not throw away edits the
     * caregiver has already typed.
     */
    @Test
    fun `hydrating twice does not overwrite what was typed`() {
        val edited = hydratedState().copy(name = "Ary")

        assertEquals("Ary", edited.hydrated().name)
    }

    /**
     * `guard let me = env.auth.currentCaregiver` — with no caregiver the fields
     * stay empty AND `isHydrated` stays false, so a caregiver that lands after
     * the sheet appeared never populates them. That is iOS's behavior; the flag
     * is only set inside the guarded body.
     */
    @Test
    fun `hydrating without a caregiver is a no-op`() {
        val state = EditProfileState()

        assertSame(state, state.hydrated())
        assertFalse(state.hydrated().isHydrated)
    }

    // -----------------------------------------------------------------------
    // Change detection
    // -----------------------------------------------------------------------

    @Test
    fun `an untouched hydrated form has no changes and cannot be saved`() {
        val state = hydratedState()

        assertFalse(state.hasChanges)
        assertFalse(state.canSave)
    }

    /**
     * Only the case differs, so this is the same address — and it must not
     * trigger Supabase's email-change flow, which would mail the caregiver a
     * confirmation link for the address they already have.
     */
    @Test
    fun `an email that differs only in case has not changed`() {
        val state = hydratedState(email = "aryaman@memoria.care")
            .copy(email = "Aryaman@Memoria.Care")

        assertFalse(state.emailChanged)
        assertFalse(state.canSave)
    }

    @Test
    fun `a genuinely different email counts as a change`() {
        val state = hydratedState().copy(email = "ary@memoria.care")

        assertTrue(state.emailChanged)
        assertTrue(state.canSave)
    }

    /** Surrounding spaces are trimmed before the compare, so they are not an edit. */
    @Test
    fun `padding a value with spaces is not a change`() {
        val state = hydratedState().copy(name = "  Aryaman Darda  ")

        assertFalse(state.nameChanged)
    }

    /**
     * Swift's `.trimmingCharacters(in: .whitespaces)` is Zs + tab, and does NOT
     * include newlines. Kotlin's `trim()` does, so using it would make a pasted
     * trailing newline invisible on Android and a real edit on iOS.
     */
    @Test
    fun `trimming follows Swifts whitespace set and leaves newlines alone`() {
        assertEquals("Ary", "  \tAry\t ".trimmingWhitespaces())
        assertEquals("Ary\n", "Ary\n".trimmingWhitespaces())
    }

    // -----------------------------------------------------------------------
    // Validity
    // -----------------------------------------------------------------------

    @Test
    fun `a blank name blocks save`() {
        val state = hydratedState().copy(name = "   ")

        assertTrue(state.hasChanges)
        assertFalse(state.canSave)
    }

    /**
     * iOS asks only that the address contain an "@" and a "." — anywhere, in any
     * order. Tightening this would reject addresses iOS accepts.
     */
    @Test
    fun `the address check wants an at sign and a dot and nothing more`() {
        assertFalse(hydratedState().copy(email = "ary@memoria").isValid)
        assertFalse(hydratedState().copy(email = "ary.memoria").isValid)
        assertTrue(hydratedState().copy(email = ".@").isValid)
    }

    /** Ten digits after formatting is stripped — punctuation is free. */
    @Test
    fun `phone needs ten digits and ignores formatting`() {
        assertTrue(hydratedState().copy(phone = "(555) 123-4567").phoneValid)
        assertFalse(hydratedState().copy(phone = "(555) 123-456").phoneValid)
    }

    /** Phone is required here exactly as it is at signup, so blanking it blocks save. */
    @Test
    fun `clearing the phone blocks save`() {
        val state = hydratedState().copy(phone = "")

        assertTrue(state.hasChanges)
        assertFalse(state.canSave)
    }

    // -----------------------------------------------------------------------
    // The save gate
    // -----------------------------------------------------------------------

    /**
     * The double-submission guard: a save already in flight closes the gate, so
     * a second tap cannot send a second `PATCH /v1/me` or a second email-change
     * request.
     */
    @Test
    fun `a save in flight closes the gate`() {
        val state = hydratedState().copy(name = "Ary", isSaving = true)

        assertTrue(state.hasChanges && state.isValid)
        assertFalse(state.canSave)
    }

    /** Valid, changed, but never hydrated — the fourth clause of the gate. */
    @Test
    fun `an unhydrated form cannot be saved`() {
        val state = EditProfileState(
            caregiver = caregiver(),
            name = "Ary",
            email = "ary@memoria.care",
            phone = "5551234567",
        )

        assertTrue(state.hasChanges && state.isValid)
        assertFalse(state.canSave)
    }

    // -----------------------------------------------------------------------
    // App settings placeholders
    // -----------------------------------------------------------------------

    /**
     * The placeholders are shipping copy on iOS, not preview fixtures — they are
     * what shows for the instant between the tab appearing and bootstrap
     * landing.
     */
    @Test
    fun `with no caregiver the card falls back to the placeholder identity`() {
        val state = AppSettingsState()

        assertEquals("Jane Patel", state.caregiverDisplayName)
        assertEquals("jane@patel.co", state.caregiverEmail)
        assertEquals("J", state.caregiverAvatarLetter)
    }

    /**
     * The name fallback is on the caregiver being absent; the avatar's is also
     * on the name being empty, because Swift's `displayName.first` is nil there.
     * Two different conditions, and only the avatar has the second one.
     */
    @Test
    fun `an empty display name shows empty but still gets the J avatar`() {
        val state = AppSettingsState(caregiver = caregiver(displayName = ""))

        assertEquals("", state.caregiverDisplayName)
        assertEquals("J", state.caregiverAvatarLetter)
    }
}
