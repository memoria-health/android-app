package com.memoria.caregiver.ui.detectors

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.nav.ScopedViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The three detector-authoring surfaces, hosted.
 *
 * Spec: `Features/Patient/Detectors/PatientDetectorsView.swift` (the two add
 * sheets), `Features/Patient/Settings/DetectorRow.swift` (the edit sheet) and
 * `Features/Patient/Settings/AddDetectorView.swift` (the create-then-review
 * chain). All three are `.sheet`s on iOS; here they are conditional branches
 * over the Detectors tab, each with its own [BackHandler].
 *
 * None of the three needs a list reload on success. `NemaraApiClient` mutates
 * the observable cache in place — `createCustomDetector` appends,
 * `addDetectorsFromLibrary` appends, `updateDetector` replaces the row — so the
 * tab behind the sheet is already correct when the sheet closes. iOS relies on
 * exactly the same property of its `@Observable` client.
 */

/**
 * "Add a custom detector".
 *
 * The create is a **draft** flow: `AddDetectorViewModel.create()` posts with
 * `is_on = false` and hands back the row, and iOS then chains straight into the
 * detail sheet so the caregiver reviews the compiled criteria before turning it
 * on. Closing that detail sheet closes this screen too — which is why the
 * created detector is held here and [onDismiss] is called from the detail's
 * Done, not from the create screen's.
 */
@Composable
fun AddDetectorHost(
    api: NemaraApiClient,
    patientId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The created draft, if we have got that far. Held above the ViewModel store
    // so the review step survives the create screen's ViewModel being cleared.
    var created by remember(patientId) { mutableStateOf<Detector?>(null) }

    val draft = created
    if (draft != null) {
        // Reviewing the OFF draft. Closing it returns to the detector list,
        // dismissing the create screen behind it — AddDetectorView.swift's
        // `.sheet(item:, onDismiss: { dismiss() })`.
        DetectorDetailHost(
            api = api,
            patientId = patientId,
            detector = draft,
            onDismiss = onDismiss,
            modifier = modifier,
        )
        return
    }

    ScopedViewModelStore("addDetector:" + patientId) {
        val vm: AddDetectorViewModel = viewModel(
            // Concatenated, not interpolated. A `${'$'}` escape in a key made it
            // a constant once already and every subsequent screen got the first
            // one's ViewModel — FINDINGS.md F-23, a Critical.
            key = "addDetector:" + patientId,
            factory = viewModelFactory {
                initializer {
                    AddDetectorViewModel(
                        api = api,
                        patientId = patientId,
                        onCreated = { created = it },
                    )
                }
            },
        )
        val state by vm.state.collectAsState()

        BackHandler(onBack = onDismiss)

        AddDetectorScreen(
            state = state,
            modifier = modifier,
            onNameChange = vm::onNameChange,
            onNoteChange = vm::onNoteChange,
            onPromptChange = vm::onPromptChange,
            onAlertTriggerChange = vm::onAlertTriggerChange,
            onCategoryChange = vm::onCategoryChange,
            onIconChange = vm::onIconChange,
            onCadenceChange = vm::onCadenceChange,
            onToggleDay = vm::onToggleDay,
            onWindowStartChange = vm::onWindowStartChange,
            onWindowEndChange = vm::onWindowEndChange,
            onDeadlineChange = vm::onDeadlineChange,
            onGenerate = vm::generate,
            onCreate = vm::create,
            onBackToEntry = vm::backToEntry,
            onClose = onDismiss,
        )
    }
}

/**
 * "Add from detection library", and the same screen as onboarding step 1.
 *
 * [mode] decides the chrome and what the terminal action means — add-mode posts
 * only the newly ticked keys and closes, onboarding reconciles (adds and
 * deletes) and continues. Both live in [DetectorLibraryViewModel]; this only
 * says where "finished" goes.
 */
