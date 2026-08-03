package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.CameraDetectorsResponse
import com.memoria.caregiver.data.model.CameraWanderingDoor
import com.memoria.caregiver.data.model.CreatePairingCodeRequest
import com.memoria.caregiver.data.model.CreatePairingCodeResponse
import com.memoria.caregiver.data.model.DetectDoorsRequest
import com.memoria.caregiver.data.model.DetectDoorsResponse
import com.memoria.caregiver.data.model.DetectedDoor
import com.memoria.caregiver.data.model.DiscoveredCamera
import com.memoria.caregiver.data.model.DiscoveredCamerasResponse
import com.memoria.caregiver.data.model.ProvisionSensorCameraRequest
import com.memoria.caregiver.data.model.ProvisionSensorCameraResponse
import com.memoria.caregiver.data.model.SetCameraDetectorsRequest
import com.memoria.caregiver.data.model.SetWanderingDoorRequest
import com.memoria.caregiver.data.model.UpdateCameraRequest
import com.memoria.caregiver.data.model.WanderingDoorInput
import io.ktor.http.HttpMethod

/**
 * The camera endpoints of `Services/NemaraAPIClient.swift`, ported as HTTP and
 * nothing else. Covers the "Cameras" and "Wandering exit-door picker" sections
 * of the Swift, which is `GET /v1/patients/{id}/cameras` through
 * `POST /v1/cameras/{id}/wandering-door`.
 *
 * **This class holds no state.** The Swift methods each do two jobs — talk to
 * the server, then write the answer into `camerasByPatient` and the two derived
 * maps (`camerasEverOnline`, `reconnectingUntil`). Only the first job lives
 * here; every method returns what it fetched and touches no cache.
 *
 * That split is safe only because it is written down. PARITY.md §10 lists the
 * cache and live-state writes as parity-critical: the 1s poll and the Realtime
 * handler must produce the SAME side effects as these calls, or the same state
 * change renders differently depending on which path observed it. So each
 * method below carries a `Caller still owes:` note quoting the Swift lines that
 * are missing from it. Those notes are the contract for whoever owns the cache
 * — treat a missing one as a bug in this file, not as "no side effect".
 *
 * Ordering: nothing here sorts. `fetchCameras` hands back the server's order
 * verbatim, exactly as Swift's `fetchCameras` does, because Swift's
 * `loadCameras` assigns the array straight into the cache without sorting. The
 * sort by `displayId` belongs to the cache-writing paths (the poll's write-back
 * and the Realtime insert/update handlers), not to the fetch.
 *
 * Identifiers are `String` here where Swift has `UUID`, matching the model
 * layer. Swift builds every path with `id.uuidString.lowercased()`, so
 * [lowercase] is applied on the way into the URL for the same reason: Swift's
 * `uuidString` is upper-case and the backend's route matching is
 * case-sensitive. A caller that passes an already-lower-case id is unaffected.
 */
internal class CamerasApi(private val http: HttpTransport) {

    // ---- Cameras ----

    /**
     * A patient's cameras, in the order the server returned them.
     *
     * Port of BOTH Swift methods, which differ only in the cache write:
     * `loadCameras(forPatient:)` and the private `fetchCameras(forPatient:)`.
     * The Swift comment on the private one — "GET a patient's cameras WITHOUT
     * mutating the cache — lets the 1s state poll fetch fresh rows and reconcile
     * only what actually changed" — describes what this method always does.
     *
     * Caller still owes, to be `loadCameras` rather than `fetchCameras`:
     *   `camerasByPatient[pid] = cams`
     *   `for cam in cams where cam.online { markEverOnline(cam.id) }`
     *
     * The second line is not bookkeeping. `camerasEverOnline` is persisted, and
     * it is what stops an established camera from reading as "preparing" after a
     * relaunch (PARITY.md §10). Dropping it makes every offline camera look like
     * it is still setting itself up.
     */
    suspend fun fetchCameras(patientId: String): List<Camera> =
        http.get("/v1/patients/${pathId(patientId)}/cameras")

    /**
     * Mints a 6-digit pairing code for a new wall phone on this patient.
     *
     * The server inserts a placeholder `cameras` row with `online = false` and a
     * code valid for about 10 minutes; the wall phone completes the exchange via
     * `POST /v1/cameras/pair`. The placeholder row is why the Swift refreshes
     * afterwards — it flips to `online = true` once the phone connects, over
     * Realtime.
     *
     * Caller still owes:
     *   `try await loadCameras(forPatient: pid)`
     * run AFTER this returns, so the placeholder row appears in the list. Note
     * this is the full `loadCameras`, cache write and ever-online marking both.
     */
    suspend fun createPairingCode(
        patientId: String,
        room: String,
        iconName: String = "tv",
    ): CreatePairingCodeResponse = http.post(
        "/v1/patients/${pathId(patientId)}/cameras/pairing-code",
        CreatePairingCodeRequest(room = room, iconName = iconName),
    )

