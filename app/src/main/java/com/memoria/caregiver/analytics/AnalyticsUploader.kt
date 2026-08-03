package com.memoria.caregiver.analytics

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from

/**
 * Sends a batch of events. Behind an interface so the buffer, the retry
 * behaviour and the batching can all be tested without a network or a Supabase
 * client.
 *
 * Throwing means "this batch did not land". The caller keeps the events and
 * tries again later; it never surfaces the failure to the caregiver, because a
 * failed analytics upload is not something they did or can fix.
 */
interface AnalyticsUploader {
    suspend fun upload(events: List<UiEvent>)
}

/**
 * Inserts straight into the `ui_events` table with the caregiver's own Supabase
 * session. There is no backend endpoint for this on purpose: the table's
 * row-level-security policy is `caregiver_id = auth.uid()`, so a caregiver
 * physically cannot write a row attributed to anyone else, which is the property
 * that makes a direct client insert safe.
 *
 * Upsert rather than insert, keyed on the client-generated `id`: if a response
 * is dropped after the rows landed, the retry is ignored instead of failing the
 * batch forever on a primary-key collision. `ignoreDuplicates` is what makes it
 * "leave the existing row alone" rather than "overwrite it".
 */
class SupabaseAnalyticsUploader(private val client: SupabaseClient) : AnalyticsUploader {

    override suspend fun upload(events: List<UiEvent>) {
        client.from(AnalyticsConfig.TABLE).upsert(events) {
            onConflict = "id"
            ignoreDuplicates = true
        }
    }
}
