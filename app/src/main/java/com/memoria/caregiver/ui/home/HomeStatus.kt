package com.memoria.caregiver.ui.home

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Patient
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The Home verdict: is everyone alright, and if not, who.
 *
 * Port of the logic half of `Features/Home/HomeStatusView.swift`.
 *
 * ## The rule the whole file exists to enforce
 *
 * **Never aggregate raw data — aggregate per-person verdicts.**
 *
 * Every hole the iOS version closed came from pooling across patients: one
 * person's online cameras hid another's blindness, falls were reported without
 * saying whose, and quiet streaks were counted across people who had nothing to
 * do with each other. So each person's status is computed independently and only
 * then folded by severity, naming names.
 *
 * One patient is a fold of one. There is no single-patient branch to keep in
 * step with the multi-patient one.
 *
 * Everything here is a pure function of its arguments, including the clock, so
 * the whole verdict is testable without a device, a backend, or a wall clock.
 */
@OptIn(ExperimentalTime::class)
object HomeStatus {

    /**
     * Severity, worst first. The ordinal **is** the sort key, so the order of
     * these cases is behaviour rather than style.
     */
    enum class Level { URGENT, BLIND, ATTENTION, QUIET }

    /** One person's independently-computed status. */
    data class PersonStatus(
        val patient: Patient,
        val name: String,
        val level: Level,
        /** Person-specific detail used by the fold. Null when quiet. */
        val headline: String?,
        /** Name-tagged fact sent to the briefing endpoint. */
        val factLine: String,
        val daysWithoutFall: String?,
        val quietNights: Int,
    )

    /** The single line shown at the top of Home, plus its supporting sentence. */
    data class Verdict(val level: Level, val line: String, val sub: String)

    /**
     * Words that make an event serious enough to interrupt someone.
     *
     * Matched against the lowercased title as substrings, so "chok" catches
     * both "choking" and "choked". Carried verbatim from the Swift — this list
     * decides what counts as urgent, so changing it changes what the app
     * escalates.
     */
    private val SERIOUS_TERMS = listOf("fall", "chok", "stroke", "heart", "seizure", "help")

    private const val DAY_MILLIS = 86_400_000L
    private const val URGENT_WINDOW_MILLIS = 3 * 60 * 60 * 1000L

    internal fun isSerious(event: DetectionEvent): Boolean {
        val title = event.title.lowercase(Locale.ROOT)
        return SERIOUS_TERMS.any { title.contains(it) }
    }

    /**
     * An event title as something a person would say.
     *
     * "Possible fall" in the kitchen becomes "what looked like a fall in the
     * kitchen". The hedging is deliberate and is not softening for its own
     * sake: these are model detections, and stating them as fact would have the
     * app telling a caregiver their mother fell when it saw a dropped cushion.
     */
    internal fun humanize(event: DetectionEvent): String {
        val title = event.title
            .replace("Possible ", "")
            .replace("possible ", "")
            .lowercase(Locale.ROOT)
        val place = if (event.location.isEmpty()) {
            ""
        } else {
            " in the " + event.location.lowercase(Locale.ROOT)
        }
        return when {
            title.contains("fall") -> "what looked like a fall$place"
            title.contains("chok") -> "possible choking$place"
            title.contains("wander") || title.contains("door") -> "activity near the door$place"
            title.contains("recover") -> "getting back up on their own$place"
            else -> "$title$place"
        }
    }

