@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

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
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.nav.NotBuiltScreen
import com.memoria.caregiver.ui.nav.ScopedViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The two camera surfaces the Sensors tab reaches: one sensor's detail, and the
 * add-a-sensor sheet.
 *
 * Spec: `Features/Patient/Cameras/PatientCamerasView.swift`, which pushes
 * `CameraDetailView` through a `NavigationLink` and presents `AddCameraSheet` as
 * a large-detent sheet. Both are conditional branches here, each with its own
 * [BackHandler] — Back falling through to the Activity and quitting the app was
 * FINDINGS.md F-25, a Critical.
 */

/**
 * One sensor's detail.
 *
 * [CameraDetailViewModel] owns everything with a request behind it. The two
 * things it deliberately does not own — the pairing-code sheet and the door
 * picker — are presentations, and iOS keeps those as `@State` booleans on the
 * view for the same reason.
 */
@Composable
fun CameraDetailHost(
    api: NemaraApiClient,
    patientId: String,
    cameraId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scoped, so leaving the detail CLEARS the ViewModel. Activity-scoped, the
    // assignment set and the door config from one sensor would still be on
    // screen when the caregiver opened the next one.
    ScopedViewModelStore("cameraDetail:" + patientId + ":" + cameraId) {
        val vm: CameraDetailViewModel = viewModel(
            // Concatenated, not interpolated — FINDINGS.md F-23.
            key = "cameraDetail:" + cameraId,
            factory = viewModelFactory {
                initializer {
                    CameraDetailViewModel(
                        api = api,
                        patientId = patientId,
                        cameraId = cameraId,
                        // Delete is the only action that leaves. Unpair stays,
                        // because the camera flips to unpaired and the pairing
                        // section appears in place of the unpair button.
                        onDeleted = onBack,
                    )
                }
            },
        )
        val state by vm.state.collectAsState()

        // iOS runs these from `.task(id: cameraId)`, which fires on appear
        // rather than at construction. Both endpoints are uncached by design.
        LaunchedEffect(vm) { vm.load() }

        var showingPairingCode by remember(cameraId) { mutableStateOf(false) }
        var showingDoorPicker by remember(cameraId) { mutableStateOf(false) }

        when {
            showingPairingCode -> PairingCodeHost(
                api = api,
                cameraId = cameraId,
                room = state.camera?.room.orEmpty(),
                onClose = { showingPairingCode = false },
                modifier = modifier,
            )

            showingDoorPicker -> NotBuiltScreen(
                title = "Exit doors",
                // Named honestly rather than left as a dead row: the picker
                // itself is built (`WanderingDoorPickerScreen`), but it draws
                // boxes over a still frame pulled from the live LiveKit feed,
                // and no LiveKit client is wired into the app yet. Wiring the
                // screen to an empty frame would be worse than saying so.
                reason = "Picking exit doors needs a still frame from the live " +
                    "camera feed, and live video is not connected in this build yet.",
                onBack = { showingDoorPicker = false },
                modifier = modifier,
            )

            else -> {
                BackHandler(onBack = onBack)
                CameraDetailScreen(
                    state = state,
                    modifier = modifier,
                    onToggleDetector = vm::toggleDetector,
                    onToggleAiPause = vm::toggleAiPause,
                    onDisconnect = vm::disconnect,
                    onReconnect = vm::reconnect,
                    onUnpair = vm::unpair,
                    onDelete = vm::delete,
                    onShowPairingCode = { showingPairingCode = true },
                    onOpenDoorPicker = { showingDoorPicker = true },
                )
            }
        }
    }
}

/**
 * "Show pairing code" — a re-mint on an existing unpaired camera.
 *
 * A fresh 6-digit code every time the sheet appears, as iOS does: the previous
 * one may have been read out over the phone and half-typed, and re-showing it
 * would be indistinguishable from a code that had silently expired.
 *
 * Unlike the add-sensor sheet this one does NOT close itself when the phone
 * pairs. The caregiver arrived from a camera that already exists, so there is
 * nothing to hand them afterwards.
 */
