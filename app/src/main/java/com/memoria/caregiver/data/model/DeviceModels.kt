@file:OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
@file:UseSerializers(NemaraInstantSerializer::class)

package com.memoria.caregiver.data.model

import com.memoria.caregiver.data.remote.NemaraInstantSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * Ports four iOS model files that describe the devices in a home and what they
 * saw:
 *
 *   Models/Camera.swift         → one wall device per room, plus pairing types
 *   Models/Detector.swift       → one configured detection, plus the door picker
 *   Models/DetectionEvent.swift → one fired detection, plus the events page
 *   Models/ClipURL.swift        → the short-lived signed clip URL
 *
 * Wire format is snake_case (PARITY.md §7), spelled out with @SerialName on
 * every name that differs from the Kotlin one.
 *
 * REQUEST bodies must be exact: the backend uses pydantic `extra="forbid"` on
 * request schemas, so one unknown key is a 422 rather than a warning
 * (PARITY.md §9 R5). RESPONSE decoding is lenient, driven by the shared
 * configuration in `NemaraJson.json`.
 *
 * Timestamps use the shared `NemaraInstantSerializer` through the
 * `@file:UseSerializers` line above, so every `Instant` field in this file
 * reads the four backend date shapes and writes the one iOS writes
 * (PARITY.md §6).
 *
 * Identifiers that Swift types as `UUID` are `String` here, matching the rest
 * of the model layer: kotlinx.serialization has no built-in java.util.UUID
 * serializer and the wire representation is a string either way. The cost is
 * that a malformed id decodes instead of throwing, which Swift would catch.
 */

// ---------------------------------------------------------------------------
// Shared decode helpers
// ---------------------------------------------------------------------------

/**
 * A boolean that reads `false` when the key is missing OR when the value is an
 * explicit `null`.
 *
 * Swift's `decodeIfPresent(Bool.self, …) ?? false` treats those two cases the
 * same. A Kotlin default alone only covers the missing key: an explicit `null`
 * would still throw, and for `clip_suppressed` / `clip_censored` that means
 * losing the whole event rather than one field (PARITY.md §7).
 */
private object BooleanOrFalseSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "com.memoria.caregiver.data.model.BooleanOrFalse",
            PrimitiveKind.BOOLEAN,
        )

    override fun deserialize(decoder: Decoder): Boolean =
        decoder.decodeSerializableValue(Boolean.serializer().nullable) ?: false

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

/**
 * Trims the characters Swift's `CharacterSet.whitespaces` trims: a horizontal
 * tab, plus anything Unicode calls a space separator.
 *
 * Kotlin's own `trim()` would also strip newlines, which Swift's set does not,
 * so a value ending in a newline would compare differently on the two platforms.
 */
private fun String.trimmingSwiftWhitespaces(): String =
    trim { ch -> ch == '\t' || Character.getType(ch) == Character.SPACE_SEPARATOR.toInt() }

/** Uppercases the first character, matching Swift's `prefix(1).uppercased() + dropFirst()`. */
private fun String.capitalizedFirst(): String =
    if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

private fun Instant.toJavaTime(): java.time.Instant =
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

private fun nowInstant(): Instant {
    val now = java.time.Instant.now()
    return Instant.fromEpochSeconds(now.epochSecond, now.nano.toLong())
}

// ===========================================================================
// Camera — mirrors Models/Camera.swift
// ===========================================================================

/**
 * A wall-mounted phone acting as a camera + microphone for one room.
 * Mirrors `nemara-backend/src/schemas/camera.py` `CameraResponse`.
 *
 * [id] is the persistent id assigned by the backend, used for DELETE and for
 * cross-table references. [displayId] is the 1-based integer rendered as
 * "CAM 01" in the UI.
 */
