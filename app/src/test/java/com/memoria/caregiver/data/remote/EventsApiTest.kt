@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.EventFilter
import com.memoria.caregiver.data.model.UpdateEventRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.ExperimentalTime

/**
 * Wire tests for [EventsApi]. Spec: the `// MARK: - Events` block of
 * `Services/NemaraAPIClient.swift`, PARITY.md §10.
 *
 * Every test asserts what actually went on the wire — method, path, query,
 * request body — through a Ktor [MockEngine], because that is the only part of
 * these one-line endpoint functions that can be wrong. Asserting that a
 * function returns the object we just told the fake server to send would prove
 * nothing, so decode assertions are only made where the decode itself carries
 * a rule: page ORDER, the end-of-stream signal, and the 409-means-null branch.
 *
 * No Android framework and no Robolectric: MockEngine needs neither.
 */
class EventsApiTest {

    private companion object {
        const val BASE = "https://backend.test"
        const val PATIENT = "11111111-2222-3333-4444-555555555555"
        const val EVENT = "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b"

        /**
         * A real cursor, produced by running the backend's `encode_cursor` on
         * an `(occurred_at, id)` pair: url-safe base64 of a small JSON object,
         * `=` padding stripped. Used verbatim rather than as a short made-up
         * token so the test would notice a client that "helpfully" re-padded
         * or re-encoded it.
         */
        const val CURSOR =
            "eyJvIjogIjIwMjYtMDctMzBUMDk6MDU6MDArMDA6MDAiLCAiaSI6ICIwZjhiM2Q3YS05YzJlLTRmNjE" +
                "tOGIwZC01ZTdhMWM5ZDJmMzQifQ"
    }

    /** Requests the code under test made, in order. */
    private val sent = mutableListOf<HttpRequestData>()

    /**
     * An [EventsApi] whose server replies with [bodies] in order, one per
     * request, each with the matching entry of [statuses]. The last body is
     * reused if more requests arrive than bodies, which keeps single-call
     * tests to one literal.
     */
    private fun api(
        vararg bodies: String,
        statuses: List<HttpStatusCode> = emptyList(),
    ): EventsApi {
        val engine = MockEngine { request ->
            val index = sent.size
            sent += request
            respond(
                content = bodies.getOrElse(index) { bodies.last() },
                status = statuses.getOrElse(index) { HttpStatusCode.OK },
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(NemaraJson.json) }
        }
        return EventsApi(HttpTransport(client, BASE, tokenProvider = { "test-token" }))
    }

    /**
     * Runs [block] and returns the [ApiError] it threw. A plain
     * `assertThrows` cannot call a suspend function, and nesting a second
     * `runTest` inside one to get around that runs the call on a different
     * scheduler than the test it belongs to.
     */
    private suspend fun apiErrorFrom(block: suspend () -> Unit): ApiError = try {
        block()
        throw AssertionError("expected an ApiError, but the call returned normally")
    } catch (e: ApiError) {
        e
    }

    private fun requestBody(index: Int = 0): JsonObject =
        NemaraJson.json.parseToJsonElement((sent[index].body as TextContent).text) as JsonObject

    private fun expected(json: String): JsonObject =
        NemaraJson.json.parseToJsonElement(json) as JsonObject

    /** One event, shaped like the REST response the backend sends. */
    private fun eventJson(
        id: String = EVENT,
        occurredAt: String = "2026-07-30T18:20:11.482910+00:00",
        reviewed: Boolean = false,
    ) = """
        {
          "id": "$id",
          "patient_id": "$PATIENT",
          "detector_id": "22222222-3333-4444-5555-666666666666",
          "camera_id": "33333333-4444-5555-6666-777777777777",
          "category": "safety",
          "source": "preset",
          "icon_name": "figure.fall",
          "title": "Possible fall",
          "location": "Kitchen",
          "occurred_at": "$occurredAt",
          "has_clip": true,
          "narrative": "Someone appeared to fall near the counter.",
          "opened": false,
          "reviewed": $reviewed,
          "review_choice": null,
          "review_notes": null,
          "reviewed_at": null,
          "clip_storage_key": "clips/$id.mp4",
          "clip_suppressed": false,
          "clip_censored": false,
          "sensitivity_categories": null,
          "created_at": "2026-07-30T18:20:13.100000+00:00"
        }
    """.trimIndent()

    private fun pageJson(items: String, nextCursor: String?) = """
        {"items":[$items],"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}
    """.trimIndent()

    // =====================================================================
    // Paging — the contract documented on EventsApi.loadEventsPage
    // =====================================================================

