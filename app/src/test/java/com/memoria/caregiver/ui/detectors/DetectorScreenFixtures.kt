@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.DetectorLibraryEntry
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Shared builders for the detector-screen tests.
 *
 * Named after this unit rather than something generic, because several units
 * write into `com.memoria.caregiver.ui.detectors` and a fixture file called
 * `Fixtures` would collide.
 */
internal object DetectorScreenFixtures {

    private val created = Instant.parse("2026-05-20T10:00:00Z")

    fun detector(
        id: String = "11111111-1111-1111-1111-111111111111",
        name: String = "Left the house",
        description: String = "Watches the exit doors.",
        iconName: String = "door.left.hand.open",
        category: Detector.Category = Detector.Category.SAFETY,
        identifier: String? = "wandering",
        isOn: Boolean = true,
        whenScope: String = "ALWAYS",
        whereRooms: List<String>? = null,
        accuracyCaveat: Boolean? = null,
    ) = Detector(
        id = id,
        patientId = "22222222-2222-2222-2222-222222222222",
        name = name,
        iconName = iconName,
        description = description,
        category = category,
        source = Detector.Source.PRESET,
        alertTrigger = Detector.AlertTrigger.LEADING,
        isSustained = false,
        whereRooms = whereRooms,
        whenScope = whenScope,
        sensitivity = Detector.Sensitivity.STANDARD,
        isOn = isOn,
        identifier = identifier,
        accuracyCaveat = accuracyCaveat,
        createdAt = created,
        updatedAt = created,
    )

    fun entry(
        key: String,
        name: String = key.replaceFirstChar { it.uppercase() },
        description: String = "Watches for $key.",
        recommended: Boolean = false,
        alreadyAdded: Boolean = false,
        accuracyCaveat: Boolean? = null,
    ) = DetectorLibraryEntry(
        key = key,
        name = name,
        iconName = "eye",
        description = description,
        category = Detector.Category.SAFETY,
        cohort = "both",
        recommended = recommended,
        alreadyAdded = alreadyAdded,
        accuracyCaveat = accuracyCaveat,
    )

    fun door(source: String = "vlm") =
        Detector.DoorConfig.Door(box = listOf(0.1, 0.2, 0.12, 0.20), label = "front door", source = source)
}
