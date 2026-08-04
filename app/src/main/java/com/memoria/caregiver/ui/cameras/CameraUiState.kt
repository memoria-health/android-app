package com.memoria.caregiver.ui.cameras

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.Detector
import java.util.Locale

/**
 * State and pure logic for the two camera screens. Spec:
 * `Features/Patient/Cameras/PatientCamerasView.swift`,
 * `Features/Patient/Cameras/CameraDetailView.swift`, PARITY.md §21.
 *
 * Same contract as AuthUiState.kt: the screens are pure `@Composable`s over
 * these types, hold no ViewModel, do no I/O, and never reach for the API
 * client. iOS keeps this state in `@State` on each view and the camera and
 * detector rows in `@Environment(AppEnvironment.self)`; the shape below is that
 * same state, hoisted.
 *
 * Everything that is a *decision* rather than a layout — which status line a row
 * shows, which badges it earns, whether the exit-door section appears — lives
 * here as a pure function or a derived property, because that is the part a
 * refactor can quietly break and the part a unit test can pin without a
 * composition.
 */

/** The Cameras sub-tab. iOS: `PatientCamerasView`. */
data class CameraListState(
    /**
     * Already filtered and ordered by the caller, exactly as iOS reads
     * `env.api.camerasByPatient[patientId]`. The API layer re-sorts by
     * `displayId` on every cache mutation (PARITY.md §10), so this screen must
     * not re-sort — doing so would hide a cache bug rather than show it.
     */
    val cameras: List<Camera> = emptyList(),
)

/** One camera's detail screen. iOS: `CameraDetailView`. */
data class CameraDetailState(
    /**
     * Null renders "Sensor not found". iOS derives this by looking the id up in
     * the patient's camera list, so it becomes null the moment a delete lands —
     * which is exactly when the screen should stop showing controls.
     */
    val camera: Camera? = null,
    /** The patient's full detector set, not the camera's assigned subset. */
    val detectors: List<Detector> = emptyList(),
    /** From `GET /v1/cameras/{id}/detectors`; the assignment checkmarks. */
    val assignedDetectorIds: Set<String> = emptySet(),
    val assignmentsError: String? = null,
    /**
     * The detector whose assignment PUT is in flight. Drives a per-row busy
     * state, and iOS's `toggleDetector` refuses to start a second flip while it
     * is set, so only one round-trip is ever outstanding.
     */
    val togglingDetectorId: String? = null,
    val isTogglingAiPause: Boolean = false,
    val isTogglingDisconnect: Boolean = false,
    val isDeleting: Boolean = false,
    val isUnpairing: Boolean = false,
    val actionError: String? = null,
    /** This sensor's wandering exit-door config, or null when never set. */
    val doorConfig: Detector.DoorConfig? = null,
) {
    /**
     * Detectors shown as plain on/off rows. **Wandering is excluded** — it is
     * controlled by the exit-doors section below (it is only active once its
     * doors are set), so a plain toggle would be a dead switch.
     */
    val togglableDetectors: List<Detector>
        get() = detectors.filter { it.identifier != WANDERING_IDENTIFIER }

    /** The patient's wandering detector, if any. Gates the exit-door section. */
    val wanderingDetector: Detector?
        get() = detectors.firstOrNull { it.identifier == WANDERING_IDENTIFIER }

    /**
     * Wandering only counts on this sensor once at least one exit door is set
     * and the caregiver has not declared that no door here leads outside.
     */
    val isWanderingActiveHere: Boolean
        get() {
            val config = doorConfig ?: return false
            return !config.noExitDoor && config.doors.isNotEmpty()
        }

    /**
     * Exit doors are per sensor, and the picker needs a LiveKit identity to pull
     * a frame from, so an unclaimed row cannot configure them.
     *
     * Also gated on the wandering detector actually **watching this room**: a
     * living-room sensor should not carry a doors section when wandering only
     * watches the hallway. See [wanderingWatchesRoom].
     */
    val showsExitDoors: Boolean
        get() {
            val wandering = wanderingDetector ?: return false
            if (!wandering.isOn) return false
            val cam = camera ?: return false
            if (cam.livekitIdentity == null) return false
            return wanderingWatchesRoom(cam.room, wandering)
        }

    /**
     * Pairing code is a wall-phone concept. Sensor (Pi) cameras get their
     * ingest token at provision time, so a 6-digit code would be misleading.
     */
    val showsPairingCode: Boolean
        get() {
            val cam = camera ?: return false
            return cam.sourceType == Camera.SourceType.PHONE && !cam.paired
        }

    /** Unpair is offered only while a phone is actually attached. */
    val showsUnpair: Boolean
        get() {
            val cam = camera ?: return false
            return cam.sourceType == Camera.SourceType.PHONE && cam.paired
        }

    /**
     * The AI-paused chip in the header. Suppressed when the camera is hidden or
     * disconnected, because then the stronger state is the true one and the chip
     * would mislead.
     */
    val showsAiPausedChip: Boolean
        get() {
            val cam = camera ?: return false
            return cam.aiPaused && !cam.disabled &&
                cam.desiredState == Camera.DesiredState.CONNECTED
        }

    /** Both control rows disable while either state flip is in flight. */
    val controlsBusy: Boolean
        get() = isTogglingAiPause || isTogglingDisconnect

    private companion object {
        const val WANDERING_IDENTIFIER = "wandering"
    }
}

