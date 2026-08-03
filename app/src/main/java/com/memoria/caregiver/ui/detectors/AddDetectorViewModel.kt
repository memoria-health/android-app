package com.memoria.caregiver.ui.detectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.InferDetectorRequest
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Produces [AddDetectorState] for [AddDetectorScreen] and runs its two round
 * trips. Port of `AddDetectorView`'s `@State` plus `generate()` and `create()`.
 *
 * **Nothing here is derived from the client's cache, and that is the point.**
 * This screen describes a detector that does not exist yet: the caregiver's
 * words, and then what `POST /v1/detectors/infer` deduced from them. iOS holds
 * all of it in `@State` for exactly that reason. The one moment it touches the
 * cache is [create], where a real row comes back from the backend.
 *
 * **An inferred plan is never written into cached state.** `/infer` is not
 * scoped to a patient and creates nothing; its response is a proposal the
 * caregiver edits before committing. Folding it into `detectorsByPatient` would
 * put a row in the Detectors tab that the backend has never heard of, and the
 * caregiver would then be able to toggle and assign it. `DetectorsApi` makes the
 * same point about `refineDetector(preview = true)`, which the detail sheet
 * guards with `if !preview`; this is the same rule one step earlier in the
 * flow.
 */
class AddDetectorViewModel(
    private val api: NemaraApiClient,
    private val patientId: String,
    /**
     * The freshly created draft, for the detail sheet the host presents over
     * this screen. iOS assigns it to `@State createdDetector`, which drives a
     * `.sheet(item:)` whose dismissal closes the create screen.
     *
     * It is a function rather than a field on [AddDetectorState] because the
     * state type is the screen's contract and the screen never renders the
     * created detector — it is a navigation result, and the created row is
     * already in the client's cache by the time this runs.
     */
    private val onCreated: (Detector) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(AddDetectorState())
    val state: StateFlow<AddDetectorState> = _state.asStateFlow()

    // -----------------------------------------------------------------------
    // Stage 1 — the words
    // -----------------------------------------------------------------------

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun onNoteChange(value: String) = _state.update { it.copy(note = value) }

    fun onPromptChange(value: String) = _state.update { it.copy(prompt = value) }

    // -----------------------------------------------------------------------
    // Stage 2 — editing what was inferred
    // -----------------------------------------------------------------------

    fun onAlertTriggerChange(value: Detector.AlertTrigger) =
        _state.update { it.copy(alertTrigger = value) }

    fun onCategoryChange(value: Detector.Category) = _state.update { it.copy(category = value) }

    fun onIconChange(value: String) = _state.update { it.copy(icon = value) }

    fun onCadenceChange(value: Detector.ScheduleConfig.Cadence) =
        _state.update { it.copy(cadence = value) }

    /** 0 = Mon .. 6 = Sun, matching `WhenScopeDayNames`. */
    fun onToggleDay(index: Int) = _state.update {
        it.copy(days = if (index in it.days) it.days - index else it.days + index)
    }

    fun onWindowStartChange(value: LocalTime) = _state.update { it.copy(windowStart = value) }

    fun onWindowEndChange(value: LocalTime) = _state.update { it.copy(windowEnd = value) }

    fun onDeadlineChange(value: LocalTime) = _state.update { it.copy(deadline = value) }

    /**
     * "← Edit". Back to stage 1 with everything inferred so far preserved, so a
     * reworded prompt starts from what the caregiver already adjusted rather
     * than from the defaults.
     */
    fun backToEntry() = _state.update { it.copy(stage = AddDetectorStage.Entry) }

    // -----------------------------------------------------------------------
    // The two round trips
    // -----------------------------------------------------------------------

    /**
     * Ask the backend to classify the prompt, and move to the review stage.
     *
     * The name is sent when there is one because it gives the model context;
     * blank becomes null rather than an empty string, since the request omits a
     * null `name` entirely and the backend forbids unknown or empty extras.
     */
    fun generate() {
        val current = _state.value
        // iOS guards twice — `.disabled(busy || prompt.trimmed.isEmpty)` on the
        // button and `guard !p.isEmpty` at the top of `generate()`. The callback
        // is a plain function here, so the second guard is the real one.
        if (!current.canGenerate) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val trimmedName = current.name.trimmingSwiftWhitespacesAndNewlines()
                val inferred = api.detectorsDirect.inferDetector(
                    InferDetectorRequest(
                        name = trimmedName.ifEmpty { null },
                        prompt = current.trimmedPrompt,
                    ),
                )
                // Into THIS screen's state and nowhere else. See the class
                // comment: nothing exists server-side yet, so there is nothing
                // for the cache to hold.
                _state.update { it.applyingInference(inferred) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _state.update { it.copy(error = messageFor(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Create the detector, OFF, and hand it to the detail sheet.
     *
     * **Create-as-draft.** [AddDetectorState.toCreateDetectorRequest] sends
     * `isOn = false`, so the caregiver reviews the compiled criteria before the
     * detector starts watching. That is also why the created row is handed
     * onward rather than simply closing the screen.
     *
     * The cache write belongs to `NemaraApiClient.createCustomDetector`, which
     * appends the created row to this patient's list — this one IS real, unlike
     * the inference above.
     */
    fun create() {
        val current = _state.value
        if (!current.canCreate) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val created = api.createCustomDetector(
                    patientId,
                    // Built from the snapshot taken before the launch, so the
                    // body is what the review stage showed at the tap.
                    current.toCreateDetectorRequest(),
                )
                onCreated(created)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _state.update { it.copy(error = messageFor(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Verbatim from [ApiError.errorDescription], which is what iOS's
     * `(error as? LocalizedError)?.errorDescription ?? error.localizedDescription`
     * resolves to for everything these two paths throw.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }
}
