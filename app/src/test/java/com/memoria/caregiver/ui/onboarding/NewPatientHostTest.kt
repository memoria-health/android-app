package com.memoria.caregiver.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import com.memoria.caregiver.ui.permissions.PermissionState
import com.memoria.caregiver.ui.theme.MemoriaTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * `NewPatientHost` — the create, and what follows it.
 *
 * The form itself is covered by `NewPatientScreenTest` and the three post-create
 * steps by `PostCreateEnrollmentFlowTest`. What is left, and what this covers, is
 * the join: that a successful create actually advances into that flow rather
 * than sitting there, that a failed one says so instead of silently doing
 * nothing, and that **Back has somewhere to go at every step** — including the
 * consent screen, which owns no Back handling of its own and would otherwise
 * quit the app on a patient that had just been created (FINDINGS.md F-25).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NewPatientHostTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val createdPatientJson = """
        {
          "id": "11111111-1111-4111-8111-111111111111",
          "caregiver_id": "cg-1",
          "full_name": "Margaret Holloway",
          "preferred_name": "Margaret Holloway",
          "relation": "other",
          "state": "calm",
          "status_line": "All quiet",
          "cameras_paused": false,
          "email_alerts_enabled": true,
          "email_alert_recipients": [],
          "data_collection_enabled": false,
          "created_at": "2026-01-04T11:00:00.000000+00:00",
          "updated_at": "2026-01-04T11:00:00.000000+00:00"
        }
    """.trimIndent()

    private class Backend {
        var createStatus: HttpStatusCode = HttpStatusCode.OK
        var createBody: String = ""
        val seen = mutableListOf<String>()
    }

    private fun apiFor(backend: Backend): NemaraApiClient = NemaraApiClient(
        HttpTransport(
            client = HttpClient(
                MockEngine { request ->
                    backend.seen += request.method.value + " " + request.url.encodedPath
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    when {
                        request.url.encodedPath == "/v1/patients" &&
                            request.method.value == "POST" ->
                            respond(backend.createBody, backend.createStatus, json)
                        // Everything the post-create flow reaches for. Empty is a
                        // valid answer for all of it and keeps this test about
                        // the join rather than about the library.
                        else -> respond("[]", HttpStatusCode.OK, json)
                    }
                },
            ) {
                install(ContentNegotiation) { json(NemaraJson.json) }
            },
            baseUrl = "https://backend.test",
            tokenProvider = { "test-jwt" },
        ),
    )

    private val deniedPermission = PermissionState(granted = false, denied = true, request = {})

    /**
     * The form, pre-filled to the point where Add patient is live. The date of
     * birth is seeded rather than picked so this stays a test of the create and
     * not of the Material date-picker dialog.
     */
    private fun filledForm() = NewPatientState(
        fullName = "Margaret Holloway",
        dateOfBirth = LocalDate.of(1955, 3, 1),
        gender = "Female",
    )

    private fun setUp(backend: Backend, onDismiss: () -> Unit = {}) {
        compose.setContent {
            MemoriaTheme {
                Box(Modifier.fillMaxSize()) {
                    BackHandler { fellThrough++ }
                    NewPatientHost(
                        api = apiFor(backend),
                        cameraPermission = deniedPermission,
                        onDismiss = onDismiss,
                        initialState = filledForm(),
                    )
                }
            }
        }
    }

    private var fellThrough = 0

    @Test
    fun `a successful create advances into the post-create flow`() {
        val backend = Backend().apply { createBody = createdPatientJson }
        setUp(backend)

        compose.onNodeWithText("Add patient").performClick()
        compose.waitForIdle()

        assertTrue(backend.seen.contains("POST /v1/patients"))
        // Step 1 of the flow is the detector library, which is what proves the
        // cover swapped rather than the form simply clearing.
        compose.onNodeWithText("Detection library").assertIsDisplayed()
        // And that it is a live step, not just its chrome: the catalog fetch is
        // the host's to start, and a library that renders but never loads shows
        // an empty picker with no error on it.
        assertTrue(
            backend.seen.contains(
                "GET /v1/patients/11111111-1111-4111-8111-111111111111/detectors/library",
            ),
        )
    }

    @Test
    fun `a failed create says so and stays on the form`() {
        val backend = Backend().apply {
            createStatus = HttpStatusCode.InternalServerError
            createBody = """{"detail":"Couldn't add that patient."}"""
        }
        setUp(backend)

        compose.onNodeWithText("Add patient").performClick()
        compose.waitForIdle()

        // Inline and verbatim, because the caregiver has to know the patient was
        // NOT created — the one thing a silent failure here would hide.
        compose.onNodeWithText("Couldn't add that patient.").assertIsDisplayed()
    }

    @Test
    fun `Back on the form leaves rather than reaching the Activity`() {
        var dismissals = 0
        setUp(Backend(), onDismiss = { dismissals++ })

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(1, dismissals)
        assertEquals(0, fellThrough)
    }

    @Test
    fun `Back on the consent step leaves rather than quitting the app`() {
        // The step with no Back handling of its own. Reaching it takes a create
        // and then the library's Skip, which is the only way this path exists at
        // all — and the reason it was easy to miss.
        var dismissals = 0
        val backend = Backend().apply { createBody = createdPatientJson }
        setUp(backend, onDismiss = { dismissals++ })

        compose.onNodeWithText("Add patient").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Would you like to add facial detection?").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(1, dismissals)
        assertEquals(0, fellThrough)
    }

    @Test
    fun `Back during the detector step is the library's own leading action`() {
        // Not the host's: the library host registers deeper, so Skip-by-Back
        // continues the flow instead of abandoning a patient that now exists.
        var dismissals = 0
        val backend = Backend().apply { createBody = createdPatientJson }
        setUp(backend, onDismiss = { dismissals++ })

        compose.onNodeWithText("Add patient").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(0, dismissals)
        compose.onNodeWithText("Would you like to add facial detection?").assertIsDisplayed()
    }
}
