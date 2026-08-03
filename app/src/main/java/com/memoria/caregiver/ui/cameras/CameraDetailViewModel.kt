package com.memoria.caregiver.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.UpdateCameraRequest
import com.memoria.caregiver.data.remote.ApiError
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
 * Produces [CameraDetailState] for [CameraDetailScreen] and runs the six actions
 * that screen can start. Port of `CameraDetailView`'s computed properties, its
 * `@State`, and its `// MARK: - Actions` block.
 *
 * Two halves, and the split is the whole design:
 *
 * - **The camera and the detector list are derived** from the client's cache,
 *   exactly as iOS's `camera` and `detectors` computed properties are. The
 *   camera becomes null the moment a delete lands anywhere — a Realtime DELETE,
 *   the poll, or this screen's own [delete] — and the screen then renders
 *   "Sensor not found" rather than controls for a row that is gone.
 * - **Everything else is this screen's own**, held in [Local]. iOS keeps the
 *   same nine values in `@State`: they exist for the duration of one visit,
 *   nothing outside can observe them, and two of them (the assignment set and
 *   the door config) come from endpoints the client deliberately does not cache.
 *
 * `onShowPairingCode` and `onOpenDoorPicker` are not here. They present a sheet,
 * which is the host's, and iOS does both with `@State` booleans on the view.
 */
