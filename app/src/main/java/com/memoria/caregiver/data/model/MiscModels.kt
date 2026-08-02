package com.memoria.caregiver.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import com.memoria.caregiver.data.remote.NemaraInstantSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/*
 * Ports five small iOS model files that have no home of their own:
 *
 *   Models/LiveKit.swift            → token mint, talk start, record clip, active camera
 *   Models/DeviceRegistration.swift → push token registration
 *   Models/FaceEmbedding.swift      → Re-ID gallery rows + enrollment summary
 *   Models/Feedback.swift           → founder feedback form
 *   Models/Support.swift            → support contact form
 *
 * Wire format is snake_case (PARITY.md §7). Every name that differs from the
 * Kotlin name carries an explicit @SerialName.
 *
 * REQUEST bodies must be exact: the backend uses pydantic `extra="forbid"` on
 * request schemas, so one unknown key is a 422 rather than a warning
 * (PARITY.md §9 R5). RESPONSE decoding is lenient, driven by the shared Json
 * configuration `Json { ignoreUnknownKeys = true; explicitNulls = false }`.
 *
 * Identifiers that Swift types as `UUID` are `String` here. kotlinx.serialization
 * has no built-in java.util.UUID serializer, the wire representation is a string
 * either way, and a second hand-written serializer would have to be kept in sync
 * across every model file. The cost is that a malformed id decodes instead of
 * throwing, which Swift would have caught.
 */

// ---------------------------------------------------------------------------
// Timestamps
// ---------------------------------------------------------------------------
//
// There is no date codec here. The wire format lives in ONE place:
// data/remote/JsonCoders.kt (NemaraInstantSerializer). A second copy written in
// parallel with it disagreed on sub-millisecond precision and on the encoded
// string, so the same wire value decoded differently depending on which model
// file it landed in. Deleted, along with the formatters it used.


// ---------------------------------------------------------------------------
// LiveKit — Models/LiveKit.swift
// ---------------------------------------------------------------------------

/**
 * Request body for `POST /v1/livekit/token`. Mirrors
 * `nemara-backend/src/schemas/livekit_io.py:MintTokenRequest`.
 *
 * `role`:
 *   - `viewer` — caregiver Look-In (read-only video + audio)
 *   - `broadcaster` — caregiver Talk mode (mic publish + subscribe)
 *
 * The `camera` role lives on the wall-phone side; the caregiver app never
 * requests it.
 */
@Serializable
data class MintTokenRequest(
    val room: String,
    val identity: String,
    val role: String,
)

