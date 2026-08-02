package com.memoria.caregiver.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `AppSettingsScreen` behavior. Spec: `Features/AppSettings/AppSettingsView.swift`.
 *
 * The screen is mostly a list of rows, and the thing worth pinning about a list
 * of rows is that each one is wired to its own action — the failure this guards
 * is a copy-pasted row calling the wrong callback, which no amount of looking at
 * the layout catches.
 *
 * The rest of the file is the account-deletion flow, which is irreversible: it
 * must take two deliberate taps, cancelling must do nothing at all, and a delete
 * already in flight must ignore the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AppSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Callbacks {
        var editProfile = 0
        val openedPatients = mutableListOf<String>()
        var addPatient = 0
        var privacy = 0
        var support = 0
        var replayTour = 0
        var signOut = 0
        var deleteAccount = 0
        var dismissDeleteError = 0
    }

    private fun setUp(state: AppSettingsState): Callbacks {
        val callbacks = Callbacks()
        compose.setContent {
            MemoriaTheme {
                AppSettingsScreen(
                    state = state,
                    onEditProfile = { callbacks.editProfile++ },
                    onOpenPatient = { callbacks.openedPatients += it },
                    onAddPatient = { callbacks.addPatient++ },
                    onPrivacy = { callbacks.privacy++ },
                    onContactSupport = { callbacks.support++ },
                    onReplayTour = { callbacks.replayTour++ },
                    onSignOut = { callbacks.signOut++ },
                    onDeleteAccount = { callbacks.deleteAccount++ },
                    onDismissDeleteError = { callbacks.dismissDeleteError++ },
                )
            }
        }
        return callbacks
    }

    // -----------------------------------------------------------------------
    // Account deletion
    // -----------------------------------------------------------------------

    /**
     * Tapping the row must only *ask*. The row itself deleting the account is
     * the single worst bug this screen could have.
     */
    @Test
    fun `the delete row asks before it deletes`() {
        val callbacks = setUp(AppSettingsState(caregiver = caregiver()))

        compose.onNodeWithText("Delete account").performScrollTo().performClick()

        assertEquals(0, callbacks.deleteAccount)
        compose.onNodeWithText(DELETE_CONFIRM_MESSAGE).assertIsDisplayed()

        compose.onNodeWithText("Delete").performClick()

        assertEquals(1, callbacks.deleteAccount)
    }

    @Test
    fun `cancelling the confirmation deletes nothing and closes the alert`() {
        val callbacks = setUp(AppSettingsState(caregiver = caregiver()))

        compose.onNodeWithText("Delete account").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, callbacks.deleteAccount)
        compose.onNodeWithText(DELETE_CONFIRM_TITLE).assertDoesNotExist()
    }

    /**
     * `.disabled(isDeleting)`. A second tap while the call is in flight must not
     * even reopen the alert, or the caregiver can queue a second delete behind
     * the first.
     */
    @Test
    fun `a delete already in flight ignores the row`() {
        // The in-flight row swaps its chevron for an indeterminate spinner,
        // which animates forever. With the clock auto-advancing, `waitForIdle`
        // inside every finder would never see an idle frame and the test would
        // hang rather than fail. Pausing the clock leaves the composition
        // fully rendered and simply stops producing new frames.
        compose.mainClock.autoAdvance = false

        setUp(AppSettingsState(caregiver = caregiver(), isDeleting = true))

        compose.onNodeWithText("Delete account").performScrollTo().performClick()

        compose.onNodeWithText(DELETE_CONFIRM_TITLE).assertDoesNotExist()
    }

    /**
     * The failure sentence is the thrown error's own description and is rendered
     * verbatim — never reworded into something friendlier in the UI layer.
     */
    @Test
    fun `the failure alert shows the error verbatim and OK dismisses it`() {
        val callbacks = setUp(
            AppSettingsState(
                caregiver = caregiver(),
                deleteError = "We couldn't reach the server. Check your connection.",
            ),
        )

        compose.onNodeWithText("We couldn't reach the server. Check your connection.")
            .assertIsDisplayed()

        compose.onNodeWithText("OK").performClick()

        assertEquals(1, callbacks.dismissDeleteError)
    }

    // -----------------------------------------------------------------------
    // Rows
    // -----------------------------------------------------------------------

    /**
     * Five rows, five different actions, and four of them are one word apart in
     * the source. Signing out when the caregiver asked for support is the kind of
     * mis-wiring only a test finds.
     */
    @Test
    fun `each account row fires its own action`() {
        val callbacks = setUp(AppSettingsState(caregiver = caregiver()))

        compose.onNodeWithText("Privacy & data").performScrollTo().performClick()
        compose.onNodeWithText("Contact support").performScrollTo().performClick()
        compose.onNodeWithText("Replay the welcome tour").performScrollTo().performClick()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()

        assertEquals(1, callbacks.privacy)
        assertEquals(1, callbacks.support)
        assertEquals(1, callbacks.replayTour)
        assertEquals(1, callbacks.signOut)
        assertEquals(0, callbacks.deleteAccount)
    }

    /** Each patient row must open the patient it names, not the first one. */
    @Test
    fun `a patient row opens the patient it names`() {
        val callbacks = setUp(
            AppSettingsState(
                caregiver = caregiver(),
                patients = listOf(
                    patient(id = "p-1", fullName = "Margaret Holloway"),
                    patient(id = "p-2", fullName = "Arthur Pike"),
                ),
            ),
        )

        compose.onNodeWithText("Arthur Pike").performScrollTo().performClick()

        assertEquals(listOf("p-2"), callbacks.openedPatients)
    }

    @Test
    fun `the caregiver card opens edit profile`() {
        val callbacks = setUp(AppSettingsState(caregiver = caregiver()))

        compose.onNodeWithText("Aryaman Darda").performScrollTo().performClick()

        assertEquals(1, callbacks.editProfile)
    }

    @Test
    fun `adding another patient is its own action`() {
        val callbacks = setUp(AppSettingsState(caregiver = caregiver()))

        compose.onNodeWithText("Add another patient").performScrollTo().performClick()

        assertEquals(1, callbacks.addPatient)
        assertEquals(0, callbacks.editProfile)
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    /**
     * The placeholder identity ships on iOS — it is what a caregiver sees for the
     * instant between the tab appearing and bootstrap landing — so it is copy,
     * not a preview fixture, and it renders when the row is absent.
     */
    @Test
    fun `with no caregiver the card shows the placeholder identity`() {
        setUp(AppSettingsState())

        compose.onNodeWithText("Jane Patel").assertIsDisplayed()
        compose.onNodeWithText("jane@patel.co").assertIsDisplayed()
    }

    /** Version copy, pinned so a bump has to be deliberate. */
    @Test
    fun `the version label is rendered`() {
        setUp(AppSettingsState(caregiver = caregiver()))

        compose.onNodeWithText(VERSION_LABEL).performScrollTo().assertIsDisplayed()
    }

    /** A screen with nothing tapped must fire nothing. */
    @Test
    fun `an untouched screen fires nothing`() {
        val callbacks = setUp(AppSettingsState(caregiver = caregiver(), patients = listOf(patient())))

        assertEquals(0, callbacks.editProfile)
        assertEquals(0, callbacks.deleteAccount)
        assertEquals(0, callbacks.signOut)
        assertEquals(emptyList<String>(), callbacks.openedPatients)
    }
}
