package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.CreateDetectorRequest
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.InferDetectorRequest
import com.memoria.caregiver.data.model.UpdateDetectorRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector endpoints, checked against what actually reaches the wire.
 * Spec: the `MARK: - Detectors` block of `Services/NemaraAPIClient.swift` plus
 * the two detection-library calls below it.
 *
 * A wrong path or a stray key in a request body is invisible from inside the
 * app — the code compiles, the screen renders, and the failure only shows up as
 * a 404 or a pydantic 422 against the real backend. So every test here asserts
 * the method, the path and the exact JSON object that went out, and request
 * bodies are compared as whole objects rather than key by key: the backend uses
 * `extra="forbid"`, so an EXTRA key is as fatal as a missing one and a partial
 * assertion would miss it.
 */
class DetectorsApiTest {

    private companion object {
        const val PATIENT = "22222222-2222-2222-2222-222222222222"
        const val DETECTOR = "11111111-1111-1111-1111-111111111111"
    }

    // ---- Harness ----

    /** The last request the API method made, for method/path/body assertions. */
    private lateinit var sent: HttpRequestData

    /**
     * Builds an API bound to a MockEngine that answers every request with
     * [responseBody]. ContentNegotiation is installed because the transport
     * hands `setBody` a @Serializable object and something has to turn it into
     * JSON — the same plugin AppEnvironment installs, with the same [Json].
     */
    private fun api(
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): DetectorsApi {
        val engine = MockEngine { request ->
            sent = request
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(NemaraJson.json) }
        }
        return DetectorsApi(
            HttpTransport(
                client = client,
                baseUrl = "https://api.memoria.test",
                tokenProvider = { null },
            ),
        )
    }

    private fun sentBody(): JsonElement {
        val raw = when (val body = sent.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            else -> ""
        }
        return NemaraJson.json.parseToJsonElement(raw)
    }

    /** Object equality, so an extra or missing key fails. Key order is ignored. */
    private fun assertSentBody(expected: String) {
        assertEquals(NemaraJson.json.parseToJsonElement(expected), sentBody())
    }

    private fun assertSentNothing() {
        assertTrue("expected no request body", sent.body is OutgoingContent.NoContent)
    }

    /** A detector row as the backend writes it, with only the required fields. */
    private fun detectorJson(
        id: String = DETECTOR,
        name: String = "Left the house",
        source: String = "preset",
        extra: String = "",
    ) = """
        {
          "id": "$id",
          "patient_id": "$PATIENT",
          "name": "$name",
          "icon_name": "eye",
          "description": "Watches the exit doors.",
          "category": "safety",
          "source": "$source",
          "alert_trigger": "leading",
          "is_sustained": false,
          "when_scope": "ALWAYS",
          "sensitivity": "standard",
          "is_on": true,
          "created_at": "2026-05-20T10:00:00.000000+00:00",
          "updated_at": "2026-05-20T10:00:00.000000+00:00"$extra
        }
    """.trimIndent()

    // ---- loadDetectors ----

    @Test fun `loadDetectors reads the patient's detector list`() = runTest {
        val body = "[${detectorJson(name = "Stove left on")},${detectorJson(name = "Fall")}]"
        val list = api(body).loadDetectors(PATIENT)

        assertEquals(HttpMethod.Get, sent.method)
        assertEquals("/v1/patients/$PATIENT/detectors", sent.url.encodedPath)
        assertTrue("GET must not carry query parameters", sent.url.parameters.isEmpty())
        assertSentNothing()
        assertEquals(2, list.size)
    }

    /**
     * iOS stores the array exactly as the server sent it — there is no `sorted`
     * anywhere in the detector path, and the Detectors tab only splits it with
     * a filter, which keeps relative order. Sorting here would reorder the list
     * under the caregiver on every reload, so the server order is the contract.
     */
    @Test fun `loadDetectors keeps the server's order rather than sorting`() = runTest {
        val body = "[${detectorJson(name = "Stove left on")},${detectorJson(name = "Fall")}]"
        val names = api(body).loadDetectors(PATIENT).map { it.name }

        assertEquals(listOf("Stove left on", "Fall"), names)
    }

    // ---- createCustomDetector ----

    /**
     * The default-carrying fields are written even when they hold the declared
     * default, because Swift's generated encoder writes every non-optional
     * field. The unset optionals are left out entirely, and `where_scope` must
     * never appear — the backend dropped that field on 2026-07-28 and now 422s
     * on it.
     */
    @Test fun `createCustomDetector posts the full body with defaults spelled out`() = runTest {
        val created = api(detectorJson(source = "custom")).createCustomDetector(
            PATIENT,
            CreateDetectorRequest(
                name = "Front gate",
                customPrompt = "Tell me if he opens the front gate",
                isOn = false,
            ),
        )

        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("/v1/patients/$PATIENT/detectors", sent.url.encodedPath)
        assertSentBody(
            """
            {
              "name": "Front gate",
              "custom_prompt": "Tell me if he opens the front gate",
              "description": "Custom detector",
              "category": "safety",
              "alert_trigger": "leading",
              "is_sustained": false,
              "icon_name": "eye",
              "when_scope": "ALWAYS",
              "sensitivity": "standard",
              "is_on": false
            }
            """.trimIndent(),
        )
        assertEquals(Detector.Source.CUSTOM, created.source)
    }

    @Test fun `createCustomDetector adds the optional fields only when they are set`() = runTest {
        api(detectorJson(source = "custom")).createCustomDetector(
            PATIENT,
            CreateDetectorRequest(
                name = "Bed at night",
                customPrompt = "Alert if he is out of bed after 11pm",
                alertTrigger = Detector.AlertTrigger.DURATION,
                durationSeconds = 600,
                isSustained = true,
                episodeGapSeconds = 90,
                whereRooms = listOf("Bedroom"),
                whenScope = "23:00-06:00",
                scheduleConfig = Detector.ScheduleConfig(
                    cadence = Detector.ScheduleConfig.Cadence.DAILY,
                    windowStart = "23:00",
                    windowEnd = "06:00",
                ),
            ),
        )

        assertSentBody(
            """
            {
              "name": "Bed at night",
              "custom_prompt": "Alert if he is out of bed after 11pm",
              "description": "Custom detector",
              "category": "safety",
              "alert_trigger": "duration",
              "is_sustained": true,
              "duration_seconds": 600,
              "icon_name": "eye",
              "where_rooms": ["Bedroom"],
              "when_scope": "23:00-06:00",
              "sensitivity": "standard",
              "schedule_config": {
                "cadence": "daily",
                "window_start": "23:00",
                "window_end": "06:00"
              },
              "episode_gap_seconds": 90,
              "is_on": true
            }
            """.trimIndent(),
        )
    }

    // ---- inferDetector ----

    /** Not scoped to a patient: the backend infers from the caregiver's words. */
    @Test fun `inferDetector posts the prompt alone when no name was typed`() = runTest {
        api(INFER_RESPONSE).inferDetector(InferDetectorRequest(prompt = "tell me if he chokes"))

        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("/v1/detectors/infer", sent.url.encodedPath)
        assertSentBody("""{"prompt":"tell me if he chokes"}""")
    }

    @Test fun `inferDetector sends the name when the caregiver typed one`() = runTest {
        api(INFER_RESPONSE).inferDetector(
            InferDetectorRequest(name = "Choking", prompt = "tell me if he chokes"),
        )

        assertSentBody("""{"name":"Choking","prompt":"tell me if he chokes"}""")
    }

    /**
     * The review screen is built directly against these fields, so decoding has
     * to survive a partial inference: the plan card, the schedule window and the
     * episode gap are each optional, and a null means "the model was unsure",
     * not "hide the detector".
     */
    @Test fun `inferDetector decodes the plan and the schedule window`() = runTest {
        val inferred = api(INFER_RESPONSE).inferDetector(InferDetectorRequest(prompt = "x"))

        assertEquals(Detector.AlertTrigger.DURATION, inferred.alertTrigger)
        assertTrue(inferred.isSustained)
        assertEquals(600, inferred.durationSeconds)
        assertEquals(Detector.Category.ROUTINE, inferred.category)
        assertEquals("pills", inferred.iconName)
        assertEquals(Detector.Sensitivity.HIGH, inferred.sensitivity)
        assertEquals(120, inferred.episodeGapSeconds)
        assertEquals(
            Detector.ScheduleConfig.Cadence.DAILY,
            inferred.scheduleConfig?.cadence,
        )
        assertEquals("08:00", inferred.scheduleConfig?.windowStart)
        assertEquals("10:00", inferred.scheduleConfig?.windowEnd)
        assertEquals("10:30", inferred.scheduleConfig?.deadline)
        assertEquals("the kitchen counter", inferred.plan?.watches)
        assertEquals("if the pills are untouched", inferred.plan?.fires)
        assertEquals("every 30 seconds", inferred.plan?.checks)
        assertEquals(false, inferred.plan?.listens)
    }

    /** Plan composition can fail server-side; the screen hides the card, not the flow. */
    @Test fun `inferDetector decodes a response with no plan`() = runTest {
        val body = """
            {"alert_trigger":"leading","is_sustained":false,"category":"safety",
             "icon_name":"eye","sensitivity":"standard"}
        """.trimIndent()
        val inferred = api(body).inferDetector(InferDetectorRequest(prompt = "x"))

        assertNull(inferred.plan)
        assertNull(inferred.scheduleConfig)
        assertNull(inferred.durationSeconds)
        assertNull(inferred.episodeGapSeconds)
    }

    // ---- updateDetector ----

    @Test fun `updateDetector patches only the fields that were set`() = runTest {
        api(detectorJson()).updateDetector(
            DETECTOR,
            UpdateDetectorRequest(isOn = false, sensitivity = Detector.Sensitivity.HIGH),
        )

        assertEquals(HttpMethod.Patch, sent.method)
        assertEquals("/v1/detectors/$DETECTOR", sent.url.encodedPath)
        assertSentBody("""{"sensitivity":"high","is_on":false}""")
    }

    /**
     * Swift builds every path from `uuidString.lowercased()`. Backend ids are
     * already lowercase, so this only matters for one that arrived from
     * elsewhere — a deep link, say — but the paths must agree either way.
     */
    @Test fun `detector ids are lowercased into the path`() = runTest {
        api(detectorJson()).updateDetector(
            DETECTOR.uppercase(),
            UpdateDetectorRequest(name = "Renamed"),
        )

        assertEquals("/v1/detectors/$DETECTOR", sent.url.encodedPath)
    }

    // ---- refineDetector ----

    /**
     * `preview` is written even when false. It defaults to false server-side
     * too, but iOS declares it non-optional on the inline body type, so it is
     * always on the wire and this pins that.
     */
    @Test fun `refineDetector commits the feedback by default`() = runTest {
        val updated = api(detectorJson(extra = ""","refinements":["stop counting eating"]"""))
            .refineDetector(DETECTOR, feedback = "stop counting eating")

        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("/v1/detectors/$DETECTOR/refine", sent.url.encodedPath)
        assertSentBody("""{"feedback":"stop counting eating","preview":false}""")
        assertEquals(listOf("stop counting eating"), updated.refinements)
    }

    /**
     * The dry run. The response is a full detector describing what the change
     * WOULD produce; nothing was persisted, which is why the criteria are read
     * off the return value instead of a reload.
     */
    @Test fun `refineDetector previews without committing`() = runTest {
        val previewed = api(detectorJson(extra = ""","user_criteria":["Counts: leaving via the front door"]"""))
            .refineDetector(DETECTOR, feedback = "ignore the patio", preview = true)

        assertSentBody("""{"feedback":"ignore the patio","preview":true}""")
        assertEquals(listOf("Counts: leaving via the front door"), previewed.userCriteria)
    }

    // ---- deleteDetector ----

    @Test fun `deleteDetector sends DELETE with no body and decodes nothing`() = runTest {
        api("", status = HttpStatusCode.NoContent).deleteDetector(DETECTOR)

        assertEquals(HttpMethod.Delete, sent.method)
        assertEquals("/v1/detectors/$DETECTOR", sent.url.encodedPath)
        assertSentNothing()
    }

    // ---- Detection library ----

    @Test fun `fetchDetectorLibrary reads the catalog for the patient's cohort`() = runTest {
        val body = """
            [{"key":"fall","name":"Fall","icon_name":"figure.fall",
              "description":"Watches for a fall.","category":"safety","cohort":"adult",
              "recommended":true,"already_added":true,"accuracy_caveat":true},
             {"key":"medication","name":"Medication","icon_name":"pills",
              "description":"Watches the pill box.","category":"routine","cohort":"both",
              "recommended":false,"already_added":false}]
        """.trimIndent()
        val entries = api(body).fetchDetectorLibrary(PATIENT)

        assertEquals(HttpMethod.Get, sent.method)
        assertEquals("/v1/patients/$PATIENT/detectors/library", sent.url.encodedPath)
        assertSentNothing()
        assertEquals(listOf("fall", "medication"), entries.map { it.key })
        assertTrue(entries[0].alreadyAdded)
        assertTrue(entries[0].showsAccuracyCaveat)
        // Absent on the wire, and false to the UI rather than a decode failure.
        assertNull(entries[1].accuracyCaveat)
        assertEquals(false, entries[1].showsAccuracyCaveat)
    }

    /**
     * Keys the patient already has are skipped server-side, so the response can
     * be shorter than the request. Pinned because a caller that assumed one row
     * per key would double-count the library selection.
     */
    @Test fun `addDetectorsFromLibrary posts the keys and returns only what was created`() = runTest {
        val created = api("[${detectorJson(name = "Fall")}]")
            .addDetectorsFromLibrary(PATIENT, keys = listOf("fall", "medication"))

        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("/v1/patients/$PATIENT/detectors/from-library", sent.url.encodedPath)
        assertSentBody("""{"keys":["fall","medication"]}""")
        assertEquals(1, created.size)
        assertEquals("Fall", created[0].name)
    }
}

/** A fully-populated inference, the shape the create review screen reads. */
private val INFER_RESPONSE = """
    {
      "alert_trigger": "duration",
      "is_sustained": true,
      "duration_seconds": 600,
      "category": "routine",
      "icon_name": "pills",
      "sensitivity": "high",
      "schedule_config": {
        "cadence": "daily",
        "days_of_week": [0, 1, 2, 3, 4],
        "window_start": "08:00",
        "window_end": "10:00",
        "deadline": "10:30"
      },
      "episode_gap_seconds": 120,
      "plan": {
        "watches": "the kitchen counter",
        "fires": "if the pills are untouched",
        "checks": "every 30 seconds",
        "listens": false
      }
    }
""".trimIndent()
