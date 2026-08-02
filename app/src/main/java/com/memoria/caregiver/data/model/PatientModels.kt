@file:OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)

package com.memoria.caregiver.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import com.memoria.caregiver.data.remote.NemaraDateOfBirthSerializer
import com.memoria.caregiver.data.remote.NemaraInstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.text.BreakIterator
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// ---------------------------------------------------------------------------
// Wire dates
// ---------------------------------------------------------------------------

/**
 * The date chain from `PARITY.md §6`, ported from `NemaraJSON` in
 * `Networking/JSONCoders.swift`.
 *
 * Pydantic v2 emits `datetime` columns as ISO-8601 with **6-digit microseconds**
 * (`2026-05-21T22:18:00.182734+00:00`) and `date` columns as `YYYY-MM-DD`. iOS
 * tries four patterns in a fixed order because `ISO8601DateFormatter` only
 * handles 3 fractional digits. The same four, in the same order, with a fixed
 * UTC zone and a fixed `en_US_POSIX`-equivalent locale, are reproduced here.
 *
 * Encoding always uses pattern 1, so `decode(encode(value))` is byte-stable —
 * the same reason the Swift picks the microsecond formatter as its encode target.
 *
 * **Parsing and formatting use separate `DateTimeFormatter` instances on
 * purpose.** `withZone(...)` overrides the offset carried by the parsed text, so
 * a formatter configured for output would silently reinterpret a
 * `+05:30` timestamp as UTC. The parsers therefore carry no zone and read the
 * offset out of the string, exactly as `DateFormatter` with `XXXXX` does.
 *
 * Scoped to this file's models rather than shared, because the port's shared
 * JSON layer is a separate unit; when it lands, these serializers should be
 * replaced by the shared ones rather than duplicated.
 */
// The date wire format lives in ONE place: data/remote/JsonCoders.kt.
//
// This file previously carried its own copy (PatientModelsWire, with
// Iso8601Instant and DateOnlyInstant). It was written in parallel with
// JsonCoders.kt and drifted: its patterns required EXACTLY 6 or EXACTLY 3
// fraction digits and an extended-form offset, so it rejected five wire shapes
// Foundation accepts, and it re-encoded microseconds that iOS truncates. Two
// codecs for one wire contract is one too many — deleted, not fixed.


// ---------------------------------------------------------------------------
// Patient
// ---------------------------------------------------------------------------

/**
 * A patient under care. Mirrors `nemara-backend/src/schemas/patient.py`
 * `PatientResponse`.
 *
 * `state`, `statusLine`, `lastEventAt`, `camerasPaused` are server-managed. Wall
 * phones / backend services own these. The app never PATCHes them directly
 * except via `cameras_paused` (the explicit Monitoring toggle).
 *
 * **Nullable-with-default is not defensive noise.** Every property that is
 * nullable here is a Swift `Optional`, which tolerates both an absent key and an
 * explicit `null`; every property that is not nullable is a required Swift
 * property whose absence throws. Supabase Realtime ships the raw DB row while
 * REST ships the API schema, and this one class parses both, so widening or
 * narrowing any of these changes which payloads decode.
 */
