package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.livekit.LiveKitIdentity
import com.memoria.caregiver.data.model.ActiveCameraResponse
import com.memoria.caregiver.data.model.MintTokenRequest
import com.memoria.caregiver.data.model.MintTokenResponse
import com.memoria.caregiver.data.model.RecordClipRequest
import com.memoria.caregiver.data.model.TalkStartRequest

/**
 * The LiveKit and Talk endpoints. Port of the
 * `// MARK: - LiveKit (POST /v1/livekit/token) + Talk` section of
 * `Services/NemaraAPIClient.swift`.
 *
 * Split out of [NemaraApiClient] because none of these six calls touch the
 * observable cache the Swift object exists to hold — they are pure request and
 * response. There is deliberately no state here: no cached token, no "current
 * talk target", no flows. The Live tab owns that; a token cached here would
 * outlive its `expiresAt` and reconnect with a dead JWT.
 *
 * The identity and room strings come from [LiveKitIdentity] and are **never**
 * built here. See that file for why the platform suffix is the one string in
 * this port that cannot be got wrong quietly.
 */
internal class LiveKitApi(private val http: HttpTransport) {

    // ---- Token minting: /v1/livekit/token ----

    /**
     * Mints a viewer JWT for the patient's room. Used by the Live tab to
     * subscribe to the wall phones' video and audio.
     *
     * [caregiverId] is the signed-in caregiver's Supabase user id, passed in
     * rather than read from an auth service so this class stays a pure HTTP
     * layer. iOS reaches into its Supabase client here and throws
     * `.unauthorized` when there is no session; on Android the caller is
     * already holding the session, so the check belongs there.
     */
    suspend fun mintViewerToken(patientId: String, caregiverId: String): MintTokenResponse =
        mintToken(patientId, caregiverId, LiveKitIdentity.VIEWER_ROLE)

    /**
     * Mints a broadcaster JWT. Same room as the viewer token, plus
     * `canPublish = true` so the caregiver's mic can go live during Talk mode.
     */
    suspend fun mintBroadcasterToken(patientId: String, caregiverId: String): MintTokenResponse =
        mintToken(patientId, caregiverId, LiveKitIdentity.BROADCASTER_ROLE)

    private suspend fun mintToken(
        patientId: String,
        caregiverId: String,
        role: String,
    ): MintTokenResponse = http.post(
        "/v1/livekit/token",
        MintTokenRequest(
            // Both strings have exactly one definition in this app. Building
            // them inline is how a copy-paste from the Swift carries `_ios`
            // across, which silently evicts the caregiver's own iPhone from the
            // stream. FINDINGS.md F-2 deleted one duplicate already.
            room = LiveKitIdentity.roomName(patientId),
            identity = LiveKitIdentity.caregiverIdentity(caregiverId),
            role = role,
        ),
    )

    // ---- Talk: /v1/patients/{id}/... ----

    /**
     * Best guess at the camera the patient is currently in front of. The Live
     * tab uses it to default the carousel to the right tile.
     *
     * Returns null when the backend has no qualifying analysis row from the last
     * 30 seconds — that is a normal, successful 200 carrying `camera_id: null`,
     * **not** a failure. Every real failure (403 on someone else's patient, 5xx,
     * offline) throws an [ApiError] instead, so a caller that sees null can
     * safely fall back to the first online camera without wondering whether the
     * request actually worked.
     */
    suspend fun getActiveCamera(patientId: String): String? =
        http.get<ActiveCameraResponse>("/v1/patients/$patientId/active-camera").cameraId

    /**
     * Opens, or transfers, a call to one specific camera. The backend sends
     * `is_active = true` to that camera and `is_active = false` to every sibling
     * in the same call, so re-posting with a different [cameraId] transfers an
     * open call atomically rather than leaving two microphones live.
     */
    suspend fun startTalk(patientId: String, cameraId: String) =
        http.postVoid("/v1/patients/$patientId/talk/start", TalkStartRequest(cameraId = cameraId))

    /**
     * Closes any open call by sending `is_active = false` to every camera in the
     * patient's room. Idempotent, so it is safe to call on teardown without
     * first checking whether a call was open.
     *
     * No request body, matching iOS's `sendNoBodyVoid`. The backend takes the
     * patient from the path and the caregiver from the JWT.
     */
    suspend fun endTalk(patientId: String) = http.postVoid("/v1/patients/$patientId/talk/end")

    /**
     * Dev-only, for data collection: tells one camera to record the next
     * [durationSeconds] seconds locally and upload the clip to the laptop
     * receiver. The backend mounts this route only when
     * `NEMARA_ENABLE_INTERNALS=true` and clamps the duration to 5-300s, so a
     * production build hitting it gets a 404. Not a caregiver-facing feature.
     */
    suspend fun recordClip(patientId: String, cameraId: String, durationSeconds: Int = 15) =
        http.postVoid(
            "/v1/patients/$patientId/cameras/$cameraId/record-clip",
            RecordClipRequest(durationSeconds = durationSeconds),
        )

    // Swift lowercases every id it interpolates into a path, because Foundation's
    // `UUID.uuidString` is uppercase. Here the ids arrive as strings decoded
    // straight from the backend, which are already lowercase, and FastAPI parses
    // a path `UUID` case-insensitively either way — so there is nothing to
    // normalise. The room and identity strings are the opposite case: LiveKit
    // compares those byte for byte, which is why LiveKitIdentity lowercases.
}
