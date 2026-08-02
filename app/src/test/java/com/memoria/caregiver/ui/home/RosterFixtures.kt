@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.home

import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.Patient
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Fixtures for the roster tests.
 *
 * `Patient` and `DetectionEvent` mirror backend response schemas, so both carry
 * a dozen required fields that no roster test cares about. Defaulting them here
 * keeps each test naming only the field it is actually about — which is the
 * difference between a test that reads as a statement and one that reads as a
 * JSON payload.
 *
 * `dateOfBirth` defaults to null on purpose: `Patient.age` resolves a real DOB
 * against `LocalDate.now()`, so any fixed date would make the rendered age
 * change once a year. No roster test asserts the age.
 */

private val FIXED_TIME = Instant.fromEpochSeconds(1_750_000_000L)

internal fun patient(
    id: String = "p1",
    preferredName: String = "Ada",
    fullName: String = "Ada Lovelace",
    camerasPaused: Boolean = false,
): Patient = Patient(
    id = id,
    caregiverId = "c1",
    fullName = fullName,
    preferredName = preferredName,
    relation = Patient.Relation.PARENT,
    state = Patient.State.CALM,
    statusLine = "",
    camerasPaused = camerasPaused,
    emailAlertsEnabled = false,
    emailAlertRecipients = emptyList(),
    dataCollectionEnabled = false,
    createdAt = FIXED_TIME,
    updatedAt = FIXED_TIME,
)

internal fun event(
    id: String = "e1",
    patientId: String = "p1",
    category: Detector.Category = Detector.Category.ROUTINE,
    opened: Boolean = false,
): DetectionEvent = DetectionEvent(
    id = id,
    patientId = patientId,
    category = category,
    source = Detector.Source.PRESET,
    iconName = "",
    title = "",
    location = "",
    occurredAt = FIXED_TIME,
    hasClip = false,
    narrative = "",
    opened = opened,
    reviewed = false,
    createdAt = FIXED_TIME,
)

/** No patient photo is ever resolvable in a test; the initials placeholder shows. */
internal val noPhotos: suspend (String) -> String? = { null }
