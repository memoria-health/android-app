package com.memoria.caregiver.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error contract, spec at PARITY.md §5.
 *
 * Every case's copy is asserted as an exact string literal. This copy is what a
 * caregiver reads when something breaks, so paraphrasing it is a silent product
 * change — an assertion built by re-interpolating the implementation's own
 * format string would not catch that.
 */
class ApiErrorTest {

    // MARK: - Verbatim copy, one exact literal per case

    @Test fun `badRequest copy`() {
        assertEquals(
            "Bad request: Couldn't assemble request URL.",
            ApiError.BadRequest("Couldn't assemble request URL.").errorDescription,
        )
    }

    @Test fun `unauthorized copy`() {
        assertEquals(
            "Couldn't verify your session with the server. Check your connection, or sign in again.",
            ApiError.Unauthorized.errorDescription,
        )
    }

    @Test fun `forbidden copy`() {
        assertEquals(
            "You don't have access to this.",
            ApiError.Forbidden.errorDescription,
        )
    }

    @Test fun `notFound copy`() {
        assertEquals(
            "That isn't here anymore.",
            ApiError.NotFound.errorDescription,
        )
    }

    @Test fun `conflict copy`() {
        assertEquals(
            "The server isn't ready yet (clip_not_ready).",
            ApiError.Conflict("clip_not_ready").errorDescription,
        )
    }

    @Test fun `serverError copy is the backend message when there is one`() {
        assertEquals(
            "Upstream timed out.",
            ApiError.ServerError(code = null, serverMessage = "Upstream timed out.").errorDescription,
        )
    }

    @Test fun `serverError copy falls back when the backend sent no message`() {
        assertEquals(
            "The server is having a moment. Try again.",
            ApiError.ServerError(code = null, serverMessage = null).errorDescription,
        )
    }

    @Test fun `offline copy`() {
        assertEquals(
            "You're offline.",
            ApiError.Offline.errorDescription,
        )
    }

    @Test fun `decoding copy`() {
        assertEquals(
            "Couldn't read the response (Expected String at path: \$.patient.name).",
            ApiError.Decoding("Expected String at path: \$.patient.name").errorDescription,
        )
    }

    @Test fun `other copy is the message verbatim with no decoration`() {
        assertEquals(
            "Check your email to confirm your account.",
            ApiError.Other("Check your email to confirm your account.").errorDescription,
        )
    }

    // MARK: - Copy rules the spec calls out explicitly

    /**
     * PARITY.md §5: the 401 copy deliberately does NOT claim the user was signed
     * out, because in dev the usual cause is the backend failing to reach
     * Supabase's JWKS while the user's session is perfectly valid.
     */
    @Test fun `unauthorized copy does not claim the user was signed out`() {
        val copy = ApiError.Unauthorized.errorDescription.lowercase()
        assertFalse(copy.contains("signed out"))
        assertFalse(copy.contains("signed you out"))
        assertFalse(copy.contains("session expired"))
        assertFalse(copy.contains("expired"))
        assertFalse(copy.contains("log in again"))
    }

    /**
     * The Swift source is pure ASCII, apostrophes included. A smart quote pasted
     * in from a doc renders as a different glyph on device and, being invisible in
     * review, is exactly the kind of drift this suite exists to stop.
     */
    @Test fun `all copy is ASCII`() {
        allCases.forEach { case ->
            case.errorDescription.forEach { ch ->
                assertTrue(
                    "non-ASCII char '$ch' in: ${case.errorDescription}",
                    ch.code in 0x20..0x7E,
                )
            }
        }
    }

    /**
     * `code` is carried for callers; iOS's `serverError(_, let m)` ignores it when
     * building the sentence, so a populated code must not leak into the copy.
     */
    @Test fun `serverError code never appears in the copy`() {
        assertEquals(
            "The server is having a moment. Try again.",
            ApiError.ServerError(code = "db_unavailable", serverMessage = null).errorDescription,
        )
        assertEquals(
            "Upstream timed out.",
            ApiError.ServerError(code = "db_unavailable", serverMessage = "Upstream timed out.").errorDescription,
        )
    }

