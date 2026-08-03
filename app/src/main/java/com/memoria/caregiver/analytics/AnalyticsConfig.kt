package com.memoria.caregiver.analytics

/**
 * Configuration and master kill switch for UI analytics. Port of
 * `Services/Analytics.swift`'s `AnalyticsConfig`.
 *
 * Set [ENABLED] to `false` to switch the whole system off with one line: nothing
 * is buffered, nothing is written to disk, nothing is uploaded. That is the
 * whole point of it being one flag in one place — under DPDP we may be asked to
 * stop collecting on short notice, and "one line, then ship" is an answer we can
 * give the same day.
 */
object AnalyticsConfig {

    /** Master on/off. When `false`, every log call and the flush worker do nothing. */
    const val ENABLED = true

    /** How often the background worker drains the buffer to Supabase — hourly. */
    const val FLUSH_INTERVAL_MS = 3_600_000L

    /** Write the buffer to disk after this many new events, so a crash loses at most 24. */
    const val PERSIST_EVERY_N = 25

    /**
     * Hard cap on the buffer, in memory and on disk. Oldest events are dropped
     * past this, so a caregiver who is offline for a week cannot grow the buffer
     * without bound.
     */
    const val MAX_BUFFERED_EVENTS = 5_000

    /** Rows per insert request when flushing. */
    const val BATCH_SIZE = 200

    /** The Supabase table. Its columns are mirrored field-for-field by [UiEvent]. */
    const val TABLE = "ui_events"
}