@Serializable(with = CameraSerializer::class)
data class Camera(
    /**
     * User-facing "off" toggle. When true the backend tick worker skips the
     * LLM call. The LiveKit connection stays alive, so re-enabling is instant.
     *
     * Independent of [desiredState]: a camera can be `disabled` AND
     * `desiredState = DISCONNECTED` at the same time.
     */
    val disabled: Boolean,
    /**
     * AI paused, lighter than [disabled]. The backend tick skips the Gemini
     * call but the live tile STAYS VISIBLE and video keeps streaming, so the
     * caregiver can watch a room without paying for AI calls.
     */
    val aiPaused: Boolean,
    val sourceType: SourceType = SourceType.PHONE,
    /** What the classifier thinks this room is. Null = not classified yet. */
    val roomClass: RoomClass? = null,
    /** Classifier confidence in [roomClass], 0-1. Null when unclassified. */
    val roomClassConfidence: Double? = null,
    /** When the classification last ran. Null when unclassified. */
    val roomClassifiedAt: Instant? = null,
    /**
     * Per-camera privacy, derived server-side from [roomClass] (bathroom or
     * bedroom becomes true, locked, with no user toggle). While true the
     * backend NEVER delivers live video or audio for this camera, and
     * sensitive event clips arrive blurred with audio stripped.
     */
    val privacyEnabled: Boolean = false,
    /**
     * Whether the wall phone should be joined to the LiveKit room. The phone
     * polls `GET /v1/cameras/me/desired-state` every second and reconciles, so
     * a flip back to CONNECTED takes about 1-2s.
     *
     * Stronger than [disabled]: when DISCONNECTED no video bytes are encoded,
     * uploaded, or seen by LiveKit Cloud at all.
     */
    val desiredState: DesiredState,
    val id: String,
    val patientId: String,
    val displayId: Int,
    val room: String,
    val iconName: String,
    val online: Boolean,
    /**
     * LiveKit participant identity for this camera (`cam_<8 hex>`). Null for
     * placeholder rows that hold an open pairing code but have not been claimed
     * by a wall phone yet. Also the canonical "this row is a real paired
     * camera" check, which is more reliable than [online].
     */
    val livekitIdentity: String? = null,
    val createdAt: Instant,
    /**
     * True once the wall phone has completed the pairing handshake. False means
     * the row exists with an open pairing code and no phone has claimed it yet,
     * which drives the "PAIRING" badge.
     */
    val paired: Boolean,
    /**
     * Whether the Memoria Sensor app is currently RUNNING on this camera's
     * iPhone. The phone polls the backend every second while the app is open,
     * and the backend flips this false when those polls stop.
     *
     * Null on older backends and on pre-migration realtime rows, and null is
     * treated as "running" so we never falsely say the app is closed. Only
     * meaningful for [SourceType.PHONE]; read it through [isAppClosed], which
     * encodes that guard.
     */
    val appPresent: Boolean? = null,
) {
    /**
     * Which physical device class this camera row represents. The detection
     * pipeline treats both the same, but the UI branches: phone cameras pair
     * with a 6-digit code, sensor cameras pair through the discovered list.
     */
    @Serializable(with = CameraSourceTypeSerializer::class)
    enum class SourceType(val wire: String) {
        /** Wall-mounted NemaraCam iPhone (the original path). */
        PHONE("iphone"),

        /**
         * Any non-iPhone published camera: a Raspberry Pi streaming daemon
         * ("pi") or a mini-PC gateway ("gateway"). They behave identically in
         * the UI, so both wire values decode into this case.
         */
        SENSOR("pi"),
    }

    /**
     * AI room classification for this camera's view. Drives the per-camera
     * privacy derivation on the backend: bathroom and bedroom rooms get
     * `privacy_enabled = true` automatically.
     */
    @Serializable(with = CameraRoomClassSerializer::class)
    enum class RoomClass(val wire: String) {
        BATHROOM("bathroom"),
        BEDROOM("bedroom"),
        OTHER("other"),
    }

    @Serializable
    enum class DesiredState(val wire: String) {
        @SerialName("connected")
        CONNECTED("connected"),

        @SerialName("disconnected")
        DISCONNECTED("disconnected"),
    }

    /**
     * True when the classifier put this camera in a room that is private by
     * policy. Drives the one-time sensitive-room explainer card on the Live tab.
     */
    val isSensitiveRoom: Boolean
        get() = roomClass == RoomClass.BATHROOM || roomClass == RoomClass.BEDROOM

    /**
     * True when this iPhone sensor's Memoria Sensor app is CLOSED: not online,
     * and the backend's heartbeat says the app stopped polling. In this state
     * the caregiver cannot reconnect from here, because the phone is not
     * running to receive the intent, so the UI must say "open Memoria Sensor"
     * rather than "Disconnected (tap Reconnect)". Never true for Pi sensors,
     * which have no app to close, and never while the camera is online.
     */
    val isAppClosed: Boolean
        get() = sourceType == SourceType.PHONE && !online && appPresent == false

    /**
     * "This camera SHOULD be rendered as a tile right now, subject to the
     * LiveKit SDK having (or being about to have) a video track for it."
     *
     * Deliberately does NOT read [online]. The SDK's track map is the source of
     * truth for "is the camera publishing right now", and the backend column
     * has lied before, hiding a tile whose publisher was fine.
     */
    val isLiveStreaming: Boolean
        get() = livekitIdentity != null && !disabled && desiredState == DesiredState.CONNECTED
}

/**
 * Lenient decode: only "iphone" is a phone. Every other backend `source_type`
 * ("pi", "gateway", and any future relay type) is a sensor-style camera. Stops
 * one unrecognized value, as "gateway" did, from failing the whole camera-list
 * decode.
 */
internal object CameraSourceTypeSerializer : KSerializer<Camera.SourceType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "com.memoria.caregiver.data.model.Camera.SourceType",
            PrimitiveKind.STRING,
        )

    override fun deserialize(decoder: Decoder): Camera.SourceType =
        if (decoder.decodeString() == "iphone") Camera.SourceType.PHONE else Camera.SourceType.SENSOR

    override fun serialize(encoder: Encoder, value: Camera.SourceType) {
        encoder.encodeString(value.wire)
    }
}

/**
 * Lenient decode: any unrecognized future class ("garage", "hallway", …) folds
 * into OTHER instead of failing the whole camera-list decode. Same defensive
 * pattern as [CameraSourceTypeSerializer].
 */
internal object CameraRoomClassSerializer : KSerializer<Camera.RoomClass> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "com.memoria.caregiver.data.model.Camera.RoomClass",
            PrimitiveKind.STRING,
        )

    override fun deserialize(decoder: Decoder): Camera.RoomClass {
        val raw = decoder.decodeString()
        return Camera.RoomClass.entries.firstOrNull { it.wire == raw } ?: Camera.RoomClass.OTHER
    }

    override fun serialize(encoder: Encoder, value: Camera.RoomClass) {
        encoder.encodeString(value.wire)
    }
}

