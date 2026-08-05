package com.memoria.caregiver.ui.detectors

import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.UpdateDetectorRequest
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import com.memoria.caregiver.ui.theme.displayName

/**
 * State for the detector detail editor, and every piece of its logic that does
 * not need a composition.
 *
 * Spec: `Features/Patient/Settings/DetectorDetailSheet.swift`, PARITY.md §17.
 *
 * iOS keeps thirteen `@State` properties on the view and reaches the API through
 * `@Environment`. The shape below is the same state, hoisted, per the pattern in
 * `ui/auth/AuthUiState.kt`: the screen is a pure `@Composable` over this class
 * plus callbacks, and the host owns every network call.
 *
 * The transitions that carry real behavior — clearing a stale preview, snapping
 * the staged pickers to a preview response, snapping back to a room after the
 * last one is deselected, building the PATCH body — live here as pure functions
 * so they are tested without a composition and cannot be re-invented by the host.
 */

// ---------------------------------------------------------------------------
// Small value types
// ---------------------------------------------------------------------------

/** "When it runs": always on, or a specific HH:MM window. iOS: `enum WhenMode`. */
enum class WhenMode { Always, Specific }

/**
 * A time of day with no date attached.
 *
 * iOS holds the two `DatePicker` values as `Date`s built from a bare
 * `DateComponents(hour:minute:)` and reads the hour and minute back out through
 * `Calendar.current` whenever it serializes them. Since the date part is never
 * used, this stores only what is read.
 */
data class HourMinute(val hour: Int, val minute: Int) {
    /**
     * `String(format: "%02d:%02d")`, the shape the backend's when-scope grammar
     * expects.
     *
     * Formatted against [Locale.ROOT] deliberately. `String.format` with the
     * default locale emits Eastern Arabic digits under an `ar` device locale,
     * and this string goes on the wire.
     */
    fun toHHMM(): String = "%02d:%02d".format(Locale.ROOT, hour, minute)
}

/**
 * Poll cadences and the duration bounds, all of them product tuning rather than
 * implementation detail. The polls are driven by the host — they are network
 * loops — so the numbers live here where the screen they belong to can be read
 * against the spec.
 */
object DetectorDetailTiming {
    /**
     * A freshly created custom detector arrives with `identifier == null` while
     * the backend compiles it. iOS `pollWhileCompiling` reloads the list 15
     * times at 1.5s, giving the compile ~22s to land.
     */
    const val COMPILE_POLL_ATTEMPTS: Int = 15
    val COMPILE_POLL_INTERVAL: Duration = 1.5.seconds

    /**
     * A committed refine recompiles server-side after the response returns, so
     * iOS reloads 3 times at 4s looking for a row with a newer `updatedAt`.
     */
    const val REFINE_POLL_ATTEMPTS: Int = 3
    val REFINE_POLL_INTERVAL: Duration = 4.seconds

    /** The duration-trigger threshold is entered in minutes and clamped. */
    const val MIN_DURATION_MINUTES: Int = 1
    const val MAX_DURATION_MINUTES: Int = 1440
}

// ---------------------------------------------------------------------------
// Staged edits
// ---------------------------------------------------------------------------

/**
 * Everything the caregiver can change without leaving the sheet, held apart from
 * the detector it came from. Nothing here is persisted until Save.
 *
 * @param allRooms the "All rooms" chip. When true [selectedRooms] is empty.
 * @param selectedRooms explicit room scope. May hold a room that has no camera
 *   any more, in which case no chip is drawn for it and it stays staged —
 *   matching iOS, which only ever draws chips for the current camera rooms.
 */
