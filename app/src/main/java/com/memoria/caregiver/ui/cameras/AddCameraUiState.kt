@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * State and pure logic for the two pairing surfaces. Spec:
 * `Features/Patient/Cameras/AddCameraSheet.swift` and
 * `Features/Patient/Settings/PairingCodeSheet.swift`.
 *
 * Same contract as `CameraUiState.kt` and `AuthUiState.kt`: both screens are
 * pure `@Composable`s over these types, hold no ViewModel, do no I/O, and never
 * reach for the API client. iOS keeps this in `@State` on each view and calls
 * `env.api.createPairingCode` / `regeneratePairingCode` inline; the shape below
 * is that same state, hoisted, with the two round trips left to the host.
 *
 * The countdown formatting lives here rather than in either screen because both
 * screens render it, from the same expiry, with **different** expired copy — the
 * kind of near-duplicate that drifts if it is written twice.
 */

// ---------------------------------------------------------------------------
// Add a sensor
// ---------------------------------------------------------------------------

/**
 * The two device classes, both branded "Memoria Sensor". iOS:
 * `AddCameraSheet.SourceTab`.
 *
 * [label] is product copy, not a description of the mechanism: "Native" was dev
 * jargon for the purpose-built hardware, and the ™ signals it is our own
 * dedicated device rather than the spare-phone option.
 */
enum class AddCameraTab(val label: String) {
    Phone("Phone"),
    Sensor("Memoria™ Sensor"),
}

/** iOS: `PhoneCameraTab.TabState`. One of these at a time, never two. */
sealed interface PhonePairingPhase {

    /** Room name + icon, with Generate code. */
    data object Composing : PhonePairingPhase

    /** The `POST .../cameras/pairing-code` round trip. */
    data object Generating : PhonePairingPhase

    /**
     * The 6-digit code and its countdown. Survives until the caregiver dismisses
     * the sheet, or until the wall phone completes pairing — see
     * [AddCameraState.generatedCameraPaired].
     */
    data class Showing(val code: String, val expiresAt: Instant) : PhonePairingPhase

    /** The request failed. [message] is the API's own sentence, verbatim. */
    data class Error(val message: String) : PhonePairingPhase
}

/**
 * The add-sensor sheet. iOS: `AddCameraSheet` + its private `PhoneCameraTab`.
 *
 * The two are one state here because the sheet has exactly one tab with state;
 * the sensor tab's own state belongs to the wizard, which this port does not
 * build (QUESTIONS.md Q5, DIFFERENCES.md D6).
 */
data class AddCameraState(
    val tab: AddCameraTab = AddCameraTab.Phone,
    val room: String = "",
    /** iOS seeds `"tv"`, the first entry in [AddCameraIconOptions]. */
    val iconName: String = "tv",
    val phase: PhonePairingPhase = PhonePairingPhase.Composing,
    /**
     * Whether the placeholder camera created by THIS sheet's pairing code has
     * flipped `paired` true.
     *
     * iOS watches `env.api.camerasByPatient[patientId]` for the camera id the
     * generate call returned, and closes the sheet the moment that ONE row pairs
     * — scoped to its own camera so an unrelated update can never dismiss it.
     * The watching is the host's (it owns the camera cache); the closing is the
     * screen's, because that is where the behavior is visible.
     */
    val generatedCameraPaired: Boolean = false,
) {
    /**
     * iOS: `.disabled(room.trimmingCharacters(in: .whitespaces).isEmpty)`, and
     * `generate()` guards on the same trimmed value, so a room of spaces is
     * neither submittable nor sent.
     */
    val canGenerate: Boolean get() = room.trim().isNotEmpty()
}

/**
 * The six room icons the sheet offers. iOS: `IconPicker.options`.
 *
 * A curated subset of the backend's allowed `icon_name` set, in this order —
 * every one of them is already drawn by `SensorSymbols.kt`.
 */
internal val AddCameraIconOptions: List<String> = listOf(
    "tv",
    "fork.knife",
    "bed.double",
    "shower",
    "door.left.hand.open",
    "car",
)

// ---------------------------------------------------------------------------
// Pair a new phone
// ---------------------------------------------------------------------------

/** iOS: `PairingCodeSheet.SheetState`. */
sealed interface PairingCodePhase {
    data object Loading : PairingCodePhase
    data class Ready(val code: String, val expiresAt: Instant) : PairingCodePhase
    data class Error(val message: String) : PairingCodePhase
}