/**
 * "SENSOR 07" — the mono prefix on both the list subtitle and the detail line.
 *
 * `Locale.ROOT`, not the default locale: Swift's `String(format:)` is
 * locale-independent unless handed a locale, while Kotlin's `String.format`
 * follows the default one and would render Arabic-Indic digits under an
 * `ar-EG` device. The surrounding text is untranslated English, so a locale
 * -specific numeral here would be a bug, not a courtesy.
 */
internal fun sensorLabel(displayId: Int): String =
    "SENSOR " + String.format(Locale.ROOT, "%02d", displayId)

/**
 * The row subtitle. One connection signal, in strict precedence order.
 *
 * ONLINE means the backend has recently seen frames arriving. Unpaired rows
 * cannot flip online true by construction, so they always read OFFLINE without
 * this needing to check `paired` in the final branch.
 */
internal fun cameraStatusLine(camera: Camera): String {
    val id = sensorLabel(camera.displayId)
    // UNPAIRED wins over everything: the pairing was cleared server-side, so
    // this camera cannot stream until a phone is re-paired. Make that obvious
    // and actionable — tapping the row opens the detail, where the pairing code
    // lives.
    if (camera.sourceType == Camera.SourceType.PHONE && !camera.paired) {
        return "$id · UNPAIRED — TAP TO RE-PAIR"
    }
    // APP CLOSED is the distinct fourth state: the Memoria Sensor app is not
    // running, so reconnect cannot help and the caregiver must reopen it. It
    // beats the OFFLINE wording (which now strictly means "reconnectable") but
    // never an active stream. iPhone-only; Pi sensors never reach it.
    if (camera.isAppClosed) return "$id · APP CLOSED"
    // Caregiver intent beats the lagging backend `online` flag: a sensor they
    // disconnected reads OFFLINE at once, though the reconciler takes a few
    // seconds to flip `online` false.
    if (camera.desiredState == Camera.DesiredState.DISCONNECTED) return "$id · OFFLINE"
    return "$id · " + if (camera.online) "ONLINE" else "OFFLINE"
}

/** The detail header's second line: `SENSOR 07 · cam_1a2b3c4d`. */
internal fun cameraDetailLine(camera: Camera): String {
    val identity = camera.livekitIdentity ?: "no identity"
    return "${sensorLabel(camera.displayId)} · $identity"
}

/**
 * Human label for the classified room, for the private-room copy.
 *
 * Falls back to the generic pair when the class is missing or non-sensitive.
 * Privacy can only be on for bathroom or bedroom, but the fallback stays as
 * defence against contract drift.
 */
internal fun roomClassLabel(camera: Camera): String = when (camera.roomClass) {
    Camera.RoomClass.BATHROOM -> "bathroom"
    Camera.RoomClass.BEDROOM -> "bedroom"
    Camera.RoomClass.OTHER, null -> "bathroom or bedroom"
}

