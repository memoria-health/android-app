package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.model.Detector

/**
 * State for the Detectors tab's own list. Spec:
 * `Features/Patient/Detectors/PatientDetectorsView.swift`, FINDINGS.md F-14.
 *
 * Same contract as `DetectorUiState.kt` above it: [PatientDetectorsScreen] is a
 * pure `@Composable` over this type, holds no ViewModel and does no I/O. iOS
 * reads `env.api.detectorsByPatient[patientId]` straight off the environment and
 * keeps the two transient values below in per-row `@State`; this is that same
 * state, hoisted into one place.
 *
 * The two groupings and the active count are derived here rather than in the
 * composable because they are the part a refactor can quietly get wrong — a
 * detector that lands in neither group disappears from the tab — and a unit test
 * can pin them without a composition.
 */
data class PatientDetectorsState(
    /**
     * The patient's full detector set, in the order the server sent it.
     *
     * **Never sorted, on either platform.** `PatientDetectorsView` splits this
     * list by source and does nothing else to it, and
     * `NemaraApiClient.loadDetectors` keeps the server's order deliberately. A
     * sort here would hide a cache bug rather than show it.
     */
    val detectors: List<Detector> = emptyList(),
    /**
     * Detectors whose delete round-trip is in flight; their overflow menus are
     * inert until it settles.
     *
     * A set rather than one id because iOS holds `deleting` in each row's own
     * `@State`, so two rows really can be deleting at once — the confirmation
     * dialog is per row and nothing stops a second one. A single slot would let
     * the second delete unstick the first row's menu.
     */
    val deletingDetectorIds: Set<String> = emptySet(),
    /**
     * Wandering only: each sensor's exit-door config, keyed by camera id. Handed
     * to every row because [DetectorRowState] already ignores it unless the row
     * is the wandering one, which is also why one map serves the whole list.
     */
    val doorConfigs: Map<String, Detector.DoorConfig> = emptyMap(),
) {
    /**
     * The two grouped sections, mirroring the web dashboard's BUILT-IN / CUSTOM.
     *
     * `filter` preserves relative order, which is the point: the server's order
     * survives the split.
     */
    val presets: List<Detector>
        get() = detectors.filter { it.source == Detector.Source.PRESET }

    val customs: List<Detector>
        get() = detectors.filter { it.source == Detector.Source.CUSTOM }

    /** The header chip. Counts the whole set, not one section. */
    val activeCount: Int
        get() = detectors.count { it.isOn }

    /** iOS: `NemaraChip("\(activeCount) active", tone: .action)`. */
    val activeCountLabel: String
        get() = "$activeCount active"

    /**
     * One row's state. [isLast] suppresses the trailing hairline, so it is the
     * position within its own section rather than within the whole list — the
     * two sections are separate cards.
     */
    fun rowState(detector: Detector, isLast: Boolean): DetectorRowState = DetectorRowState(
        detector = detector,
        isLast = isLast,
        isDeleting = detector.id in deletingDetectorIds,
        doorConfigs = doorConfigs,
    )
}
