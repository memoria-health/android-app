package com.memoria.caregiver.push

/**
 * The two ids a push carries, and nothing else.
 *
 * They are the whole routing target: `DeepLinkRouter.bufferEventTap` takes
 * exactly this pair, and the patient shell and events screen do the rest.
 *
 * Both are payload-root strings on iOS (`aps` is a sibling, not a parent) and
 * data-map entries on FCM, which is the same position — see
 * `nemara-backend/src/services/push.py:build_event_payload`.
 */
data class EventTap(
    val eventId: String,
    val patientId: String,
)

/**
 * A push we can act on: a routing target plus whatever text the server wants
 * shown.
 *
 * ## The text is the server's, verbatim, and is never derived here
 *
 * [title] and [body] are carried through untouched and are never built from
 * anything this app knows. That is deliberate and it is a privacy rule, not a
 * style one: this device holds the patient's name, their photo URL and their
 * event narratives, and a notification is read off a lock screen by whoever is
 * holding the phone. If the app ever looked any of that up to make a nicer
 * notification, it would be the port that introduced the leak.
 *
 * What the server currently sends is a separate matter and is recorded in
 * FINDINGS.md, not fixed here.
 *
 * They are nullable because a payload can legitimately arrive without them (a
 * data-only message), and because a push that we cannot label is still a push we
 * can route — see [com.memoria.caregiver.push.EventNotifications] for the
 * fallback wording.
 */
data class EventPush(
    val tap: EventTap,
    val title: String?,
    val body: String?,
)

/** Payload key for the event id. Same string on APNs, FCM and our Intent extras. */
const val PUSH_KEY_EVENT_ID: String = "event_id"

/** Payload key for the patient id. */
const val PUSH_KEY_PATIENT_ID: String = "patient_id"

/**
 * Canonical 8-4-4-4-12 hex form.
 *
 * `java.util.UUID.fromString` is NOT used, and the difference matters. It is
 * lenient to the point of uselessness — it accepts `"1-2-3-4-5"` and silently
 * zero-pads it — so a malformed id would parse into a real-looking UUID that
 * matches no row, and the caregiver would tap an alert and land on an empty
 * screen with nothing logged. iOS's `UUID(uuidString:)` rejects those, so
 * rejecting them here is also what keeps the two platforms behaving alike.
 */
private val UUID_SHAPE =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Reads a routable push out of a message, or returns null.
 *
 * Null means "nothing to route": either id missing or malformed. iOS drops the
 * tap in exactly the same case (`guard let eventId, let patientId`), because
 * half a target is not a target — opening the patient without the event would
 * take the caregiver somewhere they did not ask to go.
 *
 * A pure function of its arguments so the whole parse can be tested without
 * Firebase, an Activity or a device.
 *
 * @param data the message's data map (FCM) or payload root (APNs).
 * @param title server-supplied notification title, if the message had one.
 * @param body server-supplied notification body, if the message had one.
 */
fun eventPushFrom(
    data: Map<String, String>,
    title: String? = null,
    body: String? = null,
): EventPush? {
    val eventId = data[PUSH_KEY_EVENT_ID]?.takeIf { UUID_SHAPE.matches(it) } ?: return null
    val patientId = data[PUSH_KEY_PATIENT_ID]?.takeIf { UUID_SHAPE.matches(it) } ?: return null
    return EventPush(
        tap = EventTap(eventId = eventId, patientId = patientId),
        // Blank is treated as absent. A server that sends `""` means "no title",
        // and a notification with an empty title row looks broken.
        title = title?.takeIf { it.isNotBlank() } ?: data["title"]?.takeIf { it.isNotBlank() },
        body = body?.takeIf { it.isNotBlank() } ?: data["body"]?.takeIf { it.isNotBlank() },
    )
}