@Composable
private fun PairingCodeHost(
    api: NemaraApiClient,
    cameraId: String,
    room: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var phase by remember(cameraId) { mutableStateOf<PairingCodePhase>(PairingCodePhase.Loading) }

    LaunchedEffect(cameraId) {
        phase = try {
            val response = api.camerasDirect.regeneratePairingCode(cameraId)
            PairingCodePhase.Ready(response.pairingCode, response.expiresAt)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Throwable) {
            PairingCodePhase.Error(cameraErrorFor(e))
        }
    }

    BackHandler(onBack = onClose)
    PairingCodeSheet(
        state = PairingCodeState(room = room, phase = phase),
        modifier = modifier,
        onClose = onClose,
    )
}

/**
 * "Add a sensor".
 *
 * The phone tab is complete: compose a room, mint a pairing code, and close by
 * itself the moment THAT camera pairs. The sensor tab's step 2 is not, and the
 * screen renders whatever [AddCameraScreen]'s `sensorSetup` slot is given — so
 * this hands it an explicit "not built" panel rather than an empty box.
 *
 * **Step 2 is blocked, not skipped.** iOS joins the sensor's setup access point
 * and lets iOS present its own captive portal for the home-Wi-Fi entry; Android
 * has no equivalent automatic presentation, so that interaction has to be
 * designed rather than translated. QUESTIONS.md Q5, DIFFERENCES.md D6.
 */
@Composable
fun AddCameraHost(
    api: NemaraApiClient,
    patientId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val camerasByPatient by api.camerasByPatientFlow.collectAsState()

    var state by remember(patientId) { mutableStateOf(AddCameraState()) }
    // The camera the generate call created, watched so this sheet closes when
    // THAT one pairs. Scoped to its own id, so an unrelated camera coming online
    // can never dismiss the sheet out from under the caregiver.
    var generatedCameraId by remember(patientId) { mutableStateOf<String?>(null) }

    val paired = generatedCameraId?.let { id ->
        camerasByPatient[patientId]?.firstOrNull { it.id == id }?.paired == true
    } == true
    LaunchedEffect(paired) { state = state.copy(generatedCameraPaired = paired) }

    BackHandler(onBack = onDismiss)

    AddCameraScreen(
        state = state,
        modifier = modifier,
        onTabChange = { state = state.copy(tab = it) },
        onRoomChange = { state = state.copy(room = it) },
        onIconChange = { state = state.copy(iconName = it) },
        onGenerate = {
            // iOS guards on the same trimmed value the button is disabled by, so
            // a room of spaces is neither submittable nor sent.
            if (!state.canGenerate) return@AddCameraScreen
            state = state.copy(phase = PhonePairingPhase.Generating)
            scope.launch {
                // Snapshotted before the request: the room field is still live
                // while it is in flight.
                val room = state.room.trim()
                val icon = state.iconName
                state = try {
                    val response = api.createPairingCode(patientId, room, icon)
                    generatedCameraId = response.cameraId
                    state.copy(
                        phase = PhonePairingPhase.Showing(
                            response.pairingCode,
                            response.expiresAt,
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Throwable) {
                    state.copy(phase = PhonePairingPhase.Error(cameraErrorFor(e)))
                }
            }
        },
        onRetry = { state = state.copy(phase = PhonePairingPhase.Composing) },
        onDone = onDismiss,
        sensorSetup = {
            NotBuiltScreen(
                title = "Memoria Sensor",
                reason = "Setting up a Memoria Sensor needs it to join your home " +
                    "Wi-Fi, and that step has no Android equivalent yet. Add a " +
                    "spare phone from the Phone tab in the meantime.",
                onBack = onDismiss,
            )
        },
    )
}

/**
 * Verbatim from [ApiError.errorDescription]. iOS renders `error
 * .localizedDescription` in both sheets with no prefix of its own, which is why
 * there is no wrapping sentence here either.
 */
internal fun cameraErrorFor(e: Throwable): String = when (e) {
    is ApiError -> e.errorDescription
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
