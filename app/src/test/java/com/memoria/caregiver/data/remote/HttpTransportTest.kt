package com.memoria.caregiver.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * HTTP status → [ApiError] mapping. Spec: `NemaraAPIClient.mapStatus` and
 * `formatValidation`, PARITY.md §5.
 *
 * This is the layer a caregiver actually feels: every failed request in the app
 * reaches the screen through here, so a wrong branch shows the wrong sentence.
 */
class HttpTransportTest {

    private fun map(status: Int, body: String = ""): ApiError =
        assertThrows(ApiError::class.java) { HttpTransport.mapStatus(status, body) }

    // ---- Success ----

    @Test fun `2xx does not throw`() {
        listOf(200, 201, 202, 204, 299).forEach { HttpTransport.mapStatus(it, "") }
    }

    // ---- The status table ----

    @Test fun `401 is unauthorized`() = assertEquals(ApiError.Unauthorized, map(401))
    @Test fun `403 is forbidden`() = assertEquals(ApiError.Forbidden, map(403))
    @Test fun `404 is not found`() = assertEquals(ApiError.NotFound, map(404))

    @Test fun `400 carries the server detail`() {
        assertEquals(ApiError.BadRequest("camera_already_paired"), map(400, """{"detail":"camera_already_paired"}"""))
    }

    @Test fun `400 without a detail falls back to the generic sentence`() {
        assertEquals(ApiError.BadRequest("Bad request."), map(400, ""))
    }

    @Test fun `409 carries the reason`() {
        assertEquals(ApiError.Conflict("already_enrolled"), map(409, """{"detail":"already_enrolled"}"""))
    }

    /**
     * The literal string "conflict" is the iOS fallback, and call sites compare
     * against it — 409 is control flow on three endpoints (PARITY.md §10), not
     * an error, so this default must stay exactly this string.
     */
    @Test fun `409 without a detail falls back to the literal conflict`() {
        assertEquals(ApiError.Conflict("conflict"), map(409, ""))
    }

    @Test fun `5xx becomes a server error carrying the detail and no code`() {
        val e = map(503, """{"detail":"upstream unavailable"}""") as ApiError.ServerError
        assertNull(e.code)
        assertEquals("upstream unavailable", e.serverMessage)
    }

    @Test fun `every 5xx status maps to server error`() {
        listOf(500, 502, 503, 504, 599).forEach {
            assert(map(it) is ApiError.ServerError) { "status $it did not map to ServerError" }
        }
    }

    @Test fun `an unmapped status carries its number in the copy`() {
        assertEquals(ApiError.Other("Request failed (418)."), map(418))
    }

    @Test fun `an unmapped status prefers the server detail when there is one`() {
        assertEquals(ApiError.Other("teapot"), map(418, """{"detail":"teapot"}"""))
    }

    // ---- 422, the pydantic path ----

    @Test fun `422 formats the validation envelope`() {
        val body = """
            {"detail":[{"loc":["body","full_name"],"msg":"field required","type":"missing"}]}
        """.trimIndent()
        assertEquals(
            ApiError.BadRequest("Validation failed — body.full_name: field required"),
            map(422, body),
        )
    }

    @Test fun `422 joins multiple items with a semicolon`() {
        val body = """
            {"detail":[
              {"loc":["body","full_name"],"msg":"field required"},
              {"loc":["body","relation"],"msg":"not a valid enumeration member"}
            ]}
        """.trimIndent()
        assertEquals(
            ApiError.BadRequest(
                "Validation failed — body.full_name: field required; " +
                    "body.relation: not a valid enumeration member",
            ),
            map(422, body),
        )
    }

    @Test fun `422 with an empty loc uses the message alone`() {
        val body = """{"detail":[{"loc":[],"msg":"body is required"}]}"""
        assertEquals(ApiError.BadRequest("Validation failed — body is required"), map(422, body))
    }

    @Test fun `422 with a missing msg says invalid`() {
        val body = """{"detail":[{"loc":["body","x"]}]}"""
        assertEquals(ApiError.BadRequest("Validation failed — body.x: invalid"), map(422, body))
    }

    @Test fun `422 with an unparseable body falls back to the generic sentence`() {
        assertEquals(ApiError.BadRequest("Request failed validation (422)."), map(422, "<html>502</html>"))
    }

    @Test fun `422 with an empty detail array falls back too`() {
        assertEquals(
            ApiError.BadRequest("Request failed validation (422)."),
            map(422, """{"detail":[]}"""),
        )
    }

    /**
     * HARDENING.md H-8, pinned so the known weakness is visible rather than
     * discovered. pydantic sends an integer index inside `loc` for list-valued
     * fields; `List<String>` cannot decode it, so the whole envelope is dropped
     * and the caregiver loses the field name.
     *
     * iOS behaves identically. **If this test starts failing, the fix landed —
     * make the same change on iOS or the platforms will disagree about what the
     * same server response says.**
     */
    @Test fun `422 loses the field name when loc contains an index — known, matches iOS`() {
        val body = """{"detail":[{"loc":["body",0,"name"],"msg":"field required"}]}"""
        assertEquals(ApiError.BadRequest("Request failed validation (422)."), map(422, body))
    }

    // ---- formatValidation directly ----

    @Test fun `formatValidation returns null for null and empty`() {
        assertNull(HttpTransport.formatValidation(null))
        assertNull(HttpTransport.formatValidation(emptyList()))
    }

    // ---- The user-facing copy, which is what a caregiver actually reads ----

    @Test fun `each mapped status produces its iOS sentence, verbatim`() {
        assertEquals(
            "Couldn't verify your session with the server. Check your connection, or sign in again.",
            map(401).errorDescription,
        )
        assertEquals("You don't have access to this.", map(403).errorDescription)
        assertEquals("That isn't here anymore.", map(404).errorDescription)
        assertEquals("You're offline.", ApiError.Offline.errorDescription)
        assertEquals(
            "The server is having a moment. Try again.",
            map(500).errorDescription,
        )
        // A server-supplied message wins over the fallback.
        assertEquals("upstream unavailable", map(503, """{"detail":"upstream unavailable"}""").errorDescription)
        assertEquals("The server isn't ready yet (conflict).", map(409).errorDescription)
        assertEquals("Bad request: Bad request.", map(400).errorDescription)
    }
}
