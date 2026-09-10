@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.model

import com.memoria.caregiver.data.remote.NemaraJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Wire-format tests for the four device model files.
 *
 * Every decode case starts from a JSON string literal shaped like something the
 * backend actually sends: either the REST response (the API schema) or the raw
 * database row Supabase Realtime ships. Those two shapes differ, and the same
 * Kotlin class has to read both — that is the whole reason the lenient rules in
 * `PARITY.md §7` exist. Round-tripping our own encoder would prove nothing
 * about the wire.
 *
 * The shared `NemaraJson.json` is used rather than a locally built [Json], so
 * these tests exercise the configuration the app actually ships with.
 */
class DeviceModelsTest {

    private val json = NemaraJson.json

    /**
     * Builds an expected instant from ISO text using `java.time`, which is
     * independent of the code under test. Writing the expected values as
     * readable timestamps rather than epoch-second literals keeps each
     * assertion checkable by eye.
     */
    private fun utcInstant(iso: String): Instant {
        val parsed = java.time.OffsetDateTime.parse(iso).toInstant()
        return Instant.fromEpochSeconds(parsed.epochSecond, parsed.nano.toLong())
    }

    // =================================================================
    // Camera — the REST shape
    // =================================================================

    private val restCameraJson = """
        {
          "id": "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b",
          "patient_id": "11111111-2222-3333-4444-555555555555",
          "display_id": 1,
          "room": "Kitchen",
          "icon_name": "fork.knife",
          "online": true,
          "disabled": false,
          "ai_paused": false,
          "desired_state": "connected",
          "source_type": "iphone",
          "room_class": "other",
          "room_class_confidence": 0.87,
          "room_classified_at": "2026-05-21T22:18:00.182734+00:00",
          "privacy_enabled": false,
          "livekit_identity": "cam_1a2b3c4d",
          "created_at": "2026-05-20T10:00:00.000000+00:00",
          "paired": true,
          "app_present": true
        }
    """.trimIndent()

    @Test
    fun `decodes the REST camera shape`() {
        val cam = json.decodeFromString(Camera.serializer(), restCameraJson)

        assertEquals("8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b", cam.id)
        assertEquals("11111111-2222-3333-4444-555555555555", cam.patientId)
        assertEquals(1, cam.displayId)
        assertEquals("Kitchen", cam.room)
        assertEquals("fork.knife", cam.iconName)
        assertTrue(cam.online)
        assertFalse(cam.disabled)
        assertFalse(cam.aiPaused)
        assertEquals(Camera.DesiredState.CONNECTED, cam.desiredState)
        assertEquals(Camera.SourceType.PHONE, cam.sourceType)
        assertEquals(Camera.RoomClass.OTHER, cam.roomClass)
        assertEquals(0.87, cam.roomClassConfidence!!, 1e-9)
        assertEquals(utcInstant("2026-05-21T22:18:00.182Z"), cam.roomClassifiedAt)
        assertFalse(cam.privacyEnabled)
        assertEquals("cam_1a2b3c4d", cam.livekitIdentity)
        assertTrue(cam.paired)
        assertEquals(true, cam.appPresent)
    }

    // =================================================================
    // Camera — the Realtime raw row, and the derived `paired`
    // =================================================================

    /**
     * A raw `cameras` row as Supabase Realtime ships it: no `paired`, extra
     * columns the API schema never exposes, and explicit nulls where the REST
     * shape would leave the key out.
     */
    private fun realtimeCameraJson(
        sourceType: String = "\"gateway\"",
        apiKeyHash: String = "null",
        ingestTokenHash: String = "null",
        privacyEnabled: String = "true",
        extras: String = "",
    ) = """
        {
          "id": "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b",
          "patient_id": "11111111-2222-3333-4444-555555555555",
          "display_id": 2,
          "room": "Bathroom",
          "icon_name": "shower",
          "online": false,
          "disabled": false,
          "ai_paused": false,
          "desired_state": "connected",
          "source_type": $sourceType,
          "room_class": "bathroom",
          "room_class_confidence": null,
          "room_classified_at": null,
          "privacy_enabled": $privacyEnabled,
          "livekit_identity": null,
          "created_at": "2026-05-20T10:00:00.182734+00:00",
          "api_key_hash": $apiKeyHash,
          "ingest_token_hash": $ingestTokenHash,
          "updated_at": "2026-05-20T11:00:00.182734+00:00",
          "cameras_paused": false$extras
        }
    """.trimIndent()

    private fun decodeCamera(raw: String) = json.decodeFromString(Camera.serializer(), raw)

    @Test
    fun `a realtime row without paired derives it from api_key_hash`() {
        assertTrue(decodeCamera(realtimeCameraJson(apiKeyHash = "\"deadbeef\"")).paired)
    }

    @Test
    fun `a realtime row without paired derives it from ingest_token_hash`() {
        // The Pi-camera regression: Pi cameras skip the pairing-code flow and
        // store their credential on ingest_token_hash. Checking only
        // api_key_hash flipped every Pi camera to "PAIRING" on every update.
        assertTrue(decodeCamera(realtimeCameraJson(ingestTokenHash = "\"pi-token\"")).paired)
    }

    @Test
    fun `a realtime row with both credential hashes null is unpaired`() {
        assertFalse(decodeCamera(realtimeCameraJson()).paired)
    }

    @Test
    fun `a realtime row with both credential keys absent is unpaired`() {
        val stripped = """
            {
              "id": "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b",
              "patient_id": "11111111-2222-3333-4444-555555555555",
              "display_id": 2,
              "room": "Bathroom",
              "icon_name": "shower",
              "online": false,
              "disabled": false,
              "ai_paused": false,
              "desired_state": "connected",
              "created_at": "2026-05-20T10:00:00.182734+00:00"
            }
        """.trimIndent()
        assertFalse(decodeCamera(stripped).paired)
    }

    @Test
    fun `an explicit paired wins over the credential columns`() {
        val cam = decodeCamera(
            realtimeCameraJson(apiKeyHash = "\"deadbeef\"", extras = ",\n  \"paired\": false"),
        )
        assertFalse(cam.paired)
    }

    @Test
    fun `unknown columns on a realtime row are ignored`() {
        // The raw row carries updated_at and cameras_paused, which the API
        // schema never exposes. Decoding must not fail on them.
        assertEquals("Bathroom", decodeCamera(realtimeCameraJson()).room)
    }