    @Test fun `first page is a GET to the patient's events with filter and no cursor`() = runTest {
        api(pageJson(eventJson(), nextCursor = null)).loadEventsFirstPage(PATIENT)

        val request = sent.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/patients/$PATIENT/events", request.url.encodedPath)
        assertEquals("all", request.url.parameters["filter"])
        // A cursor must be ABSENT, not empty: the backend reads an
        // unparseable cursor as "start from the top", so `cursor=` would be
        // indistinguishable from page 1 and page 2 would repeat page 1.
        assertFalse("first page must not send a cursor", request.url.parameters.contains("cursor"))
    }

    /**
     * `limit` is never sent by either platform, so the page size is the
     * server's default of 30. Pinned because a caller that starts sending one
     * changes how many rows a page holds without changing anything visible
     * here.
     */
    @Test fun `no limit is sent, so the page size is the server default`() = runTest {
        api(pageJson("", nextCursor = null)).loadEventsFirstPage(PATIENT)
        assertFalse(sent.single().url.parameters.contains("limit"))
    }

    @Test fun `the filter goes on the wire as its backend value`() = runTest {
        val api = api(pageJson("", nextCursor = null))
        api.loadEventsPage(PATIENT, EventFilter.UNREVIEWED, cursor = null)
        api.loadEventsPage(PATIENT, EventFilter.REVIEWED, cursor = null)
        api.loadEventsFirstPage(PATIENT)

        assertEquals("unreviewed", sent[0].url.parameters["filter"])
        assertEquals("reviewed", sent[1].url.parameters["filter"])
        // loadEventsFirstPage defaults to `all`, matching iOS.
        assertEquals("all", sent[2].url.parameters["filter"])
    }

    /**
     * The whole point of the second call: page 2 is page 1 plus the cursor
     * page 1 handed back, verbatim. Same path, same filter, one extra
     * parameter — no offset, no page number.
     */
    @Test fun `the next page repeats the request with the returned cursor, unmodified`() = runTest {
        val api = api(
            pageJson(eventJson(id = "aaaaaaaa-0000-0000-0000-000000000001"), nextCursor = CURSOR),
            pageJson(eventJson(id = "aaaaaaaa-0000-0000-0000-000000000002"), nextCursor = null),
        )

        val first = api.loadEventsFirstPage(PATIENT, EventFilter.UNREVIEWED)
        assertEquals(CURSOR, first.nextCursor)
        val second = api.loadEventsPage(PATIENT, EventFilter.UNREVIEWED, cursor = first.nextCursor)

        assertEquals(2, sent.size)
        assertEquals(sent[0].url.encodedPath, sent[1].url.encodedPath)
        assertEquals("unreviewed", sent[1].url.parameters["filter"])
        // Read back decoded, so the comparison is against the token the server
        // issued rather than against whatever encoding the URL builder chose.
        assertEquals(CURSOR, sent[1].url.parameters["cursor"])
        // Null next cursor is the end of the stream, and the only end signal.
        assertNull(second.nextCursor)
    }

    @Test fun `an absent next_cursor key also means the last page`() = runTest {
        // The backend sends explicit null; a trimmed payload without the key
        // must not become a decode failure that empties the feed.
        val page = api("""{"items":[]}""").loadEventsFirstPage(PATIENT)
        assertNull(page.nextCursor)
        assertTrue(page.items.isEmpty())
    }

    /**
     * The server sorts `occurred_at DESC, id DESC` and the client keeps that
     * order. The fixture is deliberately newest-first WITH an id that sorts
     * before its successor, so a client that re-sorted by id — the obvious
     * accidental "tidy up" — would flip the two.
     */
    @Test fun `page order is the server's, not re-sorted`() = runTest {
        val newest = eventJson(
            id = "aaaaaaaa-0000-0000-0000-000000000001",
            occurredAt = "2026-07-30T18:20:11.482910+00:00",
        )
        val older = eventJson(
            id = "ffffffff-0000-0000-0000-000000000002",
            occurredAt = "2026-07-30T09:05:00.000000+00:00",
        )

        val page = api(pageJson("$newest,$older", nextCursor = null)).loadEventsFirstPage(PATIENT)

        assertEquals(
            listOf(
                "aaaaaaaa-0000-0000-0000-000000000001",
                "ffffffff-0000-0000-0000-000000000002",
            ),
            page.items.map { it.id },
        )
    }

    // =====================================================================
    // fetchEvent
    // =====================================================================

    @Test fun `fetchEvent gets the caregiver-scoped single-event path`() = runTest {
        val event = api(eventJson()).fetchEvent(EVENT)

        assertEquals(HttpMethod.Get, sent.single().method)
        assertEquals("/v1/events/$EVENT", sent.single().url.encodedPath)
        assertTrue(sent.single().url.parameters.isEmpty())
        assertEquals(EVENT, event.id)
    }