@Serializable
data class Patient(
    val id: String,
    @SerialName("caregiver_id") val caregiverId: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("preferred_name") val preferredName: String,
    @SerialName("date_of_birth")
    @Serializable(with = NemaraInstantSerializer::class)
    val dateOfBirth: Instant? = null,
    val gender: String? = null,
    val relation: Relation,
    @SerialName("home_address") val homeAddress: String? = null,
    /**
     * Country this home is in (ISO alpha-2), picked at onboarding. Optional for
     * decode resilience; backend always sends it.
     */
    val country: String? = null,
    /**
     * Data-residency region for this patient's data, DERIVED from [country].
     * Optional for decode resilience (the app doesn't switch on it yet).
     */
    val region: Region? = null,
    val state: State,
    @SerialName("status_line") val statusLine: String,
    @SerialName("last_event_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val lastEventAt: Instant? = null,
    @SerialName("cameras_paused") val camerasPaused: Boolean,
    /**
     * Facilities audio kill: false = the patient's cameras never publish an
     * audio track (no live sound, no audio in clips, no speech alerts).
     * Optional for decode resilience; **treat null as true** at the call site.
     */
    @SerialName("audio_capture_enabled") val audioCaptureEnabled: Boolean? = null,
    /**
     * Per-patient master toggle for event-driven email alerts. When on, the
     * backend fans alerts to the owning caregiver's account email plus every
     * address in [emailAlertRecipients].
     */
    @SerialName("email_alerts_enabled") val emailAlertsEnabled: Boolean,
    /**
     * Additional email addresses (beyond the caregiver's account email) to copy
     * on this patient's alerts. Server replaces the full array on every PATCH,
     * so clients send the whole list.
     */
    @SerialName("email_alert_recipients") val emailAlertRecipients: List<String>,
    /**
     * Per-patient opt-in for contributing short video/audio clips to Memoria's
     * research dataset (only captured when someone is present). Default false;
     * flipped by the first-open consent sheet and the Settings card.
     */
    @SerialName("data_collection_enabled") val dataCollectionEnabled: Boolean,
    /**
     * When the caregiver first made a data-collection decision (Share or Not
     * now). Null means never asked, so show the first-open consent sheet.
     * Non-null means decided, so never auto-prompt again. Server-stamped on any
     * PATCH that carries `data_collection_enabled`; the client never sends it.
     */
    @SerialName("data_collection_decided_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val dataCollectionDecidedAt: Instant? = null,
    /**
     * RETIRED patient-level privacy flag, superseded by per-camera privacy; no
     * UI reads or writes it anymore. Optional for decode resilience: the backend
     * is dropping the column, and a required Boolean would fail every patient
     * decode the moment it disappears from the payload.
     */
    @SerialName("privacy_mode_enabled") val privacyModeEnabled: Boolean? = null,
    /**
     * Bucket-relative key in patient-photos (e.g. `{id}.jpg`) when the caregiver
     * has uploaded an avatar. Null falls back to the initials placeholder. The
     * signed URL is fetched separately via the photo-url endpoint.
     */
    @SerialName("photo_storage_key") val photoStorageKey: String? = null,
    @SerialName("created_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val createdAt: Instant,
    @SerialName("updated_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val updatedAt: Instant,
) {
    /** Aggregated patient status surfaced on the roster row. */
    @Serializable
    enum class State {
        @SerialName("calm")
        CALM,

        @SerialName("critical")
        CRITICAL,

        @SerialName("offline")
        OFFLINE,
    }

    /**
     * Patient's relation to the caregiver. Backend stores these as a
     * `relation_kind` Postgres enum; raw values match the wire.
     */
    @Serializable
    enum class Relation(val rawValue: String) {
        @SerialName("parent")
        PARENT("parent"),

        @SerialName("spouse")
        SPOUSE("spouse"),

        @SerialName("grandparent")
        GRANDPARENT("grandparent"),

        @SerialName("sibling")
        SIBLING("sibling"),

        @SerialName("client")
        CLIENT("client"),

        @SerialName("other")
        OTHER("other"),
        ;

        /** Capitalized text used in UI rows (e.g. "Parent"). */
        val displayName: String
            get() = rawValue.substring(0, 1).uppercase(Locale.ROOT) + rawValue.substring(1)
    }

    /**
     * Data-residency region for this patient's data. The onboarding country
     * picker maps to these; the backend routes storage + liftability off it. Set
     * at creation, immutable after. Raw values match the wire (`US` / `IN`).
     */
    @Serializable
    enum class Region {
        @SerialName("US")
        US,

        @SerialName("IN")
        INDIA,
        ;

        /** Country label shown in the onboarding picker. */
        val displayName: String
            get() = when (this) {
                US -> "United States"
                INDIA -> "India"
            }
    }

    /**
     * First letter for the avatar tile while we don't have real photos.
     *
     * Swift is `String(preferredName.first ?? fullName.first ?? "?")`, and
     * `first` on a Swift String yields one **grapheme cluster**. Kotlin's
     * `first()` yields one `Char`, which would hand back half a surrogate pair
     * for an emoji and would drop the combining mark off a decomposed accented
     * letter. `BreakIterator` gives the Swift boundary — the same mechanism the
     * design system already uses for the rendered initial.
     */
    val avatarLetter: String
        get() {
            val source = if (preferredName.isNotEmpty()) preferredName else fullName
            if (source.isEmpty()) return "?"
            val boundaries = BreakIterator.getCharacterInstance()
            boundaries.setText(source)
            val end = boundaries.following(0)
            return if (end == BreakIterator.DONE) source else source.substring(0, end)
        }

    /**
     * Age computed from [dateOfBirth]. Years only; 0 when DOB is unknown.
     *
     * Swift is `Calendar.current.dateComponents([.year], from: dob, to: Date())`,
     * so the instant is resolved in the **device's** time zone, not UTC, and a
     * future DOB yields a negative count. Both carried.
     */
    val age: Int
        get() {
            val dob = dateOfBirth ?: return 0
            val zone = ZoneId.systemDefault()
            val born = java.time.Instant
                .ofEpochSecond(dob.epochSeconds, dob.nanosecondsOfSecond.toLong())
                .atZone(zone)
                .toLocalDate()
            return Period.between(born, LocalDate.now(zone)).years
        }
}

/**
 * Body of `POST /v1/patients`. Server-managed state/status fields are not
 * settable at creation.
 *
 * The backend uses `extra="forbid"` on request schemas, so an unknown field is a
 * 422 rather than a warning: this must encode exactly the keys the Swift
 * `encode(to:)` writes, and no others. `@EncodeDefault(NEVER)` reproduces
 * `encodeIfPresent` — the key is omitted when the value is null, independently
 * of whatever `encodeDefaults` the shared `Json` instance is configured with.
 */
@Serializable
data class CreatePatientRequest(
    @SerialName("full_name") val fullName: String,
    @SerialName("preferred_name") val preferredName: String,
    val relation: Patient.Relation,
    @SerialName("date_of_birth")
    @Serializable(with = NemaraDateOfBirthSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dateOfBirth: Instant? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val gender: String? = null,
    @SerialName("home_address")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val homeAddress: String? = null,
    /**
     * Country this home is in (ISO alpha-2). Set at onboarding; the residency
     * region is derived from it server-side. Immutable after creation.
     */
    val country: String,
)

/** Body of `PATCH /v1/patients/{id}`. Null = field omitted. */
@Serializable
data class UpdatePatientRequest(
    @SerialName("full_name")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fullName: String? = null,
    @SerialName("preferred_name")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val preferredName: String? = null,
    @SerialName("date_of_birth")
    @Serializable(with = NemaraDateOfBirthSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dateOfBirth: Instant? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val gender: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val relation: Patient.Relation? = null,
    @SerialName("home_address")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val homeAddress: String? = null,
    @SerialName("cameras_paused")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val camerasPaused: Boolean? = null,
    @SerialName("audio_capture_enabled")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val audioCaptureEnabled: Boolean? = null,
    @SerialName("email_alerts_enabled")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val emailAlertsEnabled: Boolean? = null,
    /** Send the full array. The backend replaces the column wholesale. */
    @SerialName("email_alert_recipients")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val emailAlertRecipients: List<String>? = null,
    /**
     * Data-collection opt-in. The backend stamps `data_collection_decided_at`
     * server-side whenever this field is present (true OR false records a
     * decision), so the client never sends the timestamp itself.
     */
    @SerialName("data_collection_enabled")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dataCollectionEnabled: Boolean? = null,
)

/**
 * Body returned by `POST /v1/patients/{id}/test-email`. Echoes a human-readable
 * status the UI shows next to the Send test button.
 */
@Serializable
data class TestEmailResponse(
    val status: String,
)

// ---------------------------------------------------------------------------
// Caregiver
// ---------------------------------------------------------------------------

/**
 * The authenticated user of the caregiver app. Mirrors
 * `nemara-backend/src/schemas/caregiver.py` `CaregiverResponse`.
 */
@Serializable
data class Caregiver(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val email: String,
    val phone: String? = null,
    @SerialName("created_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val createdAt: Instant,
    @SerialName("updated_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val updatedAt: Instant,
    /**
     * Terms of Service + Privacy acceptance (mirrors the backend). Null until
     * the caregiver accepts; the app gates account creation on acceptance, so a
     * freshly signed-up account is already stamped.
     */
    @SerialName("tos_accepted_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val tosAcceptedAt: Instant? = null,
    @SerialName("tos_version") val tosVersion: String? = null,
    /**
     * Country picked at signup (ISO alpha-2) + the residency region derived from
     * it. Optional for decode resilience; backend always sends them.
     */
    val country: String? = null,
    val region: String? = null,
)

/** Body of `POST /v1/me`. Idempotent — email is taken from the JWT. */
@Serializable
data class CreateCaregiverRequest(
    @SerialName("display_name") val displayName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val phone: String? = null,
    /**
     * ToS/Privacy version the caregiver accepted at signup. The app gates
     * account creation on acceptance, so this is set and provisioning stamps
     * `tos_accepted_at` server-side. Omitted when null.
     */
    @SerialName("tos_version")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tosVersion: String? = null,
    /**
     * Country picked at signup (ISO alpha-2). The residency region is derived
     * from it server-side. Omitted when null (backend defaults to 'US').
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val country: String? = null,
)

/**
 * Body of `PATCH /v1/me`. Null fields are omitted (backend treats omitted as
 * "leave unchanged"); to clear a column, send an explicit empty payload via a
 * different code path.
 */
@Serializable
data class UpdateCaregiverRequest(
    @SerialName("display_name")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val displayName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val phone: String? = null,
)

// ---------------------------------------------------------------------------
// Photo URL
// ---------------------------------------------------------------------------

/**
 * Decoded shape of `GET /v1/patients/{id}/photo-url`. Mirrors
 * `nemara-backend/src/schemas/patient.py` `PhotoURLResponse`.
 *
 * The URL is short-lived (default 10 min); the app fetches a fresh one each time
 * a view appears that needs the avatar.
 */
@Serializable
data class PhotoURLResponse(
    val url: String,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int,
)