    /**
     * Re-mints a pairing code for an already-created but unpaired camera. Drives
     * the camera detail view's "Show pairing code" button, for when the
     * caregiver dismissed the original sheet before entering the code on the
     * wall phone.
     *
     * Same response shape as [createPairingCode]. The backend returns 409 if the
     * camera is already paired, which surfaces as [ApiError.Conflict]; the UI
     * hides the button in that case anyway.
     *
     * No body, matching the Swift's `sendNoBody`. Caller owes no cache write:
     * the Swift does not refresh here, because re-minting a code changes nothing
     * the camera list renders.
     */
    suspend fun regeneratePairingCode(cameraId: String): CreatePairingCodeResponse =
        http.post("/v1/cameras/${pathId(cameraId)}/pairing-code")

    /**
     * Sensor cameras that have announced themselves to the backend but no
     * caregiver has claimed yet. Drives the "Sensor Camera" tab of the
     * add-camera sheet. The backend filters to unclaimed rows seen within the
     * last 5 minutes, so this list shrinks on its own.
     *
     * The response is an envelope, `{"pis": [...]}`, and the Swift unwraps it
     * before returning; so does this. Caller owes no cache write — discovered
     * cameras are not `Camera` rows and have no home in `camerasByPatient`.
     */
    suspend fun listDiscoveredCameras(): List<DiscoveredCamera> {
        val response: DiscoveredCamerasResponse = http.get("/v1/cameras/discovered")
        return response.pis
    }

    /**
     * Pairs a discovered sensor camera to this patient.
     *
     * [discoveryId] comes from [listDiscoveredCameras]. The backend reads the
     * stream URL off the discovery record, creates a `cameras` row with
     * `source_type = 'pi'`, and marks the discovery claimed so it drops out of
     * later [listDiscoveredCameras] calls.
     *
     * Caller still owes:
     *   `try await loadCameras(forPatient: pid)`
     * so the new row shows up immediately rather than waiting on Realtime.
     */
    suspend fun provisionSensorCamera(
        patientId: String,
        room: String,
        iconName: String = "tv",
        discoveryId: String,
    ): ProvisionSensorCameraResponse = http.post(
        "/v1/patients/${pathId(patientId)}/cameras/pi-provision",
        ProvisionSensorCameraRequest(
            room = room,
            iconName = iconName,
            discoveryId = discoveryId,
        ),
    )

    /**
     * Permanently deletes a camera row. Distinct from [unpairCamera], which
     * keeps the row.
     *
     * Caller still owes the local removal the Swift does inline — it searches
     * every patient's list because the caller only has a camera id:
     *   `for (pid, cams) in camerasByPatient {
     *        if let idx = cams.firstIndex(where: { $0.id == id }) {
     *            camerasByPatient[pid]?.remove(at: idx); break } }`
     * Note the `break`: it stops at the first patient that had the row, so this
     * is a remove-one, not a remove-everywhere.
     */
    suspend fun deleteCamera(id: String) {
        http.delete("/v1/cameras/${pathId(id)}")
    }

    /**
     * Soft-unpairs a camera: clears its pairing server-side so it returns to the
     * unpaired state (`paired == false`) WITHOUT deleting the row. The room, the
     * detector assignments and the history are kept, and the camera becomes
     * re-pairable via "Show pairing code".
     *
     * Caller still owes a reload of the OWNING patient, which the Swift finds by
     * scanning the cache for the id:
     *   `for (pid, cams) in camerasByPatient where cams.contains(where: { $0.id == id }) {
     *        try? await loadCameras(forPatient: pid); break }`
     * Two details worth keeping. It is `try?`, so a failed reload does not fail
     * the unpair — the Realtime UPDATE carries the same flip. And it breaks
     * after the first match, so exactly one patient is reloaded.
     */
    suspend fun unpairCamera(id: String) {
        http.postVoid("/v1/cameras/${pathId(id)}/unpair")
    }