/** Subtitle under "Exit doors on this sensor". */
internal fun doorStatusText(doorConfig: Detector.DoorConfig?): String {
    val config = doorConfig ?: return "Not set · any doorway counts as leaving"
    if (config.noExitDoor) return "No door leads outside · wandering off here"
    return when (config.doors.size) {
        0 -> "Not set · any doorway counts as leaving"
        1 -> {
            val label = config.doors.first().label
            if (!label.isNullOrEmpty()) "1 exit door · $label" else "1 exit door set"
        }
        else -> "${config.doors.size} exit doors set"
    }
}

/**
 * Whether a detector assignment row accepts a tap.
 *
 * iOS: `.disabled(isBusy || !detector.isOn)`. A detector that is off globally
 * cannot be usefully assigned to one camera — the master switch on the detector
 * itself wins — and a row with its PUT in flight must not start a second one.
 *
 * Note this disables ONLY the row that is saving. iOS also refuses a second
 * flip while any toggle is outstanding, but it does that in the action
 * (`guard togglingDetectorId == nil else { return }`), not in the view, so that
 * guard belongs to whoever owns [CameraDetailState.togglingDetectorId] and not
 * to this screen.
 */
internal fun detectorRowEnabled(detector: Detector, isBusy: Boolean): Boolean =
    !isBusy && detector.isOn

/**
 * The trailing badges on a list row.
 *
 * These are USER-triggered settings and are additive — several can be true at
 * once, and each true one shows. Deliberately distinct from the ONLINE/OFFLINE
 * subtitle, which is the system's observation of frame arrival rather than the
 * caregiver's intent.
 */
internal enum class CameraRowBadge {
    /** Terra chip. Dominates: no phone is attached, so the rest are moot. */
    UNPAIRED,

    /** Amber chip — "needs you to act". Suppresses the disconnected dot. */
    APP_CLOSED,

    /**
     * A small red dot rather than a "DISCONNECTED" chip: the word wrapped to two
     * lines in the row and read as broken. The "· OFFLINE" subtitle already
     * carries the wording.
     */
    DISCONNECTED_DOT,

    /** Amber chip. `disabled` is no longer user-settable but is still honored. */
    HIDDEN,

    /** Neutral chip. */
    AI_PAUSED,
}

internal fun cameraRowBadges(camera: Camera): List<CameraRowBadge> {
    if (camera.sourceType == Camera.SourceType.PHONE && !camera.paired) {
        return listOf(CameraRowBadge.UNPAIRED)
    }
    val badges = mutableListOf<CameraRowBadge>()
    if (camera.isAppClosed) {
        badges += CameraRowBadge.APP_CLOSED
    } else if (camera.desiredState == Camera.DesiredState.DISCONNECTED) {
        badges += CameraRowBadge.DISCONNECTED_DOT
    }
    if (camera.disabled) badges += CameraRowBadge.HIDDEN
    if (camera.aiPaused) badges += CameraRowBadge.AI_PAUSED
    return badges
}

/**
 * Whether the wandering detector watches [room].
 *
 * Port of `CameraDetailView.wanderingWatches(room:detector:)` **as it stands on
 * iOS `main`**, not as the commit that introduced it was written.
 *
 * **Case-insensitive, and that is the bug it was written for.** The backend
 * stores room names in capitals ("HALLWAY"), so comparing them as typed showed
 * the exit-doors section on the wrong cameras.
 *
 * The intermediate iOS commit (`6065961`) also fell back to a `whereScope`
 * string. That fallback is **deliberately not ported**: `where_scope` was
 * deleted from the wire on 2026-07-28, `DetectorResponse` no longer carries it,
 * and `954dd14` removed the references a merge had reintroduced. Porting it
 * here would have added a read of a field that does not arrive, on the strength
 * of a commit that had already been superseded.
 *
 * Empty, or an explicit "ALL ROOMS" / "ALL", means every camera — the
 * pre-feature behaviour, and the right default for a safety detector: scoping
 * too widely shows an extra settings section, scoping too narrowly silently
 * stops watching a door.
 */
internal fun wanderingWatchesRoom(room: String, detector: Detector): Boolean {
    val rooms = detector.whereRooms.orEmpty().map { it.uppercase(Locale.ROOT) }
    if (rooms.isEmpty()) return true
    if (rooms.contains("ALL ROOMS") || rooms.contains("ALL")) return true
    return rooms.contains(room.uppercase(Locale.ROOT))
}
