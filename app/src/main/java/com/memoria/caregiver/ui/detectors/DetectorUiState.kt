package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.model.CreateDetectorRequest
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.DetectorLibraryEntry
import com.memoria.caregiver.data.model.DetectorPlan
import com.memoria.caregiver.data.model.InferDetectorResponse
import java.time.LocalTime

/**
 * State the three detector screens render from, and the pure logic they derive.
 *
 * Same contract as `AuthUiState.kt`: **screens are pure `@Composable` functions
 * over these types.** They hold no ViewModel, do no I/O, and know nothing about
 * the API client. iOS keeps this state in `@State` on each view and reaches the
 * API through `@Environment`; the shape below is the same state, hoisted.
 *
 * Everything a caller would otherwise have to re-derive — which keys to add,
 * which to delete, how a when-scope serializes, what the create body looks like
 * — is a pure function here, so the host cannot quietly invent a second version
 * of a rule the backend parses.
 */

// ===========================================================================
// Shared string helpers
// ===========================================================================
//
// `trimmingSwiftWhitespaces` and `trimmingSwiftWhitespacesAndNewlines` are
// declared once for the whole package, in `DetectorDetailState.kt`. This file
// used to carry its own identical pair, which is a redeclaration in the same
// package and would not compile. **They belong in one shared file** — two units
// reaching independently for the same Swift-exact trim is the signal — but
// neither is this unit's file to create.
//
// Both mirror Foundation's CharacterSets: Kotlin's own `trim()` strips
// everything `Char.isWhitespace()` matches, which is a wider set than Swift's,
// so a value ending in a separator would compare differently across platforms.

// ===========================================================================
// Detection library — DetectorLibraryView.swift, PARITY.md §22
// ===========================================================================

/**
 * The two modes the one library screen serves.
 *
 * [Add] is the Detectors tab: entries the patient already has render locked and
 * cannot be re-added. [Onboarding] runs right after a patient is created: the
 * recommended and already-seeded set is pre-checked, and confirming performs a
 * full reconcile rather than a plain add.
 */
enum class DetectorLibraryMode { Add, Onboarding }

data class DetectorLibraryState(
    val mode: DetectorLibraryMode = DetectorLibraryMode.Add,
    val entries: List<DetectorLibraryEntry> = emptyList(),
    val selectedKeys: Set<String> = emptySet(),
    val query: String = "",
    val isLoading: Boolean = true,
    /** Rendered verbatim under "Couldn't load the detection library." */
    val loadError: String? = null,
    val isSubmitting: Boolean = false,
    /** Rendered verbatim above the footer button. */
    val submitError: String? = null,
    /**
     * The "which cameras should watch for wandering?" step, shown after the
     * detectors are created and before the screen closes.
     *
     * Wandering is the one preset that is per-camera: it needs to know which
     * rooms lead outside. Asking at the moment it is added is the only point
     * where the caregiver has the context — afterwards it is a settings screen
     * they have no reason to visit, and the exit-door section stays invisible
     * on every camera until they do (see `wanderingWatchesRoom`).
     */
    val wanderingRoomPrompt: Boolean = false,
    /** Distinct, sorted room names from the patient's cameras. */
    val wanderingRoomOptions: List<String> = emptyList(),
    val wanderingRoomsSelected: Set<String> = emptySet(),
) {
    /** Onboarding can skip the whole step; add-mode just closes. */
    val leadingActionTitle: String
        get() = if (mode == DetectorLibraryMode.Onboarding) "Skip" else "Cancel"

    /** Search filters name + description, case-insensitively. */
    val filteredEntries: List<DetectorLibraryEntry>
        get() {
            val q = query.trimmingSwiftWhitespaces().lowercase()
            if (q.isEmpty()) return entries
            return entries.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }

    val recommendedEntries: List<DetectorLibraryEntry>
        get() = filteredEntries.filter { it.recommended }

    val otherEntries: List<DetectorLibraryEntry>
        get() = filteredEntries.filter { !it.recommended }

    /** Keys the patient already has, whatever the current selection says. */
    private val presentKeys: Set<String>
        get() = entries.filter { it.alreadyAdded }.map { it.key }.toSet()

    /** Selected keys that are not already on the patient. */
    val newlySelectedCount: Int
        get() = (selectedKeys - presentKeys).size

    /**
     * In add-mode an already-added detector cannot be re-added. In onboarding it
     * stays toggleable, because unchecking it is how the caregiver removes it.
     */
    fun isLocked(entry: DetectorLibraryEntry): Boolean =
        mode == DetectorLibraryMode.Add && entry.alreadyAdded

    fun isSelected(entry: DetectorLibraryEntry): Boolean = selectedKeys.contains(entry.key)

    val footerTitle: String
        get() = when {
            isSubmitting -> "Saving…"
            mode == DetectorLibraryMode.Onboarding -> "Continue"
            newlySelectedCount == 0 -> "Add detectors"
            newlySelectedCount == 1 -> "Add 1 detector"
            else -> "Add $newlySelectedCount detectors"
        }

    /**
     * Add-mode needs at least one new pick. Onboarding's Continue is always
     * available, because it may legitimately be "no change, advance".
     */
    val footerDisabled: Boolean
        get() = mode == DetectorLibraryMode.Add && newlySelectedCount == 0

    /** Catalog keys to POST to `from-library`. Both modes. */
    val keysToAdd: List<String>
        get() = (selectedKeys - presentKeys).toList()

    /**
     * Catalog keys present on the patient that the caregiver has unchecked, so
     * the onboarding reconcile deletes them. Empty in add-mode, which never
     * removes anything.
     */
    val keysToRemove: Set<String>
        get() = if (mode == DetectorLibraryMode.Onboarding) presentKeys - selectedKeys else emptySet()
}

