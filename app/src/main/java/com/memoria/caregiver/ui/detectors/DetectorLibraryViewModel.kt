package com.memoria.caregiver.ui.detectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.DetectorLibraryEntry
import com.memoria.caregiver.data.model.UpdateDetectorRequest
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Produces [DetectorLibraryState] for [DetectorLibraryScreen] and runs the load
 * and the submit. Port of `DetectorLibraryView`'s `@State` plus its `load()` and
 * `submit()`.
 *
 * **Nothing here is derived from the client's cache, and that is correct.** The
 * catalog comes from `GET /v1/patients/{id}/detectors/library`, which the client
 * deliberately does not cache — it is a per-cohort catalog, not the patient's
 * rows, and `alreadyAdded` on each entry is the server's answer about this
 * patient at fetch time. iOS holds the same list in `@State` for the same
 * reason. The patient's actual detectors are loaded into the cache first
 * ([NemaraApiClient.loadDetectors]) purely so the onboarding reconcile can map a
 * catalog key onto the detector row it has to delete.
 */
class DetectorLibraryViewModel(
    private val api: NemaraApiClient,
    private val patientId: String,
    mode: DetectorLibraryMode = DetectorLibraryMode.Add,
    /**
     * Leave the screen: `dismiss()` in add-mode, `onContinue?()` in onboarding.
     * One function rather than two, because the host already knows the mode and
     * the two cases differ only in where they go next. Taken as a function for
     * the same reason `AppSettingsViewModel` takes its account actions that way.
     *
     * Not called on failure. A submit that threw leaves the caregiver on the
     * picker with [DetectorLibraryState.submitError] under the footer, which is
     * what makes a half-finished onboarding reconcile recoverable.
     */
    private val onFinished: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(DetectorLibraryState(mode = mode))
    val state: StateFlow<DetectorLibraryState> = _state.asStateFlow()

    /**
     * Load the patient's detectors, then the catalog. From the host's
     * `LaunchedEffect`, and again from the screen's Retry.
     *
     * The order is iOS's and it matters: the detector rows have to be in the
     * cache before [submit] can look one up by identifier, and doing it here
     * means the lookup itself never has to wait on a fetch.
     */
    fun load() {
        _state.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            try {
                api.loadDetectors(patientId)
                // Awaited into a local before anything is written, so the update
                // below cannot carry a value read before this suspended
                // (FINDINGS.md F-12).
                val catalog = api.detectorsDirect.fetchDetectorLibrary(patientId)
                _state.update { current ->
                    current.copy(
                        entries = catalog,
                        // Onboarding pre-checks the recommended and the
                        // already-seeded set, because unchecking is how the
                        // caregiver removes one. Add-mode starts empty: its
                        // already-added rows are locked, not selected, and
                        // pre-selecting them would make the footer count wrong.
                        selectedKeys = if (current.mode == DetectorLibraryMode.Onboarding) {
                            catalog.filter { it.recommended || it.alreadyAdded }
                                .map { it.key }
                                .toSet()
                        } else {
                            emptySet()
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _state.update { it.copy(loadError = messageFor(e)) }
            } finally {
                // iOS drops the spinner in `defer`, so a failed load reaches the
                // error branch instead of spinning forever.
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /**
     * Check or uncheck one entry.
     *
     * The locked check is repeated from the screen because iOS repeats it too
     * (`if mode == .add && e.alreadyAdded { return }` at the top of `toggle`),
     * and because a locked entry that slipped through would be sent to
     * `from-library` and silently skipped server-side — leaving the footer
     * promising an add that never happened.
     */
    fun toggleEntry(entry: DetectorLibraryEntry) {
        _state.update { current ->
            if (current.isLocked(entry)) return@update current
            val keys = current.selectedKeys
            current.copy(
                selectedKeys = if (entry.key in keys) keys - entry.key else keys + entry.key,
            )
        }
    }

    /**
     * Add the newly-checked catalog keys, and in onboarding also delete the
     * unchecked-but-present ones.
     *
     * Three rules from `DetectorLibraryView.submit()`:
     *
     * - **Adds first, deletes second.** A reconcile that deleted first would
     *   leave the patient with fewer detectors than they started with if the add
     *   then failed.
     * - **The cache is read AFTER the add lands**, not before. The add writes
     *   the created rows into `detectorsByPatient`, and reading the map before
     *   the await would take the pre-add copy — the exact shape of FINDINGS.md
     *   F-12.
     * - **A key with no matching row is loud.** The library said the patient has
     *   it and no detector carries that identifier, so skipping the delete would
     *   mean the caregiver unchecks something and it stays.
     *
     * Deletes are sequential rather than concurrent, as iOS's `for` loop is:
     * each `deleteDetector` rewrites the cached list for this patient, and
     * overlapping rewrites would drop one another's removals.
     */
    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                // Both sets are computed from the state as it was when the
                // caregiver tapped, which is what iOS reads at the top of its
                // `submit()`. `keysToRemove` is empty in add-mode by
                // construction, so the branch below is the mode difference.
                val keysToAdd = current.keysToAdd
                if (keysToAdd.isNotEmpty()) {
                    // Drive nothing off `keysToAdd` after this: the server skips
                    // keys the patient already has, so it can create fewer rows
                    // than were asked for.
                    api.addDetectorsFromLibrary(patientId, keysToAdd)
                }
                val keysToRemove = current.keysToRemove
                if (keysToRemove.isNotEmpty()) {
                    val detectors = api.detectorsByPatient[patientId].orEmpty()
                    for (key in keysToRemove) {
                        val detector = detectors.firstOrNull { it.identifier == key }
                            ?: throw MissingDetectorForKey(key)
                        api.deleteDetector(detector.id)
                    }
                }
                // Wandering is per-camera, so ask which rooms lead outside
                // while the caregiver still has the context. Only when it was
                // added in THIS submit — reopening the library and adding
                // something else must not re-ask — and only when there are
                // cameras to choose between.
                val rooms = roomOptions()
                if (WANDERING_KEY in keysToAdd && rooms.isNotEmpty()) {
                    _state.update {
                        it.copy(wanderingRoomPrompt = true, wanderingRoomOptions = rooms)
                    }
                } else {
                    onFinished()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _state.update { it.copy(submitError = messageFor(e)) }
            } finally {
                _state.update { it.copy(isSubmitting = false) }
            }
        }
    }

    /** Distinct, non-blank camera room names, sorted. iOS builds the same list inline. */
    private fun roomOptions(): List<String> =
        api.camerasByPatient[patientId].orEmpty()
            .filterNot { it.disabled }
            .map { it.room.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    fun toggleWanderingRoom(room: String) {
        _state.update {
            val selected = it.wanderingRoomsSelected
            it.copy(
                wanderingRoomsSelected =
                if (room in selected) selected - room else selected + room,
            )
        }
    }

    /**
     * Save the room choice and leave.
     *
     * **An empty selection is not an error and not a no-op — it means every
     * room**, which is what `where_rooms = null` already encodes and what the
     * sheet's footer says. So nothing is sent, and the detector keeps the
     * server's default. Sending an empty array instead would scope wandering to
     * nothing and silently stop it watching anywhere.
     *
     * A failed PATCH still closes the screen. The detectors were created — that
     * is the thing the caregiver asked for and it succeeded — and holding them
     * on this sheet to retry a scope refinement would make the whole add look
     * like it failed. Scope stays at "every room", which is the safe direction
     * for a wandering detector.
     */
    fun confirmWanderingRooms() {
        val chosen = _state.value.wanderingRoomsSelected
        viewModelScope.launch {
            if (chosen.isNotEmpty()) {
                val wandering = api.detectorsByPatient[patientId].orEmpty()
                    .firstOrNull { it.identifier == WANDERING_KEY }
                if (wandering != null) {
                    runCatching {
                        api.updateDetector(
                            wandering.id,
                            UpdateDetectorRequest(whereRooms = chosen.toList().sorted()),
                        )
                    }
                }
            }
            _state.update { it.copy(wanderingRoomPrompt = false) }
            onFinished()
        }
    }

    /**
     * Verbatim from [ApiError.errorDescription], and verbatim from
     * [missingDetectorForKeyMessage] for the reconcile's own failure — the copy
     * belongs to `DetectorUiState.kt` so this cannot paraphrase it.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }
}

/**
 * The onboarding reconcile's fail-loud. iOS declares the same case on a private
 * `DetectorLibraryError: LocalizedError`, whose `errorDescription` is the
 * sentence below — so it reaches the footer through the ordinary error path
 * rather than through a second branch.
 */
private class MissingDetectorForKey(key: String) : Exception(missingDetectorForKeyMessage(key))

/** The catalog key of the wandering preset. It is the only per-camera preset. */
private const val WANDERING_KEY = "wandering"
