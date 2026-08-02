package com.memoria.caregiver.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/**
 * Runtime permission gate. **There is no iOS counterpart to this file, and that
 * is the point.**
 *
 * iOS declares a usage description in Info.plist and the OS prompts the first
 * time the app touches the camera or the microphone — the app writes no code.
 * Android needs three separate things:
 *
 * 1. a `<uses-permission>` in the manifest,
 * 2. an explicit runtime request, and
 * 3. a check before every use.
 *
 * Miss any one and the feature fails at the point of use. Before this existed,
 * `TalkController.startCall` went straight to `publishMicrophone(true)` and
 * enrollment went straight to `bindToLifecycle` — so **talk mode and face
 * enrollment could never have worked on a real device**, while every unit test
 * passed. That is the shape of defect the emulator and the test suite are both
 * blind to.
 *
 * Recorded in DIFFERENCES.md D13.
 */
enum class AppPermission(val manifestName: String) {
    /** Talk mode. iOS: NSMicrophoneUsageDescription. */
    Microphone(Manifest.permission.RECORD_AUDIO),

    /** Face enrollment and the profile photo. iOS: NSCameraUsageDescription. */
    Camera(Manifest.permission.CAMERA),
}

/** Whether the permission is already granted, without prompting. */
fun Context.hasPermission(permission: AppPermission): Boolean =
    ContextCompat.checkSelfPermission(this, permission.manifestName) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Grant state for one permission, plus a request trigger.
 *
 * @param granted true once the permission is held.
 * @param denied true once a request came back refused. Distinguished from "not
 *   asked yet" so a screen can explain why the feature is unavailable instead
 *   of re-prompting into a system dialog the user has already dismissed —
 *   Android stops showing it after the second refusal, and an app that keeps
 *   asking just looks broken.
 * @param request fires the system dialog. Safe to call when already granted.
 */
data class PermissionState(
    val granted: Boolean,
    val denied: Boolean,
    val request: () -> Unit,
)

/**
 * Observes and requests one permission.
 *
 * Does NOT prompt on its own. The caller decides when to ask, because the
 * system dialog should follow a deliberate action — tapping Talk, or starting
 * enrollment — rather than ambushing someone who just opened a screen.
 */
@Composable
fun rememberPermissionState(permission: AppPermission): PermissionState {
    val context = LocalContext.current
    var granted by remember(permission) { mutableStateOf(context.hasPermission(permission)) }
    var denied by remember(permission) { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        denied = !isGranted
    }

    // Re-read on recomposition: the permission can be granted from system
    // settings while the app is backgrounded, and a stale `false` would leave
    // the feature locked behind a prompt that never appears again.
    LaunchedEffect(permission) {
        if (!granted) granted = context.hasPermission(permission)
    }

    return PermissionState(
        granted = granted,
        denied = denied,
        request = { if (!granted) launcher.launch(permission.manifestName) },
    )
}
