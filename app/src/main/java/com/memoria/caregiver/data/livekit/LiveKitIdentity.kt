package com.memoria.caregiver.data.livekit

import java.util.Locale

/**
 * The LiveKit participant identity and room name this app connects as.
 *
 * On iOS these two strings are built inline inside
 * `NemaraAPIClient.mintToken(forPatient:role:)`. They are lifted out here for
 * one reason: **the suffix is the single highest-consequence string in the
 * whole port**, and inline string interpolation is exactly where a copy-paste
 * from the Swift would carry `_ios` across and nobody would notice in review.
 *
 * ```
 * web     → caregiver_<uuid>_web
 * iOS     → caregiver_<uuid>_ios
 * Android → caregiver_<uuid>_android
 * ```
 *
 * LiveKit treats `identity` as the **unique participant key per room**. Two
 * clients that connect with the same identity are the same participant, and
 * the server kicks the older session. So if Android minted `_ios`, a caregiver
 * opening the Live tab on their Android phone would silently drop their own
 * iPhone off the stream, and vice versa — a disconnect with no error on either
 * device and no server-side sign that anything went wrong. Distinct suffixes
 * are what let the same caregiver watch a patient from web, iPhone and Android
 * at the same time.
 *
 * The backend validates the prefix and re-mints the token with whatever we
 * send, so this string is also what shows up in LiveKit's audit logs.
 */
object LiveKitIdentity {

    /**
     * Never `_ios`. See the class comment; `LiveKitIdentityTest` fails if this
     * is ever changed to another platform's suffix.
     */
    const val PLATFORM_SUFFIX: String = "_android"

    /**
     * `caregiver_<uuid>_android` — the identity this app publishes and
     * subscribes as.
     *
     * [caregiverId] is the Supabase `auth.uid()` for the signed-in caregiver.
     * Lowercased because Swift's `uuidString` is uppercase and the backend's
     * prefix check, the web client and the LiveKit audit logs all expect the
     * lowercase form.
     */
    fun caregiverIdentity(caregiverId: String): String =
        "caregiver_${caregiverId.lowercase(Locale.ROOT)}$PLATFORM_SUFFIX"

    /**
     * `patient_<uuid>` — the room name. One room per patient; every camera in
     * the household publishes into it, and the caregiver views one room at a
     * time.
     */
    fun roomName(patientId: String): String = "patient_${patientId.lowercase(Locale.ROOT)}"

    /** Subscribe only. iOS `mintViewerToken`. */
    const val VIEWER_ROLE: String = "viewer"

    /** Subscribe plus `canPublish = true`, so the mic can go live. iOS `mintBroadcasterToken`. */
    const val BROADCASTER_ROLE: String = "broadcaster"
}