    // MARK: - Payloads that are control flow, not display

    /**
     * PARITY.md §5: `conflict(reason)` carries the backend's `detail` as a code
     * (e.g. `clip_not_ready`) that callers compare against. It must survive
     * unmodified — not lowercased, trimmed, or humanized.
     */
    @Test fun `conflict reason is preserved verbatim for callers to compare`() {
        assertEquals("clip_not_ready", ApiError.Conflict("clip_not_ready").reason)
        assertEquals("Clip_Not_Ready ", ApiError.Conflict("Clip_Not_Ready ").reason)
    }

    @Test fun `payloads are readable off every case that carries one`() {
        assertEquals("boom", ApiError.BadRequest("boom").detail)
        assertEquals("boom", ApiError.Other("boom").detail)
        assertEquals("boom", ApiError.Decoding("boom").underlying)
        assertEquals("db_unavailable", ApiError.ServerError("db_unavailable", null).code)
        assertEquals("boom", ApiError.ServerError("db_unavailable", "boom").serverMessage)
    }

    // MARK: - Throwable bridging

    @Test fun `message mirrors errorDescription on every case`() {
        allCases.forEach { case ->
            assertEquals(case.errorDescription, case.message)
        }
    }

    @Test fun `it is throwable and catchable as itself`() {
        val thrown = try {
            throw ApiError.Conflict("clip_not_ready")
        } catch (e: ApiError) {
            e
        }
        assertEquals(ApiError.Conflict("clip_not_ready"), thrown)
    }

    // MARK: - Equatable

    @Test fun `cases with equal payloads are equal`() {
        assertEquals(ApiError.BadRequest("x"), ApiError.BadRequest("x"))
        assertEquals(ApiError.Conflict("x"), ApiError.Conflict("x"))
        assertEquals(ApiError.Decoding("x"), ApiError.Decoding("x"))
        assertEquals(ApiError.Other("x"), ApiError.Other("x"))
        assertEquals(ApiError.ServerError("a", "b"), ApiError.ServerError("a", "b"))
    }

    @Test fun `cases with different payloads are not equal`() {
        assertNotEquals(ApiError.BadRequest("x"), ApiError.BadRequest("y"))
        assertNotEquals(ApiError.ServerError("a", "b"), ApiError.ServerError("a", "c"))
        assertNotEquals(ApiError.ServerError("a", "b"), ApiError.ServerError("z", "b"))
    }

    /** Distinct cases carrying the same payload shape must not compare equal. */
    @Test fun `different cases are not equal`() {
        assertNotEquals(ApiError.BadRequest("x"), ApiError.Other("x"))
        assertNotEquals(ApiError.Forbidden, ApiError.NotFound)
    }

    @Test fun `payloadless cases are singletons`() {
        assertSame(ApiError.Unauthorized, ApiError.Unauthorized)
        assertEquals(ApiError.Offline, ApiError.Offline)
    }

    // MARK: - Shape

    /**
     * iOS has exactly nine cases. A tenth is an extra the port is not allowed to
     * invent; a missing one is a mapping the API client cannot express.
     */
    @Test fun `there are exactly the nine iOS cases`() {
        assertEquals(
            setOf(
                "BadRequest",
                "Unauthorized",
                "Forbidden",
                "NotFound",
                "Conflict",
                "ServerError",
                "Offline",
                "Decoding",
                "Other",
            ),
            ApiError::class.sealedSubclasses.map { it.simpleName }.toSet(),
        )
    }

    private val allCases: List<ApiError> = listOf(
        ApiError.BadRequest("detail"),
        ApiError.Unauthorized,
        ApiError.Forbidden,
        ApiError.NotFound,
        ApiError.Conflict("clip_not_ready"),
        ApiError.ServerError(code = "db_unavailable", serverMessage = "Upstream timed out."),
        ApiError.ServerError(code = null, serverMessage = null),
        ApiError.Offline,
        ApiError.Decoding("underlying"),
        ApiError.Other("message"),
    )
}