    // =================================================================
    // Camera — SourceType leniency
    // =================================================================

    @Test
    fun `source_type gateway decodes as a sensor rather than failing`() {
        assertEquals(
            Camera.SourceType.SENSOR,
            decodeCamera(realtimeCameraJson(sourceType = "\"gateway\"")).sourceType,
        )
    }

    @Test
    fun `source_type pi decodes as a sensor`() {
        assertEquals(
            Camera.SourceType.SENSOR,
            decodeCamera(realtimeCameraJson(sourceType = "\"pi\"")).sourceType,
        )
    }

    @Test
    fun `source_type iphone is the only value that means phone`() {
        assertEquals(
            Camera.SourceType.PHONE,
            decodeCamera(realtimeCameraJson(sourceType = "\"iphone\"")).sourceType,
        )
    }

    @Test
    fun `an unknown future source_type still decodes as a sensor`() {
        assertEquals(
            Camera.SourceType.SENSOR,
            decodeCamera(realtimeCameraJson(sourceType = "\"relay-v9\"")).sourceType,
        )
    }

    @Test
    fun `an absent source_type defaults to phone`() {
        val cam = decodeCamera(restCameraJson.replace("\"source_type\": \"iphone\",", ""))
        assertEquals(Camera.SourceType.PHONE, cam.sourceType)
    }

    @Test
    fun `an explicitly null source_type defaults to phone`() {
        assertEquals(
            Camera.SourceType.PHONE,
            decodeCamera(realtimeCameraJson(sourceType = "null")).sourceType,
        )
    }

    // =================================================================
    // Camera — RoomClass leniency
    // =================================================================

    @Test
    fun `an unknown room_class folds into other`() {
        val cam = decodeCamera(
            restCameraJson.replace("\"room_class\": \"other\"", "\"room_class\": \"garage\""),
        )
        assertEquals(Camera.RoomClass.OTHER, cam.roomClass)
    }

    @Test
    fun `known room classes decode exactly`() {
        assertEquals(
            Camera.RoomClass.BATHROOM,
            decodeCamera(restCameraJson.replace("\"room_class\": \"other\"", "\"room_class\": \"bathroom\"")).roomClass,
        )
        assertEquals(
            Camera.RoomClass.BEDROOM,
            decodeCamera(restCameraJson.replace("\"room_class\": \"other\"", "\"room_class\": \"bedroom\"")).roomClass,
        )
    }

    @Test
    fun `an absent or null room_class stays null`() {
        assertNull(decodeCamera(restCameraJson.replace("\"room_class\": \"other\",", "")).roomClass)
        assertNull(
            decodeCamera(restCameraJson.replace("\"room_class\": \"other\"", "\"room_class\": null")).roomClass,
        )
    }

    // =================================================================
    // Camera — privacy_enabled and app_present leniency
    // =================================================================

    @Test
    fun `an absent privacy_enabled reads false`() {
        assertFalse(decodeCamera(restCameraJson.replace("\"privacy_enabled\": false,", "")).privacyEnabled)
    }

    @Test
    fun `an explicitly null privacy_enabled reads false`() {
        assertFalse(decodeCamera(realtimeCameraJson(privacyEnabled = "null")).privacyEnabled)
    }

    @Test
    fun `privacy_enabled true is carried through`() {
        assertTrue(decodeCamera(realtimeCameraJson(privacyEnabled = "true")).privacyEnabled)
    }

    @Test
    fun `an absent app_present stays null so the app is never falsely reported closed`() {
        val cam = decodeCamera(restCameraJson.replace(",\n  \"app_present\": true", ""))
        assertNull(cam.appPresent)
        assertFalse(cam.isAppClosed)
    }

    // =================================================================
    // Camera — strict where iOS is strict
    // =================================================================

    @Test
    fun `a missing required camera field fails the decode`() {
        val missingOnline = restCameraJson.replace("\"online\": true,", "")
        assertThrows(SerializationException::class.java) { decodeCamera(missingOnline) }
    }

    @Test
    fun `an unknown desired_state fails the decode`() {
        val bad = restCameraJson.replace("\"desired_state\": \"connected\"", "\"desired_state\": \"limbo\"")
        assertThrows(SerializationException::class.java) { decodeCamera(bad) }
    }

    // =================================================================
    // Camera — computed state
    // =================================================================

    private fun camera(
        sourceType: Camera.SourceType = Camera.SourceType.PHONE,
        roomClass: Camera.RoomClass? = null,
        online: Boolean = true,
        disabled: Boolean = false,
        desiredState: Camera.DesiredState = Camera.DesiredState.CONNECTED,
        livekitIdentity: String? = "cam_1a2b3c4d",
        appPresent: Boolean? = null,
    ) = Camera(
        disabled = disabled,
        aiPaused = false,
        sourceType = sourceType,
        roomClass = roomClass,
        privacyEnabled = false,
        desiredState = desiredState,
        id = "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b",
        patientId = "11111111-2222-3333-4444-555555555555",
        displayId = 1,
        room = "Kitchen",
        iconName = "fork.knife",
        online = online,
        livekitIdentity = livekitIdentity,
        createdAt = utcInstant("2026-05-20T10:00:00Z"),
        paired = true,
        appPresent = appPresent,
    )

    @Test
    fun `isSensitiveRoom is true only for bathroom and bedroom`() {
        assertTrue(camera(roomClass = Camera.RoomClass.BATHROOM).isSensitiveRoom)
        assertTrue(camera(roomClass = Camera.RoomClass.BEDROOM).isSensitiveRoom)
        assertFalse(camera(roomClass = Camera.RoomClass.OTHER).isSensitiveRoom)
        assertFalse(camera(roomClass = null).isSensitiveRoom)
    }

    @Test
    fun `isAppClosed needs a phone that is offline with a false heartbeat`() {
        assertTrue(camera(online = false, appPresent = false).isAppClosed)
        // A Pi sensor has no app to close.
        assertFalse(
            camera(sourceType = Camera.SourceType.SENSOR, online = false, appPresent = false).isAppClosed,
        )
        // Never while online.
        assertFalse(camera(online = true, appPresent = false).isAppClosed)
        // A null heartbeat means "running".
        assertFalse(camera(online = false, appPresent = null).isAppClosed)
    }

    @Test
    fun `isLiveStreaming ignores online on purpose`() {
        assertTrue(camera(online = false).isLiveStreaming)
        assertFalse(camera(livekitIdentity = null).isLiveStreaming)
        assertFalse(camera(disabled = true).isLiveStreaming)
        assertFalse(camera(desiredState = Camera.DesiredState.DISCONNECTED).isLiveStreaming)
    }