/** Response from the same endpoint. */
@Serializable
data class MintTokenResponse(
    val token: String,
    val url: String,
    /**
     * Instant when the JWT expires. Clients refresh at TTL/2 (~30 min for the
     * current 1h TTL).
     */
    @SerialName("expires_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val expiresAt: Instant,
)

/**
 * Request body for `POST /v1/patients/{id}/talk/start`. The caregiver picks the
 * routing target explicitly via the carousel; re-POSTing with a different
 * `cameraId` transfers the active call.
 */
@Serializable
data class TalkStartRequest(
    @SerialName("camera_id") val cameraId: String,
)

/**
 * Request body for the dev-only
 * `POST /v1/patients/{id}/cameras/{cid}/record-clip` (data collection). Tells
 * the wall camera to record the next `durationSeconds` seconds and ship the clip
 * to the laptop receiver. Backend clamps to 5-300s.
 *
 * Swift's synthesized encoder always writes the field, default or not, so
 * `@EncodeDefault(ALWAYS)` pins that regardless of how the shared Json is
 * configured.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RecordClipRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("duration_seconds")
    val durationSeconds: Int = 15,
)

/**
 * Response from `GET /v1/patients/{id}/active-camera`. The Live tab uses this to
 * default the carousel to the right tile; `cameraId` is null when no qualifying
 * analyses row exists in the last 30s.
 */
@Serializable
data class ActiveCameraResponse(
    @SerialName("camera_id") val cameraId: String? = null,
)

// ---------------------------------------------------------------------------
// Device registration — Models/DeviceRegistration.swift
// ---------------------------------------------------------------------------

/**
 * Body of `POST /v1/me/devices`. Mirrors
 * `nemara-backend/src/schemas/devices.py:RegisterDeviceRequest`.
 *
 * The caregiver app POSTs this on launch (after auth) and on any push token
 * refresh. Idempotent on `apnsToken` — back-to-back POSTs with the same token
 * are de-duplicated on the server.
 *
 * TODO(REQ-8): this is the iOS shape, and the backend contract for Android is
 * unsettled (QUESTIONS.md Q1). FCM has one token in a different namespace, and
 * `environment` has no client-side meaning on Android — iOS picks it at compile
 * time because the APNs entitlement is fixed at codesign time, while FCM
 * resolves it server-side from the service-account key. The backend column also
 * carries a check constraint accepting exactly `sandbox` | `production`, so a
 * third value cannot simply be sent. Whether `/v1/me/devices` grows a `platform`
 * discriminator with a nullable `environment` or Android gets a sibling route is
 * a backend decision. No FCM variant is invented here.
 */
@Serializable
data class RegisterDeviceRequest(
    @SerialName("apns_token") val apnsToken: String,
    @SerialName("bundle_id") val bundleId: String,
    val environment: ApnsEnvironment,
)

/**
 * APNs environment selector. The backend's check constraint accepts exactly
 * these two strings (lowercase); token registration is routed to the matching
 * APNs host.
 */
@Serializable
enum class ApnsEnvironment {
    @SerialName("sandbox") SANDBOX,

    @SerialName("production") PRODUCTION,
}

/**
 * Response from `POST /v1/me/devices`. The caregiver app doesn't strictly need
 * the row back — the registration is fire-and-forget — but mirroring the
 * canonical row keeps the API symmetric with the other endpoints and lets future
 * code cache the registration state.
 */
@Serializable
data class DeviceResponse(
    val id: String,
    @SerialName("caregiver_id") val caregiverId: String,
    @SerialName("apns_token") val apnsToken: String,
    @SerialName("bundle_id") val bundleId: String,
    val environment: ApnsEnvironment,
    @SerialName("created_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val createdAt: Instant,
    @SerialName("updated_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val updatedAt: Instant,
)

// ---------------------------------------------------------------------------
// Face embeddings — Models/FaceEmbedding.swift
// ---------------------------------------------------------------------------

/**
 * One face embedding row in the patient's Re-ID gallery. Mirrors
 * `nemara-backend/src/schemas/face_embedding.py:FaceEmbeddingResponse`.
 *
 * Two row kinds:
 *   - [Source.SELFIE]: caregiver-uploaded during onboarding (10-15 captures)
 *   - [Source.HARVESTED]: backend auto-added a wall-cam face crop after a
 *     high-confidence Re-ID lock. Closes the selfie-to-wall-cam domain gap over
 *     time.
 *
 * The raw 512-dim embedding bytes are NEVER returned over the wire (privacy +
 * uselessness to a client). This type carries only the metadata the dashboard
 * renders.
 */
@Serializable
data class FaceEmbedding(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    val source: Source,
    val status: Status,
    @SerialName("det_score") val detScore: Float,
    @SerialName("failure_reason") val failureReason: String? = null,
    @SerialName("created_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val createdAt: Instant,
) {
    @Serializable
    enum class Source {
        @SerialName("selfie") SELFIE,

        @SerialName("harvested") HARVESTED,
    }

    @Serializable
    enum class Status {
        @SerialName("processing") PROCESSING,

        @SerialName("ready") READY,

        @SerialName("failed") FAILED,
    }
}

/**
 * Summary numbers driving the patient-settings card AND the web dashboard status
 * card. Mirrors `nemara-backend/src/schemas/face_embedding.py:FaceEnrollmentSummary`.
 *
 * The status copy rendered from this type is BYTE-IDENTICAL across the apps and
 * web — caregivers see the same numbers regardless of device.
 */
@Serializable
data class FaceEnrollmentSummary(
    @SerialName("selfie_count") val selfieCount: Int,
    @SerialName("harvested_count") val harvestedCount: Int,
    @SerialName("processing_count") val processingCount: Int,
    @SerialName("failed_count") val failedCount: Int,
    @SerialName("last_update")
    @Serializable(with = NemaraInstantSerializer::class)
    val lastUpdate: Instant? = null,
) {
    /**
     * True when the patient has at least one ready selfie embedding. Drives the
     * "Enrolled" vs "Not enrolled" branch in the status card.
     */
    val isEnrolled: Boolean get() = selfieCount > 0

    /**
     * True when an enrollment batch is mid-processing — caregiver sees
     * "Processing N photos..." instead of the steady-state copy.
     */
    val isProcessing: Boolean get() = processingCount > 0
}

// ---------------------------------------------------------------------------
// Feedback — Models/Feedback.swift
// ---------------------------------------------------------------------------

/**
 * Body of `POST /v1/feedback`. Mirrors
 * `nemara-backend/src/schemas/feedback.py:FeedbackRequest`. Null fields are
 * omitted; the backend requires a rating or at least one non-blank text field.
 */
@Serializable
data class FeedbackRequest(
    val rating: Int? = null,
    @SerialName("whats_working") val whatsWorking: String? = null,
    @SerialName("whats_frustrating") val whatsFrustrating: String? = null,
    val category: String? = null,
    /**
     * Optional screenshots, each a base64-encoded JPEG (no `data:` prefix).
     * Null/empty → omitted. The backend forwards these to the founder
     * notification email and never persists them.
     */
    val attachments: List<String>? = null,
)

/** `{ status, id }` echoed by `POST /v1/feedback`. */
@Serializable
data class FeedbackResponse(
    val status: String,
    val id: String,
)

// ---------------------------------------------------------------------------
// Support — Models/Support.swift
// ---------------------------------------------------------------------------

/**
 * Body of `POST /v1/support`. Mirrors
 * `nemara-backend/src/schemas/support.py:SupportRequest`.
 */
@Serializable
data class SupportRequest(
    val subject: String,
    val message: String,
)

/**
 * Echoed status string the backend returns so the UI can show a friendly toast
 * (e.g. "Sent. We'll reply to your account email.").
 */
@Serializable
data class SupportResponse(
    val status: String,
)
