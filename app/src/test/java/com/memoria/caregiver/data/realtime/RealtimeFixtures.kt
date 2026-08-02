@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.realtime

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.remote.NemaraJson
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject

/**
 * Rows for the Realtime tests.
 *
 * Payloads are built by ENCODING a real model through the app's own [NemaraJson]
 * rather than by hand-writing JSON. That keeps the tests exercising the decode
 * path the handlers actually use — including `CameraSerializer`, which has to
 * hydrate a camera from a raw database row — instead of a hand-rolled shape that
 * could drift from the wire.
 */
internal object Fx {

    val T: Instant = Instant.fromEpochSeconds(1_700_000_000)

    fun patient(id: String, preferredName: String = "Ann"): Patient = Patient(
        id = id,
        caregiverId = "caregiver-1",
        fullName = "Ann Example",
        preferredName = preferredName,
        relation = Patient.Relation.PARENT,
        state = Patient.State.CALM,
        statusLine = "All calm",
        camerasPaused = false,
        emailAlertsEnabled = false,
        emailAlertRecipients = emptyList(),
        dataCollectionEnabled = false,
        createdAt = T,
        updatedAt = T,
    )

    fun camera(
        id: String,
        patientId: String = "p1",
        displayId: Int = 1,
        online: Boolean = false,
        desiredState: Camera.DesiredState = Camera.DesiredState.CONNECTED,
        disabled: Boolean = false,
        paired: Boolean = true,
        livekitIdentity: String? = "cam_abcd1234",
        room: String = "Living room",
    ): Camera = Camera(
        disabled = disabled,
        aiPaused = false,
        desiredState = desiredState,
        id = id,
        patientId = patientId,
        displayId = displayId,
        room = room,
        iconName = "sofa",
        online = online,
        livekitIdentity = livekitIdentity,
        createdAt = T,
        paired = paired,
    )

    fun detector(id: String, patientId: String = "p1", isOn: Boolean = true): Detector = Detector(
        id = id,
        patientId = patientId,
        name = "Fall",
        iconName = "figure.fall",
        description = "Someone falls",
        category = Detector.Category.SAFETY,
        source = Detector.Source.PRESET,
        alertTrigger = Detector.AlertTrigger.LEADING,
        isSustained = false,
        whenScope = "always",
        sensitivity = Detector.Sensitivity.STANDARD,
        isOn = isOn,
        createdAt = T,
        updatedAt = T,
    )

    fun event(id: String, patientId: String = "p1", title: String = "Fall"): DetectionEvent =
        DetectionEvent(
            id = id,
            patientId = patientId,
            category = Detector.Category.SAFETY,
            source = Detector.Source.PRESET,
            iconName = "figure.fall",
            title = title,
            location = "Living room",
            occurredAt = T,
            hasClip = false,
            narrative = "Someone fell",
            opened = false,
            reviewed = false,
            createdAt = T,
        )

    fun <T> row(serializer: SerializationStrategy<T>, value: T): JsonObject =
        NemaraJson.json.encodeToJsonElement(serializer, value) as JsonObject

    fun insert(camera: Camera) = RowChange.Insert(row(Camera.serializer(), camera))

    fun update(camera: Camera) = RowChange.Update(row(Camera.serializer(), camera))

    fun insert(patient: Patient) = RowChange.Insert(row(Patient.serializer(), patient))

    fun update(patient: Patient) = RowChange.Update(row(Patient.serializer(), patient))

    fun insert(detector: Detector) = RowChange.Insert(row(Detector.serializer(), detector))

    fun update(detector: Detector) = RowChange.Update(row(Detector.serializer(), detector))

    fun insert(event: DetectionEvent) = RowChange.Insert(row(DetectionEvent.serializer(), event))

    fun update(event: DetectionEvent) = RowChange.Update(row(DetectionEvent.serializer(), event))

    /** Postgres sends only the replica-identity columns on a delete. */
    fun delete(id: String) = RowChange.Delete(
        NemaraJson.json.parseToJsonElement("""{"id":"$id"}""") as JsonObject,
    )
}

internal class FakeCache(
    override var patients: List<Patient> = emptyList(),
    override var camerasByPatient: Map<String, List<Camera>> = emptyMap(),
    override var detectorsByPatient: Map<String, List<Detector>> = emptyMap(),
    override var eventsByPatient: Map<String, List<DetectionEvent>> = emptyMap(),
) : RealtimeCache

internal class RecordingLog : RealtimeLog {
    val lines = mutableListOf<String>()

    override fun debug(message: String) {
        lines += message
    }
}

/** A clock the test moves by hand, so the 10-second window can be crossed instantly. */
internal class TestClock(var millis: Long = 0L) : () -> Long {
    override fun invoke(): Long = millis
}
