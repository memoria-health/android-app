package com.memoria.caregiver.ui.detectors

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.WanderingDoorInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives [WanderingDoorPickerScreen]. Port of the `@State` and the four `Task`s
 * on `WanderingDoorPickerView`.
 *
 * The screen was already complete — the geometry, the hit testing, the stacked
 * detection filter, the payload builder — and had no producer, which is why it
 * was the last unreachable screen in the app (FINDINGS.md F-41).
 *
 * ## The order of the two loads
 *
 * The frame comes first and the doors come **after**, in the background, because
 * the caregiver can use this screen without the VLM: tapping places a door. So
 * the picture appears as soon as it is grabbed, `isDetectingDoors` says the
 * eyebrow's piece out loud, and a failed detection costs nothing.
 *
 * Existing configuration is applied once, when detection returns, so a stored
 * box can be matched against a detected door rather than duplicated beside it.
 */
class WanderingDoorPickerViewModel(
    private val cameraId: String,
    private val patientId: String,
    private val identity: String?,
    cameraName: String,
    private val cache: WanderingDoorCache,
    private val loadExisting: suspend () -> Detector.DoorConfig?,
    private val save: suspend (List<WanderingDoorInput>, Boolean) -> Unit,
    private val decodeFrame: (ByteArray) -> ImageBitmap? = ::decodeJpeg,
) : ViewModel() {

    private val _state = MutableStateFlow(WanderingDoorPickerState(cameraName = cameraName))
    val state: StateFlow<WanderingDoorPickerState> = _state.asStateFlow()

    /**
     * True once a saved configuration has been folded into the selection, so it
     * happens exactly once. iOS calls the same guard `didApplyExisting`.
     *
     * Without it, a rescan would re-apply the stored doors over whatever the
     * caregiver had just changed.
     */
    private var didApplyExisting = false

    /** Set when the save succeeds, so the host can dismiss. */
    private val _didSave = MutableStateFlow(false)
    val didSave: StateFlow<Boolean> = _didSave.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)

    /** Set when a save fails; the host shows it and clears it. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    init {
        load(isReload = false)
    }

    /**
     * Grab the frame, then detect doors behind it.
     *
     * @param isReload the Rescan button and the "Try again" on a failure. Forces
     *   a fresh capture and drops the doors that belonged to the old picture.
     */
    fun load(isReload: Boolean = true) {
        val cameraIdentity = identity
        if (cameraIdentity == null) {
            // A camera with no LiveKit identity has never been paired, so there
            // is nothing to photograph. Blocked rather than Failed: retrying
            // will not change it.
            _state.value = _state.value.copy(
                phase = WanderingDoorPhase.Blocked(NO_IDENTITY),
            )
            return
        }
        _state.value = _state.value.copy(
            phase = WanderingDoorPhase.Loading(
                if (isReload) WanderingDoorCopy.LOADING_RELOAD else WanderingDoorCopy.LOADING_INITIAL,
            ),
            // Dropped so the old picture cannot sit under a spinner and then
            // have new doors drawn over it.
            frame = if (isReload) null else _state.value.frame,
            detectedDoors = if (isReload) emptyList() else _state.value.detectedDoors,
        )
        viewModelScope.launch {
            val outcome = if (isReload) {
                cache.reloadFrame(cameraId, patientId, cameraIdentity)
            } else {
                cache.ensureFrame(cameraId, patientId, cameraIdentity)
            }
            when (outcome) {
                is DoorLoadOutcome.Blocked -> {
                    _state.value = _state.value.copy(phase = WanderingDoorPhase.Blocked(outcome.message))
                    return@launch
                }
                is DoorLoadOutcome.Failed -> {
                    _state.value = _state.value.copy(phase = WanderingDoorPhase.Failed(outcome.message))
                    return@launch
                }
                DoorLoadOutcome.Ok -> Unit
            }

            val bytes = cache.cachedFrame(cameraId)
            val image = bytes?.let(decodeFrame)
            if (image == null) {
                _state.value = _state.value.copy(
                    phase = WanderingDoorPhase.Failed(WanderingDoorCache.CAPTURE_FAILED),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                phase = WanderingDoorPhase.Ready,
                frame = image,
                isDetectingDoors = true,
            )
            detectDoorsInBackground()
        }
    }

    private suspend fun detectDoorsInBackground() {
        val found = cache.ensureDoors(cameraId)
        val current = _state.value
        // Filtered against the manual doors AS THEY ARE NOW, once. A later
        // manual placement does not retire a dot on iOS, and cannot land on one
        // anyway — a tap within the hit radius toggles the dot instead.
        val doors = dropStackedDetections(found, current.selection.manualDoors)

        val selection = if (!didApplyExisting) {
            didApplyExisting = true
            val existing = try {
                loadExisting()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // A configuration we cannot read is not a reason to block the
                // screen: the caregiver can still choose doors. It is a reason
                // not to pretend there was none, which is why nothing is
                // cleared.
                null
            }
            existingSelection(existing, doors)
        } else {
            current.selection
        }

        _state.value = _state.value.copy(
            detectedDoors = doors,
            isDetectingDoors = false,
            selection = selection,
        )
    }

    fun onSelectionChange(selection: WanderingDoorSelection) {
        _state.value = _state.value.copy(selection = selection)
    }

    /** The CTA. Saves the chosen doors. */
    fun saveSelection() {
        val current = _state.value
        if (current.selection.count == 0) return
        commit(
            doors = wanderingDoorInputs(current.detectedDoors, current.selection),
            noExitDoor = false,
            failureMessage = WanderingDoorCopy.SAVE_FAILED,
        )
    }

    /**
     * "No exit door here". A real choice rather than an empty selection, which
     * is why it is sent explicitly — the backend turns wandering off for this
     * camera rather than inferring it from a missing list.
     */
    fun saveNoExitDoor() {
        commit(
            doors = emptyList(),
            noExitDoor = true,
            failureMessage = WanderingDoorCopy.SAVE_NO_EXIT_DOOR_FAILED,
        )
    }

    private fun commit(
        doors: List<WanderingDoorInput>,
        noExitDoor: Boolean,
        failureMessage: String,
    ) {
        if (_state.value.phase == WanderingDoorPhase.Saving) return
        _state.value = _state.value.copy(phase = WanderingDoorPhase.Saving)
        viewModelScope.launch {
            try {
                save(doors, noExitDoor)
                _didSave.value = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Back to Ready, not to Failed: the picture and the caregiver's
                // choices are all still valid and still on screen. Failed would
                // replace them with a retry button and lose the selection they
                // just made.
                _state.value = _state.value.copy(phase = WanderingDoorPhase.Ready)
                _saveError.value = failureMessage
            }
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    private companion object {
        const val NO_IDENTITY: String =
            "This sensor hasn't finished pairing yet, so there's no camera view to show."
    }
}

/**
 * JPEG bytes to an [ImageBitmap].
 *
 * A top-level function, and injectable, because `BitmapFactory` is an Android
 * class: this keeps the ViewModel's own logic reachable from a JVM test.
 */
private fun decodeJpeg(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
