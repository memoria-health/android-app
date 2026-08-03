package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.model.Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PatientDetectorsState]'s derivations. Spec: `PatientDetectorsView.swift`.
 *
 * What the Detectors tab can quietly get wrong: a detector that lands in neither
 * group and so disappears from the tab, a count that reads the wrong set, and a
 * per-row busy flag that leaks onto the rows next to it.
 */
class PatientDetectorsUiStateTest {

    private val fx = DetectorScreenFixtures

    private fun preset(id: String, name: String, isOn: Boolean = true) =
        fx.detector(id = id, name = name, isOn = isOn)

    private fun custom(id: String, name: String, isOn: Boolean = true) =
        fx.detector(id = id, name = name, isOn = isOn).copy(source = Detector.Source.CUSTOM)

    /**
     * The split is `filter`, which preserves relative order, and that is the
     * contract: the Swift sorts detectors nowhere and neither does the client's
     * cache, so the server's order has to survive the grouping.
     */
    @Test
    fun `the split keeps the server's order inside each group`() {
        // Both groups are in reverse alphabetical order on purpose, so any sort
        // slipped into either one shows up here.
        val state = PatientDetectorsState(
            detectors = listOf(
                preset("p1", "Wandering"),
                custom("c1", "Kettle"),
                preset("p2", "Fall"),
                custom("c2", "Front step"),
            ),
        )

        assertEquals(listOf("Wandering", "Fall"), state.presets.map { it.name })
        assertEquals(listOf("Kettle", "Front step"), state.customs.map { it.name })
    }

    /** Every detector belongs to exactly one group, or the tab loses rows. */
    @Test
    fun `no detector falls out of both groups`() {
        val detectors = listOf(preset("p1", "Fall"), custom("c1", "Kettle"))
        val state = PatientDetectorsState(detectors = detectors)

        assertEquals(detectors.size, state.presets.size + state.customs.size)
    }

    /** The chip counts what is ON across BOTH groups, not the whole set. */
    @Test
    fun `the active chip counts every detector that is on`() {
        val state = PatientDetectorsState(
            detectors = listOf(
                preset("p1", "Fall", isOn = true),
                preset("p2", "Wandering", isOn = false),
                custom("c1", "Kettle", isOn = true),
            ),
        )

        assertEquals(2, state.activeCount)
        assertEquals("2 active", state.activeCountLabel)
    }

    @Test
    fun `a patient with everything off still reads a count`() {
        val state = PatientDetectorsState(detectors = listOf(preset("p1", "Fall", isOn = false)))

        assertEquals("0 active", state.activeCountLabel)
    }

    /**
     * The busy flag belongs to the one row whose delete is running. A flag that
     * spread would freeze the overflow menu on rows the caregiver never touched.
     */
    @Test
    fun `only the deleting row is marked busy`() {
        val deleting = preset("p1", "Fall")
        val other = preset("p2", "Wandering")
        val state = PatientDetectorsState(
            detectors = listOf(deleting, other),
            deletingDetectorIds = setOf("p1"),
        )

        assertTrue(state.rowState(deleting, isLast = false).isDeleting)
        assertFalse(state.rowState(other, isLast = true).isDeleting)
    }

    /** Two rows can be deleting at once — each confirmation dialog is its own. */
    @Test
    fun `two deletes in flight mark both rows`() {
        val a = preset("p1", "Fall")
        val b = preset("p2", "Wandering")
        val state = PatientDetectorsState(
            detectors = listOf(a, b),
            deletingDetectorIds = setOf("p1", "p2"),
        )

        assertTrue(state.rowState(a, isLast = false).isDeleting)
        assertTrue(state.rowState(b, isLast = true).isDeleting)
    }

    /**
     * The wandering row's activation hint reads its sensor count off this map,
     * so it has to reach the row rather than stopping at the list.
     */
    @Test
    fun `the exit-door configs reach the row`() {
        val wandering = preset("p1", "Left the house")
        val state = PatientDetectorsState(
            detectors = listOf(wandering),
            doorConfigs = mapOf(
                "cam-a" to Detector.DoorConfig(doors = listOf(fx.door())),
            ),
        )

        assertEquals(1, state.rowState(wandering, isLast = true).activeSensorCount)
    }

    /** Nothing loaded yet is the "No detectors yet" card, not a blank list. */
    @Test
    fun `a patient with no detectors reads empty in both groups`() {
        val state = PatientDetectorsState()

        assertTrue(state.detectors.isEmpty())
        assertTrue(state.presets.isEmpty())
        assertTrue(state.customs.isEmpty())
        assertEquals("0 active", state.activeCountLabel)
    }
}
