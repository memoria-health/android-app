package com.memoria.caregiver.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RosterScreen behavior. Spec: Features/Home/RosterView.swift +
 * EmptyRosterView.swift, PARITY.md §15.
 *
 * Two things are deliberately not asserted. Layout and colour are judged by
 * screenshot (DECISIONS.md D-004). And pull-to-refresh is not driven here: it
 * needs a fling past the indicator's threshold, and a test that pretends to
 * measure that would be asserting `PullToRefreshBox`'s behavior rather than
 * this screen's.
 *
 * The greeting strings below are uppercase because `NemaraEyebrow` owns the
 * casing — call sites pass normal-case copy and the component shouts it, so the
 * analytics and accessibility labels stay readable.
 */
@RunWith(RobolectricTestRunner::class)
class RosterScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp(
        state: RosterState,
        hourOfDay: Int = 9,
        onOpenPatient: (String) -> Unit = {},
        onAddPatient: () -> Unit = {},
        onEditProfile: () -> Unit = {},
        onRefresh: () -> Unit = {},
    ) {
        compose.setContent {
            MemoriaTheme {
                RosterScreen(
                    state = state,
                    resolvePhotoUrl = noPhotos,
                    onOpenPatient = onOpenPatient,
                    onAddPatient = onAddPatient,
                    onEditProfile = onEditProfile,
                    onRefresh = onRefresh,
                    hourOfDay = hourOfDay,
                )
            }
        }
    }

    private val twoPatients = RosterState(
        patients = listOf(
            patient(id = "p1", preferredName = "Ada", fullName = "Ada Lovelace"),
            patient(id = "p2", preferredName = "Bob", fullName = "Bob Vance"),
        ),
        caregiverDisplayName = "Ada Lovelace",
    )

    // ---- Empty state -------------------------------------------------------

    /**
     * `RosterView.swift:14` switches on `api.patients.isEmpty`, and the empty
     * branch is a different screen — not the roster with zero rows. A caregiver
     * on their first sign-in must see the invitation, not a bare "Today".
     */
    @Test
    fun `an empty roster shows the first-patient invitation`() {
        setUp(RosterState())

        compose.onNodeWithText("Add your first patient").assertIsDisplayed()
        compose
            .onNodeWithText(
                "Add their profile and you'll see everything you're verified " +
                    "to care for, all in one place.",
            )
            .assertIsDisplayed()
    }

    /** The empty state greets with "Welcome", where the populated roster greets by hour. */
    @Test
    fun `an empty roster welcomes the caregiver by first name`() {
        setUp(RosterState(caregiverDisplayName = "Ada Lovelace"))

        compose.onNodeWithText("WELCOME, ADA").assertIsDisplayed()
    }

    /** `?? "Jane"` — a caregiver record that has not loaded yet still greets someone. */
    @Test
    fun `an empty roster falls back to Jane before the caregiver loads`() {
        setUp(RosterState(caregiverDisplayName = null))

        compose.onNodeWithText("WELCOME, JANE").assertIsDisplayed()
    }

    @Test
    fun `the empty state CTA opens the new-patient sheet`() {
        var adds = 0
        setUp(RosterState(), onAddPatient = { adds++ })

        compose.onNodeWithText("Add a patient").performClick()

        assertEquals(1, adds)
    }

    // ---- Populated roster --------------------------------------------------

    @Test
    fun `a populated roster greets by time of day and lists every patient`() {
        setUp(twoPatients, hourOfDay = 14)

        compose.onNodeWithText("GOOD AFTERNOON, ADA").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Ada").assertIsDisplayed()
        compose.onNodeWithText("Bob").assertIsDisplayed()
    }

    /** With patients present the invitation must be gone, not merely scrolled away. */
    @Test
    fun `a populated roster does not show the empty state`() {
        setUp(twoPatients)

        compose.onNodeWithText("Add your first patient").assertDoesNotExist()
    }

    @Test
    fun `tapping a row opens that patient and no other`() {
        var opened: String? = null
        setUp(twoPatients, onOpenPatient = { opened = it })

        compose.onNodeWithText("Bob").performScrollTo().performClick()

        assertEquals("p2", opened)
    }

    /**
     * With patients on screen the empty state's button does not exist, so this
     * `+` is the only way to add another one — and it is the welcome tour's
     * "add a patient" anchor for exactly that reason
     * (`RosterView.swift:28-33`).
     */
    @Test
    fun `the header plus opens the new-patient sheet`() {
        var adds = 0
        var opened: String? = null
        setUp(twoPatients, onOpenPatient = { opened = it }, onAddPatient = { adds++ })

        compose.onNodeWithTag(RosterAddPatientTestTag).performClick()

        assertEquals(1, adds)
        assertNull(opened)
    }

    @Test
    fun `the header avatar opens edit profile`() {
        var edits = 0
        setUp(twoPatients, onEditProfile = { edits++ })

        compose.onNodeWithTag(RosterEditProfileTestTag).performClick()

        assertEquals(1, edits)
    }

    // ---- Row content -------------------------------------------------------

    /**
     * The roster hands each row only its own patient's events. Getting the key
     * wrong would show one patient's alerts against another's name, which is
     * the worst failure this screen has available to it.
     */
    @Test
    fun `each row counts only its own patient's unopened events`() {
        setUp(
            twoPatients.copy(
                eventsByPatient = mapOf(
                    "p1" to listOf(event(id = "a", patientId = "p1")),
                    "p2" to emptyList(),
                ),
            ),
        )

        compose.onNodeWithText("1 New event").assertIsDisplayed()
        compose.onNodeWithText("All quiet").assertIsDisplayed()
    }
}
