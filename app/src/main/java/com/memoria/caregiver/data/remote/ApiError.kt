package com.memoria.caregiver.data.remote

/**
 * Errors surfaced from the Nemara API client. Maps to nemara-backend's error
 * envelope (FastAPI's `{"detail": ...}` shape).
 *
 * Port of `Networking/APIError.swift`. Spec: PARITY.md §5 — the nine cases and
 * their user-facing copy are fixed there and the copy is verbatim, because it is
 * what a caregiver reads when something breaks.
 *
 * Swift's `enum APIError: Error` becomes a Kotlin sealed class extending
 * [Exception] so the client can throw it, and the payload-carrying cases are
 * data classes so `Equatable` carries over as structural `equals`.
 *
 * Two mechanical notes on the translation:
 *  - Swift's payload label `message:` cannot be reused as a Kotlin property name
 *    on [BadRequest] and [ServerError], because [Throwable.message] is already
 *    that name and it holds the *rendered* copy, not the raw payload. Those
 *    payloads are named after the backend field they actually come from
 *    (`detail`) and after their role (`serverMessage`).
 *  - `LocalizedError.errorDescription` is optional in Swift only because the
 *    protocol declares it so; all nine branches return a value, so
 *    [errorDescription] is non-null here.
 */
sealed class ApiError : Exception() {

    /** The user-facing copy. Swift: `APIError.errorDescription`. */
    abstract val errorDescription: String

    /**
     * Kotlin's counterpart to Swift's `Error.localizedDescription`, which
     * `LocalizedError` conformance routes to `errorDescription`. Anything that
     * logs or prints the throwable therefore reads the same sentence the UI shows.
     */
    override val message: String
        get() = errorDescription

    /**
     * 400 — bad request shape, likely a client-side bug. [detail] is the
     * backend's `{"detail": ...}` string.
     */
    data class BadRequest(val detail: String) : ApiError() {
        override val errorDescription: String
            get() = "Bad request: $detail"
    }

    /**
     * 401 — the backend rejected our token. Usually an expired session, but in
     * local/dev it's often the backend failing to verify a valid token (e.g. it
     * can't reach Supabase's JWKS) — so the copy deliberately does not claim the
     * user was signed out, and points at both causes.
     */
    data object Unauthorized : ApiError() {
        override val errorDescription: String
            get() = "Couldn't verify your session with the server. Check your connection, or sign in again."
    }

    /** 403 — authenticated but forbidden. */
    data object Forbidden : ApiError() {
        override val errorDescription: String
            get() = "You don't have access to this."
    }

    /** 404 — entity does not exist. */
    data object NotFound : ApiError() {
        override val errorDescription: String
            get() = "That isn't here anymore."
    }

    /**
     * 409 — request conflicts with the current resource state. [reason] is the
     * backend's `{"detail": "code"}` value (e.g. `clip_not_ready`); callers
     * compare against it to decide whether to surface a user-facing message or
     * retry later. It is control flow, not just display text.
     */
    data class Conflict(val reason: String) : ApiError() {
        override val errorDescription: String
            get() = "The server isn't ready yet ($reason)."
    }

    /** 5xx — backend is unhealthy. */
    data class ServerError(val code: String?, val serverMessage: String?) : ApiError() {
        override val errorDescription: String
            get() = serverMessage ?: "The server is having a moment. Try again."
    }

    /** Network reachability — no response received. */
    data object Offline : ApiError() {
        override val errorDescription: String
            get() = "You're offline."
    }

    /**
     * Decoding failure — response shape didn't match the contract. Usually means
     * the backend's pydantic schema drifted from the client model; the decode
     * tests under `src/test/.../data/remote/` exist to catch this earlier.
     */
    data class Decoding(val underlying: String) : ApiError() {
        override val errorDescription: String
            get() = "Couldn't read the response ($underlying)."
    }

    /** Catch-all for unexpected paths. [detail] is shown to the user as-is. */
    data class Other(val detail: String) : ApiError() {
        override val errorDescription: String
            get() = detail
    }
}
