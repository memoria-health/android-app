@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.events

import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One event, with everything the tests do not care about pinned to something
 * neutral.
 *
 * `occurredAt` deliberately defaults to "a moment ago" rather than a fixed
 * date: `DetectionEvent.time` and `.ago` read the wall clock, so no assertion
 * in this package touches them. They have their own clock-injected tests in
 * `DeviceModelsTest`.
 */
internal fun sampleEvent(
    id: String = "e1",
    title: String = "Possible fall",
    location: String = "Kitchen",
    category: Detector.Category = Detector.Category.SAFETY,
    source: Detector.Source = Detector.Source.PRESET,
    iconName: String = "figure.fall",
    narrative: String = "Someone appeared to fall near the counter.",
    opened: Boolean = false,
    reviewed: Boolean = false,
    hasClip: Boolean = true,
    clipSuppressed: Boolean = false,
    clipCensored: Boolean = false,
): DetectionEvent = DetectionEvent(
    id = id,
    patientId = "p1",
    category = category,
    source = source,
    iconName = iconName,
    title = title,
    location = location,
    occurredAt = Instant.fromEpochSeconds(1_780_000_000),
    hasClip = hasClip,
    narrative = narrative,
    opened = opened,
    reviewed = reviewed,
    clipSuppressed = clipSuppressed,
    clipCensored = clipCensored,
    createdAt = Instant.fromEpochSeconds(1_780_000_000),
)