/**
 * The fail-loud message for the onboarding reconcile.
 *
 * The reconcile only ever touches catalog detectors, matched by `identifier`.
 * If the library reported a key as present and no detector row carries that
 * identifier, the host must surface this rather than silently skipping the
 * delete — otherwise the caregiver unchecks something and it stays (PARITY.md
 * §22). The copy lives here so the host cannot paraphrase it.
 */
fun missingDetectorForKeyMessage(key: String): String =
    "Couldn't find the detector to remove for “$key”."

// ===========================================================================
// Detector row — DetectorRow.swift, PARITY.md §21
// ===========================================================================

data class DetectorRowState(
    val detector: Detector,
    /** Last row in its card: suppresses the trailing hairline. */
    val isLast: Boolean = false,
    /** A delete round-trip is in flight; the overflow menu is inert. */
    val isDeleting: Boolean = false,
    /**
     * Wandering only — the per-sensor exit-door config, keyed by camera id.
     * Used solely to tell the caregiver whether wandering is live on any sensor
     * yet. Empty for every other detector.
     */
    val doorConfigs: Map<String, Detector.DoorConfig> = emptyMap(),
) {
    /** The wandering detector gets the per-sensor "exit doors" affordance. */
    val isWandering: Boolean get() = detector.identifier == "wandering"

    /** A sensor counts as active for wandering once it has at least one exit door. */
    val activeSensorCount: Int
        get() = doorConfigs.values.count { !it.noExitDoor && it.doors.isNotEmpty() }

    val showsWanderingHint: Boolean get() = detector.isOn && isWandering

    /**
     * Wandering only runs on a sensor once its exit doors are set, so "on" alone
     * would imply it is watching something when it is not.
     */
    val wanderingHintText: String
        get() = if (activeSensorCount > 0) {
            val plural = if (activeSensorCount == 1) "" else "s"
            "Active on $activeSensorCount sensor$plural · set exit doors per sensor in the Sensors tab"
        } else {
            "Not active yet. Open a sensor in the Sensors tab and set its exit doors to turn wandering on"
        }

    val whenLabel: String
        get() = if (detector.whenScope.trimmingSwiftWhitespaces().uppercase() == "ALWAYS") {
            "Always"
        } else {
            detector.whenScope
        }

    val deleteConfirmTitle: String get() = "Delete \"${detector.name}\"?"

    /**
     * iOS re-runs the door prefetch on `.task(id:)` keyed by this, so flipping
     * the toggle refetches and switching detector identity does not show the
     * previous row's doors.
     */
    val doorPrefetchKey: String get() = "${detector.id}-${detector.isOn}"
}

// ===========================================================================
// Create a custom detector — AddDetectorView.swift, PARITY.md §17
// ===========================================================================

/** Stage 1 collects the words; stage 2 reviews what the backend inferred. */
enum class AddDetectorStage { Entry, Review }

