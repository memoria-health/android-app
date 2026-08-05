package com.memoria.caregiver.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier

/**
 * The Live tab: [LiveViewModel] joined to [LiveVideoScreen], plus the one
 * presentation this screen owns — the full-screen view of a single camera.
 *
 * Kept apart from `MainActivity` for the same reason every other host in this
 * app is: the Activity's job is to build the object graph and route, and a
 * screen that needs a modal of its own should not push that decision up to it.
 *
 * @param onOpenCameraDetail routes to the per-camera screen, which the patient
 *   shell owns because it is reachable from the Sensors tab too.
 */
@Composable
fun LiveHost(
    viewModel: LiveViewModel,
    onOpenCameraDetail: (String) -> Unit,
    /** Pairing, moved here when the Sensors tab folded into Live. */
    onAddSensor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    /**
     * Which camera is presented full-screen, if any. `rememberSaveable` so a
     * rotation does not dump the caregiver back to the carousel — the tile they
     * were watching is the whole reason they are on this screen.
     */
    var fullScreenCameraId by rememberSaveable { mutableStateOf<String?>(null) }

    // iOS runs `tabAwayCleanup()` from `PatientLiveView.onDisappear`, which
    // fires on sub-tab switches AND on the shell dismiss. This is the same
    // moment: leaving the Live tab during a call is "hang up" in the phone
    // metaphor. The room deliberately stays up — see LiveViewModel's class
    // comment for the split, and PatientShell for where disconnect lives.
    DisposableEffect(Unit) {
        onDispose { viewModel.onLeaveLiveTab() }
    }

    val presented = fullScreenCameraId
    if (presented != null) {
        // Back closes the full-screen view rather than the patient, matching
        // every other overlay in this app. Without it, Android's back gesture
        // would leave the caregiver looking at the roster with a call still up.
        BackHandler { fullScreenCameraId = null }
        LiveFullScreenView(
            state = state,
            cameraId = presented,
            onDismiss = { fullScreenCameraId = null },
            modifier = modifier,
        )
        return
    }

    LiveVideoScreen(
        state = state,
        modifier = modifier,
        onSelectCamera = viewModel::onSelectCamera,
        onToggleAudio = viewModel::toggleAudio,
        onOpenCameraDetail = onOpenCameraDetail,
        onAddSensor = onAddSensor,
        onExpandCamera = { fullScreenCameraId = it },
        onAcknowledgeSensitiveRoom = viewModel::acknowledgeSensitiveRoom,
        onCallButtonTap = viewModel::onCallButtonTap,
        onCaptureClip = viewModel::captureClip,
        onDisconnectAll = viewModel::disconnectAll,
        onReconnectAll = viewModel::reconnectAll,
        // No `videoTile` override. The blurred renderer needs a `PersonDetector`
        // and none is vendored (REQ-23, FINDINGS.md F-19), so the plain path
        // renders for both values of `peopleBlurEnabled`. Passing a blur
        // renderer with no detector would draw nothing over the feed while the
        // toggle claimed people were hidden, which is the one failure mode this
        // feature cannot have.
    )
}
