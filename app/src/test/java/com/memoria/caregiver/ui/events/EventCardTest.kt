package com.memoria.caregiver.ui.events

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * EventCard behavior. Spec: `Features/Patient/Events/EventCard.swift`.
 *
 * The colour and opacity axes are not asserted — parity there is judged by
 * screenshot (DECISIONS.md D-004). What is asserted is the set of badges the
 * card shows, which is the part a refactor can silently get wrong: the CUSTOM
 * pill is conditional, and the REVIEWED pill *replaces* the chevron rather than
 * joining it.
 */
@RunWith(RobolectricTestRunner::class)
class EventCardTest {

    @get:Rule val compose = createComposeRule()

    /** Every card in one composition — setContent may only be called once. */
    private fun setUp() {
        compose.setContent {
            MemoriaTheme {
                Column {
                    EventCard(sampleEvent(id = "1", title = "Preset safety"))
                    EventCard(
                        sampleEvent(
                            id = "2",
                            title = "Custom routine",
                            category = Detector.Category.ROUTINE,
                            source = Detector.Source.CUSTOM,
                        ),
                    )
                    EventCard(sampleEvent(id = "3", title = "Already reviewed", reviewed = true))
                }
            }
        }
    }

    /**
     * The category pills are GONE (iOS `daf1063`), and this asserts their
     * absence rather than being deleted.
     *
     * Deleting the test would have left nothing saying the removal was
     * deliberate, and the next person to see a bare card would be free to add
     * the pills back. The category was the same word on nearly every card, so
     * it separated nothing — and the glyph beside the title already carries the
     * type.
     */
    @Test
    fun `no category or custom pills are rendered`() {
        setUp()
        compose.onAllNodes(hasText("SAFETY")).assertCountEquals(0)
        compose.onAllNodes(hasText("ROUTINE")).assertCountEquals(0)
        compose.onAllNodes(hasText("CUSTOM")).assertCountEquals(0)
    }

    /** iOS: the REVIEWED capsule replaces the chevron, on reviewed events only. */
    @Test
    fun `only the reviewed event carries the reviewed badge`() {
        setUp()
        compose.onAllNodes(hasText("REVIEWED")).assertCountEquals(1)
    }
}
