package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.CreatePatientRequest
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.model.PhotoURLResponse
import com.memoria.caregiver.data.model.TestEmailResponse
import com.memoria.caregiver.data.model.UpdatePatientRequest
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/**
 * The patients resource. Port of the `// MARK: - Patients` block of
 * `Services/NemaraAPIClient.swift`, plus `deletePatient` (which sits further
 * down the Swift file, next to the multipart helpers).
 *
 * **HTTP only — no cache.** The Swift methods each do two things: talk to the
 * server, and then mutate the `@Published var patients` array that the roster
 * observes. Only the first half lives here; every method returns what the
 * server said and the caller decides what to store. That split is why this
 * class can be tested against a mock engine with no view model in sight.
 *
 * The Swift's local mutations, for whoever wires the cache up:
 *  - `loadPatients` replaces the array wholesale
 *  - `createPatient` appends
 *  - `updatePatient` / `uploadPatientPhoto` replace the row with the same id
 *  - `deletePatientPhoto` clears `photoStorageKey` on the row in place
 *  - `deletePatient` removes the row and its sub-collections
 *
 * **Ids are lowercased into every path.** Swift builds them from
 * `id.uuidString.lowercased()`, because `UUID.uuidString` is uppercase and the
 * backend's paths are not. Ids are plain Strings here and already arrive
 * lowercase from the wire, so it is a no-op in practice — it is here so a caller
 * holding an uppercase UUID (a deep link, a pasted id) still hits the URL iOS
 * would. `lowercase()` with no argument is locale-independent, so a Turkish
 * device does not turn `I` into `ı`. `CamerasApi` and `DetectorsApi` do the same
 * thing inline; this file matches them rather than introducing a shared helper.
 */
internal class PatientsApi(private val http: HttpTransport) {

    /**
     * The caregiver's roster.
     *
     * **Returned in server order, unsorted.** Worth stating because the sibling
     * collections are not: cameras are re-sorted by `displayId` on every load
     * and on every Realtime insert, and events are kept newest-first. Patients
     * are neither — the Swift is a bare `patients = list`, so the roster shows
     * whatever order `GET /v1/patients` returns. Sorting here would silently
     * reorder the Android roster relative to iOS.
     */
    suspend fun loadPatients(): List<Patient> = http.get("/v1/patients")

    suspend fun createPatient(body: CreatePatientRequest): Patient =
        http.post("/v1/patients", body)

    suspend fun updatePatient(id: String, body: UpdatePatientRequest): Patient =
        http.patch("/v1/patients/${pathId(id)}", body)

    /**
     * Deletes the patient and everything that hangs off them.
     *
     * A 404 is swallowed. It means the row is already gone server-side — a
     * prior delete from Supabase Studio, or a Realtime DELETE this client
     * missed — and the caller's intent ("this patient should not exist") is
     * satisfied either way. The Swift falls through to its local cleanup on
     * 404 for the same reason, so the caller here must run its cleanup on
     * normal return rather than only on a 204.
     */
    suspend fun deletePatient(id: String) {
        try {
            http.delete("/v1/patients/${pathId(id)}")
        } catch (_: ApiError.NotFound) {
            // Already gone. Nothing left to delete.
        }
    }

    /**
     * Triggers a one-off test alert email for a patient. Goes to the patient's
     * configured recipients (the account email plus the extras) whether or not
     * `emailAlertsEnabled` is on; the backend treats this as an explicit user
     * action. Returns a human-readable status the UI shows next to the button.
     */
    suspend fun sendPatientTestEmail(id: String): TestEmailResponse =
        http.post("/v1/patients/${pathId(id)}/test-email")

    /**
     * Uploads (or replaces) a patient's avatar and returns the updated row,
     * with `photoStorageKey` populated.
     *
     * One multipart part named `photo`, already encoded as JPEG or PNG by the
     * caller. The field name and the filename are copied from the Swift
     * exactly: the backend binds `photo` by name, and it sniffs the extension
     * of the filename to pick the storage key, so `avatar.png` for
     * `image/png` and `avatar.jpg` for everything else is contract, not
     * cosmetics.
     *
     * Ktor builds the body instead of the hand-rolled byte concatenation the
     * Swift uses, and picks its own boundary. The boundary value is not part of
     * the contract — the server reads it out of the Content-Type header — so
     * only the part name, filename and part content type have to match.
     *
     * **On the Content-Type header:** [HttpTransport.execute] sets
     * `application/json` for any non-null body. That header is discarded for a
     * body like this one. Ktor's `mergeHeaders` resolves the outgoing
     * Content-Type as `content.contentType ?: content.headers[Content-Type] ?:
     * requestHeaders[Content-Type]`, so the multipart type — with its boundary
     * — wins and is emitted once. Asserted in `PatientsApiTest` so a Ktor
     * upgrade that reversed that precedence would fail a test rather than
     * produce 422s on every avatar upload.
     */
    suspend fun uploadPatientPhoto(
        patientId: String,
        imageData: ByteArray,
        contentType: String,
    ): Patient = http.post(
        "/v1/patients/${pathId(patientId)}/photo",
        MultiPartFormDataContent(
            formData {
                append(
                    key = PHOTO_PART_NAME,
                    value = imageData,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${photoFilename(contentType)}\"",
                        )
                    },
                )
            },
        ),
    )

    /**
     * Mints a short-lived signed URL for a patient's avatar, or null when no
     * photo has been uploaded.
     *
     * The 404 is control flow, not an error: "no avatar" is the normal state
     * for most patients, and letting it through would put an error banner on
     * the roster. PARITY.md §10 lists this as one of the three swallowed
     * statuses.
     *
     * Returned as a String rather than a parsed URL because that is what
     * [PhotoURLResponse] holds; the URL is short-lived (~10 min) and is meant
     * to be handed straight to the image loader, never cached.
     */
    suspend fun patientPhotoUrl(patientId: String): String? = try {
        http.get<PhotoURLResponse>("/v1/patients/${pathId(patientId)}/photo-url").url
    } catch (_: ApiError.NotFound) {
        null
    }

    /**
     * Removes the patient's avatar. The server is idempotent — 204 even when
     * there was no photo — so unlike [deletePatient] there is no 404 to catch.
     */
    suspend fun deletePatientPhoto(patientId: String) {
        http.delete("/v1/patients/${pathId(patientId)}/photo")
    }

    private companion object {

        /** The multipart field name the backend binds the upload to. */
        const val PHOTO_PART_NAME = "photo"

        /**
         * Exact-match on `image/png`, as the Swift does — anything else,
         * including a `image/png; charset=…` variant, gets the `.jpg` name.
         * Kept literal rather than "improved" so both platforms send the same
         * filename for the same input.
         */
        fun photoFilename(contentType: String): String =
            if (contentType == "image/png") "avatar.png" else "avatar.jpg"
    }
}
