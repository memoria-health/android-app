package com.memoria.caregiver.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the roster header announces, and what a tap near it reaches.
 * Spec: FINDINGS.md F-31, Features/Home/RosterView.swift.
 *
 * The two controls here are the app's worst case for a screen reader: both are
 * drawn shapes with no text — a plus and an avatar showing one letter — and
 * they are the only ways to add a patient or reach the profile. Before this
 * they announced as an unnamed clickable and as the letter "A".
 */
@RunWith(RobolectricTestRunner::class)
class RosterAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private var addPatients = 0
    private var editProfiles = 0

    private fun setUp() {
        compose.setContent {
            MemoriaTheme {
                RosterScreen(
                    state = RosterState(
                        patients = listOf(patient(id = "p1", preferredName = "Ada")),
                        caregiverDisplayName = "Ada Lovelace",
                    ),
                    resolvePhotoUrl = noPhotos,
                    onOpenPatient = {},
                    onAddPatient = { addPatients++ },
                    onEditProfile = { editProfiles++ },
                    onRefresh = {},
                    hourOfDay = 9,
                )
            }
        }
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun `the plus is named for what it does, not left as a bare glyph`() {
        setUp()
        compose.onNodeWithTag(RosterAddPatientTestTag)
            .assert(hasContentDescription("Add a patient"))
    }

    /**
     * The same words the empty-roster button uses. Two entry points to one sheet
     * that announce differently is two features to a caregiver who cannot see
     * that they are the same.
     */
    @Test
    fun `the plus and the empty-state button say the same thing`() {
        compose.setContent {
            MemoriaTheme {
                RosterScreen(
                    state = RosterState(caregiverDisplayName = "Ada Lovelace"),
                    resolvePhotoUrl = noPhotos,
                    onOpenPatient = {},
                    onAddPatient = {},
                    onEditProfile = {},
                    onRefresh = {},
                    hourOfDay = 9,
                )
            }
        }
        compose.onNodeWithText("Add a patient").assertExists()
    }

    @Test
    fun `the plus announces as a button`() {
        setUp()
        compose.onNodeWithTag(RosterAddPatientTestTag).assert(isButton())
    }

    /** The avatar's letter is the caregiver's initial, not a description of it. */
    @Test
    fun `the profile avatar is named for what it opens`() {
        setUp()
        compose.onNodeWithTag(RosterEditProfileTestTag)
            .assert(hasContentDescription("Edit profile"))
        compose.onNodeWithTag(RosterEditProfileTestTag).assert(isButton())
    }

    @Test
    fun `a patient row announces as a button`() {
        setUp()
        compose.onNodeWithText("Ada").assert(isButton())
    }

    /**
     * The reason the 20dp plus was left drawn at 20dp.
     *
     * Compose inflates every clickable's hit rect to the platform's 48dp
     * minimum, so a tap well outside the glyph still lands. Growing the glyph's
     * LAYOUT box to 44dp would have pushed the avatar sideways for no gain —
     * see the F-31 report. This is the measurement that decision rests on, so
     * it is pinned: if a future change puts a clipping or clickable ancestor
     * around the header, this fails and the decision has to be revisited.
     */
    @Test
    fun `a tap 10dp clear of the plus still adds a patient`() {
        setUp()
        compose.onNodeWithTag(RosterAddPatientTestTag).performTouchInput {
            // 10, not 15: the expansion is to 48dp TOTAL, so a 20dp glyph gains
            // 14dp a side. 15 lands outside it and does not fire, which is the
            // boundary this number is chosen against.
            click(Offset(-10.dp.toPx(), height / 2f))
        }
        assertEquals(1, addPatients)
    }

    @Test
    fun `the profile avatar is reachable by its name`() {
        setUp()
        compose.onNodeWithContentDescription("Edit profile").performClick()
        assertEquals(1, editProfiles)
    }
}
