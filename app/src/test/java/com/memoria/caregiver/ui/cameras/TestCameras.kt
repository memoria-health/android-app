@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.Detector
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Builders for the two camera screens' fixtures.
 *
 * Both models carry a dozen required fields, and a test that spells all of them
 * out buries the one field it is actually about. Every default here is the
 * boring case — a paired, online, connected wall phone and an on, compiled
 * detector — so each test names only its own variable.
 */

internal fun testCamera(
    id: String = "cam-1",
    room: String = "Living room",
    displayId: Int = 1,
    iconName: String = "tv",
    online: Boolean = true,
    paired: Boolean = true,
    sourceType: Camera.SourceType = Camera.SourceType.PHONE,
    desiredState: Camera.DesiredState = Camera.DesiredState.CONNECTED,
    disabled: Boolean = false,
    aiPaused: Boolean = false,
    appPresent: Boolean? = null,
    livekitIdentity: String? = "cam_1a2b3c4d",
    privacyEnabled: Boolean = false,
    roomClass: Camera.RoomClass? = null,
): Camera = Camera(
    disabled = disabled,
    aiPaused = aiPaused,
    sourceType = sourceType,
    roomClass = roomClass,
    privacyEnabled = privacyEnabled,
    desiredState = desiredState,
    id = id,
    patientId = "pat-1",
    displayId = displayId,
    room = room,
    iconName = iconName,
    online = online,
    livekitIdentity = livekitIdentity,
    createdAt = Instant.fromEpochSeconds(1_760_000_000),
    paired = paired,
    appPresent = appPresent,
)

internal fun testDetector(
    id: String = "det-1",
    name: String = "Falls",
    description: String = "Someone on the floor",
    identifier: String? = "fall",
    isOn: Boolean = true,
    iconName: String = "figure.fall",
): Detector = Detector(
    id = id,
    patientId = "pat-1",
    name = name,
    iconName = iconName,
    description = description,
    category = Detector.Category.SAFETY,
    source = Detector.Source.PRESET,
    alertTrigger = Detector.AlertTrigger.LEADING,
    isSustained = false,
    whenScope = "ALWAYS",
    sensitivity = Detector.Sensitivity.STANDARD,
    isOn = isOn,
    identifier = identifier,
    // Required on the model; a fixed instant so nothing in these tests depends
    // on the wall clock.
    createdAt = Instant.fromEpochSeconds(1_779_401_880L),
    updatedAt = Instant.fromEpochSeconds(1_779_401_880L),
)

internal fun testDoor(label: String? = null): Detector.DoorConfig.Door =
    Detector.DoorConfig.Door(box = listOf(0.1, 0.2, 0.3, 0.4), label = label, source = "vlm")