@Composable
fun DetectorLibraryHost(
    api: NemaraApiClient,
    patientId: String,
    mode: DetectorLibraryMode,
    onFinished: () -> Unit,
    onLeading: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScopedViewModelStore("detectorLibrary:" + patientId) {
        val vm: DetectorLibraryViewModel = viewModel(
            // Keyed on the mode as well as the patient: the same store can host
            // the onboarding pass and a later add pass, and the mode is fixed at
            // construction.
            key = "detectorLibrary:" + patientId + ":" + mode.name,
            factory = viewModelFactory {
                initializer {
                    DetectorLibraryViewModel(
                        api = api,
                        patientId = patientId,
                        mode = mode,
                        onFinished = onFinished,
                    )
                }
            },
        )
        val state by vm.state.collectAsState()

        // From here rather than from the ViewModel's init: iOS loads in `.task`,
        // which fires on appear, and the catalog is a per-patient fetch that a
        // screen nobody opened should not pay for.
        LaunchedEffect(vm) { vm.load() }

        // Back is the leading action, whatever that means in this mode: Cancel
        // closes, Skip continues past the step. Falling through to the Activity
        // would quit the app mid-onboarding.
        BackHandler(onBack = onLeading)

        if (state.wanderingRoomPrompt) {
            // Back does NOT cancel this step. The detectors are already
            // created; the only thing left to decide is scope, and backing out
            // of it should mean "every room" rather than leaving the caregiver
            // on a screen whose work is done. Done is the single way forward.
            BackHandler(onBack = vm::confirmWanderingRooms)
            WanderingRoomsSheet(
                rooms = state.wanderingRoomOptions,
                selected = state.wanderingRoomsSelected,
                onToggle = vm::toggleWanderingRoom,
                onDone = vm::confirmWanderingRooms,
                modifier = modifier,
            )
            return@ScopedViewModelStore
        }

        DetectorLibraryScreen(
            state = state,
            modifier = modifier,
            onQueryChange = vm::setQuery,
            onToggleEntry = vm::toggleEntry,
            onRetry = vm::load,
            onLeadingAction = onLeading,
            onSubmit = vm::submit,
        )
    }
}

/**
 * The detector detail editor.
 *
 * **This is the one surface with no ViewModel**, and that is not an oversight:
 * `DetectorDetailState` already carries every transition as a pure function —
 * `withPreview`, `withRefineCommitted`, `withPatchCommitted`, `savePlan`,
 * `isFresher` — and its own header says the host performs the calls and the
 * state says what they are. A ViewModel here would re-implement that or wrap it,
 * and the two polls it runs are scoped to the screen either way.
 *
 * The two polls are iOS's, with iOS's numbers (see [DetectorDetailTiming]):
 * a fresh draft polls for its compile, and a committed refine polls for the
 * server's rewrite.
 */
@Composable
fun DetectorDetailHost(
    api: NemaraApiClient,
    patientId: String,
    detector: Detector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val cameras by api.camerasByPatientFlow.collectAsState()
    val detectors by api.detectorsByPatientFlow.collectAsState()

    var state by remember(detector.id) { mutableStateOf(DetectorDetailState(detector = detector)) }

    // The room chips come from the patient's cameras, live. Folded in on every
    // change rather than snapshotted, so a camera renamed on another device does
    // not leave a chip for a room that no longer exists.
    val roomOptions = roomOptionsFrom(cameras[patientId].orEmpty().map { it.room })
    LaunchedEffect(roomOptions) { state = state.copy(roomOptions = roomOptions) }

    // A brand-new draft arrives with `identifier == null` while the backend
    // compiles it. Keyed on `compiling` exactly as iOS's `.task(id: compiling)`
    // is, so it starts once and stops the moment the compile lands.
    val compiling = state.compiling
    LaunchedEffect(compiling, detector.id) {
        if (!compiling) return@LaunchedEffect
        repeat(DetectorDetailTiming.COMPILE_POLL_ATTEMPTS) {
            delay(DetectorDetailTiming.COMPILE_POLL_INTERVAL)
            runCatching { api.loadDetectors(patientId) }
            val fresh = api.detectorsByPatient[patientId]
                ?.firstOrNull { it.id == state.detector.id }
            if (fresh != null) {
                // Only the detector moves. The caregiver may already be editing,
                // and iOS does not disturb the pickers on this path.
                state = state.withCompiledDetector(fresh)
                if (fresh.identifier != null) return@LaunchedEffect
            }
        }
    }

    BackHandler(onBack = onDismiss)

    DetectorDetailSheet(
        state = state,
        modifier = modifier,
        onFeedbackChange = { state = state.withFeedback(it) },
        onStagedChange = { state = state.copy(staged = it) },
        onRegenerate = {
            val text = state.trimmedFeedback
            if (text.isEmpty() || state.regenerating) return@DetectorDetailSheet
            state = state.copy(regenerating = true, error = null)
            scope.launch {
                try {
                    // A dry run: the server returns the criteria this change
                    // WOULD produce and persists nothing.
                    val previewed = api.refineDetector(state.detector.id, text, preview = true)
                    state = state.withPreview(previewed)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Throwable) {
                    state = state.copy(
                        regenerating = false,
                        error = detectorErrorFor(e, PREVIEW_FAILURE_MESSAGE),
                    )
                }
            }
        },
        onTurnOn = {
            scope.launch {
                // iOS's `setOn(true)` on the draft banner swallows its failure —
                // the toggle simply stays off and the banner stays up.
                runCatching {
                    api.updateDetector(
                        state.detector.id,
                        com.memoria.caregiver.data.model.UpdateDetectorRequest(isOn = true),
                    )
                }.onSuccess { state = state.copy(detector = it) }
            }
        },
        onSave = {
            if (!state.canSave) return@DetectorDetailSheet
            state = state.copy(saving = true, error = null)
            scope.launch {
                runSave(
                    api = api,
                    patientId = patientId,
                    read = { state },
                    write = { state = it },
                )
            }
        },
        onDone = onDismiss,
    )

    // Keeps the on-screen row in step with a change made elsewhere while the
    // editor is open — a toggle from the list behind it, or a Realtime update.
    // Only adopted when it is genuinely newer, so this can never fight the
    // caregiver's own in-flight save.
    val cached = detectors[patientId]?.firstOrNull { it.id == detector.id }
    LaunchedEffect(cached?.updatedAt) {
        val fresh = cached ?: return@LaunchedEffect
        if (!state.saving && !state.rewriting && state.isFresher(fresh)) {
            state = state.withRefreshedDetector(fresh)
        }
    }
}

