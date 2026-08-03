package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.ClipURLResponse
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.EventFilter
import com.memoria.caregiver.data.model.EventListResponse
import com.memoria.caregiver.data.model.UpdateEventRequest

/**
 * The events endpoints. Port of the `// MARK: - Events` block of
 * `Services/NemaraAPIClient.swift`.
 *
 * Four endpoints (PARITY.md §10): `GET /v1/patients/{id}/events`,
 * `GET /v1/events/{id}`, `GET /v1/events/{id}/clip-url`,
 * `PATCH /v1/events/{id}`.
 *
 * **This class holds no state.** The Swift writes straight into
 * `eventsByPatient` — the first page replaces the cached list, `updateEvent`
 * patches the matching row in place. Here every call just returns what the
 * server said and the caller owns the merge. The paging contract that merge
 * has to satisfy is written out above [loadEventsPage], in full, because
 * cursor paging is the one place where getting the merge wrong looks like
 * working code — a duplicated or silently short feed — rather than a crash.
 *
 * Ids are `String` here and `UUID` in Swift, matching the rest of the model
 * layer. The Swift lowercases `uuidString` before interpolating because
 * Foundation renders a UUID in upper case; the ids we hold already came off
 * the wire in the backend's lower case, and FastAPI's UUID path converter is
 * case-insensitive either way, so there is nothing to normalise.
 */
internal class EventsApi(private val http: HttpTransport) {

    /**
     * The first page for a patient, newest first.
     *
     * Swift returns `Void` and assigns `eventsByPatient[pid] = page.items`.
     * The page is returned instead; **the caller REPLACES its cached list for
     * this patient with `items`** — this is the "start over" call, used by
     * bootstrap, pull-to-refresh, the foreground resync and the 20-second
     * Events-tab poll, and it must not append.
     *
     * Default filter is `all`, as on iOS. The filter chips in the Events tab
     * narrow the already-loaded list client-side; they do not re-request.
     */
    suspend fun loadEventsFirstPage(
        patientId: String,
        filter: EventFilter = EventFilter.ALL,
    ): EventListResponse = loadEventsPage(patientId, filter, cursor = null)

    // -----------------------------------------------------------------------
    // The paging contract
    //
    // `GET /v1/patients/{id}/events` is CURSOR paged, not offset paged.
    //
    // Request. `filter` is always sent (`all` / `unreviewed` / `reviewed`).
    // `cursor` is sent only when the caller has one. `limit` is never sent, by
    // either platform, so the server default of 30 ROWS PER PAGE applies (the
    // server itself accepts 1..100).
    //
    // First page vs next page. The only difference is the cursor. No cursor
    // means "start at the newest row"; a cursor means "continue strictly after
    // this position". There is no page number and no offset, so pages cannot
    // drift or repeat rows when new events land mid-scroll — which is why the
    // backend chose a cursor here in the first place.
    //
    // Ordering. The server sorts occurred_at DESC, then id DESC as the
    // tie-break, so events sharing a timestamp still have one definite order.
    // NEITHER PLATFORM RE-SORTS. Keep `items` in the order it arrived and
    // append pages in the order they were fetched; the concatenation is then
    // the same descending sequence the server would have returned as one long
    // list. Realtime INSERTs go in at index 0 for the same reason
    // (PARITY.md §10).
    //
    // The cursor is opaque: base64 of the (occurred_at, id) of the last row on
    // the page. Pass `next_cursor` back VERBATIM. Do not parse it, do not
    // re-encode it, do not build one.
    //
    // End of stream. `next_cursor` is null (or absent) on the last page. That
    // is the ONLY end signal — treat it as authoritative and stop. Do not
    // infer "there is more" from a full-looking page of 30, and do not call
    // again with a null cursor hoping for more: that restarts at the newest
    // row and re-serves page 1, which as an append is a duplicated feed.
    //
    // The filter is part of the position. A cursor is only meaningful against
    // the filter it was issued under, because it names a spot in that
    // filtered, ordered stream. Changing the filter means starting a new run
    // with cursor = null; carrying the old cursor across skips rows, with no
    // error anywhere.
    //
    // The caller's merge, in one line: first page replaces, later pages
    // append, stop when nextCursor == null.
    // -----------------------------------------------------------------------

    /** One page of events. See the paging contract directly above. */
    suspend fun loadEventsPage(
        patientId: String,
        filter: EventFilter,
        cursor: String?,
    ): EventListResponse {
        // Swift builds the same two query items: `filter` always, `cursor`
        // only when non-null. An empty `cursor=` is NOT the same as omitting
        // it — the backend treats a present-but-unparseable cursor as "start
        // from the top", so sending one by accident would silently re-serve
        // page 1 forever.
        val query = buildMap {
            put("filter", filter.wire)
            if (cursor != null) put("cursor", cursor)
        }
        return http.get("/v1/patients/$patientId/events", query)
    }

    /**
     * One event by id. Caregiver-scoped on the backend, so an id belonging to
     * someone else, or one whose event has since been deleted, is a 404 →
     * [ApiError.NotFound].
     *
     * Used by the notification deep link when the target is not in the loaded
     * first page: a cold launch, or an old event tapped out of the
     * notification shade.
     */
    suspend fun fetchEvent(id: String): DetectionEvent = http.get("/v1/events/$id")

    /**
     * A short-lived signed URL for the event's archived clip, or null when the
     * clip is not ready yet.
     *
     * Port of `clipURL(forEvent:)`, including its one piece of control flow:
     * the backend answers 409 `clip_not_ready` while archival is still in
     * flight (roughly 25-45 seconds after the event fires), and iOS catches
     * that and returns nil so the detail view keeps showing the "processing"
     * placeholder and asks again in 3 seconds. **A 409 is an expected empty
     * result, not an error.**
     *
     * Swift catches `APIError.conflict` without binding the reason, so any 409
     * becomes nil whatever the detail string says. That is preserved rather
     * than narrowed to `clip_not_ready`: matching on the string would mean a
     * backend that renamed the code starts throwing at caregivers instead of
     * showing the placeholder.
     *
     * **Every other status still throws**, and 404 is the one that matters: a
     * missing or foreign event id is a real error and has to surface as one.
     * Folding it in here would render it as "this event has no video", which
     * is a different and untrue statement.
     *
     * The URL comes back as a `String`, where Swift decodes a `URL`. The model
     * types it as a string and the player takes a string, so nothing here
     * parses it — a malformed URL that Swift would have rejected at decode
     * time reaches the player on Android instead. It fails visibly either way.
     */
    suspend fun clipUrl(eventId: String): String? = try {
        val response: ClipURLResponse = http.get("/v1/events/$eventId/clip-url")
        response.url
    } catch (_: ApiError.Conflict) {
        null
    }

    /**
     * Marks an event opened / reviewed and returns the row the server wrote.
     *
     * Swift also patches the cached copy in `eventsByPatient` from the
     * response. The caller does that here: **replace the matching row with the
     * returned event**, rather than merging the request fields into the local
     * copy. The server stamps `reviewed_at` when `reviewed` flips false to
     * true, so the response carries a field the request never sent.
     *
     * Null fields in [UpdateEventRequest] are left out of the body entirely
     * (`explicitNulls = false` in [NemaraJson]), which is what makes the PATCH
     * mean "change only what I sent".
     */
    suspend fun updateEvent(id: String, body: UpdateEventRequest): DetectionEvent =
        http.patch("/v1/events/$id", body)
}