/**
 * The literal wire shape of a camera row, which is the union of two payloads:
 * the REST response and the raw database row Supabase Realtime ships.
 *
 * REST sends `paired`. Realtime does not — it sends the credential columns
 * instead — so everything Realtime can leave out is optional here and
 * [CameraSerializer] supplies the defaults.
 */
@Serializable
private data class CameraWire(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    @SerialName("display_id") val displayId: Int,
    val room: String,
    @SerialName("icon_name") val iconName: String,
    val online: Boolean,
    val disabled: Boolean,
    @SerialName("ai_paused") val aiPaused: Boolean,
    @SerialName("desired_state") val desiredState: Camera.DesiredState,
    @SerialName("source_type")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sourceType: Camera.SourceType? = null,
    @SerialName("room_class")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roomClass: Camera.RoomClass? = null,
    @SerialName("room_class_confidence")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roomClassConfidence: Double? = null,
    @SerialName("room_classified_at")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roomClassifiedAt: Instant? = null,
    @SerialName("privacy_enabled")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val privacyEnabled: Boolean? = null,
    @SerialName("livekit_identity")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val livekitIdentity: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val paired: Boolean? = null,
    @SerialName("app_present")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val appPresent: Boolean? = null,
    // Realtime-payload-only. Read on decode to derive `paired`, then dropped:
    // they are read-only on the wire and are never sent back.
    @SerialName("api_key_hash")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apiKeyHash: String? = null,
    @SerialName("ingest_token_hash")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ingestTokenHash: String? = null,
)

/**
 * Hydrates a [Camera] from BOTH the REST response, where `paired` is present
 * and explicit, and a Supabase Realtime row, where it is absent and has to be
 * derived from the credential columns.
 *
 * Without the second path the realtime handler would silently fail to decode
 * every cameras-table change and the local cache would go stale across
 * pair and unpair events.
 */
internal object CameraSerializer : KSerializer<Camera> {
    override val descriptor: SerialDescriptor = CameraWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): Camera {
        val w = decoder.decodeSerializableValue(CameraWire.serializer())
        return Camera(
            disabled = w.disabled,
            aiPaused = w.aiPaused,
            // Default to PHONE for realtime payloads that predate the
            // source_type column. Without this, decoding older rows would fail
            // and take the realtime stream down with them.
            sourceType = w.sourceType ?: Camera.SourceType.PHONE,
            roomClass = w.roomClass,
            roomClassConfidence = w.roomClassConfidence,
            roomClassifiedAt = w.roomClassifiedAt,
            privacyEnabled = w.privacyEnabled ?: false,
            desiredState = w.desiredState,
            id = w.id,
            patientId = w.patientId,
            displayId = w.displayId,
            room = w.room,
            iconName = w.iconName,
            online = w.online,
            livekitIdentity = w.livekitIdentity,
            createdAt = w.createdAt,
            // Derived from the credential columns when the server did not send
            // `paired`. iPhone cameras carry their bearer hash on api_key_hash;
            // Pi cameras skip the pairing-code flow and store the equivalent on
            // ingest_token_hash. Checking only api_key_hash made every Pi camera
            // decode as unpaired from Realtime, so the UI flipped to "PAIRING"
            // on every update even though pairing completed at provision time.
            paired = w.paired ?: (w.apiKeyHash != null || w.ingestTokenHash != null),
            appPresent = w.appPresent,
        )
    }

    override fun serialize(encoder: Encoder, value: Camera) {
        encoder.encodeSerializableValue(
            CameraWire.serializer(),
            CameraWire(
                id = value.id,
                patientId = value.patientId,
                displayId = value.displayId,
                room = value.room,
                iconName = value.iconName,
                online = value.online,
                disabled = value.disabled,
                aiPaused = value.aiPaused,
                desiredState = value.desiredState,
                sourceType = value.sourceType,
                roomClass = value.roomClass,
                roomClassConfidence = value.roomClassConfidence,
                roomClassifiedAt = value.roomClassifiedAt,
                privacyEnabled = value.privacyEnabled,
                livekitIdentity = value.livekitIdentity,
                createdAt = value.createdAt,
                paired = value.paired,
                appPresent = value.appPresent,
            ),
        )
    }
}

/** Body for `POST /v1/patients/{id}/cameras/pairing-code`. */
@Serializable
data class CreatePairingCodeRequest(
    val room: String,
    @SerialName("icon_name") val iconName: String,
)

/** Response from the same. The app displays the code plus a countdown to expiry. */
@Serializable
data class CreatePairingCodeResponse(
    /** 6 digits, 100000-999999. */
    @SerialName("pairing_code") val pairingCode: String,
    @SerialName("expires_at") val expiresAt: Instant,
    @SerialName("camera_id") val cameraId: String,
)

/**
 * One sensor camera the backend has heard from but no caregiver has claimed
 * yet. Populates the "Sensor Camera" tab of the add-camera sheet. Mirrors
 * `nemara-backend/src/schemas/pi_discovery.py` `DiscoveredPi`.
 */
@Serializable
data class DiscoveredCamera(
    @SerialName("discovery_id") val discoveryId: String,
    @SerialName("hardware_id") val hardwareId: String,
    @SerialName("device_label") val deviceLabel: String,
    @SerialName("current_url") val currentUrl: String,
    @SerialName("current_audio_url") val currentAudioUrl: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: Instant,
    @SerialName("last_seen_at") val lastSeenAt: Instant,
) {
    val id: String get() = discoveryId
}