    // =================================================================
    // Camera — encoding
    // =================================================================

    @Test
    fun `encoding a camera never emits the credential columns`() {
        val obj = json.encodeToJsonElement(Camera.serializer(), camera()).jsonObject
        assertFalse(obj.containsKey("api_key_hash"))
        assertFalse(obj.containsKey("ingest_token_hash"))
        assertTrue(obj.containsKey("paired"))
        assertEquals("iphone", obj["source_type"]!!.jsonPrimitive.content)
        assertEquals("connected", obj["desired_state"]!!.jsonPrimitive.content)
        assertEquals("false", obj["privacy_enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encoding a camera omits the fields that are unset`() {
        val obj = json.encodeToJsonElement(Camera.serializer(), camera(livekitIdentity = null)).jsonObject
        assertFalse(obj.containsKey("livekit_identity"))
        assertFalse(obj.containsKey("room_class"))
        assertFalse(obj.containsKey("room_classified_at"))
        assertFalse(obj.containsKey("app_present"))
    }

    @Test
    fun `a camera survives a decode encode decode round trip`() {
        val first = decodeCamera(restCameraJson)
        val second = decodeCamera(json.encodeToString(Camera.serializer(), first))
        assertEquals(first, second)
    }

    // =================================================================
    // Dates — the models route every Instant through the shared chain
    // =================================================================

    private fun cameraCreatedAt(raw: String) = decodeCamera(
        restCameraJson.replace(
            "\"created_at\": \"2026-05-20T10:00:00.000000+00:00\"",
            "\"created_at\": $raw",
        ),
    ).createdAt

    @Test
    fun `reads the six-digit microsecond timestamp pydantic emits`() {
        assertEquals(
            utcInstant("2026-05-21T22:18:00.182Z"),
            cameraCreatedAt("\"2026-05-21T22:18:00.182734+00:00\""),
        )
    }

    @Test
    fun `reads a three-digit millisecond timestamp`() {
        assertEquals(
            utcInstant("2026-05-21T22:18:00.182Z"),
            cameraCreatedAt("\"2026-05-21T22:18:00.182+00:00\""),
        )
    }

    @Test
    fun `reads a timestamp with no fractional part`() {
        assertEquals(
            utcInstant("2026-05-21T22:18:00Z"),
            cameraCreatedAt("\"2026-05-21T22:18:00+00:00\""),
        )
    }

    @Test
    fun `reads a bare calendar date as midnight UTC`() {
        assertEquals(utcInstant("2026-05-21T00:00:00Z"), cameraCreatedAt("\"2026-05-21\""))
    }

    @Test
    fun `honours a non-UTC offset`() {
        assertEquals(
            utcInstant("2026-05-21T17:18:00Z"),
            cameraCreatedAt("\"2026-05-21T22:48:00+05:30\""),
        )
    }

    @Test
    fun `an unreadable timestamp fails the decode`() {
        assertThrows(SerializationException::class.java) { cameraCreatedAt("\"21 May 2026\"") }
    }

    @Test
    fun `timestamps are written back in the form iOS writes`() {
        val cam = camera().copy(createdAt = utcInstant("2026-05-21T22:18:00.182Z"))
        val obj = json.encodeToJsonElement(Camera.serializer(), cam).jsonObject
        assertEquals("2026-05-21T22:18:00.182000Z", obj["created_at"]!!.jsonPrimitive.content)
    }

    // =================================================================
    // Camera side types
    // =================================================================

    @Test
    fun `decodes a pairing-code response`() {
        val res = json.decodeFromString(
            CreatePairingCodeResponse.serializer(),
            """
            {
              "pairing_code": "418302",
              "expires_at": "2026-05-21T22:28:00.182734+00:00",
              "camera_id": "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b"
            }
            """.trimIndent(),
        )
        assertEquals("418302", res.pairingCode)
        assertEquals(utcInstant("2026-05-21T22:28:00.182Z"), res.expiresAt)
        assertEquals("8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b", res.cameraId)
    }

    @Test
    fun `decodes the discovered-cameras response and exposes discovery_id as the row id`() {
        val res = json.decodeFromString(
            DiscoveredCamerasResponse.serializer(),
            """
            {
              "pis": [
                {
                  "discovery_id": "aaaa1111-2222-3333-4444-555555555555",
                  "hardware_id": "b8:27:eb:0a:1b:2c",
                  "device_label": "Memoria Sensor 4F2A",
                  "current_url": "rtsp://192.168.1.44:8554/stream",
                  "current_audio_url": null,
                  "first_seen_at": "2026-05-21T22:00:00.000000+00:00",
                  "last_seen_at": "2026-05-21T22:18:00.182734+00:00"
                }
              ]
            }
            """.trimIndent(),
        )
        val pi = res.pis.single()
        assertEquals("aaaa1111-2222-3333-4444-555555555555", pi.id)
        assertNull(pi.currentAudioUrl)
        assertEquals("Memoria Sensor 4F2A", pi.deviceLabel)
    }

    @Test
    fun `decodes the pi-provision response`() {
        val res = json.decodeFromString(
            ProvisionSensorCameraResponse.serializer(),
            """
            {
              "camera_id": "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b",
              "patient_id": "11111111-2222-3333-4444-555555555555",
              "livekit_identity": "cam_1a2b3c4d",
              "livekit_room": "patient_11111111-2222-3333-4444-555555555555"
            }
            """.trimIndent(),
        )
        assertEquals("cam_1a2b3c4d", res.livekitIdentity)
    }

    @Test
    fun `decodes the camera detector-assignment response`() {
        val res = json.decodeFromString(
            CameraDetectorsResponse.serializer(),
            """{"detector_ids": ["d1", "d2"]}""",
        )
        assertEquals(listOf("d1", "d2"), res.detectorIds)
    }

    @Test
    fun `an update-camera body sends only the fields that were set`() {
        val obj = json.encodeToJsonElement(
            UpdateCameraRequest.serializer(),
            UpdateCameraRequest(aiPaused = true),
        ).jsonObject
        assertEquals(setOf("ai_paused"), obj.keys)
        assertEquals("true", obj["ai_paused"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty update-camera body sends nothing`() {
        val obj = json.encodeToJsonElement(UpdateCameraRequest.serializer(), UpdateCameraRequest()).jsonObject
        assertEquals(emptySet<String>(), obj.keys)
    }

    @Test
    fun `an update-camera body encodes desired_state with the wire value`() {
        val obj = json.encodeToJsonElement(
            UpdateCameraRequest.serializer(),
            UpdateCameraRequest(desiredState = Camera.DesiredState.DISCONNECTED),
        ).jsonObject
        assertEquals("disconnected", obj["desired_state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `camera request bodies use the snake_case wire names`() {
        assertEquals(
            setOf("room", "icon_name"),
            json.encodeToJsonElement(
                CreatePairingCodeRequest.serializer(),
                CreatePairingCodeRequest("Kitchen", "fork.knife"),
            ).jsonObject.keys,
        )
        assertEquals(
            setOf("room", "icon_name", "discovery_id"),
            json.encodeToJsonElement(
                ProvisionSensorCameraRequest.serializer(),
                ProvisionSensorCameraRequest("Kitchen", "fork.knife", "aaaa1111"),
            ).jsonObject.keys,
        )
        assertEquals(
            setOf("detector_ids"),
            json.encodeToJsonElement(
                SetCameraDetectorsRequest.serializer(),
                SetCameraDetectorsRequest(listOf("d1")),
            ).jsonObject.keys,
        )
    }

    // =================================================================
    // Detector
    // =================================================================

    private val detectorJson = """
        {
          "id": "d1111111-2222-3333-4444-555555555555",
          "patient_id": "11111111-2222-3333-4444-555555555555",
          "name": "Left the house",
          "icon_name": "door.left.hand.open",
          "description": "Wandering",
          "category": "safety",
          "source": "preset",
          "alert_trigger": "leading",
          "is_sustained": false,
          "duration_seconds": null,
          "where_rooms": ["Kitchen", "Hallway"],
          "when_scope": "ALWAYS",
          "sensitivity": "standard",
          "is_on": true,
          "custom_prompt": null,
          "identifier": "wandering",
          "schedule_config": null,
          "episode_gap_seconds": null,
          "user_criteria": ["Counts: leaving through the front door"],
          "refinements": [],
          "door_config": null,
          "accuracy_caveat": false,
          "created_at": "2026-05-20T10:00:00.182734+00:00",
          "updated_at": "2026-05-21T10:00:00.182734+00:00"
        }
    """.trimIndent()

    private fun decodeDetector(raw: String) = json.decodeFromString(Detector.serializer(), raw)

    @Test
    fun `decodes a detector`() {
        val d = decodeDetector(detectorJson)
        assertEquals("Left the house", d.name)
        assertEquals(Detector.Category.SAFETY, d.category)
        assertEquals(Detector.Source.PRESET, d.source)
        assertEquals(Detector.AlertTrigger.LEADING, d.alertTrigger)
        assertEquals(Detector.Sensitivity.STANDARD, d.sensitivity)
        assertEquals(listOf("Kitchen", "Hallway"), d.whereRooms)
        assertEquals("ALWAYS", d.whenScope)
        assertTrue(d.isOn)
        assertEquals("wandering", d.identifier)
        assertEquals(listOf("Counts: leaving through the front door"), d.userCriteria)
        assertEquals(emptyList<String>(), d.refinements)
        assertNull(d.doorConfig)
        assertEquals(utcInstant("2026-05-21T10:00:00.182Z"), d.updatedAt)
    }

    @Test
    fun `a freshly created custom detector decodes with a null identifier`() {
        val d = decodeDetector(detectorJson.replace("\"identifier\": \"wandering\"", "\"identifier\": null"))
        assertNull(d.identifier)
    }

    @Test
    fun `an absent accuracy_caveat reads as no caveat`() {
        val d = decodeDetector(detectorJson.replace("\"accuracy_caveat\": false,", ""))
        assertNull(d.accuracyCaveat)
        assertFalse(d.showsAccuracyCaveat)
    }

    @Test
    fun `an unknown detector category reads as UNKNOWN rather than failing`() {
        // REVERSED on 2026-08-05, and the reversal is the point.
        //
        // This used to assert the decode THREW. That was written before
        // `Category.UNKNOWN` existed. `38aea39` then added UNKNOWN and
        // documented it as absorbing "a value from a newer server" — without
        // enabling the coercion that would make it do so, and without updating
        // this test. The old behaviour and the new comment contradicted each
        // other, and the comment was the one people would read.
        //
        // The comment wins, because `category` is on Detector and on
        // DetectionEvent, both of which load inside `bootstrap()`, which runs
        // inside SIGN-IN. Throwing here means a fifth category added
        // server-side locks every older client out of the app. Losing the
        // colour on one row does not. FINDINGS.md F-44.
        val bad = detectorJson.replace("\"category\": \"safety\"", "\"category\": \"comfort\"")
        assertEquals(Detector.Category.UNKNOWN, decodeDetector(bad).category)
    }

    @Test
    fun `roomsLabel falls back to all rooms when the scope is empty`() {
        val d = decodeDetector(detectorJson)
        assertEquals("Kitchen, Hallway", d.roomsLabel)
        assertEquals("All rooms", d.copy(whereRooms = null).roomsLabel)
        assertEquals("All rooms", d.copy(whereRooms = emptyList()).roomsLabel)
    }

    @Test
    fun `roomsLabel recognises the ALL ROOMS sentinel whatever its case or padding`() {
        val d = decodeDetector(detectorJson)
        assertEquals("All rooms", d.copy(whereRooms = listOf("ALL ROOMS")).roomsLabel)
        assertEquals("All rooms", d.copy(whereRooms = listOf("  all rooms  ")).roomsLabel)
        assertEquals("All rooms", d.copy(whereRooms = listOf("Kitchen", "All Rooms")).roomsLabel)
    }

    @Test
    fun `roomsLabel does not treat a trailing newline as padding, matching Swift`() {
        // Swift's CharacterSet.whitespaces has no newline in it, so "ALL ROOMS\n"
        // is not the sentinel there and must not be here either.
        val d = decodeDetector(detectorJson)
        assertEquals("ALL ROOMS\n", d.copy(whereRooms = listOf("ALL ROOMS\n")).roomsLabel)
    }

    @Test
    fun `alert trigger carries all three caregiver-facing label variants`() {
        assertEquals("Alerts the moment it happens", Detector.AlertTrigger.LEADING.caregiverLabel)
        assertEquals("Alerts when the activity's finished", Detector.AlertTrigger.TRAILING.caregiverLabel)
        assertEquals("Alerts once it's gone on too long", Detector.AlertTrigger.DURATION.caregiverLabel)
        assertEquals(
            "Alerts if it doesn't happen in your time window",
            Detector.AlertTrigger.ABSENCE.caregiverLabel,
        )
        assertEquals(
            "Never alerts — tracked quietly for daily insights",
            Detector.AlertTrigger.RECORD_ONLY.caregiverLabel,
        )

        assertEquals("When it happens", Detector.AlertTrigger.LEADING.caregiverShort)
        assertEquals("When it ends", Detector.AlertTrigger.TRAILING.caregiverShort)
        assertEquals("If it lasts too long", Detector.AlertTrigger.DURATION.caregiverShort)
        assertEquals("If it doesn't happen", Detector.AlertTrigger.ABSENCE.caregiverShort)
        assertEquals("Tracked quietly", Detector.AlertTrigger.RECORD_ONLY.caregiverShort)

        assertEquals("Fires as soon as it's seen.", Detector.AlertTrigger.LEADING.caregiverSub)
        assertEquals(
            "A single alert once the whole activity is over.",
            Detector.AlertTrigger.TRAILING.caregiverSub,
        )
        assertEquals(
            "Fires only after it has continued past your time limit.",
            Detector.AlertTrigger.DURATION.caregiverSub,
        )
        assertEquals(
            "Fires when the thing you're expecting is missing by your deadline.",
            Detector.AlertTrigger.ABSENCE.caregiverSub,
        )
        assertEquals(
            "Builds daily summaries (like sleep) without ever notifying anyone.",
            Detector.AlertTrigger.RECORD_ONLY.caregiverSub,
        )
    }

    @Test
    fun `record_only uses an underscored wire value in both directions`() {
        val d = decodeDetector(
            detectorJson.replace("\"alert_trigger\": \"leading\"", "\"alert_trigger\": \"record_only\""),
        )
        assertEquals(Detector.AlertTrigger.RECORD_ONLY, d.alertTrigger)
        assertEquals(
            "record_only",
            json.encodeToJsonElement(Detector.serializer(), d).jsonObject["alert_trigger"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `sensitivity display names capitalise the wire value`() {
        assertEquals("Low", Detector.Sensitivity.LOW.displayName)
        assertEquals("Standard", Detector.Sensitivity.STANDARD.displayName)
        assertEquals("High", Detector.Sensitivity.HIGH.displayName)
        assertEquals("Off", Detector.Sensitivity.OFF.displayName)
    }

    @Test
    fun `decodes a schedule config`() {
        val d = decodeDetector(
            detectorJson.replace(
                "\"schedule_config\": null",
                """
                "schedule_config": {
                  "cadence": "weekly",
                  "days_of_week": [0, 2],
                  "window_start": "08:00",
                  "window_end": "09:00",
                  "deadline": "09:30"
                }
                """.trimIndent(),
            ),
        )
        val sc = d.scheduleConfig!!
        assertEquals(Detector.ScheduleConfig.Cadence.WEEKLY, sc.cadence)
        assertEquals(listOf(0, 2), sc.daysOfWeek)
        assertEquals("08:00", sc.windowStart)
        assertEquals("09:00", sc.windowEnd)
        assertEquals("09:30", sc.deadline)
    }

    @Test
    fun `a partial schedule config leaves the missing pickers blank`() {
        val d = decodeDetector(detectorJson.replace("\"schedule_config\": null", "\"schedule_config\": {}"))
        val sc = d.scheduleConfig!!
        assertNull(sc.cadence)
        assertNull(sc.daysOfWeek)
        assertNull(sc.windowStart)
    }

    // =================================================================
    // DoorConfig — the legacy single-door fold
    // =================================================================

    private fun doorConfig(raw: String) =
        decodeDetector(detectorJson.replace("\"door_config\": null", "\"door_config\": $raw")).doorConfig!!

    @Test
    fun `door_config reads the multi-door array`() {
        val dc = doorConfig(
            """
            {
              "doors": [
                {"box": [0.1, 0.2, 0.3, 0.4], "label": "Front door", "source": "vlm"},
                {"box": [0.5, 0.2, 0.2, 0.4], "label": "Back door", "source": "manual"}
              ],
              "no_exit_door": false
            }
            """.trimIndent(),
        )
        assertEquals(2, dc.doors.size)
        assertEquals("Front door", dc.doors[0].label)
        assertEquals("manual", dc.doors[1].source)
        assertFalse(dc.noExitDoor)
    }

    @Test
    fun `door_config folds a legacy single door into a one-element array`() {
        val dc = doorConfig("""{"box": [0.1, 0.2, 0.3, 0.4], "label": "Front door", "source": "vlm"}""")
        val door = dc.doors.single()
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), door.box)
        assertEquals("Front door", door.label)
        assertEquals("vlm", door.source)
        assertFalse(dc.noExitDoor)
    }

    @Test
    fun `a legacy single door with no label or source still folds`() {
        val dc = doorConfig("""{"box": [0.1, 0.2, 0.3, 0.4]}""")
        assertEquals(1, dc.doors.size)
        assertNull(dc.doors.single().label)
        assertNull(dc.doors.single().source)
    }

    @Test
    fun `door_config with neither doors nor a legacy box is empty`() {
        assertEquals(emptyList<Detector.DoorConfig.Door>(), doorConfig("""{"no_exit_door": true}""").doors)
    }

    @Test
    fun `no_exit_door reads false when absent or null`() {
        assertFalse(doorConfig("""{"doors": []}""").noExitDoor)
        assertFalse(doorConfig("""{"doors": [], "no_exit_door": null}""").noExitDoor)
        assertTrue(doorConfig("""{"doors": [], "no_exit_door": true}""").noExitDoor)
    }

    @Test
    fun `door_config is written back in the current shape only`() {
        val dc = Detector.DoorConfig(
            doors = listOf(
                Detector.DoorConfig.Door(box = listOf(0.1, 0.2, 0.3, 0.4), label = "Front door", source = "vlm"),
            ),
            noExitDoor = false,
        )
        val obj = json.encodeToJsonElement(Detector.DoorConfig.serializer(), dc).jsonObject
        assertEquals(setOf("doors", "no_exit_door"), obj.keys)
    }

    @Test
    fun `a door omits label and source when they are unset`() {
        val obj = json.encodeToJsonElement(
            Detector.DoorConfig.Door.serializer(),
            Detector.DoorConfig.Door(box = listOf(0.1)),
        ).jsonObject
        assertEquals(setOf("box"), obj.keys)
    }

    // =================================================================
    // Wandering exit-door picker
    // =================================================================

    @Test
    fun `decodes the detected-doors response and builds a stable row id`() {
        val res = json.decodeFromString(
            DetectDoorsResponse.serializer(),
            """{"doors": [{"box": [0.1, 0.2, 0.3, 0.4], "label": "Front door"}]}""",
        )
        assertEquals("Front door-0.100,0.200,0.300,0.400", res.doors.single().id)
    }

    @Test
    fun `decodes per-camera wandering door config, including a null config`() {
        val rows = json.decodeFromString(
            ListSerializer(CameraWanderingDoor.serializer()),
            """
            [
              {"camera_id": "cam-1", "door_config": null},
              {"camera_id": "cam-2", "door_config": {"doors": [], "no_exit_door": true}}
            ]
            """.trimIndent(),
        )
        assertNull(rows[0].doorConfig)
        assertEquals("cam-1", rows[0].id)
        assertTrue(rows[1].doorConfig!!.noExitDoor)
    }

    @Test
    fun `a wandering-door body carries the doors and the no-exit flag`() {
        val obj = json.encodeToJsonElement(
            SetWanderingDoorRequest.serializer(),
            SetWanderingDoorRequest(
                doors = listOf(WanderingDoorInput(box = listOf(0.1, 0.2, 0.3, 0.4), source = "vlm")),
                noExitDoor = false,
            ),
        ).jsonObject
        assertEquals(setOf("doors", "no_exit_door"), obj.keys)
    }

    @Test
    fun `a wandering-door input omits an unset label`() {
        assertEquals(
            setOf("box", "source"),
            json.encodeToJsonElement(
                WanderingDoorInput.serializer(),
                WanderingDoorInput(box = listOf(0.1), source = "vlm"),
            ).jsonObject.keys,
        )
    }

    @Test
    fun `a detect-doors body uses the image_base64 wire name`() {
        assertEquals(
            setOf("image_base64"),
            json.encodeToJsonElement(DetectDoorsRequest.serializer(), DetectDoorsRequest("QUJD")).jsonObject.keys,
        )
    }

    // =================================================================
    // Detector requests
    // =================================================================

    @Test
    fun `a create-detector body sends every default explicitly`() {
        val obj = json.encodeToJsonElement(
            CreateDetectorRequest.serializer(),
            CreateDetectorRequest(name = "Stove left on", customPrompt = "tell me if the stove is on"),
        ).jsonObject

        assertEquals(
            setOf(
                "name", "custom_prompt", "description", "category", "alert_trigger",
                "is_sustained", "icon_name", "when_scope", "sensitivity", "is_on",
            ),
            obj.keys,
        )
        assertEquals("Custom detector", obj["description"]!!.jsonPrimitive.content)
        assertEquals("safety", obj["category"]!!.jsonPrimitive.content)
        assertEquals("leading", obj["alert_trigger"]!!.jsonPrimitive.content)
        assertEquals("false", obj["is_sustained"]!!.jsonPrimitive.content)
        assertEquals("eye", obj["icon_name"]!!.jsonPrimitive.content)
        assertEquals("ALWAYS", obj["when_scope"]!!.jsonPrimitive.content)
        assertEquals("standard", obj["sensitivity"]!!.jsonPrimitive.content)
        assertEquals("true", obj["is_on"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a create-detector body never sends where_scope`() {
        // The backend dropped where_scope on 2026-07-28 and rejects unknown
        // fields, so sending it fails every custom-detector create with a 422.
        val obj = json.encodeToJsonElement(
            CreateDetectorRequest.serializer(),
            CreateDetectorRequest(name = "n", customPrompt = "p", whereRooms = listOf("Kitchen")),
        ).jsonObject
        assertFalse(obj.containsKey("where_scope"))
        assertTrue(obj.containsKey("where_rooms"))
    }

    @Test
    fun `a create-detector body omits the optional timing fields when unset`() {
        val obj = json.encodeToJsonElement(
            CreateDetectorRequest.serializer(),
            CreateDetectorRequest(name = "n", customPrompt = "p"),
        ).jsonObject
        assertFalse(obj.containsKey("duration_seconds"))
        assertFalse(obj.containsKey("episode_gap_seconds"))
        assertFalse(obj.containsKey("schedule_config"))
        assertFalse(obj.containsKey("where_rooms"))
    }

    @Test
    fun `a create-as-draft body carries is_on false`() {
        val obj = json.encodeToJsonElement(
            CreateDetectorRequest.serializer(),
            CreateDetectorRequest(name = "n", customPrompt = "p", isOn = false),
        ).jsonObject
        assertEquals("false", obj["is_on"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an infer body omits an unset name`() {
        assertEquals(
            setOf("prompt"),
            json.encodeToJsonElement(
                InferDetectorRequest.serializer(),
                InferDetectorRequest(prompt = "watch the stove"),
            ).jsonObject.keys,
        )
        assertEquals(
            setOf("name", "prompt"),
            json.encodeToJsonElement(
                InferDetectorRequest.serializer(),
                InferDetectorRequest(name = "Stove", prompt = "p"),
            ).jsonObject.keys,
        )
    }

    @Test
    fun `decodes an infer response with a plan`() {
        val res = json.decodeFromString(
            InferDetectorResponse.serializer(),
            """
            {
              "alert_trigger": "duration",
              "is_sustained": true,
              "duration_seconds": 600,
              "category": "safety",
              "icon_name": "flame",
              "sensitivity": "high",
              "schedule_config": null,
              "episode_gap_seconds": 120,
              "plan": {
                "watches": "the stove",
                "fires": "after 10 minutes",
                "checks": "every few seconds",
                "listens": false
              }
            }
            """.trimIndent(),
        )
        assertEquals(Detector.AlertTrigger.DURATION, res.alertTrigger)
        assertEquals(600, res.durationSeconds)
        assertEquals(120, res.episodeGapSeconds)
        assertEquals("the stove", res.plan!!.watches)
        assertFalse(res.plan!!.listens)
    }

    @Test
    fun `decodes an infer response whose plan composition failed`() {
        val res = json.decodeFromString(
            InferDetectorResponse.serializer(),
            """
            {
              "alert_trigger": "leading",
              "is_sustained": false,
              "category": "routine",
              "icon_name": "eye",
              "sensitivity": "standard"
            }
            """.trimIndent(),
        )
        assertNull(res.plan)
        assertNull(res.durationSeconds)
        assertNull(res.scheduleConfig)
    }

    @Test
    fun `an update-detector body sends only the fields that were set`() {
        val obj = json.encodeToJsonElement(
            UpdateDetectorRequest.serializer(),
            UpdateDetectorRequest(
                whereRooms = listOf("ALL ROOMS"),
                sensitivity = Detector.Sensitivity.HIGH,
                isOn = true,
            ),
        ).jsonObject
        assertEquals(setOf("where_rooms", "sensitivity", "is_on"), obj.keys)
        assertEquals("high", obj["sensitivity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty update-detector body sends nothing`() {
        val obj = json.encodeToJsonElement(
            UpdateDetectorRequest.serializer(),
            UpdateDetectorRequest(),
        ).jsonObject
        assertEquals(emptySet<String>(), obj.keys)
    }

    @Test
    fun `decodes a detector-library entry`() {
        val entry = json.decodeFromString(
            DetectorLibraryEntry.serializer(),
            """
            {
              "key": "fall",
              "name": "Falls",
              "icon_name": "figure.fall",
              "description": "Someone falls",
              "category": "safety",
              "cohort": "adult",
              "recommended": true,
              "already_added": false
            }
            """.trimIndent(),
        )
        assertEquals("fall", entry.id)
        assertTrue(entry.recommended)
        assertFalse(entry.alreadyAdded)
        assertNull(entry.accuracyCaveat)
        assertFalse(entry.showsAccuracyCaveat)
    }

    @Test
    fun `an add-from-library body carries the catalog keys`() {
        assertEquals(
            setOf("keys"),
            json.encodeToJsonElement(
                AddFromLibraryRequest.serializer(),
                AddFromLibraryRequest(listOf("fall", "wandering")),
            ).jsonObject.keys,
        )
    }

    // =================================================================
    // DetectionEvent
    // =================================================================

    private val eventJson = """
        {
          "id": "e1111111-2222-3333-4444-555555555555",
          "patient_id": "11111111-2222-3333-4444-555555555555",
          "detector_id": "d1111111-2222-3333-4444-555555555555",
          "camera_id": "8a1f9c2e-0b3d-4e5f-9a6b-7c8d9e0f1a2b",
          "category": "safety",
          "source": "preset",
          "icon_name": "figure.fall",
          "title": "Possible fall",
          "location": "Kitchen",
          "occurred_at": "2026-05-21T16:42:00.182734+00:00",
          "has_clip": true,
          "narrative": "Someone appeared to fall near the counter.",
          "opened": false,
          "reviewed": false,
          "review_choice": null,
          "review_notes": null,
          "reviewed_at": null,
          "clip_storage_key": "incidents/2026/05/21/e111.mp4",
          "clip_suppressed": false,
          "clip_censored": false,
          "sensitivity_categories": null,
          "created_at": "2026-05-21T16:42:03.900000+00:00"
        }
    """.trimIndent()

    private fun decodeEvent(raw: String) = json.decodeFromString(DetectionEvent.serializer(), raw)

    @Test
    fun `decodes an event`() {
        val e = decodeEvent(eventJson)
        assertEquals("Possible fall", e.title)
        assertEquals(Detector.Category.SAFETY, e.category)
        assertEquals(Detector.Source.PRESET, e.source)
        assertEquals("Kitchen", e.location)
        assertTrue(e.hasClip)
        assertFalse(e.opened)
        assertFalse(e.reviewed)
        assertNull(e.reviewChoice)
        assertNull(e.reviewedAt)
        assertEquals("incidents/2026/05/21/e111.mp4", e.clipStorageKey)
        assertEquals(utcInstant("2026-05-21T16:42:00.182Z"), e.occurredAt)
    }

    @Test
    fun `an event with clip_suppressed missing decodes instead of being dropped`() {
        // A synthesized decoder throws keyNotFound here, and the whole event
        // would silently never reach the caregiver.
        assertFalse(decodeEvent(eventJson.replace("\"clip_suppressed\": false,", "")).clipSuppressed)
    }

    @Test
    fun `an event with clip_suppressed explicitly null decodes to false`() {
        assertFalse(
            decodeEvent(eventJson.replace("\"clip_suppressed\": false", "\"clip_suppressed\": null")).clipSuppressed,
        )
    }

    @Test
    fun `clip_suppressed true is carried through`() {
        assertTrue(
            decodeEvent(eventJson.replace("\"clip_suppressed\": false", "\"clip_suppressed\": true")).clipSuppressed,
        )
    }

    @Test
    fun `an event with clip_censored missing decodes instead of being dropped`() {
        assertFalse(decodeEvent(eventJson.replace("\"clip_censored\": false,", "")).clipCensored)
    }

    @Test
    fun `an event with clip_censored explicitly null decodes to false`() {
        assertFalse(
            decodeEvent(eventJson.replace("\"clip_censored\": false", "\"clip_censored\": null")).clipCensored,
        )
    }

    @Test
    fun `an event missing both clip flags at once still decodes`() {
        val stripped = eventJson
            .replace("\"clip_suppressed\": false,", "")
            .replace("\"clip_censored\": false,", "")
        val e = decodeEvent(stripped)
        assertFalse(e.clipSuppressed)
        assertFalse(e.clipCensored)
        assertEquals("Possible fall", e.title)
    }

    @Test
    fun `a censored event carries its sensitivity categories`() {
        val e = decodeEvent(
            eventJson
                .replace("\"clip_censored\": false", "\"clip_censored\": true")
                .replace("\"sensitivity_categories\": null", "\"sensitivity_categories\": [\"bathing\"]"),
        )
        assertTrue(e.clipCensored)
        assertEquals(listOf("bathing"), e.sensitivityCategories)
    }

    @Test
    fun `a reviewed event decodes its review fields`() {
        val e = decodeEvent(
            eventJson
                .replace("\"reviewed\": false", "\"reviewed\": true")
                .replace("\"review_choice\": null", "\"review_choice\": \"wrong\"")
                .replace("\"review_notes\": null", "\"review_notes\": \"she just sat down\"")
                .replace("\"reviewed_at\": null", "\"reviewed_at\": \"2026-05-21T17:00:00.000000+00:00\""),
        )
        assertTrue(e.reviewed)
        assertEquals(DetectionEvent.ReviewChoice.WRONG, e.reviewChoice)
        assertEquals("she just sat down", e.reviewNotes)
        assertEquals(utcInstant("2026-05-21T17:00:00Z"), e.reviewedAt)
    }

    @Test
    fun `an event realtime row with unknown columns still decodes`() {
        val raw = eventJson.dropLast(1) + """, "tick_id": 4412, "updated_at": null}"""
        assertEquals("Possible fall", decodeEvent(raw).title)
    }

    @Test
    fun `a missing required event field fails the decode`() {
        assertThrows(SerializationException::class.java) {
            decodeEvent(eventJson.replace("\"has_clip\": true,", ""))
        }
    }

    // =================================================================
    // DetectionEvent — the time labels
    // =================================================================

    private val utc = ZoneId.of("UTC")

    private fun eventAt(occurred: String) = decodeEvent(
        eventJson.replace(
            "\"occurred_at\": \"2026-05-21T16:42:00.182734+00:00\"",
            "\"occurred_at\": \"$occurred\"",
        ),
    )

    @Test
    fun `time shows a 12-hour clock for an event earlier today`() {
        val e = eventAt("2026-05-21T16:42:00+00:00")
        assertEquals("4:42 PM", e.timeLabel(utcInstant("2026-05-21T20:00:00Z"), utc))
    }

    @Test
    fun `time labels yesterday explicitly`() {
        val e = eventAt("2026-05-21T16:42:00+00:00")
        assertEquals("Yesterday 4:42 PM", e.timeLabel(utcInstant("2026-05-22T01:00:00Z"), utc))
    }

    @Test
    fun `an older event shows the time alone, with no weekday`() {
        // 2026-05-21 is a Thursday, and the label deliberately does not say so:
        // the "3d ago" chip beside it already carries recency, and a weekday was
        // a second answer to a question already answered (iOS 9d12c07).
        val e = eventAt("2026-05-21T16:42:00+00:00")
        assertEquals("4:42 PM", e.timeLabel(utcInstant("2026-05-24T20:00:00Z"), utc))
    }

    @Test
    fun `an event beyond a week still shows the time alone`() {
        // No calendar-date fallback either — same reasoning, and the chip
        // switches to "11d ago" on its own.
        val e = eventAt("2026-05-21T16:42:00+00:00")
        assertEquals("4:42 PM", e.timeLabel(utcInstant("2026-06-01T20:00:00Z"), utc))
    }

    @Test
    fun `morning and midnight read correctly on a 12-hour clock`() {
        // The two h:mm a cases that a 24-hour pattern would have hidden.
        assertEquals(
            "9:05 AM",
            eventAt("2026-05-21T09:05:00+00:00").timeLabel(utcInstant("2026-05-21T20:00:00Z"), utc),
        )
        assertEquals(
            "12:30 AM",
            eventAt("2026-05-21T00:30:00+00:00").timeLabel(utcInstant("2026-05-21T20:00:00Z"), utc),
        )
    }

    @Test
    fun `ago counts up through minutes, hours and days`() {
        val e = eventAt("2026-05-21T16:42:00+00:00")
        val occurred = utcInstant("2026-05-21T16:42:00Z")
        assertEquals("just now", e.agoLabel(occurred + 30.seconds))
        assertEquals("1 min ago", e.agoLabel(occurred + 60.seconds))
        assertEquals("42 min ago", e.agoLabel(occurred + 42.minutes))
        assertEquals("2h ago", e.agoLabel(occurred + 2.hours))
        assertEquals("23h ago", e.agoLabel(occurred + 23.hours))
        assertEquals("1d ago", e.agoLabel(occurred + 25.hours))
        assertEquals("3d ago", e.agoLabel(occurred + 72.hours))
    }

    // =================================================================
    // Event list, filter, and update
    // =================================================================

    @Test
    fun `decodes a paged event list`() {
        val page = json.decodeFromString(
            EventListResponse.serializer(),
            """{"items": [$eventJson], "next_cursor": "MjAyNi0wNS0yMQ=="}""",
        )
        assertEquals(1, page.items.size)
        assertEquals("MjAyNi0wNS0yMQ==", page.nextCursor)
    }

    @Test
    fun `the last page has no cursor, whether null or absent`() {
        assertNull(
            json.decodeFromString(
                EventListResponse.serializer(),
                """{"items": [], "next_cursor": null}""",
            ).nextCursor,
        )
        assertNull(json.decodeFromString(EventListResponse.serializer(), """{"items": []}""").nextCursor)
    }

    @Test
    fun `event filters expose wire values and display names`() {
        assertEquals(listOf("all", "unreviewed", "reviewed"), EventFilter.entries.map { it.id })
        assertEquals(listOf("All", "Unreviewed", "Reviewed"), EventFilter.entries.map { it.displayName })
        assertEquals("\"unreviewed\"", json.encodeToString(EventFilter.serializer(), EventFilter.UNREVIEWED))
    }

    @Test
    fun `an update-event body sends only the fields that were set`() {
        assertEquals(
            setOf("opened"),
            json.encodeToJsonElement(
                UpdateEventRequest.serializer(),
                UpdateEventRequest(opened = true),
            ).jsonObject.keys,
        )
        val review = json.encodeToJsonElement(
            UpdateEventRequest.serializer(),
            UpdateEventRequest(
                reviewed = true,
                reviewChoice = DetectionEvent.ReviewChoice.CORRECT,
                reviewNotes = "yes, she fell",
            ),
        ).jsonObject
        assertEquals(setOf("reviewed", "review_choice", "review_notes"), review.keys)
        assertEquals("correct", review["review_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty update-event body sends nothing`() {
        val obj = json.encodeToJsonElement(UpdateEventRequest.serializer(), UpdateEventRequest()).jsonObject
        assertEquals(emptySet<String>(), obj.keys)
    }

    // =================================================================
    // ClipURL
    // =================================================================

    @Test
    fun `decodes a signed clip url`() {
        val res = json.decodeFromString(
            ClipURLResponse.serializer(),
            """
            {
              "url": "https://storage.example.com/incident-clips/e111.mp4?token=abc",
              "expires_in_seconds": 600
            }
            """.trimIndent(),
        )
        assertEquals("https://storage.example.com/incident-clips/e111.mp4?token=abc", res.url)
        assertEquals(600, res.expiresInSeconds)
    }
}