data class StagedEdits(
    val alertTrigger: Detector.AlertTrigger,
    val durationSeconds: Int?,
    val allRooms: Boolean,
    val selectedRooms: Set<String>,
    val category: Detector.Category,
    val sensitivity: Detector.Sensitivity,
    val whenMode: WhenMode,
    val whenStart: HourMinute,
    val whenEnd: HourMinute,
) {
    /**
     * Snap the alert timing, the active window and the rooms to [d], leaving
     * category and sensitivity alone. iOS: `syncStagedControls(from:)`.
     *
     * Used after a refine preview and after a committed refine, because the
     * backend re-infers those three from the caregiver's words (PARITY.md §17).
     * Skip it and the pickers silently disagree with the criteria above them.
     *
     * Two differences from [initial] that are easy to flatten and must not be:
     * the duration is dropped unless the trigger actually is `DURATION`, and
     * category and sensitivity are NOT touched — iOS re-reads those from the
     * server's echo only on the pure-PATCH path.
     */
    fun syncedFrom(d: Detector): StagedEdits {
        val (allRooms, rooms) = initialRoomState(d)
        val (mode, start, end) = parseWhenScope(d.whenScope)
        return copy(
            alertTrigger = d.alertTrigger,
            durationSeconds =
                if (d.alertTrigger == Detector.AlertTrigger.DURATION) d.durationSeconds else null,
            allRooms = allRooms,
            selectedRooms = rooms,
            whenMode = mode,
            whenStart = start,
            whenEnd = end,
        )
    }

    fun withAllRooms(): StagedEdits = copy(allRooms = true, selectedRooms = emptySet())

    /**
     * Tapping a room chip. Selecting any room leaves "All rooms", and
     * **deselecting the last one snaps back to it** — an empty room scope means
     * nothing, and saving it would scope the detector to no camera at all.
     */
    fun withRoomToggled(room: String): StagedEdits {
        val next = selectedRooms.toMutableSet()
        if (!next.remove(room)) next.add(room)
        return copy(allRooms = next.isEmpty(), selectedRooms = next)
    }

    /** The duration field, in minutes. See [durationSecondsFromInput]. */
    fun withDurationInput(text: String): StagedEdits =
        copy(durationSeconds = durationSecondsFromInput(text))

    companion object {
        /**
         * First read of a detector into the pickers. iOS: `initRoomsIfNeeded`.
         *
         * Note the duration is carried across unconditionally here, unlike
         * [syncedFrom]: a detector that is not currently a duration trigger can
         * still have a stored threshold, and iOS keeps it so switching the chip
         * to "If it lasts too long" pre-fills the old value.
         */
        fun initial(d: Detector): StagedEdits {
            val (allRooms, rooms) = initialRoomState(d)
            val (mode, start, end) = parseWhenScope(d.whenScope)
            return StagedEdits(
                alertTrigger = d.alertTrigger,
                durationSeconds = d.durationSeconds,
                allRooms = allRooms,
                selectedRooms = rooms,
                category = d.category,
                sensitivity = d.sensitivity,
                whenMode = mode,
                whenStart = start,
                whenEnd = end,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The screen state
// ---------------------------------------------------------------------------

/**
 * @param roomOptions the patient's distinct camera rooms, first-seen order, with
 *   blanks dropped. iOS derives this from `env.api.camerasByPatient`; the screen
 *   has no API access, so the host passes it in. Build it with [roomOptionsFrom].
 * @param previewCriteria criteria a "Re-generate" dry run produced. Non-null is
 *   what puts the PREVIEW · NOT SAVED pill on the card, including when the
 *   backend returns an empty list.
 * @param error a user-facing sentence, rendered verbatim.
 */
data class DetectorDetailState(
    val detector: Detector,
    val roomOptions: List<String> = emptyList(),
    val feedback: String = "",
    val previewCriteria: List<String>? = null,
    val regenerating: Boolean = false,
    val saving: Boolean = false,
    val rewriting: Boolean = false,
    val error: String? = null,
    val staged: StagedEdits = StagedEdits.initial(detector),
) {
    /** Only custom detectors expose the prompt and the refine box. */
    val isCustom: Boolean get() = detector.source == Detector.Source.CUSTOM

    /** The backend has not finished compiling this detector yet. */
    val compiling: Boolean get() = detector.identifier == null

    /** The preview when there is one, otherwise the live compiled criteria. */
    val shownCriteria: List<String>
        get() = previewCriteria ?: detector.userCriteria ?: emptyList()

    /** The staged window as the backend string: "ALWAYS" or "HH:MM-HH:MM". */
    val computedWhen: String
        get() = if (staged.whenMode == WhenMode.Always) {
            "ALWAYS"
        } else {
            "${staged.whenStart.toHHMM()}-${staged.whenEnd.toHHMM()}"
        }

    val categoryChanged: Boolean get() = staged.category != detector.category
    val sensitivityChanged: Boolean get() = staged.sensitivity != detector.sensitivity

    val whenChanged: Boolean
        get() = computedWhen.uppercase() != detector.whenScope.trimmingSwiftWhitespaces().uppercase()

    val timingChanged: Boolean
        get() = staged.alertTrigger != detector.alertTrigger ||
            staged.durationSeconds != detector.durationSeconds

    /** A duration trigger needs a time — never save it without one. */
    val timingInvalid: Boolean
        get() = staged.alertTrigger == Detector.AlertTrigger.DURATION && staged.durationSeconds == null

    val roomsChanged: Boolean
        get() {
            val (initAll, initSet) = initialRoomState(detector)
            if (staged.allRooms != initAll) return true
            if (!staged.allRooms) return staged.selectedRooms != initSet
            return false
        }

    /**
     * Save is gated on this. Seven independent conditions, in iOS's order.
     *
     * Pending feedback counts as dirty on its own: a caregiver who types a
     * change and taps Save without previewing still gets it applied.
     */
    val isDirty: Boolean
        get() = when {
            feedback.trimmingSwiftWhitespacesAndNewlines().isNotEmpty() -> true
            previewCriteria != null -> true
            timingChanged && !timingInvalid -> true
            categoryChanged -> true
            sensitivityChanged -> true
            whenChanged -> true
            else -> roomsChanged
        }

    /** The trimmed pending change, or empty. The host sends this to `refine`. */
    val trimmedFeedback: String get() = feedback.trimmingSwiftWhitespacesAndNewlines()

    val canSave: Boolean get() = !saving && isDirty && !timingInvalid

    val canRegenerate: Boolean get() = !regenerating && trimmedFeedback.isNotEmpty()

    // ---- What the criteria card is doing right now -------------------------

    /**
     * The line over the shimmer while the criteria card is (re)generating, or
     * null when it should show criteria instead.
     *
     * Three different waits share one skeleton and are told apart only by this
     * sentence, so the order matters: a dry run in flight reads as "re-reading",
     * a committed refine recompiling reads as "applying", and a brand-new
     * detector still compiling reads as "writing".
     *
     * The compile case is the only one that also requires the card to be empty —
     * a detector that has criteria already keeps showing them while a later
     * compile poll runs, rather than blanking out under the caregiver.
     */
    val generatingLabel: String?
        get() = when {
            regenerating -> "Re-reading your change…"
            rewriting -> "Applying your change…"
            compiling && shownCriteria.isEmpty() -> "Writing the instructions…"
            else -> null
        }

    /**
     * Whether the alert-timing chips are offered at all.
     *
     * Only a custom, sustained detector has an edge to choose. Instant detectors
     * have one moment, absence detectors fire on a deadline, and presets are
     * Memoria's shape rather than the caregiver's — all three get the read-only
     * sentence and nothing else.
     */
    val showsTimingChoice: Boolean
        get() = isCustom &&
            detector.isSustained &&
            staged.alertTrigger != Detector.AlertTrigger.ABSENCE

    // ---- Transitions -------------------------------------------------------

    /**
     * Typing in the refine box invalidates any preview on screen, because that
     * preview was compiled from different words.
     *
     * The staged pickers are deliberately NOT rolled back to the detector: iOS
     * leaves them wherever the preview put them, and they still save.
     */
    fun withFeedback(text: String): DetectorDetailState =
        copy(feedback = text, previewCriteria = null)

    /**
     * A "Re-generate" dry run landed. Nothing here is persisted.
     *
     * `userCriteria == null` becomes an empty list rather than staying null, so
     * the preview pill shows even when the compile produced no bullet summary.
     */
    fun withPreview(previewed: Detector): DetectorDetailState = copy(
        previewCriteria = previewed.userCriteria ?: emptyList(),
        staged = staged.syncedFrom(previewed),
        regenerating = false,
        error = null,
    )

    /**
     * `refine(preview: false)` returned: the change is committed and recompiling
     * server-side. The pending words and the preview are consumed.
     */
    fun withRefineCommitted(updated: Detector): DetectorDetailState = copy(
        detector = updated,
        feedback = "",
        previewCriteria = null,
        staged = staged.syncedFrom(updated),
    )

    /**
     * A pure PATCH committed with no refine behind it. The controls already
     * match what was sent, but the server's echo is authoritative — it may have
     * normalized `where_rooms` — so they are re-read from it, category and
     * sensitivity included.
     */
    fun withPatchCommitted(updated: Detector): DetectorDetailState = copy(
        detector = updated,
        staged = staged.syncedFrom(updated).copy(
            category = updated.category,
            sensitivity = updated.sensitivity,
        ),
    )

    /**
     * A poll found a fresher row. The staged pickers follow, because the row is
     * fresher precisely when a recompile re-inferred them.
     */
    fun withRefreshedDetector(fresh: Detector): DetectorDetailState =
        copy(detector = fresh, staged = staged.syncedFrom(fresh))

    /**
     * A compile poll found the row. Only the detector moves: this fires while
     * the caregiver may already be editing, and iOS does not disturb the
     * pickers here.
     */
    fun withCompiledDetector(fresh: Detector): DetectorDetailState = copy(detector = fresh)

    /**
     * Step 1 of Save: one PATCH carrying only what changed, or null when nothing
     * did. Fields left null are not serialized (`EncodeDefault.Mode.NEVER`).
     *
     * `duration_seconds` is sent only when the trigger IS a duration. Switching
     * away from a duration trigger therefore leaves the stored threshold behind
     * on the backend rather than clearing it, which is what iOS does.
     */
    fun buildPatch(): UpdateDetectorRequest? {
        var patch = UpdateDetectorRequest()
        var hasPatch = false
        if (roomsChanged) {
            patch = patch.copy(
                whereRooms =
                    if (staged.allRooms) listOf(ALL_ROOMS) else staged.selectedRooms.toList(),
            )
            hasPatch = true
        }
        if (whenChanged) {
            patch = patch.copy(whenScope = computedWhen)
            hasPatch = true
        }
        if (categoryChanged) {
            patch = patch.copy(category = staged.category)
            hasPatch = true
        }
        if (sensitivityChanged) {
            patch = patch.copy(sensitivity = staged.sensitivity)
            hasPatch = true
        }
        if (timingChanged && !timingInvalid) {
            patch = patch.copy(
                alertTrigger = staged.alertTrigger,
                durationSeconds =
                    if (staged.alertTrigger == Detector.AlertTrigger.DURATION) {
                        staged.durationSeconds
                    } else {
                        null
                    },
            )
            hasPatch = true
        }
        return if (hasPatch) patch else null
    }

    /**
     * Step 2 of Save: the words to commit through `refine(preview = false)`, or
     * null when there is nothing to commit.
     *
     * A preview on screen is NOT enough on its own — the preview was a dry run
     * and the server kept nothing, so what gets committed is always the text in
     * the box. Presets never reach here: only a custom detector has a prompt to
     * recompile.
     */
    val pendingRefine: String?
        get() = if (isCustom && trimmedFeedback.isNotEmpty()) trimmedFeedback else null

    /** Both steps of Save in the order the host must run them. See [SavePlan]. */
    val savePlan: SavePlan get() = SavePlan(patch = buildPatch(), refineFeedback = pendingRefine)

    /**
     * Whether a polled row is newer than the one on screen — the test that ends
     * the post-refine poll.
     *
     * A committed refine recompiles server-side *after* the response returns, so
     * the row that comes back still carries the old summary. Only a strictly
     * newer `updated_at` proves the rewrite landed; adopting an equal timestamp
     * would end the poll on the row we already have and leave the stale summary
     * up until the caregiver reopened the sheet.
     */
    @OptIn(ExperimentalTime::class)
    fun isFresher(candidate: Detector): Boolean = candidate.updatedAt > detector.updatedAt
}

/**
 * The two steps of Save, in order. iOS runs them inline in `save()`; here the
 * host performs the calls and this says what they are.
 *
 * 1. [patch] — one `PATCH` carrying only the fields that changed, or null when
 *    none did. An alert-timing change rides along with it: the detection
 *    fragment is unchanged, so nothing has to recompile, only when it fires.
 * 2. [refineFeedback] — `refine(preview = false)`, which DOES recompile.
 *
 * Order is load-bearing. The refine re-infers alert trigger, active window and
 * rooms from the caregiver's words, so running it second lets the server's
 * inference win over the staged pickers rather than the other way round.
 *
 * Two things the host owns that this cannot express, both of them iOS behavior:
 * after a committed refine the saving flag drops **before** the poll and the
 * rewriting flag goes up for its duration, and that path does NOT re-read
 * category and sensitivity from the server echo the way a patch-only save does
 * (see [DetectorDetailState.withRefineCommitted] versus
 * [DetectorDetailState.withPatchCommitted]).
 */
data class SavePlan(
    val patch: UpdateDetectorRequest?,
    val refineFeedback: String?,
) {
    val hasWork: Boolean get() = patch != null || refineFeedback != null
}

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

/** The sentinel `where_rooms` value meaning "every room". */
const val ALL_ROOMS: String = "ALL ROOMS"

/**
 * The fallbacks iOS shows when a failure carries no `errorDescription` of its
 * own. The host renders the platform's own sentence when there is one and these
 * otherwise, so they live here rather than being retyped at the call site.
 */
const val PREVIEW_FAILURE_MESSAGE: String = "Couldn't preview that. Please try again."
const val SAVE_FAILURE_MESSAGE: String = "Couldn't save that. Please try again."

/**
 * The chip label for a category. iOS: `categoryLabel(_:)`.
 *
 * Delegates to the palette rather than keeping a second list — this `when`
 * is exactly what went stale when Behaviour and Care were added.
 */
fun categoryLabel(category: Detector.Category): String = category.displayName

/**
 * The line under the sensitivity chips. iOS: `sensitivityHint`.
 *
 * `OFF` is unreachable from the three chips, but the wire type carries it and a
 * detector saved by another surface can arrive with it, so it has a sentence
 * rather than a crash.
 */
fun sensitivityHint(level: Detector.Sensitivity): String = when (level) {
    Detector.Sensitivity.LOW -> "Fewer alerts, only the clearest signals"
    Detector.Sensitivity.STANDARD -> "Recommended baseline"
    Detector.Sensitivity.HIGH -> "More alerts"
    Detector.Sensitivity.OFF -> "Detector off"
}

/** The window used when a detector has no parseable one of its own. */
val DEFAULT_WINDOW_START: HourMinute = HourMinute(8, 0)
val DEFAULT_WINDOW_END: HourMinute = HourMinute(20, 0)

/**
 * Reads the room scope off a detector.
 *
 * `whereRooms` is the only truth (`where_scope` was deleted 2026-07-28): null or
 * empty means every room, and an explicit [ALL_ROOMS] entry does too.
 */
fun initialRoomState(d: Detector): Pair<Boolean, Set<String>> {
    val wr = d.whereRooms
    if (!wr.isNullOrEmpty()) {
        if (wr.any { it.trimmingSwiftWhitespaces().uppercase() == ALL_ROOMS }) {
            return true to emptySet()
        }
        return false to wr.toSet()
    }
    return true to emptySet()
}

/**
 * Parses a backend when-scope. "HH:MM-HH:MM" is a specific window; anything
 * else — "ALWAYS", an absence detector's `MON,WED 8:00 AM - 9:00 AM`, junk —
 * falls back to Always with the 08:00-20:00 default, which is what the caregiver
 * sees pre-filled if they switch the chip to "Specific hours".
 */
fun parseWhenScope(raw: String): Triple<WhenMode, HourMinute, HourMinute> {
    // Swift's `split(separator:)` omits empty subsequences; Kotlin's keeps them.
    val parts = raw.trimmingSwiftWhitespaces().split("-").filter { it.isNotEmpty() }
    if (parts.size == 2) {
        val s = hourMinuteFromHHMM(parts[0])
        val e = hourMinuteFromHHMM(parts[1])
        if (s != null && e != null) return Triple(WhenMode.Specific, s, e)
    }
    return Triple(WhenMode.Always, DEFAULT_WINDOW_START, DEFAULT_WINDOW_END)
}

/** "HH:MM" on a 24-hour clock, or null. Rejects out-of-range components. */
fun hourMinuteFromHHMM(raw: String): HourMinute? {
    val comps = raw.trimmingSwiftWhitespaces().split(":").filter { it.isNotEmpty() }
    if (comps.size != 2) return null
    val h = comps[0].asciiIntOrNull() ?: return null
    val m = comps[1].asciiIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return HourMinute(h, m)
}

/**
 * The duration field's binding: minutes in, seconds out, clamped to
 * 1-1440 minutes. Empty or unparseable input clears the value, which is what
 * makes `timingInvalid` block Save.
 *
 * Two Swift behaviors that a straight rewrite loses:
 *
 * 1. `filter(\.isNumber)` keeps every character Unicode calls numeric — Roman
 *    numerals and vulgar fractions included — not just decimal digits, which is
 *    all Kotlin's `Char.isDigit()` keeps.
 * 2. `Int(_:)` then accepts only ASCII digits, so a surviving non-ASCII numeral
 *    makes the whole value null rather than being skipped. Kotlin's
 *    `toIntOrNull()` goes through `Character.digit` and would happily read an
 *    Eastern Arabic digit as a value iOS rejects.
 *
 * Together they mean a numeral the device's keyboard can produce but Foundation
 * cannot parse blanks the field on both platforms instead of quietly setting a
 * different number on one of them.
 */
fun durationSecondsFromInput(text: String): Int? {
    val digits = text.filter { it.isSwiftNumber() }
    val minutes = digits.asciiIntOrNull() ?: return null
    return minutes
        .coerceIn(DetectorDetailTiming.MIN_DURATION_MINUTES, DetectorDetailTiming.MAX_DURATION_MINUTES) * 60
}

/** The duration field's text: whole minutes, or empty when unset. */
fun durationMinutesText(durationSeconds: Int?): String =
    durationSeconds?.let { (it / 60).toString() } ?: ""

/**
 * The distinct rooms of a patient's cameras, first-seen order, blanks dropped.
 * iOS builds this inline from `env.api.camerasByPatient[patientId]`.
 *
 * Takes room strings rather than cameras so the screen's package does not have
 * to know the camera model.
 */
fun roomOptionsFrom(cameraRooms: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    for (room in cameraRooms) if (room.isNotEmpty()) seen.add(room)
    return seen.toList()
}

/**
 * Trims what Swift's `CharacterSet.whitespaces` trims: a horizontal tab plus
 * anything Unicode calls a space separator. Kotlin's `trim()` would also strip
 * newlines, which that set does not, so a value ending in a newline would
 * compare differently on the two platforms.
 *
 * The same helper exists privately in the model layer; Kotlin file-private
 * declarations are not shareable, and this file must not add to that package.
 */
internal fun String.trimmingSwiftWhitespaces(): String =
    trim { ch -> ch == '\t' || Character.getType(ch) == Character.SPACE_SEPARATOR.toInt() }

/**
 * Swift's `.whitespacesAndNewlines`: the set above plus its newline set, which
 * is U+000A through U+000D, U+0085, U+2028 and U+2029.
 */
internal fun String.trimmingSwiftWhitespacesAndNewlines(): String =
    trim { ch ->
        ch == '\t' ||
            ch in '\u000A'..'\u000D' ||
            ch == '\u0085' ||
            ch == '\u2028' ||
            ch == '\u2029' ||
            Character.getType(ch) == Character.SPACE_SEPARATOR.toInt()
    }

/** Swift's `Character.isNumber`: any character with a Unicode numeric type. */
private fun Char.isSwiftNumber(): Boolean = when (Character.getType(this)) {
    Character.DECIMAL_DIGIT_NUMBER.toInt(),
    Character.LETTER_NUMBER.toInt(),
    Character.OTHER_NUMBER.toInt(),
    -> true

    else -> false
}

/**
 * Swift's `Int(_ text: String)`: ASCII digits with an optional sign, nothing
 * else, and null on overflow.
 */
private fun String.asciiIntOrNull(): Int? {
    if (isEmpty()) return null
    val body = if (this[0] == '+' || this[0] == '-') substring(1) else this
    if (body.isEmpty() || body.any { it !in '0'..'9' }) return null
    return toIntOrNull()
}