/** Top-level shape of `GET /v1/cameras/discovered`. The picker iterates [pis]. */
@Serializable
data class DiscoveredCamerasResponse(
    val pis: List<DiscoveredCamera>,
)

/**
 * Body for `POST /v1/patients/{id}/cameras/pi-provision`, discovery mode. Binds
 * an already-announced sensor camera to a patient and room. The direct-URL mode
 * is deliberately not surfaced in the app.
 */
@Serializable
data class ProvisionSensorCameraRequest(
    val room: String,
    @SerialName("icon_name") val iconName: String,
    @SerialName("discovery_id") val discoveryId: String,
)

/**
 * Response from `pi-provision`. The app uses [cameraId] to navigate to the new
 * camera's detail view after pairing.
 *
 * The backend's push refactor removed the stream and audio URLs from this
 * response: in push mode the Pi opens an outbound connection to the backend
 * rather than the backend pulling from a Pi-served URL, so those fields no
 * longer mean anything. The Pi's ingest bearer token arrives separately, on the
 * next `/v1/cameras/pi-announce` response.
 */
@Serializable
data class ProvisionSensorCameraResponse(
    @SerialName("camera_id") val cameraId: String,
    @SerialName("patient_id") val patientId: String,
    @SerialName("livekit_identity") val livekitIdentity: String,
    @SerialName("livekit_room") val livekitRoom: String,
)

/**
 * Body for `PATCH /v1/cameras/{id}`. Null fields are left out, which matches
 * the backend's reject-unknown-fields policy: sending a field commits us to
 * changing it.
 */
@Serializable
data class UpdateCameraRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val disabled: Boolean? = null,
    @SerialName("ai_paused")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val aiPaused: Boolean? = null,
    @SerialName("desired_state")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val desiredState: Camera.DesiredState? = null,
)

/**
 * Response for `GET /v1/cameras/{id}/detectors` — the detector ids currently
 * assigned to this camera. The app cross-references these against the patient's
 * full detector list to render per-camera toggles.
 */
@Serializable
data class CameraDetectorsResponse(
    @SerialName("detector_ids") val detectorIds: List<String>,
)

/**
 * Body for `PUT /v1/cameras/{id}/detectors`. Replaces the camera's detector
 * assignment set with exactly these ids. Every id must belong to the same
 * patient as the camera, or the backend returns 422.
 */
@Serializable
data class SetCameraDetectorsRequest(
    @SerialName("detector_ids") val detectorIds: List<String>,
)

// ===========================================================================
// Detector — mirrors Models/Detector.swift
// ===========================================================================

/**
 * A detector configured for a patient. Mirrors
 * `nemara-backend/src/schemas/detector.py` `DetectorResponse`.
 *
 * Every detector, preset or custom, describes itself along four axes: what,
 * shape, where, when.
 */