class CameraDetailViewModel(
    private val api: NemaraApiClient,
    private val patientId: String,
    private val cameraId: String,
    /**
     * `dismiss()` after a successful delete. iOS pops back to the camera list
     * itself; here the navigation graph owns the back stack, so this is how the
     * pop is asked for. Taken as a function for the same reason
     * `AppSettingsViewModel` takes its two account actions that way.
     *
     * Delete is the only action that leaves the screen. **Unpair deliberately
     * stays**, because the camera flips to unpaired and the "Show pairing code"
     * section appears in place of the unpair button.
     */
    private val onDeleted: () -> Unit,
) : ViewModel() {

    /**
     * The screen's own state, in one flow rather than nine.
     *
     * Every mutation goes through [MutableStateFlow.update], which is a
     * compare-and-set: it re-reads the current value inside the block, so a
     * write can never carry a value that was read before an intervening
     * suspension. That is the shape FINDINGS.md F-12 was — a map read before the
     * fetch it depended on suspended, then written back over a concurrent load.
     * Several actions here are in flight at once by design (a detector toggle
     * and a door-config reload, say), so this matters.
     */
    private data class Local(
        val assignedDetectorIds: Set<String> = emptySet(),
        val assignmentsError: String? = null,
        val togglingDetectorId: String? = null,
        val isTogglingAiPause: Boolean = false,
        val isTogglingDisconnect: Boolean = false,
        val isDeleting: Boolean = false,
        val isUnpairing: Boolean = false,
        val actionError: String? = null,
        val doorConfig: Detector.DoorConfig? = null,
    )

    private val _local = MutableStateFlow(Local())

    val state: StateFlow<CameraDetailState> = combine(
        api.camerasByPatientFlow,
        api.detectorsByPatientFlow,
        _local,
    ) { camerasByPatient, detectorsByPatient, local ->
        CameraDetailState(
            camera = camerasByPatient[patientId]?.firstOrNull { it.id == cameraId },
            // The patient's FULL detector set, not this camera's assigned
            // subset. The checkmarks come from `assignedDetectorIds`; the rows
            // themselves are every detector the patient has.
            detectors = detectorsByPatient[patientId].orEmpty(),
            assignedDetectorIds = local.assignedDetectorIds,
            assignmentsError = local.assignmentsError,
            togglingDetectorId = local.togglingDetectorId,
            isTogglingAiPause = local.isTogglingAiPause,
            isTogglingDisconnect = local.isTogglingDisconnect,
            isDeleting = local.isDeleting,
            isUnpairing = local.isUnpairing,
            actionError = local.actionError,
            doorConfig = local.doorConfig,
        )
    }.stateIn(
        scope = viewModelScope,
        // See CameraListViewModel. The default here renders "Sensor not found",
        // so a frame of it after a configuration change would be a visible lie.
        started = SharingStarted.Eagerly,
        initialValue = CameraDetailState(),
    )

    /**
     * The two per-screen fetches, from the host's `LaunchedEffect`.
     *
     * iOS runs these from `.task(id: cameraId)`, which fires when the view
     * appears rather than when it is constructed — so this is a method and not
     * an `init` block. Both endpoints are uncached by design: the assignment set
     * lives only in the join table, and the door config is per camera.
     *
     * iOS runs a third step, `prewarmDoorFrame()`, which pulls a LiveKit frame
     * and pre-runs door detection so opening the picker is instant. It has no
     * counterpart yet — `WanderingDoorCache` is not ported — and it is a
     * latency optimisation with no state of its own, so nothing here depends on
     * it. Reported rather than faked.
     */
    fun load() {
        viewModelScope.launch {
            loadAssignments()
            loadDoorConfig()
        }
    }

    /**
     * Re-read this sensor's exit doors. The picker's "saved" callback, which iOS
     * wires as `onSaved: { Task { await loadDoorConfig() } }` — the picker
     * writes through `POST /v1/cameras/{id}/wandering-door`, whose response goes
     * nowhere near the client's cache, so this screen would otherwise keep
     * showing the doors it read on entry.
     */
    fun reloadDoorConfig() {
        viewModelScope.launch { loadDoorConfig() }
    }

    // -----------------------------------------------------------------------
    // Per-camera detector assignment
    // -----------------------------------------------------------------------

    /**
     * Flip one detector's membership in this camera's set, optimistically, and
     * PUT the whole new set.
     *
     * Three details are load-bearing, all of them iOS's:
     *
     * - **One flip at a time.** `guard togglingDetectorId == nil else { return }`
     *   — the screen only disables the row that is saving, so a second tap on a
     *   different row is reachable, and two overlapping PUTs of a whole-set
     *   replace would settle on whichever landed last.
     * - **The rollback target is captured before the optimistic write**, not
     *   re-read in the catch, where it would already hold the optimistic value.
     * - **The server's echo is adopted as truth**, because `setCameraDetectors`
     *   is free to normalise the set and the response is what the UI should show.
     */
    fun toggleDetector(detector: Detector) {
        val previous = _local.value
        if (previous.togglingDetectorId != null) return
        val before = previous.assignedDetectorIds
        val next = if (detector.id in before) before - detector.id else before + detector.id
        _local.update { it.copy(assignedDetectorIds = next, togglingDetectorId = detector.id) }
        viewModelScope.launch {
            try {
                val settled = api.camerasDirect.setCameraDetectors(cameraId, next.toList())
                _local.update { it.copy(assignedDetectorIds = settled.toSet()) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _local.update {
                    it.copy(
                        assignedDetectorIds = before,
                        assignmentsError = "Couldn't update: " + messageFor(e),
                    )
                }
            } finally {
                // Cleared in `finally`, as iOS clears it in `defer`: a
                // success-only reset leaves the row permanently busy, which is
                // the bug the web grid shipped.
                _local.update { it.copy(togglingDetectorId = null) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // The two state flips
    // -----------------------------------------------------------------------

    /** Pause or resume the AI tick. Video keeps streaming either way. */
    fun toggleAiPause() {
        val camera = currentCamera() ?: return
        runAction(
            busy = CONTROLS_BUSY,
            begin = { it.copy(isTogglingAiPause = true, actionError = null) },
            end = { it.copy(isTogglingAiPause = false) },
            failurePrefix = "Couldn't update: ",
        ) {
            api.updateCamera(cameraId, UpdateCameraRequest(aiPaused = !camera.aiPaused))
        }
    }

    /** The wall phone leaves LiveKit entirely. Confirmed by the screen's dialog. */
    fun disconnect() {
        runAction(
            busy = CONTROLS_BUSY,
            begin = { it.copy(isTogglingDisconnect = true, actionError = null) },
            end = { it.copy(isTogglingDisconnect = false) },
            failurePrefix = "Couldn't disconnect: ",
        ) {
            api.updateCamera(
                cameraId,
                UpdateCameraRequest(desiredState = Camera.DesiredState.DISCONNECTED),
            )
        }
    }

    /**
     * The wall phone rejoins LiveKit, in about 1-2 seconds.
     *
     * **The "Connecting…" window is NOT opened here.** `NemaraApiClient
     * .updateCamera` opens it itself, on the disconnected → connected edge,
     * comparing against the cached row before it replaces it. Opening a second
     * one here would push the deadline out by however long the round trip took,
     * so a sensor that never comes back would sit on "Connecting…" past the ten
     * seconds PARITY.md §10 fixes.
     */
    fun reconnect() {
        runAction(
            busy = CONTROLS_BUSY,
            begin = { it.copy(isTogglingDisconnect = true, actionError = null) },
            end = { it.copy(isTogglingDisconnect = false) },
            failurePrefix = "Couldn't reconnect: ",
        ) {
            api.updateCamera(
                cameraId,
                UpdateCameraRequest(desiredState = Camera.DesiredState.CONNECTED),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Pairing and deletion
    // -----------------------------------------------------------------------

    /**
     * Return a paired phone camera to unpaired without deleting it.
     *
     * The cache cleanup is the client's: `unpairCamera` reloads the owning
     * patient's cameras, best-effort, so the row's `paired` flips false and the
     * screen swaps the unpair button for the pairing-code section. Reloading
     * again here would double the request for the same rows.
     */
    fun unpair() {
        runAction(
            busy = { it.isUnpairing },
            begin = { it.copy(isUnpairing = true, actionError = null) },
            end = { it.copy(isUnpairing = false) },
            failurePrefix = "Couldn't unpair: ",
        ) {
            api.unpairCamera(cameraId)
        }
    }

    /**
     * Delete the camera row, then leave.
     *
     * `deleteCamera` removes the row from the client's cache itself, so the list
     * behind this screen is already correct by the time [onDeleted] runs — and
     * `state.camera` is already null, which is why the pop happens rather than
     * leaving "Sensor not found" on screen.
     */
    fun delete() {
        runAction(
            busy = { it.isDeleting },
            begin = { it.copy(isDeleting = true, actionError = null) },
            end = { it.copy(isDeleting = false) },
            failurePrefix = "Couldn't delete: ",
            onSuccess = onDeleted,
        ) {
            api.deleteCamera(cameraId)
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * The five one-shot actions differ only in which rows they make busy and
     * which sentence they prefix a failure with, so the shape they share is
     * written once.
     *
     * [busy] repeats the screen's own `enabled` condition for this action, and
     * repeats it exactly — Pause AI, Disconnect and Reconnect share one
     * condition ([CameraDetailState.controlsBusy]) while unpair and delete each
     * gate on themselves alone. Widening it would silently drop a delete tapped
     * during a reconnect, which the screen permits.
     *
     * [begin] is applied synchronously, before the launch: the busy flag is what
     * stops a second tap, and a flag raised inside the coroutine would be raised
     * a dispatch too late.
     */
    private fun runAction(
        busy: (Local) -> Boolean,
        begin: (Local) -> Local,
        end: (Local) -> Local,
        failurePrefix: String,
        onSuccess: () -> Unit = {},
        action: suspend () -> Unit,
    ) {
        // The screen disables the row, but the callback is a plain function and
        // two overlapping PATCHes of `desired_state` would settle on whichever
        // reply landed last, not on what the caregiver asked for second.
        if (busy(_local.value)) return
        _local.update(begin)
        viewModelScope.launch {
            try {
                action()
                onSuccess()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _local.update { it.copy(actionError = failurePrefix + messageFor(e)) }
            } finally {
                // iOS drops these in `defer`, not in `catch`. The row stays
                // mounted across the state change, and a success-only path left
                // the button permanently disabled.
                _local.update(end)
            }
        }
    }

    private suspend fun loadAssignments() {
        _local.update { it.copy(assignmentsError = null) }
        try {
            // Awaited FIRST, into a local, so the write below carries no value
            // read before this suspended.
            val ids = api.camerasDirect.getCameraDetectors(cameraId)
            _local.update { it.copy(assignedDetectorIds = ids.toSet()) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Throwable) {
            _local.update {
                it.copy(assignmentsError = "Couldn't load detector assignments: " + messageFor(e))
            }
        }
    }

    /**
     * Silent on failure, exactly as iOS's `guard let list = try? …` is: the exit
     * doors are one row on a screen full of working controls, and a red sentence
     * for a fetch the caregiver did not ask for would be noise. A camera with no
     * saved config reads null, which the screen renders as "Not set".
     */
    private suspend fun loadDoorConfig() {
        val list = try {
            api.camerasDirect.wanderingDoors(patientId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return
        }
        val config = list.firstOrNull { it.cameraId == cameraId }?.doorConfig
        _local.update { it.copy(doorConfig = config) }
    }

    /**
     * The camera as the cache has it right now.
     *
     * Read off the client rather than off [state] so an action works before the
     * shared flow's first emission — the same value either way, and iOS's
     * `camera` computed property is likewise a direct dictionary lookup.
     */
    private fun currentCamera(): Camera? =
        api.camerasByPatientFlow.value[patientId]?.firstOrNull { it.id == cameraId }

    /**
     * Verbatim from [ApiError.errorDescription], which is what Swift's
     * `error.localizedDescription` resolves to for everything these paths throw.
     * The prefixes above ("Couldn't update: " and friends) are iOS's own and are
     * equally fixed.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }

    private companion object {
        /**
         * [CameraDetailState.controlsBusy], read off the private half. The
         * screen disables Pause AI, Disconnect and Reconnect together, because
         * all three are the same physical sensor changing state.
         */
        val CONTROLS_BUSY: (Local) -> Boolean =
            { it.isTogglingAiPause || it.isTogglingDisconnect }
    }
}
