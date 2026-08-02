package com.memoria.caregiver.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PatientRow behavior. Spec: Features/Home/PatientRow.swift, PARITY.md §15.
 *
 * The row's colour treatment (terra tint, terra border, pulse dot) is asserted
 * in `RosterUiStateTest` through `patientRowHasUnopenedSafety`, because parity
 * on the painted result is judged by screenshot (DECISIONS.md D-004) and a test
 * that read a Color off a node would only restate the implementation. What is
 * worth pinning here is the text the row puts in front of a caregiver.
 */
@RunWith(RobolectricTestRunner::class)
class PatientRowTest {

    @get:Rule val compose = createComposeRule()

    private fun setUp(patient: Patient, events: List<DetectionEvent>) {
        compose.setContent {
            MemoriaTheme {
                PatientRow(
                    patient = patient,
                    events = events,
                    resolvePhotoUrl = noPhotos,
                )
            }
        }
    }

    @Test
    fun `a patient with nothing unopened reads All quiet`() {
        setUp(patient(), events = listOf(event(opened = true)))

        compose.onNodeWithText("All quiet").assertIsDisplayed()
    }

    @Test
    fun `the status line counts only the unopened events`() {
        setUp(
            patient(),
            events = listOf(
                event(id = "a", opened = false),
                event(id = "b", opened = false),
                event(id = "c", opened = true),
            ),
        )

        compose.onNodeWithText("2 New events").assertIsDisplayed()
    }

    @Test
    fun `the row shows the preferred name`() {
        setUp(patient(preferredName = "Ada", fullName = "Ada Lovelace"), events = emptyList())

        compose.onNodeWithText("Ada").assertIsDisplayed()
    }

    /**
     * The load-bearing half of the pinned-`false` decision (PARITY.md §15).
     * `cameras_paused` no longer gates detection or display, so a patient whose
     * flag is set must still read as a normal row — the roster deliberately
     * stopped showing a "Monitoring paused" treatment that no longer matched
     * what the cameras were doing.
     *
     * Written against a patient with `camerasPaused = true` precisely because
     * that is the payload that would resurrect the old behavior if someone
     * rewired the flag without reading the comment.
     */
    @Test
    fun `a patient with cameras_paused set still reads as a normal row`() {
        setUp(
            patient(camerasPaused = true),
            events = listOf(event(category = Detector.Category.SAFETY)),
        )

        compose.onNodeWithText("Monitoring paused").assertDoesNotExist()
        compose.onNodeWithText("PAUSED").assertDoesNotExist()
        compose.onNodeWithText("1 New event").assertIsDisplayed()
    }
}
