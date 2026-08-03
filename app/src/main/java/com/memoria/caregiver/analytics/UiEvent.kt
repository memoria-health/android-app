package com.memoria.caregiver.analytics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One buffered analytics row. Port of `Analytics.Event` in `Analytics.swift`.
 *
 * ## This type is a privacy boundary, and that is its main job
 *
 * These twelve fields are the ENTIRE set of things this app is allowed to send
 * about what a caregiver did. The app watches people with dementia in their own
 * homes; a patient name, an event narrative, a photo URL, an email address, a
 * pairing code or a recovery code must never travel with an interaction event,
 * and the way that is guaranteed is that there is nowhere here to put one.
 *
 * The only identifier is [caregiverId], a UUID. It is required, not incidental:
 * the `ui_events` row-level-security policy is `caregiver_id = auth.uid()`, so
 * an unstamped or wrongly-stamped row is rejected by the database.
 *
 * **Adding a field to this class is a privacy decision, not a schema decision.**
 * `UiEventSchemaTest` pins the exact JSON key set so a new field cannot be added
 * quietly.
 *
 * Field names map to the `ui_events` columns one-for-one, so a row is inserted
 * by serialising this object directly. `received_at` is deliberately absent —
 * the database defaults it to server time, which is what lets a device with a
 * skewed clock be spotted by comparing it against [occurredAt].
 */
@Serializable
data class UiEvent(
    /**
     * Client-generated UUID. It is the conflict key on upsert, which is what
     * makes a retry after a dropped response harmless instead of a duplicate.
     */
    val id: String,

    /**
     * The caregiver this event belongs to, or null when it happened before
     * sign-in. Null-stamped events are never uploaded — see `BufferedAnalytics.flush`.
     */
    @SerialName("caregiver_id") val caregiverId: String?,

    /** One id per app launch; groups a session's events. */
    @SerialName("session_id") val sessionId: String,

    /** Monotonic per-session counter. Gives exact ordering when two timestamps tie. */
    val seq: Long,

    /** `button` | `screen` | `toggle` | `lifecycle`. */
    @SerialName("event_type") val eventType: String,

    /**
     * The fixed label for the thing that happened: a button's track id or title,
     * a screen name, or a lifecycle name such as `app_background`. Never a value
     * a caregiver typed and never a name of a person.
     */
    val name: String,

    /** The screen the event happened on. Null before the first screen is logged. */
    val screen: String?,

    /** Client clock, ISO-8601 with millisecond precision. */
    @SerialName("occurred_at") val occurredAt: String,

    @SerialName("app_build") val appBuild: String?,

    @SerialName("os_version") val osVersion: String?,

    /**
     * The device's marketing model name, e.g. "Pixel 8" — the closest Android
     * equivalent of the hardware identifier iOS sends. A device property, not a
     * person: it says what hardware the app is running on and nothing about who
     * is holding it.
     */
    @SerialName("device_model") val deviceModel: String?,

    /**
     * Extra fixed key/value pairs. In practice this only ever carries a toggle's
     * new position, `{"value": "on"}` or `{"value": "off"}` — see
     * `BufferedAnalytics.logToggle`, which is the only caller that passes one.
     */
    val metadata: Map<String, String>?,
)
