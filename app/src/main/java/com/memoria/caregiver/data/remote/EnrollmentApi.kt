package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.ApnsEnvironment
import com.memoria.caregiver.data.model.DeviceResponse
import com.memoria.caregiver.data.model.FaceEmbedding
import com.memoria.caregiver.data.model.FaceEnrollmentSummary
import com.memoria.caregiver.data.model.FeedbackRequest
import com.memoria.caregiver.data.model.FeedbackResponse
import com.memoria.caregiver.data.model.RegisterDeviceRequest
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/**
 * Face enrollment, push-token registration and feedback. Port of the
 * `Face enrollment (Re-ID)`, `Device tokens` and `Feedback` sections of
 * `Services/NemaraAPIClient.swift`.
 *
 * Three small families in one class because each is two or three calls and none
 * of them owns any state — every method is one request in, one decoded response
 * out. There is no cache here on purpose: iOS caches patients, cameras and
 * events, and caches none of these.
 *
 * **Nothing in this file logs.** The payloads are selfies of a person with
 * dementia, the embeddings derived from them, and push tokens that identify a
 * device. [HttpTransport] already declines to log a failing response body for
 * the same reason — logcat is readable by anything holding READ_LOGS on a rooted
 * device — and that reasoning applies with more force to a request body full of
 * face JPEGs. If something here ever needs a breadcrumb, log the endpoint, not
 * the payload and not a byte count (a count of captured frames is itself a
 * detail about a specific person's enrollment session).
 */
internal class EnrollmentApi(private val http: HttpTransport) {

    // ---- Face enrollment: /v1/patients/{id}/face-embeddings ----

    /**
     * Uploads N selfies for the patient's Re-ID gallery.
     *
     * The response is the placeholder rows with `status = processing`; the
     * embeddings themselves are computed server-side. The caller polls
     * [faceEnrollmentSummary] until `processingCount` reaches zero.
     *
     * Empty input is a programming error rather than a request: iOS asserts with
     * `precondition`, and the caller there (`FaceEnrollmentView.submit`) already
     * guards on a non-empty capture set. Sending an empty multipart body would
     * cost a round trip to get a 422 back.
     */
    suspend fun submitFaceEmbeddings(
        patientId: String,
        photos: List<ByteArray>,
    ): List<FaceEmbedding> {
        require(photos.isNotEmpty()) { "submitFaceEmbeddings requires at least one photo" }
        return http.post(
            "/v1/patients/${pathId(patientId)}/face-embeddings",
            photosMultipart(photos),
        )
    }

    /** The status-card numbers. Same values the web dashboard renders. */
    suspend fun faceEnrollmentSummary(patientId: String): FaceEnrollmentSummary =
        http.get("/v1/patients/${pathId(patientId)}/face-embeddings/summary")

    /**
     * Every gallery row for the patient, selfies and harvested crops alike. The
     * status card uses [faceEnrollmentSummary] instead; this is the full
     * breakdown.
     */
    suspend fun listFaceEmbeddings(patientId: String): List<FaceEmbedding> =
        http.get("/v1/patients/${pathId(patientId)}/face-embeddings")

    /**
     * Deletes one gallery row. The backend cascades to the stored image, so this
     * is the call that actually removes a face from the system rather than just
     * hiding it.
     *
     * Note the path: an embedding is addressed directly, **not** under its
     * patient. Matches the Swift and the backend route.
     */
    suspend fun deleteFaceEmbedding(embeddingId: String) =
        http.delete("/v1/face-embeddings/${pathId(embeddingId)}")