    /**
     * One person's status, from their own events and their own cameras.
     *
     * @param nowMillis the caregiver's "now", injected so every window below is
     *   testable.
     */
    fun statusFor(
        patient: Patient,
        events: List<DetectionEvent>,
        cameras: List<Camera>,
        nowMillis: Long,
    ): PersonStatus {
        val name = patient.preferredName.ifEmpty { patient.fullName }
        val sorted = events.sortedByDescending { it.occurredAt }
        val recent = sorted.filter { it.occurredAt.toEpochMilliseconds() > nowMillis - DAY_MILLIS }

        val oldest = sorted.lastOrNull()?.occurredAt
        val daysWithoutFall = daysWithoutFall(sorted, oldest, nowMillis)
        val quiet = quietNights(sorted, oldest, nowMillis)

        // Urgent: a serious moment in the last three hours that nobody has
        // looked at. Reviewed means a human has already seen it, so it is no
        // longer something to interrupt them with.
        val urgent = recent.firstOrNull {
            isSerious(it) &&
                it.occurredAt.toEpochMilliseconds() > nowMillis - URGENT_WINDOW_MILLIS &&
                !it.reviewed
        }
        if (urgent != null) {
            return PersonStatus(
                patient = patient,
                name = name,
                level = Level.URGENT,
                headline = humanize(urgent),
                factLine = "$name: NEEDS ATTENTION — ${humanize(urgent)}",
                daysWithoutFall = daysWithoutFall,
                quietNights = quiet,
            )
        }

        // Blind outranks attention, and that ordering is the point: with no
        // working camera we do not know whether there is anything to attend to,
        // so claiming "all quiet" would be a claim we cannot support.
        if (cameras.isEmpty()) {
            return PersonStatus(
                patient, name, Level.BLIND,
                headline = "no cameras set up yet",
                factLine = "$name: no cameras set up yet",
                daysWithoutFall = daysWithoutFall, quietNights = quiet,
            )
        }
        if (cameras.none { it.online }) {
            return PersonStatus(
                patient, name, Level.BLIND,
                headline = "cameras are offline",
                factLine = "$name: cameras offline",
                daysWithoutFall = daysWithoutFall, quietNights = quiet,
            )
        }

        val unreviewed = recent.filter { !it.reviewed }
        if (unreviewed.isNotEmpty()) {
            val plural = if (unreviewed.size == 1) "" else "s"
            return PersonStatus(
                patient, name, Level.ATTENTION,
                headline = "${unreviewed.size} unreviewed moment$plural",
                factLine = "$name: ${humanize(unreviewed.first())} — unreviewed",
                daysWithoutFall = daysWithoutFall, quietNights = quiet,
            )
        }

        return PersonStatus(
            patient, name, Level.QUIET,
            headline = null,
            factLine = "$name: quiet",
            daysWithoutFall = daysWithoutFall, quietNights = quiet,
        )
    }

    /**
     * "12" or "30+" days since the last fall, or null when there is nothing
     * loaded to reason from.
     *
     * The "+" is honesty about the window: with no fall anywhere in the loaded
     * events, all we can say is "at least as long as we have records for".
     */
    private fun daysWithoutFall(
        sorted: List<DetectionEvent>,
        oldest: Instant?,
        nowMillis: Long,
    ): String? {
        if (oldest == null) return null
        val lastFall = sorted.firstOrNull { it.title.lowercase(Locale.ROOT).contains("fall") }
        return if (lastFall != null) {
            val days = (nowMillis - lastFall.occurredAt.toEpochMilliseconds()) / DAY_MILLIS
            maxOf(0L, days).toString()
        } else {
            val days = (nowMillis - oldest.toEpochMilliseconds()) / DAY_MILLIS
            "${maxOf(1L, days)}+"
        }
    }

    /**
     * Consecutive nights (22:00–06:00) back from last night with no events.
     *
     * **No loaded events means zero, never thirty.** A brand-new patient must
     * not be credited with a month of quiet nights nobody was watching — that
     * was a real bug on iOS, and it is the difference between a reassurance
     * that is earned and one that is invented.
     *
     * Counting also stops at the oldest event we hold, for the same reason: past
     * that point there is no evidence either way.
     */
    private fun quietNights(
        sorted: List<DetectionEvent>,
        oldest: Instant?,
        nowMillis: Long,
    ): Int {
        if (oldest == null) return 0
        val startOfToday = nowMillis - (nowMillis % DAY_MILLIS)
        var quiet = 0
        for (back in 1..30) {
            val dayStart = startOfToday - back * DAY_MILLIS
            val nightStart = dayStart + 22 * 3_600_000L - DAY_MILLIS
            val nightEnd = dayStart + 6 * 3_600_000L
            val hadEvent = sorted.any {
                val at = it.occurredAt.toEpochMilliseconds()
                at >= nightStart && at < nightEnd
            }
            if (hadEvent) break
            quiet = back
            if (nightStart < oldest.toEpochMilliseconds()) break
        }
        return quiet
    }

    /** Every patient's status, worst first. */
    fun statuses(
        patients: List<Patient>,
        eventsByPatient: Map<String, List<DetectionEvent>>,
        camerasByPatient: Map<String, List<Camera>>,
        nowMillis: Long,
    ): List<PersonStatus> = patients
        .map {
            statusFor(
                patient = it,
                events = eventsByPatient[it.id].orEmpty(),
                cameras = camerasByPatient[it.id].orEmpty(),
                nowMillis = nowMillis,
            )
        }
        .sortedBy { it.level.ordinal }

    private fun names(list: List<PersonStatus>): String =
        list.joinToString(" and ") { it.name }