@Serializable
data class Detector(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    val name: String,
    @SerialName("icon_name") val iconName: String,
    val description: String,
    val category: Category,
    val source: Source,
    /** The trigger, the internal temporal nature, and the duration bar. */
    @SerialName("alert_trigger") val alertTrigger: AlertTrigger,
    @SerialName("is_sustained") val isSustained: Boolean,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    /**
     * Rooms the detector is scoped to (multi-select). Null means every room.
     * The ONLY room-scope truth; `where_scope` was deleted 2026-07-28.
     */
    @SerialName("where_rooms") val whereRooms: List<String>? = null,
    @SerialName("when_scope") val whenScope: String,
    val sensitivity: Sensitivity,
    @SerialName("is_on") val isOn: Boolean,
    @SerialName("custom_prompt") val customPrompt: String? = null,
    /**
     * Runtime-contract identifier. For presets: "fall" / "wandering" /
     * "agitation" / "medication". For custom detectors: `custom_<8hex>` once
     * the backend's asynchronous compile finishes.
     *
     * Null while a freshly created custom detector is still compiling, about
     * 3-5s, so the UI can show a "compiling…" state during that window.
     */
    val identifier: String? = null,
    /** Null for reactive detectors; the evaluation window for `absence`. */
    @SerialName("schedule_config") val scheduleConfig: ScheduleConfig? = null,
    /** Null unless [isSustained]; the rising-edge episode gap in seconds. */
    @SerialName("episode_gap_seconds") val episodeGapSeconds: Int? = null,
    /**
     * Caregiver-readable summary of the compiled instructions ("Counts: …" /
     * "Doesn't count: …" / "Caution: …"). Null while Memoria is rewriting them,
     * or if summarization failed.
     */
    @SerialName("user_criteria") val userCriteria: List<String>? = null,
    /** Plain-words change requests the caregiver has submitted, oldest first. */
    val refinements: List<String>? = null,
    /**
     * Wandering only: the caregiver's saved exit-door choice, so the picker can
     * pre-highlight it. Null until the caregiver runs the door picker.
     */
    @SerialName("door_config") val doorConfig: DoorConfig? = null,
    /**
     * True for detectors that read FINE visual detail: small motion (seizure,
     * choking) or small objects (medication, stove). Their accuracy varies with
     * camera distance, lighting, and angle, so we show an expectations notice
     * at enroll and in the detail sheet.
     *
     * Server-computed, and optional on the wire because older backends leave it
     * out. Read it through [showsAccuracyCaveat], which treats null as false.
     */
    @SerialName("accuracy_caveat") val accuracyCaveat: Boolean? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    @Serializable
    enum class Category(val wire: String) {
        @SerialName("safety")
        SAFETY("safety"),

        @SerialName("routine")
        ROUTINE("routine"),
    }

    @Serializable
    enum class Source(val wire: String) {
        @SerialName("preset")
        PRESET("preset"),

        @SerialName("custom")
        CUSTOM("custom"),
    }

    /**
     * Which MOMENT fires the alert. Wire values match the backend
     * `alert_trigger`. [isSustained] (instant versus sustained) is a separate
     * flag on the detector, and [durationSeconds] is the "too long" threshold
     * for the DURATION trigger.
     */
    @Serializable
    enum class AlertTrigger(val wire: String) {
        /** The moment it starts or happens. */
        @SerialName("leading")
        LEADING("leading"),

        /** When a sustained activity ends. */
        @SerialName("trailing")
        TRAILING("trailing"),

        /** Once it has persisted past `durationSeconds`. */
        @SerialName("duration")
        DURATION("duration"),

        /** NOT detected within its active window (`whenScope`). */
        @SerialName("absence")
        ABSENCE("absence"),

        /** Never alerts; feeds daily summaries. */
        @SerialName("record_only")
        RECORD_ONLY("record_only"),
        ;

        /** Caregiver-facing label in plain words. Mirrors web TRIGGER_LABEL. */
        val caregiverLabel: String
            get() = when (this) {
                LEADING -> "Alerts the moment it happens"
                TRAILING -> "Alerts when the activity's finished"
                DURATION -> "Alerts once it's gone on too long"
                ABSENCE -> "Alerts if it doesn't happen in your time window"
                RECORD_ONLY -> "Never alerts — tracked quietly for daily insights"
            }

        /** Compact caregiver label for dense captions. Mirrors web TRIGGER_LABEL_SHORT. */
        val caregiverShort: String
            get() = when (this) {
                LEADING -> "When it happens"
                TRAILING -> "When it ends"
                DURATION -> "If it lasts too long"
                ABSENCE -> "If it doesn't happen"
                RECORD_ONLY -> "Tracked quietly"
            }

        /** One-line "why" under the label. Mirrors web TRIGGER_SUB. */
        val caregiverSub: String
            get() = when (this) {
                LEADING -> "Fires as soon as it's seen."
                TRAILING -> "A single alert once the whole activity is over."
                DURATION -> "Fires only after it has continued past your time limit."
                ABSENCE -> "Fires when the thing you're expecting is missing by your deadline."
                RECORD_ONLY -> "Builds daily summaries (like sleep) without ever notifying anyone."
            }
    }

    @Serializable
    enum class Sensitivity(val wire: String) {
        @SerialName("low")
        LOW("low"),

        @SerialName("standard")
        STANDARD("standard"),

        @SerialName("high")
        HIGH("high"),

        @SerialName("off")
        OFF("off"),
        ;

        val displayName: String get() = wire.capitalizedFirst()
    }

    /**
     * Evaluation window for a scheduled detector. Fields are optional so the
     * LLM can return a partial inference, leaving blanks the user fills in.
     * Times are local "HH:MM" on a 24-hour clock; [daysOfWeek] is 0=Mon .. 6=Sun.
     */
    @Serializable
    data class ScheduleConfig(
        val cadence: Cadence? = null,
        @SerialName("days_of_week") val daysOfWeek: List<Int>? = null,
        @SerialName("window_start") val windowStart: String? = null,
        @SerialName("window_end") val windowEnd: String? = null,
        val deadline: String? = null,
    ) {
        @Serializable
        enum class Cadence(val wire: String) {
            @SerialName("daily")
            DAILY("daily"),

            @SerialName("weekly")
            WEEKLY("weekly"),
        }
    }

    /**
     * The exit-door selection stored on a `wandering` detector. Mirrors the
     * backend `door_config` jsonb: `{ doors: [{box,label,source}], no_exit_door }`.
     * A caregiver can pick MULTIPLE exit doors.
     */
    @Serializable(with = DoorConfigSerializer::class)
    data class DoorConfig(
        val doors: List<Door> = emptyList(),
        /** True means no door leads outside, and the detector is turned off. */
        val noExitDoor: Boolean = false,
    ) {
        /** One selected exit door. */
        @Serializable
        data class Door(
            /** `[x, y, w, h]` as fractions of the frame, 0-1. */
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val box: List<Double>? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val label: String? = null,
            /** "vlm" (tapped a highlighted box) or "manual" (tap-override). */
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val source: String? = null,
        )
    }

    /** Non-optional accessor for the UI. A missing field reads as "no caveat". */
    val showsAccuracyCaveat: Boolean get() = accuracyCaveat ?: false

    /**
     * Human room-scope label. [whereRooms] is the only truth; null or empty
     * means every room. Mirrors web `roomsLabel`.
     */
    val roomsLabel: String
        get() {
            val wr = whereRooms
            if (wr != null && wr.isNotEmpty()) {
                if (wr.any { it.trimmingSwiftWhitespaces().uppercase() == "ALL ROOMS" }) {
                    return "All rooms"
                }
                return wr.joinToString(", ")
            }
            return "All rooms"
        }
}