data class AddDetectorState(
    val stage: AddDetectorStage = AddDetectorStage.Entry,
    /** An infer or create round-trip is in flight. */
    val busy: Boolean = false,
    val error: String? = null,

    // ---- entry ----
    val name: String = "",
    val note: String = "",
    val prompt: String = "",

    // ---- inferred, and editable on the review stage ----
    val alertTrigger: Detector.AlertTrigger = Detector.AlertTrigger.LEADING,
    val isSustained: Boolean = false,
    val category: Detector.Category = Detector.Category.SAFETY,
    val icon: String = "eye",
    val cadence: Detector.ScheduleConfig.Cadence = Detector.ScheduleConfig.Cadence.DAILY,
    val days: Set<Int> = emptySet(),
    val windowStart: LocalTime = DefaultWindowStart,
    val windowEnd: LocalTime = DefaultWindowEnd,
    val deadline: LocalTime = DefaultWindowEnd,
    /** The backend could not pin the times down, so ask the caregiver to confirm. */
    val timesUncertain: Boolean = false,

    // ---- inferred, sent on create, never shown on this screen ----
    //
    // These three are not rendered anywhere in AddDetectorView. They are carried
    // because the create body needs them and because the detail sheet is where
    // they become editable. Dropping them here would silently send the defaults.
    val durationSeconds: Int? = null,
    val episodeGapSeconds: Int? = null,
    val sensitivity: Detector.Sensitivity = Detector.Sensitivity.STANDARD,

    /**
     * Plain-language preview of the compiled behaviour. Null means preview
     * composition failed server-side, and the card is hidden rather than shown
     * empty.
     */
    val plan: DetectorPlan? = null,
) {
    val trimmedPrompt: String get() = prompt.trimmingSwiftWhitespacesAndNewlines()

    /** iOS: `.disabled(busy || prompt.trimmed.isEmpty)`. */
    val canGenerate: Boolean get() = !busy && trimmedPrompt.isNotEmpty()

    val canCreate: Boolean get() = !busy

    /**
     * Only a sustained activity has an edge to choose between. Instant and
     * absence detectors have exactly one moment, so the choice is replaced by a
     * sentence describing it.
     */
    val showsTriggerChoice: Boolean
        get() = isSustained && alertTrigger != Detector.AlertTrigger.ABSENCE

    val showsScheduleEditor: Boolean
        get() = alertTrigger == Detector.AlertTrigger.ABSENCE
}

/**
 * iOS: `Self.defaultTime(9, 0)` / `Self.defaultTime(17, 0)`. Top-level rather
 * than companion members so the primary constructor's default arguments do not
 * depend on companion initialisation order.
 */
internal val DefaultWindowStart: LocalTime = LocalTime.of(9, 0)
internal val DefaultWindowEnd: LocalTime = LocalTime.of(17, 0)

/**
 * Folds an `/infer` response into the review stage.
 *
 * The deadline falls back to the window end, and then to the 17:00 default —
 * the same two-step fallback as iOS, which matters because the unified
 * `window(hit|miss)` fire rule treats the deadline as the window end anyway.
 *
 * [AddDetectorState.timesUncertain] is set only for absence detectors, and only
 * when at least one of the three times failed to parse. A reactive detector has
 * no window to be unsure about.
 */
fun AddDetectorState.applyingInference(inferred: InferDetectorResponse): AddDetectorState {
    val schedule = inferred.scheduleConfig
    val start = parseHHMM(schedule?.windowStart)
    val end = parseHHMM(schedule?.windowEnd)
    val by = parseHHMM(schedule?.deadline)
    return copy(
        alertTrigger = inferred.alertTrigger,
        isSustained = inferred.isSustained,
        durationSeconds = inferred.durationSeconds,
        episodeGapSeconds = inferred.episodeGapSeconds,
        category = inferred.category,
        icon = inferred.iconName,
        sensitivity = inferred.sensitivity,
        plan = inferred.plan,
        cadence = schedule?.cadence ?: Detector.ScheduleConfig.Cadence.DAILY,
        days = (schedule?.daysOfWeek ?: emptyList()).toSet(),
        windowStart = start ?: DefaultWindowStart,
        windowEnd = end ?: DefaultWindowEnd,
        deadline = by ?: end ?: DefaultWindowEnd,
        timesUncertain = inferred.alertTrigger == Detector.AlertTrigger.ABSENCE &&
            (start == null || end == null || by == null),
        stage = AddDetectorStage.Review,
    )
}

/**
 * The create body, exactly as iOS builds it.
 *
 * Three rules here are load-bearing and easy to lose:
 *
 * - **`scheduleConfig` is never sent** (dissolved 2026-07-18). `whenScope` is
 *   the windows mechanism; absence detectors serialize their pickers into scope
 *   text the backend parses directly, everything else sends `ALWAYS`.
 * - **`durationSeconds` only when the trigger is `DURATION`**, and
 *   **`episodeGapSeconds` only when the activity is sustained.** Sending either
 *   otherwise configures a threshold the detector will never reach.
 * - **`isOn = false`** — create-as-draft. The caregiver reviews the compiled
 *   criteria on the detail sheet before the detector starts watching.
 */
