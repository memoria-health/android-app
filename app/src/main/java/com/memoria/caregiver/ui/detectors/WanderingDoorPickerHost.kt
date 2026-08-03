package com.memoria.caregiver.ui.detectors

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.memoria.caregiver.data.livekit.LiveRoom
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.live.LiveKitFrameCapture

/**
 * The exit-door picker, joined to its data. Replaces the `NotBuiltScreen` that
 * stood in for it (FINDINGS.md F-41).
 *
 * Everything expensive lives in [WanderingDoorCache], which is created here and
 * therefore lives as long as this presentation: reopening the picker for the
 * same camera within one visit reuses the frame and the doors, and closing the
 * patient drops both. iOS scopes its cache the same way.
 *
 * @param onClose dismissal, used for Cancel, for Back, and for a completed save.
 */
@Composable
fun WanderingDoorPickerHost(
    api: NemaraApiClient,
    liveKit: LiveRoom,
    patientId: String,
    cameraId: String,
    cameraName: String,
    livekitIdentity: String?,
    /**
     * The signed-in caregiver's id, read at mint time rather than captured —
     * same reason as `LiveViewModel`: a token minted for a caregiver who has
     * since signed out is a token for the wrong household.
     */
    caregiverId: () -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cache = remember(patientId) {
        WanderingDoorCache(
            liveKit = liveKit,
            detectDoors = { base64 -> api.camerasDirect.detectDoors(base64) },
            mintViewerToken = {
                val caregiver = caregiverId()
                    ?: error("No signed-in caregiver; this screen is unreachable when signed out.")
                val token = api.liveKit.mintViewerToken(patientId, caregiver)
                token.url to token.token
            },
            captureJpeg = { identity ->
                val track = liveKit.videoTracksByIdentity.value[identity] ?: return@WanderingDoorCache null
                LiveKitFrameCapture.jpeg(track)
            },
            // The map read the cache takes as a predicate, wired to the map it
            // replaced. See WanderingDoorCache.hasVideoTrack.
            hasVideoTrack = { identity -> liveKit.videoTracksByIdentity.value[identity] != null },
        )
    }

    val viewModel: WanderingDoorPickerViewModel = viewModel(
        key = "doorPicker:$cameraId",
        factory = viewModelFactory {
            initializer {
                WanderingDoorPickerViewModel(
                    cameraId = cameraId,
                    patientId = patientId,
                    identity = livekitIdentity,
                    cameraName = cameraName,
                    cache = cache,
                    loadExisting = {
                        api.camerasDirect.wanderingDoors(patientId)
                            .firstOrNull { it.cameraId == cameraId }
                            ?.doorConfig
                    },
                    save = { doors, noExitDoor ->
                        api.camerasDirect.setCameraWanderingDoors(
                            cameraId = cameraId,
                            doors = doors,
                            noExitDoor = noExitDoor,
                        )
                    },
                )
            }
        },
    )

    val state by viewModel.state.collectAsState()
    val didSave by viewModel.didSave.collectAsState()

    // Back is Cancel, not "leave the patient". Same rule as every other overlay
    // in this app; Back falling through to the Activity was F-25, a Critical.
    BackHandler(onBack = onClose)

    LaunchedEffect(didSave) { if (didSave) onClose() }

    WanderingDoorPickerScreen(
        state = state,
        modifier = modifier,
        onCancel = onClose,
        onRescan = { viewModel.load() },
        onSelectionChange = viewModel::onSelectionChange,
        // The screen builds the payload itself from the state it was handed, so
        // it arrives here already in wire shape. Rebuilding it from the
        // ViewModel's own copy would be two sources for one list.
        onSave = { viewModel.saveSelection() },
        onNoExitDoor = viewModel::saveNoExitDoor,
    )
}