    /**
     * One part per photo, **all named `photos`** — the repeated field name is
     * what FastAPI needs to bind `list[UploadFile]`. Filenames are `photo_0.jpg`,
     * `photo_1.jpg`, … exactly as iOS numbers them (`_multipartManyParts`).
     *
     * The JPEG bytes go on the wire raw, not base64. (Feedback screenshots are
     * the opposite — see [submitFeedback] — so the two are easy to confuse.)
     *
     * Ktor composes the body instead of the hand-rolled byte concatenation iOS
     * uses. Two harmless differences from the iOS bytes: the boundary string is
     * Ktor's random one rather than `nemara-<uuid>`, and Ktor writes a
     * `Content-Length` on each part. Neither is anything the multipart parser
     * reads back out.
     *
     * [HttpTransport] sets `Content-Type: application/json` on any request with a
     * body, which would be wrong here. It does not survive: Ktor's engine header
     * merge drops the builder's `Content-Type` and emits the body's own instead
     * (`io.ktor.client.engine.mergeHeaders` skips `Content-Type` while copying
     * request headers, then writes `content.contentType`), so what leaves the
     * device is `multipart/form-data; boundary=…`. That is also the only way to
     * get the boundary into the header at all, since it is generated with the
     * body.
     */
    private fun photosMultipart(photos: List<ByteArray>): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                photos.forEachIndexed { index, jpeg ->
                    append(
                        key = "photos",
                        value = jpeg,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "filename=\"photo_$index.jpg\"")
                        },
                    )
                }
            },
        )

    // ---- Device tokens: /v1/me/devices ----

    /**
     * Registers or refreshes this device's push token. Idempotent on the token —
     * the backend updates the existing row rather than inserting a duplicate, so
     * calling it on every launch is the intended pattern.
     *
     * TODO(REQ-8): **this is the iOS wire shape and Android's is not decided
     * yet** (QUESTIONS.md Q1, REQUIREMENTS.md REQ-8, and the matching note on
     * [RegisterDeviceRequest]). It is ported in the iOS shape so the call site
     * and the failure handling exist; the body is expected to change.
     *
     * What is unresolved, and why nothing is guessed here:
     *  - `apns_token` names an APNs token. An FCM registration token is a
     *    different namespace, and the backend has no FCM dispatch path today.
     *  - `environment` has no Android meaning. iOS picks `sandbox` or
     *    `production` at compile time because the APNs entitlement is fixed when
     *    the app is signed; FCM has one token and resolves the environment
     *    server-side from the service-account key. The backend column also
     *    carries a check constraint accepting exactly those two strings, so a
     *    third value like `fcm` cannot simply be sent — hence [ApnsEnvironment]
     *    stays the parameter type and the caller has to pick one of the two.
     *
     * The backend decides between a `platform` discriminator with a nullable
     * `environment` and a sibling route for Android; the client differs either
     * way. When it lands, change this method, [RegisterDeviceRequest], and
     * [deleteDeviceToken] together.
     *
     * `bundleId` deliberately has **no default**, unlike the Swift's
     * `co.sunny.nemara`. Android's applicationId is `com.memoria.caregiver`
     * (DECISIONS.md D-006) and the two platforms are not required to match, so a
     * defaulted identifier here would be a value invented at the wrong layer.
     * The caller passes what it actually is.
     */
    suspend fun registerDeviceToken(
        apnsToken: String,
        bundleId: String,
        environment: ApnsEnvironment,
    ): DeviceResponse = http.post(
        "/v1/me/devices",
        RegisterDeviceRequest(
            apnsToken = apnsToken,
            bundleId = bundleId,
            environment = environment,
        ),
    )

    /**
     * De-registers a push token on sign-out, so a caregiver who signs out stops
     * receiving alerts on a device they walked away from.
     *
     * Scoped server-side to the caller's own rows: a token that has since moved
     * to another account on the same device is left alone. Best-effort — the
     * sign-out flow ignores the failure rather than blocking sign-out on it.
     *
     * TODO(REQ-8): the token travels in the path, so whatever the registration
     * contract turns into, this route moves with it.
     */
    suspend fun deleteDeviceToken(apnsToken: String) =
        http.delete("/v1/me/devices/$apnsToken")

    // ---- Feedback: /v1/feedback ----

    /**
     * Sends the in-app feedback form. The backend stores the row and
     * best-effort emails the team.
     *
     * Null fields are left out of the body entirely (the shared `Json` sets
     * `explicitNulls = false`), which is what makes "I only rated it" and "I only
     * wrote a sentence" both valid. The backend requires a rating or at least one
     * non-blank text field, and the screen enforces its own stricter rule — the
     * "what's not working" box is the one that must be filled.
     *
     * `attachments` are screenshots, each a base64-encoded JPEG with no `data:`
     * prefix. The caller encodes them (`ui/support/FeedbackAttachments.kt`); the
     * backend forwards them to the notification email and never stores them.
     */
    suspend fun submitFeedback(
        rating: Int? = null,
        whatsWorking: String? = null,
        whatsFrustrating: String? = null,
        category: String? = null,
        attachments: List<String>? = null,
    ): FeedbackResponse = http.post(
        "/v1/feedback",
        FeedbackRequest(
            rating = rating,
            whatsWorking = whatsWorking,
            whatsFrustrating = whatsFrustrating,
            category = category,
            attachments = attachments,
        ),
    )

    // Path ids go through the shared `pathId` in PathId.kt.
}
