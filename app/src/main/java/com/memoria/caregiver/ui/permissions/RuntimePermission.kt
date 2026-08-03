package com.memoria.caregiver.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    /**
     * Showing an alert at all. iOS asks for the equivalent through
     * `UNUserNotificationCenter.requestAuthorization` at launch.
     *
     * Unlike the other two, this one did not exist before Android 13. Callers
     * should use [rememberNotificationPermission] rather than naming it
     * directly — see the comment there.
     */
    Notifications(Manifest.permission.POST_NOTIFICATIONS),
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

/**
 * The notification gate, which needs a version check the other two do not.
 *
 * `POST_NOTIFICATIONS` was introduced in Android 13 (API 33). This app's minSdk
 * is 26, so on roughly the older half of the phones it runs on the permission
 * does not exist: notifications are granted at install and the caregiver turns
 * them off in Settings if they want to.
 *
 * So below 33 this reports granted and asks for nothing, which is the truth.
 *
 * Being honest about how much this branch earns: most platform builds already
 * answer `checkSelfPermission` with "granted" below 33, so on those the branch
 * changes nothing, and a mutation that deletes it does not fail any test here.
 * It is kept because the failure it guards against is not a wrong boolean, it is
 * `launcher.launch()` being called with a permission name the platform has never
 * defined — on any build that answers the check the other way, which is exactly
 * the kind of variation no emulator and no unit test can show us.
 *
 * [rememberPermissionState] is called on every version rather than only above
 * 33, because a `remember` that appears and disappears between compositions is
 * how launchers get unregistered underneath an in-flight result. The version is
 * constant for the life of the process, so only the returned value branches.
 */
@Composable
fun rememberNotificationPermission(): PermissionState {
    val state = rememberPermissionState(AppPermission.Notifications)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        state
    } else {
        PermissionState(granted = true, denied = false, request = {})
    }
}
