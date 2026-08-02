package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.model.CreateCaregiverRequest
import com.memoria.caregiver.data.model.SupportRequest
import com.memoria.caregiver.data.model.SupportResponse
import com.memoria.caregiver.data.model.UpdateCaregiverRequest

/**
 * The backend API. Port of `Services/NemaraAPIClient.swift`.
 *
 * **iOS deliberately puts the HTTP client and the observable cache in ONE
 * object**, and the Swift comment explains why: an earlier project split them
 * into repository + store and ended up with two sources of truth that drifted.
 * That structure is preserved. The endpoints live here; the cache and its
 * refetch paths arrive in Phase 3 with the roster, because there is nothing to
 * cache until then.
 *
 * Do not "modernise" this into a repository layer per resource. The comment on
 * the Swift is a scar, not a style preference.
 */
class NemaraApiClient(
    private val http: HttpTransport,
) {

    // ---- Identity: /v1/me ----

    /**
     * Creates the caregiver row after sign-up. Called from the
     * `onAuthenticated` closure with a display name; the JWT identifies who.
     */
    suspend fun provisionMe(
        displayName: String,
        phone: String? = null,
        tosVersion: String? = null,
        country: String? = null,
    ): Caregiver = http.post(
        "/v1/me",
        CreateCaregiverRequest(
            displayName = displayName,
            phone = phone,
            tosVersion = tosVersion,
            country = country,
        ),
    )

    /** The signed-in caregiver. Called on sign-in and on session restore. */
    suspend fun me(): Caregiver = http.get("/v1/me")

    suspend fun updateMe(displayName: String? = null, phone: String? = null): Caregiver =
        http.patch("/v1/me", UpdateCaregiverRequest(displayName = displayName, phone = phone))

    /**
     * Permanently deletes the account and everything server-side: the Supabase
     * auth user, and by cascade every patient, camera, detector, event and
     * device token, plus the storage objects they referenced.
     *
     * **After this returns the session is dead**, so the caller signs out to
     * reset local state rather than leaving the app holding a JWT for a user
     * that no longer exists.
     */
    suspend fun deleteAccount() = http.delete("/v1/me")

    // ---- Support: /v1/support ----

    /**
     * Forwards a Contact Support message to the team. The backend resolves the
     * caregiver from the JWT and sets reply-to to their account email, so
     * replies land in their inbox.
     */
    suspend fun sendSupport(subject: String, message: String): SupportResponse =
        http.post("/v1/support", SupportRequest(subject = subject, message = message))
}
