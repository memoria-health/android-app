package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.livekit.LiveKitConnectionState
import com.memoria.caregiver.data.livekit.LiveKitIdentity
import com.memoria.caregiver.data.livekit.LiveRoom
import com.memoria.caregiver.data.model.DetectedDoor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Base64

/**
 * How a frame grab ended. Port of `WanderingDoorCache.DoorLoadOutcome`.
 *
 * [Blocked] and [Failed] are different on purpose, and the difference is the
 * whole reason this is not a nullable frame. Blocked means the caregiver can fix
 * it — the sensor is asleep, wake it — and the screen says so with no Retry.
 * Failed means something went wrong on our side and retrying is worth a try.
 */
sealed interface DoorLoadOutcome {
    data object Ok : DoorLoadOutcome
    data class Blocked(val message: String) : DoorLoadOutcome
    data class Failed(val message: String) : DoorLoadOutcome
}

/**
 * The frame the exit-door picker draws on, and the doors the VLM found in it.
 *
 * Port of `Features/Patient/Settings/WanderingDoorCache.swift`.
 *
 * ## Why a cache and not a plain fetch
 *
 * Reaching a frame is expensive: it may need a LiveKit token, a room connect and
 * a wait for the publisher. The picker is opened, cancelled and reopened while a
 * caregiver decides, and doing all that again each time would put a multi-second
 * spinner in front of a screen they were just looking at. Doors cost a VLM call
 * on top, which is worse.
 *
 * ## The room rule
 *
 * If the Live tab already has the patient's room up, this **reuses it and does
 * not disconnect afterwards**. If it opens one itself, it tears that one down.
 * Getting this wrong in either direction is user-visible: disconnecting a room
 * we did not open kills the Live tab's video underneath the caregiver, and
 * leaving one we did open holds a subscription for the rest of the session.
 *
 * Not thread-safe. Like the iOS original it is used from the main thread.
 */