/**
 * The literal wire shape of a `door_config`, carrying both the current
 * multi-door array and the legacy single-door keys.
 */
@Serializable
private data class DoorConfigWire(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val doors: List<Detector.DoorConfig.Door>? = null,
    @SerialName("no_exit_door")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val noExitDoor: Boolean? = null,
    // Legacy single-door keys, for rows saved before multi-door shipped.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val box: List<Double>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val label: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val source: String? = null,
)

/**
 * Reads `doors[]` when it is there, and otherwise folds a legacy single
 * `box`/`label`/`source` row into a one-element array. Writes only the current
 * shape, never the legacy keys.
 */
internal object DoorConfigSerializer : KSerializer<Detector.DoorConfig> {
    override val descriptor: SerialDescriptor = DoorConfigWire.serializer().descriptor

    override fun deserialize(decoder: Decoder): Detector.DoorConfig {
        val w = decoder.decodeSerializableValue(DoorConfigWire.serializer())
        val doors = when {
            w.doors != null -> w.doors
            w.box != null -> listOf(
                Detector.DoorConfig.Door(box = w.box, label = w.label, source = w.source),
            )
            else -> emptyList()
        }
        return Detector.DoorConfig(doors = doors, noExitDoor = w.noExitDoor ?: false)
    }

    override fun serialize(encoder: Encoder, value: Detector.DoorConfig) {
        encoder.encodeSerializableValue(
            DoorConfigWire.serializer(),
            DoorConfigWire(doors = value.doors, noExitDoor = value.noExitDoor),
        )
    }
}

// --- Wandering exit-door picker wire types ---

/**
 * Body of `POST /v1/detect-doors` — one camera frame (base64 JPEG) the VLM
 * scans for doorways to highlight in the exit-door picker.
 */
@Serializable
data class DetectDoorsRequest(
    @SerialName("image_base64") val imageBase64: String,
)

/**
 * One doorway the VLM found. [box] is `[x, y, w, h]` as fractions of the frame,
 * 0-1, with a top-left origin.
 */
@Serializable
data class DetectedDoor(
    val box: List<Double>,
    val label: String,
) {
    val id: String
        get() = "$label-" + box.joinToString(",") { String.format(Locale.US, "%.3f", it) }
}

@Serializable
data class DetectDoorsResponse(
    val doors: List<DetectedDoor>,
)

/** One chosen exit door in a [SetWanderingDoorRequest]. */
@Serializable
data class WanderingDoorInput(
    val box: List<Double>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val label: String? = null,
    /** "vlm" or "manual". */
    val source: String,
)

/**
 * Body of `POST /v1/cameras/{camera_id}/wandering-door` — the caregiver's
 * exit-door choice FOR ONE SENSOR. One or more doors, or [noExitDoor] when this
 * camera watches no exit, in which case wandering is skipped there.
 */
@Serializable
data class SetWanderingDoorRequest(
    val doors: List<WanderingDoorInput>,
    @SerialName("no_exit_door") val noExitDoor: Boolean,
)

/**
 * Per-sensor wandering exit-door config, from
 * `GET /v1/patients/{id}/wandering-doors` and from the POST response. The exit
 * door is per camera, because each sensor sees a different scene.
 */
@Serializable
data class CameraWanderingDoor(
    @SerialName("camera_id") val cameraId: String,
    @SerialName("door_config") val doorConfig: Detector.DoorConfig? = null,
) {
    val id: String get() = cameraId
}

/**
 * Body of `POST /v1/patients/{id}/detectors`. The server forces
 * `source = custom`. Only [name] and [customPrompt] are required; the rest are
 * normally LLM-inferred and then confirmed on the review screen.
 *
 * The non-null fields below are sent on every create, matching Swift's
 * generated encoder, which writes a non-optional field whether or not it holds
 * the declared default. `@EncodeDefault(ALWAYS)` pins that regardless of how
 * the shared Json is configured.
 *
 * `where_scope` was REMOVED on 2026-07-28: the backend dropped it in favour of
 * `where_rooms` and rejects unknown fields, so sending it made every
 * custom-detector create fail with a 422.
 */
@Serializable
data class CreateDetectorRequest(
    val name: String,
    @SerialName("custom_prompt") val customPrompt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val description: String = "Custom detector",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val category: Detector.Category = Detector.Category.SAFETY,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("alert_trigger")
    val alertTrigger: Detector.AlertTrigger = Detector.AlertTrigger.LEADING,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("is_sustained")
    val isSustained: Boolean = false,
    @SerialName("duration_seconds")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val durationSeconds: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("icon_name")
    val iconName: String = "eye",
    /**
     * Rooms the caregiver picked (multi-select). Null or ["ALL ROOMS"] means
     * every room. Drives camera auto-assignment.
     */
    @SerialName("where_rooms")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val whereRooms: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("when_scope")
    val whenScope: String = "ALWAYS",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val sensitivity: Detector.Sensitivity = Detector.Sensitivity.STANDARD,
    @SerialName("schedule_config")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scheduleConfig: Detector.ScheduleConfig? = null,
    /** Set only when [isSustained]; the rising-edge episode gap in seconds. */
    @SerialName("episode_gap_seconds")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val episodeGapSeconds: Int? = null,
    /**
     * Create-as-draft: the create screen passes false so the detector is made
     * OFF and the caregiver reviews the compiled criteria on the detail sheet
     * before turning it on.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("is_on")
    val isOn: Boolean = true,
)

/**
 * Body of `POST /v1/detectors/infer` — the plain-English ask the LLM classifies
 * and structures for the create review screen.
 */
@Serializable
data class InferDetectorRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null,
    val prompt: String,
)

