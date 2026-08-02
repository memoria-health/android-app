@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.settings

import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.model.Patient
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Fixtures shared by the three settings test classes.
 *
 * `Caregiver` and `Patient` carry a lot of required fields that these screens
 * never read, so building one inline in every test would bury the two or three
 * values a test actually cares about.
 */

private val EPOCH = Instant.fromEpochSeconds(0)

internal fun caregiver(
    displayName: String = "Aryaman Darda",
    email: String = "aryaman@memoria.care",
    phone: String? = "+15551234567",
): Caregiver = Caregiver(
    id = "cg-1",
    displayName = displayName,
    email = email,
    phone = phone,
    createdAt = EPOCH,
    updatedAt = EPOCH,
)

internal fun patient(
    id: String = "p-1",
    fullName: String = "Margaret Holloway",
    preferredName: String = "Peggy",
    relation: Patient.Relation = Patient.Relation.PARENT,
): Patient = Patient(
    id = id,
    caregiverId = "cg-1",
    fullName = fullName,
    preferredName = preferredName,
    relation = relation,
    state = Patient.State.CALM,
    statusLine = "All quiet",
    camerasPaused = false,
    emailAlertsEnabled = true,
    emailAlertRecipients = emptyList(),
    dataCollectionEnabled = false,
    createdAt = EPOCH,
    updatedAt = EPOCH,
)
