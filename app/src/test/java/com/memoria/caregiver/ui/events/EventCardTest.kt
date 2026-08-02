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

    /** Two safety events and one routine one, so a stuck label shows as a count. */
    @Test
    fun `category drives the pill label`() {
        setUp()
        compose.onAllNodes(hasText("SAFETY")).assertCountEquals(2)
        compose.onAllNodes(hasText("ROUTINE")).assertCountEquals(1)
    }

    /**
     * iOS: `if event.source == .custom`. Exactly one of the three cards is
     * custom, so a count of one is the assertion — "displayed somewhere" would
     * pass even if the pill leaked onto every card.
     */
    @Test
    fun `the custom pill appears on the custom event only`() {
        setUp()
        compose.onAllNodes(hasText("CUSTOM")).assertCountEquals(1)
    }

    /** iOS: the REVIEWED capsule replaces the chevron, on reviewed events only. */
    @Test
    fun `only the reviewed event carries the reviewed badge`() {
        setUp()
        compose.onAllNodes(hasText("REVIEWED")).assertCountEquals(1)
    }
}