/**
 * Plain-English preview of the compiled behavior: what the backend deduced from
 * the caregiver's words, shown on the review screen before the detector exists.
 */
@Serializable
data class DetectorPlan(
    val watches: String,
    val fires: String,
    val checks: String,
    val listens: Boolean,
)

/**
 * LLM-inferred fields for the create review screen. Nulls where the model could
 * not confidently determine a value.
 */
@Serializable
data class InferDetectorResponse(
    @SerialName("alert_trigger") val alertTrigger: Detector.AlertTrigger,
    @SerialName("is_sustained") val isSustained: Boolean,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val category: Detector.Category,
    @SerialName("icon_name") val iconName: String,
    val sensitivity: Detector.Sensitivity,
    @SerialName("schedule_config") val scheduleConfig: Detector.ScheduleConfig? = null,
    /** Set when the activity is sustained; null otherwise. */
    @SerialName("episode_gap_seconds") val episodeGapSeconds: Int? = null,
    /** Null only when preview composition failed server-side, so hide the card. */
    val plan: DetectorPlan? = null,
)

/** Body of `PATCH /v1/detectors/{id}`. Null means the field is left out. */
@Serializable
data class UpdateDetectorRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val name: String? = null,
    @SerialName("icon_name")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val iconName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val category: Detector.Category? = null,
    @SerialName("alert_trigger")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val alertTrigger: Detector.AlertTrigger? = null,
    @SerialName("is_sustained")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isSustained: Boolean? = null,
    @SerialName("duration_seconds")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val durationSeconds: Int? = null,
    /**
     * Re-scope the detector's rooms; the backend replaces its camera
     * assignments to match. ["ALL ROOMS"] means every room.
     */
    @SerialName("where_rooms")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val whereRooms: List<String>? = null,
    @SerialName("when_scope")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val whenScope: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sensitivity: Detector.Sensitivity? = null,
    @SerialName("is_on")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isOn: Boolean? = null,
    @SerialName("custom_prompt")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val customPrompt: String? = null,
)

/**
 * Body of `POST /v1/detectors/{id}/refine` — one plain-words change request
 * ("don't count eating as choking"). The backend appends it to the detector's
 * accumulated list and recompiles the instructions from all of them.
 *
 * [preview] is a dry run: the backend compiles WITH this feedback and returns
 * the resulting criteria without persisting anything, which is what the
 * "Re-generate" button uses. Both fields are always written — iOS declares them
 * as non-optional on an inline `Encodable`, so both appear on the wire even
 * when `preview` is false, and the backend's `extra="forbid"` means the shape
 * has to match exactly.
 */
@Serializable
data class RefineDetectorRequest(
    val feedback: String,
    val preview: Boolean,
)

/**
 * One detector-catalog entry from `GET /v1/patients/{id}/detectors/library`,
 * the detection library. Mirrors
 * `nemara-backend/src/schemas/detector.py` `DetectorLibraryEntry`.
 *
 * [alreadyAdded] is true when the patient already has a detector with this
 * identifier, for example an auto-seeded preset, so the picker pre-checks and
 * disables it. [recommended] entries are the cohort's default presets.
 */
@Serializable
data class DetectorLibraryEntry(
    val key: String,
    val name: String,
    @SerialName("icon_name") val iconName: String,
    val description: String,
    val category: Detector.Category,
    /** "child" | "adult" | "both". */
    val cohort: String,
    val recommended: Boolean,
    @SerialName("already_added") val alreadyAdded: Boolean,
    /**
     * Fine-detail detector, so show the accuracy notice in the picker. Optional
     * on the wire because older backends leave it out; read it through
     * [showsAccuracyCaveat].
     */
    @SerialName("accuracy_caveat") val accuracyCaveat: Boolean? = null,
) {
    val id: String get() = key
    val showsAccuracyCaveat: Boolean get() = accuracyCaveat ?: false
}

/**
 * Body of `POST /v1/patients/{id}/detectors/from-library` — the catalog keys to
 * instantiate as preset detectors. The server skips keys that are already
 * present, and returns 422 for unknown ones.
 */
@Serializable
data class AddFromLibraryRequest(
    val keys: List<String>,
)

// ===========================================================================
// DetectionEvent — mirrors Models/DetectionEvent.swift
// ===========================================================================

/**
 * A detection event surfaced in the Events tab for a patient. Mirrors
 * `nemara-backend/src/schemas/event.py` `EventResponse`.
 */
