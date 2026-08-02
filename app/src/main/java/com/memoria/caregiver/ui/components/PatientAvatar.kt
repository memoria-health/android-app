package com.memoria.caregiver.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/**
 * Key that decides when the photo URL is re-resolved.
 *
 * Carried from PatientAvatar.swift: "Re-runs the task whenever the patient
 * identity OR the storage key changes — covers both 'switched to a different
 * patient' and 'same patient just uploaded a new photo'."
 *
 * The `|` separator is only an equality token, never parsed. iOS builds it from
 * `patient.id.uuidString`, which cannot contain `|`, so no id/key pair can
 * collide with another.
 */
internal fun patientAvatarRefreshKey(patientId: String, photoStorageKey: String?): String =
    "$patientId|${photoStorageKey ?: ""}"

/**
 * The photo lookup, extracted from the composable so it is testable without a
 * composition.
 *
 * Two behaviors from the Swift, both load-bearing:
 *
 * 1. **No storage key means no request.** `guard patient.photoStorageKey != nil
 *    else { return }` — a patient with no uploaded avatar never hits the
 *    network, and every avatar on the roster would otherwise mint a signed URL
 *    for nothing. The check is null-ness, not emptiness: an empty-but-present
 *    key still fetches, exactly as `!= nil` does.
 * 2. **Failure is silent.** `try? await env.api.patientPhotoURL(...)` swallows
 *    the error and leaves the URL nil, so a failed fetch falls through to the
 *    serif-initial placeholder in [NemaraAvatar] rather than surfacing an error.
 *
 * **Divergence from `try?`:** [CancellationException] is rethrown. Swift's `try?`
 * would absorb a cancellation too, but on Android swallowing it breaks
 * structured concurrency — a cancelled `LaunchedEffect` whose body keeps running
 * to completion writes state into a composition that is going away. The
 * observable result is the same in both cases, because a cancelled task's
 * assignment is discarded either way.
 */
internal suspend fun resolvePatientPhotoUrl(
    patientId: String,
    photoStorageKey: String?,
    resolve: suspend (String) -> String?,
): String? {
    if (photoStorageKey == null) return null
    return try {
        resolve(patientId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        // Matches `try?`: any thrown error resolves to "no photo".
        null
    }
}

/**
 * [NemaraAvatar] wrapper that resolves a patient's signed photo URL on appear
 * and re-resolves when the patient's `photoStorageKey` flips.
 *
 * Centralizes the "fetch the URL once when the avatar appears" logic so every
 * call site (roster row, patient header, settings header, event-detail nav) gets
 * photo support automatically. Failure / loading / no-photo all fall through to
 * the serif-initial placeholder built into [NemaraAvatar].
 *
 * **Why the parameters are patient *fields* rather than a `Patient`:** the model
 * layer and the API client are Phase 2; this is Phase 1's design system. iOS
 * reads exactly three things off the patient — `avatarLetter`, `id`, and
 * `photoStorageKey` — and one method off the environment's API client, so those
 * four are the parameters. This is the same seam as `NemaraWaveform`, which
 * takes levels and does not source them. When `Patient` lands, a one-line
 * overload can restore the iOS call shape; nothing here needs to change.
 *
 * Takes a `modifier` even though the Swift initialiser has no such parameter,
 * because SwiftUI gives every view an implicit external modifier chain and this
 * one is used: PatientRow.swift:41-42 is
 * `PatientAvatar(patient:size:48).opacity(isPaused ? 0.55 : 1)`. Without it the
 * paused-patient row could not be ported at all. This is the Compose equivalent
 * of a capability iOS has for free, not an added feature.
 */
@Composable
fun PatientAvatar(
    patientId: String,
    avatarLetter: String,
    photoStorageKey: String?,
    resolvePhotoUrl: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    ring: Color? = null,
) {
    var photoUrl by remember { mutableStateOf<String?>(null) }

    // Keyed on the refresh key alone, mirroring `.task(id: refreshKey)`. In
    // particular NOT keyed on `resolvePhotoUrl`: on iOS the API client comes
    // from the environment and is stable for the app's lifetime, so a caller
    // that passes a fresh lambda each recomposition must not cause a refetch.
    LaunchedEffect(patientAvatarRefreshKey(patientId, photoStorageKey)) {
        // Cleared first, like the Swift, so a stale photo never shows against a
        // newly-selected patient while the new URL is in flight.
        photoUrl = null
        photoUrl = resolvePatientPhotoUrl(patientId, photoStorageKey, resolvePhotoUrl)
    }

    NemaraAvatar(
        letter = avatarLetter,
        modifier = modifier,
        size = size,
        ring = ring,
        photoUrl = photoUrl,
    )
}