    /**
     * A foreign or deleted id is a 404 on this endpoint. It must throw: the
     * deep-link caller shows "That isn't here anymore" rather than opening an
     * empty detail sheet.
     */
    @Test fun `fetchEvent surfaces a 404 as NotFound`() = runTest {
        val api = api("""{"detail":"event_not_found"}""", statuses = listOf(HttpStatusCode.NotFound))
        assertEquals(ApiError.NotFound, apiErrorFrom { api.fetchEvent(EVENT) })
    }

    // =====================================================================
    // clipUrl — 409 is control flow, everything else is an error
    // =====================================================================

    @Test fun `clipUrl returns the signed url from the clip-url path`() = runTest {
        val body = """{"url":"https://storage.test/clip.mp4?token=abc","expires_in_seconds":600}"""
        val url = api(body).clipUrl(EVENT)

        assertEquals(HttpMethod.Get, sent.single().method)
        assertEquals("/v1/events/$EVENT/clip-url", sent.single().url.encodedPath)
        assertEquals("https://storage.test/clip.mp4?token=abc", url)
    }

    /**
     * The clip is still being archived. This is the expected answer for the
     * first ~25-45 seconds after an event fires, and it must read as "no video
     * YET", not as a failure — the detail view polls on it.
     */
    @Test fun `clipUrl returns null when the clip is not ready`() = runTest {
        val api = api("""{"detail":"clip_not_ready"}""", statuses = listOf(HttpStatusCode.Conflict))
        assertNull(api.clipUrl(EVENT))
    }

    /**
     * Swift catches `APIError.conflict` without binding its reason, so ANY 409
     * is nil. Pinned so nobody narrows this to the literal `clip_not_ready`
     * and turns a renamed backend code into an error banner mid-playback.
     */
    @Test fun `clipUrl returns null for any 409, whatever the reason says`() = runTest {
        val api = api("""{"detail":"some_other_code"}""", statuses = listOf(HttpStatusCode.Conflict))
        assertNull(api.clipUrl(EVENT))
    }

    /**
     * The distinction this endpoint exists to preserve: 409 is "no video yet",
     * 404 is "no such event". Collapsing them would tell a caregiver that a
     * broken deep link is a missing clip.
     */
    @Test fun `clipUrl still throws on 404`() = runTest {
        val api = api("""{"detail":"event_not_found"}""", statuses = listOf(HttpStatusCode.NotFound))
        assertEquals(ApiError.NotFound, apiErrorFrom { api.clipUrl(EVENT) })
    }

    @Test fun `clipUrl still throws on a server error`() = runTest {
        val api = api("""{"detail":"storage down"}""", statuses = listOf(HttpStatusCode.BadGateway))
        assertEquals(
            ApiError.ServerError(code = null, serverMessage = "storage down"),
            apiErrorFrom { api.clipUrl(EVENT) },
        )
    }

    // =====================================================================
    // updateEvent — the body has to be exact (pydantic extra="forbid")
    // =====================================================================

    @Test fun `updateEvent PATCHes only the fields that were set`() = runTest {
        api(eventJson()).updateEvent(EVENT, UpdateEventRequest(opened = true))

        assertEquals(HttpMethod.Patch, sent.single().method)
        assertEquals("/v1/events/$EVENT", sent.single().url.encodedPath)
        // Not `{"opened":true,"reviewed":null,...}`: an explicit null would
        // clear the column, and the tap-to-open path only means "opened".
        assertEquals(expected("""{"opened":true}"""), requestBody())
    }

    @Test fun `updateEvent sends the review fields under their wire names`() = runTest {
        api(eventJson(reviewed = true)).updateEvent(
            EVENT,
            UpdateEventRequest(
                reviewed = true,
                reviewChoice = DetectionEvent.ReviewChoice.CORRECT,
                reviewNotes = "She did fall.",
            ),
        )

        assertEquals(
            expected(
                """{"reviewed":true,"review_choice":"correct","review_notes":"She did fall."}""",
            ),
            requestBody(),
        )
    }

    @Test fun `an empty update sends an empty object, not nulls`() = runTest {
        api(eventJson()).updateEvent(EVENT, UpdateEventRequest())
        assertEquals(expected("{}"), requestBody())
    }

    /**
     * The response is the row to keep, not the request echoed back: the server
     * stamps `reviewed_at` when `reviewed` first flips true, so the fields the
     * caller has to store are only in the response.
     */
    @Test fun `updateEvent returns the server's row, including server-stamped fields`() = runTest {
        val body = eventJson(reviewed = true).replace(
            """"reviewed_at": null""",
            """"reviewed_at": "2026-07-30T19:00:00.000000+00:00"""",
        )
        val updated = api(body).updateEvent(EVENT, UpdateEventRequest(reviewed = true))

        assertTrue(updated.reviewed)
        val stamped = java.time.OffsetDateTime.parse("2026-07-30T19:00:00+00:00").toInstant()
        assertEquals(stamped.epochSecond, updated.reviewedAt?.epochSeconds)
    }
}
