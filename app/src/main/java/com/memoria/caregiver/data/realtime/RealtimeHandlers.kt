package com.memoria.caregiver.data.realtime

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.remote.NemaraJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The four per-table handlers. Port of the `handle*Change` methods in
 * `NemaraAPIClient.swift`.
 *
 * Ordering rules, from PARITY.md §10, are the whole point of this file:
 *
 * | table       | INSERT                     | UPDATE                    | DELETE                       |
 * |-------------|----------------------------|---------------------------|------------------------------|
 * | `patients`  | append if absent           | replace, else append      | remove + cascade-clear       |
 * | `cameras`   | append if absent, re-sort  | replace + window, re-sort | remove                       |
 * | `detectors` | append if absent           | replace, else append      | remove                       |
 * | `events`    | insert at index 0          | replace, else insert at 0 | remove                       |
 *
 * Plain (non-suspending) functions with no I/O, called on one dispatcher — the
 * `@MainActor` handlers they are ported from.
 */
internal class RealtimeHandlers(
    private val cache: RealtimeCache,
    private val live: CameraLiveState,
    private val log: RealtimeLog,
    /**
     * Called with a camera's `livekit_identity` so `AppEnvironment` can ask
     * `LiveKitClient.ensureSubscribed` to recover a `track_subscribed` event
     * lost during a publisher reconnect. Read through a lambda so the hook can
     * be wired after this object is built.
     */
    private val onCameraOnline: (String) -> Unit,
    private val json: Json = NemaraJson.json,
) {

    // ---- patients ----

    fun handlePatientChange(change: RowChange) {
        when (change) {
            is RowChange.Insert -> {
                val p = decode(change.record, Patient.serializer()) ?: return
                if (cache.patients.none { it.id == p.id }) {
                    cache.patients = cache.patients + p
                }
            }

            is RowChange.Update -> {
                val p = decode(change.record, Patient.serializer()) ?: return
                cache.patients = cache.patients.replacingOrAppending(p) { it.id == p.id }
            }

            is RowChange.Delete -> {
                val id = change.oldRecord.rowId() ?: return
                cache.patients = cache.patients.filterNot { it.id == id }
                // Cascade: the sub-collections are keyed by patient id, so
                // leaving them behind leaks every camera, detector and event of
                // a patient who no longer exists.
                cache.camerasByPatient = cache.camerasByPatient - id
                cache.detectorsByPatient = cache.detectorsByPatient - id
                cache.eventsByPatient = cache.eventsByPatient - id
            }
        }
    }

    // ---- cameras ----

    fun handleCameraChange(change: RowChange) {
        when (change) {
            is RowChange.Insert -> {
                val cam = decode(change.record, Camera.serializer()) ?: return
                val arr = cache.camerasByPatient[cam.patientId].orEmpty()
                val next = if (arr.none { it.id == cam.id }) arr + cam else arr
                cache.camerasByPatient =
                    cache.camerasByPatient + (cam.patientId to next.sortedBy { it.displayId })
                notifyOnlineIfNeeded(cam)
            }

            is RowChange.Update -> {
                val cam = decode(change.record, Camera.serializer()) ?: return
                val arr = cache.camerasByPatient[cam.patientId].orEmpty()
                val idx = arr.indexOfFirst { it.id == cam.id }
                val next = if (idx >= 0) {
                    // Backstop for reconnects triggered elsewhere (web, another
                    // device): the optimistic path in `updateCamera` did not
                    // run, so the disconnected → connected flip is detected
                    // here too.
                    if (arr[idx].desiredState == Camera.DesiredState.DISCONNECTED &&
                        cam.desiredState == Camera.DesiredState.CONNECTED
                    ) {
                        live.beginReconnectWindow(cam.id)
                    }
                    arr.toMutableList().also { it[idx] = cam }
                } else {
                    arr + cam
                }
                cache.camerasByPatient =
                    cache.camerasByPatient + (cam.patientId to next.sortedBy { it.displayId })
                notifyOnlineIfNeeded(cam)
            }

            is RowChange.Delete -> {
                val id = change.oldRecord.rowId() ?: return
                removeFirstMatch(cache.camerasByPatient, id, Camera::id) {
                    cache.camerasByPatient = it
                }
            }
        }
    }

    /**
     * Fires [onCameraOnline] whenever an INSERT or UPDATE lands a row for a
     * camera that is meant to be live AND has a `livekit_identity`.
     *
     * "Meant to be live" is `online == true` (worker-confirmed presence) **OR**
     * `desired_state == connected` (caregiver intent). Gating on `online` alone
     * left tiles black: `online` is written by the detector worker's
     * reconciler, so in local/dev — or any moment before the worker has flipped
     * it — the update arrived with `online = false` and the re-subscribe was
     * skipped even though the publisher was live. `desired_state` is always
     * present in the row, so it is the reliable trigger. Over-calling is cheap:
     * `ensureSubscribed` no-ops when the track already exists.
     */
    private fun notifyOnlineIfNeeded(camera: Camera) {
        // A worker-confirmed online row means this sensor has started at least
        // once — clear it out of "preparing" for good, and close any open
        // "Connecting…" buffer: it is up now.
        if (camera.online) {
            live.markEverOnline(camera.id)
            live.clearReconnectWindow(camera.id)
        }
        if (!camera.online && camera.desiredState != Camera.DesiredState.CONNECTED) return
        val identity = camera.livekitIdentity ?: return
        onCameraOnline(identity)
    }

    // ---- detectors ----

    fun handleDetectorChange(change: RowChange) {
        when (change) {
            is RowChange.Insert -> {
                val d = decode(change.record, Detector.serializer()) ?: return
                val arr = cache.detectorsByPatient[d.patientId].orEmpty()
                if (arr.none { it.id == d.id }) {
                    cache.detectorsByPatient =
                        cache.detectorsByPatient + (d.patientId to (arr + d))
                }
            }

            is RowChange.Update -> {
                val d = decode(change.record, Detector.serializer()) ?: return
                val arr = cache.detectorsByPatient[d.patientId].orEmpty()
                cache.detectorsByPatient = cache.detectorsByPatient +
                    (d.patientId to arr.replacingOrAppending(d) { it.id == d.id })
            }

            is RowChange.Delete -> {
                val id = change.oldRecord.rowId() ?: return
                removeFirstMatch(cache.detectorsByPatient, id, Detector::id) {
                    cache.detectorsByPatient = it
                }
            }
        }
    }

    // ---- events ----

    fun handleEventChange(change: RowChange) {
        when (change) {
            is RowChange.Insert -> {
                val e = decodeEvent(change.record) ?: return
                val arr = cache.eventsByPatient[e.patientId].orEmpty()
                if (arr.none { it.id == e.id }) {
                    // Newest first, matching the GET-list sort.
                    cache.eventsByPatient =
                        cache.eventsByPatient + (e.patientId to (listOf(e) + arr))
                }
            }

            is RowChange.Update -> {
                val e = decodeEvent(change.record) ?: return
                val arr = cache.eventsByPatient[e.patientId].orEmpty()
                val idx = arr.indexOfFirst { it.id == e.id }
                val next = if (idx >= 0) {
                    arr.toMutableList().also { it[idx] = e }
                } else {
                    listOf(e) + arr
                }
                cache.eventsByPatient = cache.eventsByPatient + (e.patientId to next)
            }

            is RowChange.Delete -> {
                val id = change.oldRecord.rowId() ?: return
                removeFirstMatch(cache.eventsByPatient, id, DetectionEvent::id) {
                    cache.eventsByPatient = it
                }
            }
        }
    }

    /**
     * Decodes an event, **logging** on failure where the other three handlers
     * swallow silently. Deliberate asymmetry: a swallowed event decode means a
     * real alert never appears, with no trace anywhere. Events are byte-for-byte
     * the REST shape, so this should never fire; the log is the canary if it
     * ever does.
     */
    private fun decodeEvent(record: JsonObject): DetectionEvent? = try {
        json.decodeFromJsonElement(DetectionEvent.serializer(), record)
    } catch (e: Exception) {
        log.debug("[Realtime] events decode failed: $e")
        null
    }

    private fun <T> decode(record: JsonObject, serializer: DeserializationStrategy<T>): T? = try {
        json.decodeFromJsonElement(serializer, record)
    } catch (_: Exception) {
        null
    }
}

/** Swift's `if let idx = … { arr[idx] = new } else { arr.append(new) }`. */
private fun <T> List<T>.replacingOrAppending(value: T, matches: (T) -> Boolean): List<T> {
    val idx = indexOfFirst(matches)
    if (idx < 0) return this + value
    return toMutableList().also { it[idx] = value }
}

/**
 * Removes the row with [id] from whichever patient's list holds it and stops at
 * the first hit — the `return` inside the Swift delete loops.
 *
 * A delete payload carries only the row's own id, not its `patient_id`, so
 * there is nothing to key on and the map has to be searched.
 */
private fun <T> removeFirstMatch(
    byPatient: Map<String, List<T>>,
    id: String,
    idOf: (T) -> String,
    write: (Map<String, List<T>>) -> Unit,
) {
    for ((pid, arr) in byPatient) {
        val idx = arr.indexOfFirst { idOf(it) == id }
        if (idx < 0) continue
        write(byPatient + (pid to arr.filterIndexed { i, _ -> i != idx }))
        return
    }
}