    /**
     * Flips the user-facing toggles on a camera: `disabled` and `ai_paused`
     * (pause the AI call; LiveKit stays connected) and `desired_state` (a hard
     * disconnect — the wall phone polls `/v1/cameras/me/desired-state` every
     * second and reconciles). Null fields in the body are left off the wire, so
     * sending a field is what commits us to changing it.
     *
     * Caller still owes TWO things, and the second is the one that gets missed.
     *
     * 1. The optimistic cache write, replacing the row with the response:
     *      `arr[idx] = updated; camerasByPatient[updated.patientId] = arr`
     *
     * 2. The reconnect window, opened only on the disconnected → connected edge,
     *    and read from the row that was in the cache BEFORE the write:
     *      `if arr[idx].desiredState == .disconnected, updated.desiredState == .connected {
     *           reconnectingUntil[updated.id] = Date().addingTimeInterval(reconnectWindow) }`
     *    That 10-second window is what shows "Connecting…" on the Live tile
     *    instead of nothing while the phone reconnects. PARITY.md §10 lists this
     *    as one of three places the window is set, all three required; the other
     *    two are the Realtime handler and the 1s poll. Because it is an edge
     *    check, the comparison has to happen before the row is replaced.
     */
    suspend fun updateCamera(id: String, body: UpdateCameraRequest): Camera =
        http.patch("/v1/cameras/${pathId(id)}", body)

    // ---- Per-camera detector assignment ----

    /**
     * The detector ids currently assigned to a camera. The camera detail view
     * cross-references these against the patient's full detector list to render
     * the assignment toggles.
     *
     * Unwraps the `{"detector_ids": [...]}` envelope, as the Swift does. No
     * cache: the Swift keeps no per-camera detector map.
     */
    suspend fun getCameraDetectors(id: String): List<String> {
        val response: CameraDetectorsResponse =
            http.get("/v1/cameras/${pathId(id)}/detectors")
        return response.detectorIds
    }

    /**
     * Replaces a camera's detector assignment set with exactly [detectorIds].
     *
     * PUT, not POST — this is a whole-set replace, and an empty list means "no
     * detectors on this camera". The backend returns 422 if any id belongs to a
     * different patient than the camera.
     *
     * Returns the resulting id list from the response envelope rather than
     * echoing the argument back, which is what the Swift does. The two can
     * differ: the server is free to normalise the set, so the response is what
     * the UI should render.
     *
     * Takes effect on the next detector tick — the backend reads the join table
     * per call, so there is no server-side cache to invalidate either.
     */
    suspend fun setCameraDetectors(id: String, detectorIds: List<String>): List<String> {
        val response: CameraDetectorsResponse = http.send(
            HttpMethod.Put,
            "/v1/cameras/${pathId(id)}/detectors",
            body = SetCameraDetectorsRequest(detectorIds = detectorIds),
        )
        return response.detectorIds
    }

    // ---- Wandering exit-door picker ----

    /**
     * Sends one camera frame to the VLM and returns the doorways it found, so
     * the exit-door picker can highlight them.
     *
     * [imageBase64] is a base64-encoded JPEG in the request BODY, not a file
     * upload — this endpoint is plain JSON, unlike the photo and face-embedding
     * endpoints, which are multipart. Boxes come back as `[x, y, w, h]`
     * fractions of the frame with a top-left origin.
     *
     * Note the path has no camera in it: `/v1/detect-doors` is patient- and
     * camera-agnostic, because it only looks at the pixels it was handed.
     */
    suspend fun detectDoors(imageBase64: String): List<DetectedDoor> {
        val response: DetectDoorsResponse = http.post(
            "/v1/detect-doors",
            DetectDoorsRequest(imageBase64 = imageBase64),
        )
        return response.doors
    }

    /**
     * The saved exit-door config for a patient, one entry per camera that has a
     * wandering assignment. Per camera rather than per patient because each
     * sensor sees a different scene.
     */
    suspend fun wanderingDoors(patientId: String): List<CameraWanderingDoor> =
        http.get("/v1/patients/${pathId(patientId)}/wandering-doors")

    /**
     * Saves the exit-door choice for ONE camera. The backend builds that
     * camera's wandering prompt from it.
     *
     * [noExitDoor] means this camera watches no exit, and wandering is skipped
     * there — it is a real choice, not an empty state, which is why it is sent
     * alongside [doors] rather than inferred from an empty list.
     *
     * The Swift is `@discardableResult`; the returned row is the freshly saved
     * config. No cache write on either side.
     */
    suspend fun setCameraWanderingDoors(
        cameraId: String,
        doors: List<WanderingDoorInput>,
        noExitDoor: Boolean,
    ): CameraWanderingDoor = http.post(
        "/v1/cameras/${pathId(cameraId)}/wandering-door",
        SetWanderingDoorRequest(doors = doors, noExitDoor = noExitDoor),
    )
}