    /**
     * One honest verdict across everyone.
     *
     * The branch that matters most is partial blindness: when some people are
     * watched and others are not, the line names **the ones we cannot see**.
     * Rolling that into an "all quiet" because the majority are fine is exactly
     * the pooling this file exists to prevent.
     */
    fun verdict(statuses: List<PersonStatus>): Verdict {
        if (statuses.isEmpty()) {
            return Verdict(Level.QUIET, "Welcome", "add a patient to get started")
        }
        val urgent = statuses.filter { it.level == Level.URGENT }
        val blind = statuses.filter { it.level == Level.BLIND }
        val attention = statuses.filter { it.level == Level.ATTENTION }
        val single = statuses.size == 1

        if (urgent.isNotEmpty()) {
            val first = urgent.first()
            return Verdict(
                level = Level.URGENT,
                line = if (single) {
                    "Something needs your attention"
                } else {
                    "Something needs your attention — ${names(urgent)}"
                },
                sub = "We noticed ${first.headline ?: "a serious moment"} — open " +
                    (if (single) "the moment" else names(urgent)) + " to review it",
            )
        }
        if (blind.isNotEmpty()) {
            if (blind.size == statuses.size) {
                return Verdict(
                    level = Level.BLIND,
                    line = if (single) {
                        "We can’t see ${blind.first().name} right now"
                    } else {
                        "No one is being watched right now"
                    },
                    sub = "",
                )
            }
            return Verdict(
                level = Level.BLIND,
                line = "We can’t see ${names(blind)} right now",
                sub = if (attention.isEmpty()) "Everyone else looks fine" else "",
            )
        }
        if (attention.isNotEmpty()) {
            val n = attention.size
            return Verdict(
                level = Level.ATTENTION,
                line = if (single) {
                    if (n == 1) "One thing to look at" else "$n things to look at"
                } else {
                    "Something to look at — ${names(attention)}"
                },
                sub = if (n == 1 && single) {
                    "A moment from the last day hasn’t been reviewed — nothing urgent"
                } else {
                    "Moments from the last day haven’t been reviewed — nothing urgent"
                },
            )
        }
        return Verdict(
            level = Level.QUIET,
            line = "All quiet for " + if (single) statuses.first().name else "everyone",
            sub = "nothing unusual in the last day",
        )
    }

    /** The name the briefing endpoint should write about. */
    fun briefingName(statuses: List<PersonStatus>): String =
        if (statuses.size == 1) statuses.first().name else "everyone"

    /**
     * The sentence shown immediately, before any network call returns — and the
     * one that stays if both endpoints fail.
     *
     * Names are wrapped in `**` because the same renderer draws this and the
     * AI-written prose, and the AI is instructed to bold names. Two sources, one
     * appearance.
     */
    fun templateBriefing(statuses: List<PersonStatus>): String {
        if (statuses.isEmpty()) return ""
        if (statuses.size == 1) {
            val s = statuses.first()
            val lead = if (s.level == Level.QUIET) {
                "**${s.name}** has had a calm stretch"
            } else {
                "**${s.name}** has ${s.headline ?: "something to review"}"
            }
            val days = s.daysWithoutFall
            return if (days == null) "$lead." else "$lead — it’s been $days days without a fall."
        }
        val calm = statuses.filter { it.level == Level.QUIET }.map { it.name }
        val busy = statuses.filter { it.level != Level.QUIET }
        val bits = mutableListOf<String>()
        if (calm.isNotEmpty()) {
            val joined = calm.joinToString(" and ") { "**$it**" }
            bits += "$joined ${if (calm.size == 1) "has" else "have"} had a calm day"
        }
        busy.forEach { bits += "**${it.name}** has ${it.headline ?: "something to review"}" }
        return bits.joinToString(". ") + "."
    }

    /**
     * The ⓘ expansion: exact camera truth per person, straight from the rows.
     *
     * Deliberately separate from the briefing prose, and the report prompt
     * forbids the model from mentioning camera status at all — because this is
     * the place that states it exactly, and two sources for one fact is how
     * "3 offline" ends up next to "everything is fine".
     */
    fun cameraDetail(
        patients: List<Patient>,
        camerasByPatient: Map<String, List<Camera>>,
    ): List<Pair<String, String>> = patients.map { patient ->
        val name = patient.preferredName.ifEmpty { patient.fullName }
        val cams = camerasByPatient[patient.id].orEmpty()
        if (cams.isEmpty()) return@map name to "no cameras set up"
        val off = cams.count { !it.online }
        val paused = cams.count { it.online && it.aiPaused }
        val watching = cams.count { it.online && !it.aiPaused }
        val parts = buildList {
            if (watching > 0) add("$watching watching")
            if (paused > 0) add("$paused on but AI paused")
            if (off > 0) add("$off offline")
        }
        name to parts.joinToString(" · ")
    }
}