/**
 * The pairing-code sheet. iOS: `PairingCodeSheet`.
 *
 * iOS carries a `Source` — `.create(patientId, iconName)` or
 * `.regenerate(cameraId)` — purely to pick which endpoint `load()` hits. Both
 * return the same response and render the same UI, so the discriminator is the
 * host's and does not appear here; this screen only knows the outcome.
 */
data class PairingCodeState(
    /** Rendered as "for {room}" under the eyebrow. */
    val room: String = "",
    val phase: PairingCodePhase = PairingCodePhase.Loading,
)

// ---------------------------------------------------------------------------
// The countdown
// ---------------------------------------------------------------------------

/** iOS: `Timer.publish(every: 1, ...)` on both sheets. */
internal const val CountdownTickMillis: Long = 1_000L

/** iOS `AddCameraSheet`: "Code expired, close and try again." */
internal const val AddCameraExpiredText: String = "Code expired, close and try again."

/**
 * iOS `PairingCodeSheet`: "Code expired — close and try again."
 *
 * The em dash and the comma above are the two sheets' actual copy. They differ
 * on iOS and are carried as they are, because copy is ported verbatim and
 * unifying them here would be a silent product edit.
 */
internal const val PairingCodeExpiredText: String = "Code expired — close and try again."

/**
 * "Expires in 4:07", or [expiredText] once the code is dead.
 *
 * iOS: `Int(expiresAt.timeIntervalSinceNow)`, which truncates toward zero, then
 * `max(0, ...)`; `inWholeSeconds` truncates the same way, so a code with 4.9s
 * left reads 0:04 on both platforms.
 *
 * `Locale.ROOT` on the zero-pad for the reason `sensorLabel` gives: Kotlin's
 * `String.format` follows the default locale and would render Arabic-Indic
 * digits under an `ar-EG` device, inside an otherwise untranslated English
 * sentence.
 */
internal fun countdownText(expiresAt: Instant, now: Instant, expiredText: String): String {
    val remaining = (expiresAt - now).inWholeSeconds
    if (remaining <= 0L) return expiredText
    val minutes = remaining / 60
    val seconds = remaining % 60
    return "Expires in $minutes:" + String.format(Locale.ROOT, "%02d", seconds)
}

/**
 * A wall clock that re-reads itself once a second while [running].
 *
 * Both sheets need this for the same reason `EventCard`'s ago-label does: the
 * countdown is computed from `Date()`, which is not a Compose state read, so
 * without a ticking source the text freezes at whatever it said on first
 * composition. iOS solves it with a `Timer` publisher and a `clockTick` counter
 * that exists only to invalidate the body.
 *
 * iOS's `AddCameraSheet` timer runs always and only bumps the tick while
 * showing a code; this starts and stops with [running] instead. Observably
 * identical — nothing but the countdown reads the tick — and it does not leave
 * a coroutine waking every second behind an error view.
 *
 * The clock is re-read when [running] turns on, not only on the next tick, so a
 * code that appears 900ms into a second does not render a stale remaining time.
 */
@Composable
internal fun rememberCountdownClock(running: Boolean): Instant {
    var now by remember { mutableStateOf(currentInstant()) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        now = currentInstant()
        while (true) {
            delay(CountdownTickMillis)
            now = currentInstant()
        }
    }
    return now
}

/**
 * `kotlin.time.Instant` for right now.
 *
 * The same two-line bridge `DeviceModels.kt` uses for `DetectionEvent.ago`,
 * repeated because that one is private to its file. Both exist because the
 * models speak `kotlin.time.Instant` while the platform clock is
 * `java.time.Instant`.
 */
internal fun currentInstant(): Instant {
    val now = java.time.Instant.now()
    return Instant.fromEpochSeconds(now.epochSecond, now.nano.toLong())
}

/**
 * The 6-digit code itself. iOS: `NemaraFont.mono(size: 56, weight: .bold)`.
 *
 * Not a `NemaraType` token — iOS builds it from the `mono(size:weight:)`
 * factory at these two call sites only. Carries no letter spacing: the
 * `.tracking(8)` is applied where it is used, per DECISIONS.md D-008.
 */
internal val PairingCodeTextStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontSize = 56.sp,
    fontWeight = FontWeight.Bold,
)

/** iOS: `.tracking(8)` on the code, on both sheets. */
internal val PairingCodeTracking = 8.sp