/**
 * Save, in the two steps [SavePlan] names and the order it requires.
 *
 * The refine runs **second** on purpose: it re-infers alert timing, the active
 * window and the rooms from the caregiver's words, so running it after the PATCH
 * lets the server's inference win rather than being overwritten by the staged
 * pickers.
 *
 * [read]/[write] rather than a captured value and a return: every step re-reads
 * the state it is about to change, so nothing read before a suspension is
 * written back over something that moved while the request was in flight
 * (FINDINGS.md F-12). It also lets the **intermediate** phases reach the screen —
 * the post-refine poll runs for up to twelve seconds and has to show as
 * "Applying your change…" while it does, which a single return at the end could
 * not express.
 */
internal suspend fun runSave(
    api: NemaraApiClient,
    patientId: String,
    read: () -> DetectorDetailState,
    write: (DetectorDetailState) -> Unit,
) {
    try {
        val plan = read().savePlan
        if (plan.patch != null) {
            val patched = api.updateDetector(read().detector.id, plan.patch)
            write(read().copy(detector = patched))
        }
        if (plan.refineFeedback != null) {
            val updated = api.refineDetector(
                read().detector.id,
                plan.refineFeedback,
                preview = false,
            )
            // iOS drops `saving` BEFORE the poll and raises `rewriting` for its
            // duration: the write has landed, the rewrite has not, and the two
            // read as different things on screen.
            write(read().withRefineCommitted(updated).copy(saving = false, rewriting = true))
            repeat(DetectorDetailTiming.REFINE_POLL_ATTEMPTS) {
                delay(DetectorDetailTiming.REFINE_POLL_INTERVAL)
                runCatching { api.loadDetectors(patientId) }
                val fresh = api.detectorsByPatient[patientId]
                    ?.firstOrNull { it.id == read().detector.id }
                // Strictly newer only. The refine response still carries the old
                // summary — the rewrite happens after it returns — so adopting an
                // equal timestamp would end the poll on the row we already have
                // and leave the stale summary up.
                if (fresh != null && read().isFresher(fresh)) {
                    write(read().withRefreshedDetector(fresh).copy(rewriting = false))
                    return
                }
            }
            write(read().copy(rewriting = false))
            return
        }
        // A pure PATCH: the controls already match what was sent, but the
        // server's echo is authoritative — it may have normalized where_rooms —
        // so they are re-read from it, category and sensitivity included.
        if (plan.patch != null) write(read().withPatchCommitted(read().detector))
        write(read().copy(saving = false))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Throwable) {
        write(
            read().copy(
                saving = false,
                rewriting = false,
                error = detectorErrorFor(e, SAVE_FAILURE_MESSAGE),
            ),
        )
    }
}

/**
 * `(error as? LocalizedError)?.errorDescription ?? fallback` — [ApiError
 * .errorDescription] verbatim when there is one, this surface's own sentence
 * otherwise. The two fallbacks live in `DetectorDetailState.kt` beside the
 * screen whose copy they are.
 */
internal fun detectorErrorFor(e: Throwable, fallback: String): String = when (e) {
    is ApiError -> e.errorDescription
    else -> fallback
}
