package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.AddFromLibraryRequest
import com.memoria.caregiver.data.model.CreateDetectorRequest
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.DetectorLibraryEntry
import com.memoria.caregiver.data.model.InferDetectorRequest
import com.memoria.caregiver.data.model.InferDetectorResponse
import com.memoria.caregiver.data.model.RefineDetectorRequest
import com.memoria.caregiver.data.model.UpdateDetectorRequest

/**
 * The detector endpoints. Port of the `MARK: - Detectors` block of
 * `Services/NemaraAPIClient.swift`, plus the two detection-library calls that
 * sit further down that file.
 *
 * **Pure HTTP. No cache lives here.** iOS keeps a `detectorsByPatient`
 * dictionary on the same object as the requests, and after each call it writes
 * the result into that dictionary: `loadDetectors` replaces the patient's
 * array, `createCustomDetector` and `addDetectorsFromLibrary` append,
 * `updateDetector` and a committed `refineDetector` swap the row in place, and
 * `deleteDetector` removes it. Every one of those functions here **returns**
 * the value instead, and the caller that owns the state does the writing. That
 * keeps the ordering rules below stated in exactly one place — the caller —
 * rather than split between the request layer and the screen.
 *
 * **Order is the server's order.** iOS does not sort detectors anywhere: the
 * list is stored exactly as `GET /v1/patients/{id}/detectors` returned it, new
 * detectors are appended to the end, and the Detectors tab only splits the list
 * into "built-in" and "custom" with a filter, which preserves relative order.
 * A caller that appends a created detector to the end of its cached list, and
 * replaces the whole list on the next load, reproduces iOS exactly. Sorting
 * here or in the UI would reorder the list under the caregiver on every reload.
 *
 * Detector and patient ids are lowercased into the path because Swift's
 * `UUID.uuidString` is uppercase and every call site writes
 * `.uuidString.lowercased()`. Ids reaching Kotlin are already lowercase strings
 * from the backend, so this only matters for an id that arrived from somewhere
 * else, such as a deep link a caregiver typed.
 */
internal class DetectorsApi(private val http: HttpTransport) {

    /**
     * Every detector configured for the patient, preset and custom, in the
     * order the server returned them. The caller replaces its cached list for
     * this patient with the result.
     */
    suspend fun loadDetectors(patientId: String): List<Detector> =
        http.get("/v1/patients/${pathId(patientId)}/detectors")

    /**
     * Creates a custom detector. The server forces `source = custom` and starts
     * an asynchronous compile, so the returned row usually has a null
     * `identifier` for the first few seconds; the create screen polls
     * [loadDetectors] until it fills in.
     *
     * The caller appends the returned detector to its cached list.
     */
    suspend fun createCustomDetector(patientId: String, body: CreateDetectorRequest): Detector =
        http.post("/v1/patients/${pathId(patientId)}/detectors", body)

    /**
     * Classify and structure a plain-English detector request for the create
     * review screen. Synchronous, because the caregiver is watching a
     * "Generate" spinner. Returns shape, category, icon and sensitivity, plus a
     * schedule and a plain-words plan, with nulls where the model was unsure.
     *
     * Not scoped to a patient: the backend infers from the words alone.
     */
    suspend fun inferDetector(body: InferDetectorRequest): InferDetectorResponse =
        http.post("/v1/detectors/infer", body)

    /**
     * Patches a detector's settings. The body leaves out every field the caller
     * did not set, which is what makes a PATCH mean "change only these".
     *
     * Returns the server's echo of the whole row, which the caller swaps in
     * place of its cached copy.
     */
    suspend fun updateDetector(id: String, body: UpdateDetectorRequest): Detector =
        http.patch("/v1/detectors/${pathId(id)}", body)

    /**
     * Submits one plain-words change request. The backend appends it to the
     * detector's refinements and rewrites the compiled instructions; the fresh
     * summary lands on the row a few seconds later, which is why the detail
     * sheet re-polls after a committed refine.
     *
     * [preview] is a dry run: the backend compiles WITH this feedback and
     * returns the resulting criteria WITHOUT persisting anything, so the
     * caregiver can read the change before committing it. A preview response
     * describes a detector that does not exist in that form yet, so **the
     * caller must not write it into its cache** — iOS guards the cache write
     * with `if !preview` for exactly this reason. It is still returned in full
     * because the sheet renders the previewed criteria.
     */
    suspend fun refineDetector(id: String, feedback: String, preview: Boolean = false): Detector =
        http.post(
            "/v1/detectors/${pathId(id)}/refine",
            RefineDetectorRequest(feedback = feedback, preview = preview),
        )

    /** Deletes the detector. The caller drops it from its cached list. */
    suspend fun deleteDetector(id: String) =
        http.delete("/v1/detectors/${pathId(id)}")

    /**
     * The detection library for this patient's cohort — the prebuilt detectors
     * they can add. Entries the patient already has come back with
     * `already_added` set, so the picker can pre-check and disable them.
     */
    suspend fun fetchDetectorLibrary(patientId: String): List<DetectorLibraryEntry> =
        http.get("/v1/patients/${pathId(patientId)}/detectors/library")

    /**
     * Instantiates one or more catalog detectors for the patient. No compile
     * step, so these are usable immediately, unlike a custom detector.
     *
     * Returns only the detectors that were actually created: keys the patient
     * already has are skipped server-side, so the returned list can be shorter
     * than [keys] and can even be empty. The caller appends what came back
     * rather than assuming one row per key.
     */
    suspend fun addDetectorsFromLibrary(patientId: String, keys: List<String>): List<Detector> =
        http.post(
            "/v1/patients/${pathId(patientId)}/detectors/from-library",
            AddFromLibraryRequest(keys = keys),
        )
}
