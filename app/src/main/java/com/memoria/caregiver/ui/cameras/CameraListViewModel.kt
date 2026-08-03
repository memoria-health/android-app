package com.memoria.caregiver.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Produces [CameraListState] for [CameraListScreen]. Port of what
 * `PatientCamerasView` reads off `@Environment(AppEnvironment.self)`.
 *
 * iOS has no object here either: the view declares
 * `private var cameras: [Camera] { env.api.camerasByPatient[patientId] ?? [] }`
 * and `@Observable` redraws it whenever that dictionary changes. [state] is that
 * one line, so a Realtime camera INSERT, the 1-second state poll, and a
 * `loadCameras` after pairing all reach this screen with nothing plumbed in
 * between.
 *
 * **Nothing else belongs here.** The two callbacks the screen declares —
 * `onSelectCamera` and `onAddSensor` — are navigation, which iOS does with a
 * `NavigationLink` and a `.sheet`, and neither touches this state. There is
 * deliberately no load or refresh method: `bootstrap()` and `refresh()` on the
 * client populate this patient's cameras, exactly as they do on iOS, and adding
 * a second load path here would fetch the same rows twice on every tab visit.
 *
 * **Derived, never snapshotted.** Copying `camerasByPatient[patientId]` into a
 * `var` at construction would look identical on first paint and then stop
 * updating — the failure the observable cache exists to prevent.
 *
 * **This screen must not sort.** The order is the server's, and the two paths
 * that reorder cameras (the poll's write-back and the Realtime insert/update
 * handlers) already sort by `displayId` before writing, matching Swift's
 * `NemaraAPIClient` lines 1352/1390/1406. Sorting again here would hide a cache
 * bug rather than show it.
 */
class CameraListViewModel(
    api: NemaraApiClient,
    private val patientId: String,
) : ViewModel() {

    val state: StateFlow<CameraListState> = api.camerasByPatientFlow
        .map { CameraListState(cameras = it[patientId].orEmpty()) }
        .stateIn(
            scope = viewModelScope,
            // Eagerly, for the reason RosterViewModel gives: the upstream is an
            // in-memory StateFlow, so staying hot costs one data class per
            // change — while WhileSubscribed would hand the first frame after a
            // configuration change the empty default, which on this screen is
            // not a blank list but the whole "No sensors yet" card.
            started = SharingStarted.Eagerly,
            initialValue = CameraListState(),
        )
}
