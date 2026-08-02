@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.live

import com.memoria.caregiver.data.model.Camera
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Camera builders for the Live tests.
 *
 * The model carries a dozen required fields and a test that spells them all out
 * buries the one field it is actually about. Every default here is the boring
 * case — a paired, online, connected wall phone with a track-able identity — so
 * each test names only its own variable. Same shape as
 * `ui/cameras/TestCameras.kt`, kept separate because a porting unit owns its own
 * fixtures and these defaults differ (this one always has an identity, because a
 * camera with none can never reach the carousel).
 */
internal fun liveCamera(
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

/**
 * An iPhone sensor whose Memoria Sensor app has stopped polling: not online and
 * the heartbeat says the app is gone. The one state a remote Reconnect cannot
 * fix.
 */
internal fun appClosedCamera(
    id: String = "cam-closed",
    room: String = "Kitchen",
    displayId: Int = 2,
    desiredState: Camera.DesiredState = Camera.DesiredState.CONNECTED,
    disabled: Boolean = false,
): Camera = liveCamera(
    id = id,
    room = room,
    displayId = displayId,
    online = false,
    appPresent = false,
    desiredState = desiredState,
    disabled = disabled,
)