@Serializable
data class DetectionEvent(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    @SerialName("detector_id") val detectorId: String? = null,
    @SerialName("camera_id") val cameraId: String? = null,
    val category: Detector.Category,
    val source: Detector.Source,
    @SerialName("icon_name") val iconName: String,
    val title: String,
    val location: String,
    @SerialName("occurred_at") val occurredAt: Instant,
    @SerialName("has_clip") val hasClip: Boolean,
    val narrative: String,
    val opened: Boolean,
    val reviewed: Boolean,
    @SerialName("review_choice") val reviewChoice: ReviewChoice? = null,
    @SerialName("review_notes") val reviewNotes: String? = null,
    @SerialName("reviewed_at") val reviewedAt: Instant? = null,
    /**
     * Bucket-relative key in incident-clips once the clip has been archived,
     * and null on the wire while archival is still in flight. We do not fetch
     * the bytes through this key — we ask `/v1/events/{id}/clip-url` for a
     * signed URL — but the field is decoded so a future preload flow can use it.
     */
    @SerialName("clip_storage_key") val clipStorageKey: String? = null,
    /**
     * True when the clip was intentionally NOT recorded because the patient was
     * in privacy mode. Distinguishes "no clip, never coming" from "clip still
     * processing" so the detail view can say which.
     *
     * Reads false when the key is missing or null, so an older payload decodes
     * cleanly instead of the whole event being dropped.
     */
    @SerialName("clip_suppressed")
    @Serializable(with = BooleanOrFalseSerializer::class)
    val clipSuppressed: Boolean = false,
    /**
     * True when this event's clip comes from a privacy-enabled camera and
     * contains a sensitive moment: the video arrives ALREADY blurred
     * server-side with audio stripped. The clip plays like any other; this flag
     * only drives the "Sensitive moment" badge on the detail view.
     *
     * Reads false when the key is missing or null, for the same reason as
     * [clipSuppressed].
     */
    @SerialName("clip_censored")
    @Serializable(with = BooleanOrFalseSerializer::class)
    val clipCensored: Boolean = false,
    /**
     * What the backend judged sensitive about the censored clip, for example
     * "undressing" or "bathing". Null or absent on non-censored events; shown
     * as a subtle secondary line under the censored badge.
     */
    @SerialName("sensitivity_categories") val sensitivityCategories: List<String>? = null,
    @SerialName("created_at") val createdAt: Instant,
) {
    /** The caregiver's choice from the Event Review step. */
    @Serializable
    enum class ReviewChoice(val wire: String) {
        @SerialName("correct")
        CORRECT("correct"),

        @SerialName("wrong")
        WRONG("wrong"),

        @SerialName("unsure")
        UNSURE("unsure"),
    }

    /**
     * Human-readable time label for the event card and detail header.
     * Same day: "HH:mm". Yesterday: "Yesterday HH:mm". Older this week:
     * "EEE HH:mm", for example "Mon 16:42". Beyond that: "MMM d HH:mm".
     */
    val time: String get() = timeLabel(nowInstant())

    /** "42 min ago" / "2h ago" / "1d ago". */
    val ago: String get() = agoLabel(nowInstant())

    /**
     * [time] against a caller-supplied clock. The public property reads the
     * wall clock, exactly as iOS does; this overload exists so the formatting
     * rules can be tested without waiting for a particular day.
     */
    internal fun timeLabel(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val occurred = occurredAt.toJavaTime().atZone(zone)
        val current = now.toJavaTime().atZone(zone)
        val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        if (occurred.toLocalDate() == current.toLocalDate()) {
            return hm.format(occurred)
        }
        if (occurred.toLocalDate() == current.toLocalDate().minusDays(1)) {
            return "Yesterday ${hm.format(occurred)}"
        }
        val days = ChronoUnit.DAYS.between(occurred, current)
        val pattern = if (days < 7) "EEE HH:mm" else "MMM d HH:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(occurred)
    }

    /** [ago] against a caller-supplied clock. See [timeLabel]. */
    internal fun agoLabel(now: Instant): String {
        val secs = (now - occurredAt).inWholeSeconds
        if (secs < 60) return "just now"
        val mins = secs / 60
        if (mins < 60) return "$mins min ago"
        val hours = mins / 60
        if (hours < 24) return "${hours}h ago"
        return "${hours / 24}d ago"
    }
}

/**
 * Server-side filter for `GET /v1/patients/{id}/events?filter=`. Wire values
 * match the backend `EventFilter` enum.
 */
@Serializable
enum class EventFilter(val wire: String) {
    @SerialName("all")
    ALL("all"),

    @SerialName("unreviewed")
    UNREVIEWED("unreviewed"),

    @SerialName("reviewed")
    REVIEWED("reviewed"),
    ;

    val id: String get() = wire
    val displayName: String get() = wire.capitalizedFirst()
}

/** Paged event list response. */
@Serializable
data class EventListResponse(
    val items: List<DetectionEvent>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * Body of `PATCH /v1/events/{id}`. Null means the field is left out. The
 * backend stamps `reviewed_at = now()` when `reviewed` flips false to true.
 */
@Serializable
data class UpdateEventRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val opened: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reviewed: Boolean? = null,
    @SerialName("review_choice")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reviewChoice: DetectionEvent.ReviewChoice? = null,
    @SerialName("review_notes")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reviewNotes: String? = null,
)

// ===========================================================================
// ClipURL — mirrors Models/ClipURL.swift
// ===========================================================================

/**
 * Decoded shape of `GET /v1/events/{id}/clip-url`. Mirrors
 * `nemara-backend/src/schemas/event.py` `ClipURLResponse`.
 *
 * The URL is short-lived, 10 minutes by default. The app fetches a fresh one
 * every time the player opens; we do not cache it.
 */
@Serializable
data class ClipURLResponse(
    val url: String,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int,
)
