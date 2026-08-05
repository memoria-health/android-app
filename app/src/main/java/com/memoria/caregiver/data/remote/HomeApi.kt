package com.memoria.caregiver.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The three calls behind the Home briefing.
 *
 * ## Every one of these is allowed to fail
 *
 * `POST /v1/home-briefing` is described by the backend as *"a pure LLM proxy …
 * clients treat any failure as 'use the local template sentence', so this
 * endpoint can be absent (old prod) or degraded without breaking the app."*
 * The same holds for the report and for presence: they add prose and detail to
 * a screen that already computes its own verdict locally
 * (`ui/home/HomeStatus.kt`).
 *
 * So the caller never surfaces an error from any of these. That is not
 * swallowing a failure — it is the contract. Compare `loadPatients`, where a
 * failure genuinely means the screen has nothing to show.
 */
internal class HomeApi(private val http: HttpTransport) {

    /**
     * The LLM-authored Home summary: one author for the highlight box and the
     * description beneath it.
     *
     * The caregiver's timezone and local time go up because the model writes
     * "this morning" and "last night", and the server has no other way to know
     * what those mean for this person.
     */
    suspend fun homeReport(timezone: String, localTime: String): HomeReport =
        http.post(
            "/v1/home-report",
            HomeReportRequest(timezone = timezone, localTime = localTime),
        )

    /**
     * The older, simpler path: the client computes the facts and this turns
     * them into a sentence.
     *
     * Kept because the report endpoint may be absent on an older backend — and
     * because it is the only one of the two that works without database access,
     * so it degrades differently.
     *
     * [facts] is a flat name → sentence map. Nothing here is a patient
     * identifier: the server is sent display names and plain-language facts,
     * which is all the prompt needs.
     */
    suspend fun homeBriefing(name: String, facts: Map<String, String>): String =
        http.post<BriefingResponse>(
            "/v1/home-briefing",
            BriefingRequest(name = name, facts = facts),
        ).briefing

    /**
     * When a person was last seen, and when the pipeline last looked at all.
     *
     * The second field is what makes the first honest: "nobody seen for an
     * hour" means something very different when the analyser has been down for
     * an hour, and the Home screen says nothing at all in that case.
     */
    suspend fun lastPresence(patientId: String): LastPresence =
        http.get("/v1/patients/${pathId(patientId)}/last-presence")
}

@Serializable
internal data class HomeReportRequest(
    val timezone: String,
    @SerialName("local_time") val localTime: String,
)

/** `severity` is `ok | warn | urgent`; the app maps it onto its own levels. */
@Serializable
data class HomeReport(
    val severity: String = "ok",
    val highlight: String = "",
    val description: String = "",
)

@Serializable
internal data class BriefingRequest(
    val name: String,
    val facts: Map<String, String> = emptyMap(),
)

@Serializable
internal data class BriefingResponse(val briefing: String = "")

@OptIn(ExperimentalTime::class)
@Serializable
data class LastPresence(
    @SerialName("last_person_seen_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val lastPersonSeenAt: Instant? = null,
    @SerialName("last_analysis_at")
    @Serializable(with = NemaraInstantSerializer::class)
    val lastAnalysisAt: Instant? = null,
)
