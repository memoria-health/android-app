package com.memoria.caregiver.ui.detectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.UpdateDetectorRequest
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Produces [PatientDetectorsState] for [PatientDetectorsScreen], and runs the
 * three round-trips its rows can start. Port of what `PatientDetectorsView`
 * reads off `@Environment(AppEnvironment.self)` plus the actions
 * `DetectorRow.swift` fires inline.
 *
 * iOS has no object for the list at all: the view declares
 * `private var detectors: [Detector] { env.api.detectorsByPatient[patientId] ?? [] }`
 * and `@Observable` redraws it whenever that dictionary changes. [state] is that
 * one line, so a detector created by the add sheet, one removed by the library
 * reconcile, and a `loadDetectors` after bootstrap all reach this screen with
 * nothing plumbed in between.
 *
 * **Derived, never snapshotted.** Copying `detectorsByPatient[patientId]` into a
 * `var` at construction would look identical on first paint and then stop
 * updating — the failure the observable cache exists to prevent.
 *
 * **This screen must not sort.** The order is the server's;
 * `NemaraApiClient.loadDetectors` says so in as many words, and the Swift sorts
 * detectors nowhere. Sorting here would hide a cache bug rather than show it.
 *
 * The actions live here rather than in the host because the Swift row owns them
 * — it holds `deleting` in `@State` and calls `env.api` directly — and because
 * [Local] is the only place the per-row busy flags can live once the row itself
 * is a pure composable. What is NOT here is anything that presents a sheet: the
 * add flows and the detail sheet are navigation, which the host owns, exactly as
 * `CameraDetailViewModel` leaves `onShowPairingCode` to its host.
 */
class PatientDetectorsViewModel(
    private val api: NemaraApiClient,
    private val patientId: String,
) : ViewModel() {

    /**
     * The screen's own state, the part no cache can supply.
     *
     * Every mutation goes through [MutableStateFlow.update], a compare-and-set
     * that re-reads the current value inside the block, so a write can never
     * carry a value read before an intervening suspension (FINDINGS.md F-12).
     * That matters here because a delete and a door-config fetch are routinely
     * in flight together.
     */
    private data class Local(
        val deletingDetectorIds: Set<String> = emptySet(),
        val doorConfigs: Map<String, Detector.DoorConfig> = emptyMap(),
    )

    private val _local = MutableStateFlow(Local())

    val state: StateFlow<PatientDetectorsState> = combine(
        api.detectorsByPatientFlow,
        _local,
    ) { detectorsByPatient, local ->
        PatientDetectorsState(
            detectors = detectorsByPatient[patientId].orEmpty(),
            deletingDetectorIds = local.deletingDetectorIds,
            doorConfigs = local.doorConfigs,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly, for the reason RosterViewModel gives: every upstream is an
        // in-memory StateFlow, so staying hot costs one data class per change —
        // while WhileSubscribed would hand the first frame after a configuration
        // change the empty default, which on this screen is not a blank list but
        // the whole "No detectors yet" card.
        started = SharingStarted.Eagerly,
        initialValue = PatientDetectorsState(),
    )

    /**
     * Flip one detector on or off, patient-wide.
     *
     * **Nothing is written locally first.** The switch renders `detector.isOn`
     * straight from the cache and `updateDetector` replaces the cached row with
     * the server's answer, so the switch moves when the PATCH lands and stays
     * put when it does not. iOS's binding setter is the same shape.
     *
     * **The failure is swallowed, deliberately**, matching the Swift's
     * `_ = try? await …`. The row has no error surface on either platform; what
     * the caregiver sees is the switch not moving. Adding a message here would
     * be a new behaviour, not a fix.
     */
    fun toggleDetector(detector: Detector, isOn: Boolean) {
        viewModelScope.launch {
            try {
                api.updateDetector(detector.id, UpdateDetectorRequest(isOn = isOn))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Silent on both platforms, as above.
            }
        }
    }

    /**
     * Remove the detector from the patient. Already confirmed by the row's
     * dialog before this is reached.
     *
     * `deleteDetector` drops the row from the client's cache itself, so the list
     * is correct the moment this returns and nothing needs reloading. Failure is
     * swallowed for the same reason as [toggleDetector] — the row simply stays.
     */
    fun deleteDetector(detector: Detector) {
        // The row disables its own overflow menu while its delete runs, but the
        // callback is a plain function; two DELETEs of one id would make the
        // second a 404 that the cache cleanup then treats as "nothing to remove".
        if (detector.id in _local.value.deletingDetectorIds) return
        _local.update { it.copy(deletingDetectorIds = it.deletingDetectorIds + detector.id) }
        viewModelScope.launch {
            try {
                api.deleteDetector(detector.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Silent, matching iOS's `try?`.
            } finally {
                // Cleared in `finally`, as iOS clears it in `defer`: a
                // success-only reset would leave a failed row's menu dead, and
                // the row survives the failure precisely so it can be retried.
                _local.update { it.copy(deletingDetectorIds = it.deletingDetectorIds - detector.id) }
            }
        }
    }

    /**
     * Re-read every sensor's exit doors, for the wandering row's activation hint.
     *
     * One patient-wide fetch with no detector argument, as iOS's
     * `wanderingDoors(forPatient:)` is, and uncached by design — the config
     * lives per camera in its own endpoint.
     *
     * Silent on failure, exactly as iOS's `guard let list = try? … else
     * { return }` is: this is a hint under one row, and a red sentence for a
     * fetch the caregiver did not ask for would be noise. The map is left alone
     * rather than emptied, so a transient failure does not blank a hint that was
     * already correct.
     */
    fun loadDoorConfigs() {
        viewModelScope.launch {
            // Awaited FIRST, into a local, so the write below carries no value
            // read before this suspended (FINDINGS.md F-12).
            val list = try {
                api.camerasDirect.wanderingDoors(patientId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return@launch
            }
            // A camera the caregiver never configured comes back with a null
            // config and is left out of the map entirely, as iOS's
            // `if let dc = entry.doorConfig` leaves it out.
            val configs = list.mapNotNull { entry ->
                entry.doorConfig?.let { entry.cameraId to it }
            }.toMap()
            _local.update { it.copy(doorConfigs = configs) }
        }
    }
}