fun AddDetectorState.toCreateDetectorRequest(): CreateDetectorRequest {
    val trimmedName = name.trimmingSwiftWhitespacesAndNewlines()
    val trimmedNote = note.trimmingSwiftWhitespacesAndNewlines()
    val scope = if (alertTrigger == Detector.AlertTrigger.ABSENCE) {
        formatWhenScope(cadence, days, windowStart, windowEnd)
    } else {
        "ALWAYS"
    }
    return CreateDetectorRequest(
        name = trimmedName.ifEmpty { "Custom detector" },
        customPrompt = trimmedPrompt,
        description = trimmedNote.ifEmpty { "Custom detector" },
        category = category,
        alertTrigger = alertTrigger,
        isSustained = isSustained,
        durationSeconds = if (alertTrigger == Detector.AlertTrigger.DURATION) durationSeconds else null,
        iconName = icon,
        whenScope = scope,
        sensitivity = sensitivity,
        scheduleConfig = null,
        episodeGapSeconds = if (isSustained) episodeGapSeconds else null,
        isOn = false,
    )
}

/** 0 = Mon .. 6 = Sun, matching `schedule_config.days_of_week` and the backend grammar. */
internal val WhenScopeDayNames = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

/** Day initials for the weekly picker. 0 = Mon .. 6 = Sun, so both T's and both S's repeat. */
internal val WhenScopeDayInitials = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * One time in the backend's scope grammar, for example `8:00 AM`.
 *
 * Formatted by hand rather than through `DateTimeFormatter`. iOS pins
 * `en_US_POSIX` here because **the backend grammar requires AM/PM tokens
 * regardless of the device's 24-hour setting**, and going through the platform
 * formatter would reintroduce exactly what the pin exists to prevent: CLDR data
 * varies by Android version, and recent releases emit a narrow no-break space
 * before the marker, which the parser would not recognise.
 */
internal fun formatScopeTime(time: LocalTime): String {
    val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
    val marker = if (time.hour < 12) "AM" else "PM"
    val minute = if (time.minute < 10) "0${time.minute}" else "${time.minute}"
    return "$hour12:$minute $marker"
}

/**
 * Serializes the absence pickers into scope text, for example
 * `MON,WED 8:00 AM - 9:00 AM`. A daily cadence, or a weekly one with no days
 * picked, sends the bare window.
 */
internal fun formatWhenScope(
    cadence: Detector.ScheduleConfig.Cadence,
    days: Set<Int>,
    start: LocalTime,
    end: LocalTime,
): String {
    val window = "${formatScopeTime(start)} - ${formatScopeTime(end)}"
    if (cadence != Detector.ScheduleConfig.Cadence.WEEKLY || days.isEmpty()) return window
    val prefix = days.sorted()
        .filter { it in WhenScopeDayNames.indices }
        .joinToString(",") { WhenScopeDayNames[it] }
    return if (prefix.isEmpty()) window else "$prefix $window"
}

/**
 * Parses an inferred `"HH:MM"`, or null when the field is absent or unusable.
 *
 * Two details are deliberate:
 *
 * - **ASCII digits only.** `toIntOrNull()` is Unicode-aware and would accept
 *   Eastern Arabic numerals that Swift's `Int(_:)` rejects, so the same payload
 *   would yield a window on Android and a blank on iOS.
 * - **Empty pieces are dropped** before counting, matching Swift's
 *   `split(separator:)`, which omits them by default.
 *
 * One intentional divergence: an out-of-range value such as `"99:00"` returns
 * null here. iOS's `Calendar.date(bySettingHour:)` returns nil for it and the
 * `?? Date()` fallback quietly turns it into *the current time*, which then
 * reads as a confidently-parsed window. Null is the honest answer and it feeds
 * `timesUncertain`, which asks the caregiver to confirm.
 */
internal fun parseHHMM(value: String?): LocalTime? {
    if (value == null || value.length != 5) return null
    val parts = value.split(":").filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val hour = parts[0].asciiToIntOrNull() ?: return null
    val minute = parts[1].asciiToIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

private fun String.asciiToIntOrNull(): Int? {
    if (isEmpty() || any { it !in '0'..'9' }) return null
    return toIntOrNull()
}