class WanderingDoorCache(
    private val liveKit: LiveRoom,
    /**
     * `POST /v1/detect-doors`. Taken as a function rather than the API client so
     * this class needs no HTTP layer to be exercised — the same seam
     * `LiveViewModel` uses for the room.
     */
    private val detectDoors: suspend (imageBase64: String) -> List<DetectedDoor>,
    private val mintViewerToken: suspend () -> Pair<String, String>,
    private val captureJpeg: suspend (identity: String) -> ByteArray?,
    /**
     * Whether [liveKit] currently holds a video track for an identity.
     *
     * A predicate rather than a read of `videoTracksByIdentity`, for the same
     * reason the three calls above are lambdas: `VideoTrack` is an abstract
     * LiveKit class that cannot be instantiated off a device, so a map typed
     * with it cannot be populated by a test. The only thing this class does with
     * a track is ask whether one exists, so that is what it takes. The app wires
     * it to exactly the map read it replaces.
     */
    private val hasVideoTrack: (identity: String) -> Boolean,
    private val trackWaitAttempts: Int = TRACK_WAIT_ATTEMPTS,
    private val trackWaitIntervalMillis: Long = TRACK_WAIT_INTERVAL_MILLIS,
) {

    private val frames = mutableMapOf<String, ByteArray>()
    private val doors = mutableMapOf<String, List<DetectedDoor>>()

    /** The cached JPEG for [cameraId], or null if none has been grabbed. */
    fun cachedFrame(cameraId: String): ByteArray? = frames[cameraId]

    /** The cached doors for [cameraId]. Empty is a real answer, not "not asked". */
    fun cachedDoors(cameraId: String): List<DetectedDoor>? = doors[cameraId]

    /**
     * Grab a frame if there is not one already. Port of `ensureFrame`.
     */
    suspend fun ensureFrame(
        cameraId: String,
        patientId: String,
        identity: String,
    ): DoorLoadOutcome {
        if (frames[cameraId] != null) return DoorLoadOutcome.Ok
        return grab(cameraId = cameraId, patientId = patientId, identity = identity)
    }

    /**
     * Force a fresh capture and drop the doors that went with the old one. The
     * Rescan button. Port of `reloadFrame`.
     *
     * Dropping the doors matters: they are boxes in the coordinate space of a
     * specific picture, and keeping them across a re-grab would draw the old
     * scene's doorways over the new scene.
     */
    suspend fun reloadFrame(
        cameraId: String,
        patientId: String,
        identity: String,
    ): DoorLoadOutcome {
        frames.remove(cameraId)
        doors.remove(cameraId)
        return grab(cameraId = cameraId, patientId = patientId, identity = identity)
    }

    /**
     * Detect doors on the already-captured frame. Port of `ensureDoors`.
     *
     * Returns an empty list when there is no frame or the call fails, and caches
     * that: the caregiver tap-places doors instead, which is a complete way to
     * use this screen rather than a degraded one. Re-asking on every reopen
     * would spend a VLM call to be told the same thing.
     */
    suspend fun ensureDoors(cameraId: String): List<DetectedDoor> {
        doors[cameraId]?.let { return it }
        val frame = frames[cameraId] ?: return emptyList()
        val found = try {
            // java.util.Base64, not android.util.Base64. Both exist at
            // minSdk 26 and produce the same string, but the Android one is a
            // throwing stub on a plain JVM — so with it this class could not be
            // tested without Robolectric, and worse, the `catch` below turned
            // that stub exception into "the VLM found no doors". A swallowed
            // programming error that looks like a valid answer is the failure
            // mode this whole file is trying to avoid.
            detectDoors(Base64.getEncoder().encodeToString(frame))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            emptyList()
        }
        doors[cameraId] = found
        return found
    }

    private suspend fun grab(
        cameraId: String,
        patientId: String,
        identity: String,
    ): DoorLoadOutcome {
        val roomName = LiveKitIdentity.roomName(patientId)
        var weConnected = false

        if (liveKit.currentRoom.value != roomName ||
            liveKit.state.value != LiveKitConnectionState.Connected
        ) {
            try {
                // VIEWER, not broadcaster. This screen never publishes, and a
                // publish grant handed out for a still capture is a grant that
                // outlives the reason for it.
                val (url, token) = mintViewerToken()
                liveKit.connect(url = url, token = token, roomName = roomName)
                weConnected = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                return DoorLoadOutcome.Failed(CONNECT_FAILED)
            }
        }

        // The publisher may not have been subscribed yet even on a room that was
        // already up: this camera might be one the Live tab has not rendered.
        // A `for` with `break`, not `repeat` with `return@repeat` — the latter
        // continues to the next iteration rather than leaving the loop, so it
        // would sit through all six seconds after the track had already arrived.
        var hasTrack = false
        for (attempt in 0 until trackWaitAttempts) {
            if (hasVideoTrack(identity)) {
                hasTrack = true
                break
            }
            delay(trackWaitIntervalMillis)
        }
        if (!hasTrack) {
            if (weConnected) liveKit.disconnect()
            return DoorLoadOutcome.Blocked(NOT_STREAMING)
        }

        val jpeg = try {
            captureJpeg(identity)
        } catch (cancellation: CancellationException) {
            if (weConnected) liveKit.disconnect()
            throw cancellation
        } catch (error: Throwable) {
            null
        }
        // Before the null check, so every path that opened a room closes it.
        if (weConnected) liveKit.disconnect()
        if (jpeg == null) return DoorLoadOutcome.Failed(CAPTURE_FAILED)

        frames[cameraId] = jpeg
        return DoorLoadOutcome.Ok
    }

    companion object {
        /** iOS: `for _ in 0..<30` with a 200ms sleep. */
        const val TRACK_WAIT_ATTEMPTS: Int = 30
        const val TRACK_WAIT_INTERVAL_MILLIS: Long = 200L

        /** Carried verbatim from the Swift, so both platforms say the same thing. */
        const val CONNECT_FAILED: String =
            "Couldn't connect to the camera. Check your connection and try again."

        const val NOT_STREAMING: String =
            "This sensor isn't streaming right now. Open its Live view to wake it, then try again."

        const val CAPTURE_FAILED: String =
            "Couldn't capture a frame from the camera. Please try again."
    }
}
