package com.memoria.caregiver.ui.home

import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.model.CreatePatientRequest
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RosterViewModel] behavior. Spec: `Features/Home/RosterView.swift` and the
 * doc comments on `RosterUiState.kt`.
 *
 * The client under it is the real one, driven by a Ktor [MockEngine] through
 * the real [HttpTransport], as the `data/remote` tests are. Substituting a fake
 * client would only prove that this file and the fake agree with each other —
 * and the property being tested is precisely that a change written into the
 * client's cache surfaces in the state without extra plumbing.
 *
 * No Android framework and no Robolectric: a `ViewModel` is a plain JVM object,
 * and `viewModelScope` needs only a Main dispatcher, which [setMain] supplies.
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class RosterViewModelTest {

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private companion object {
        const val BASE = "https://backend.test"
        const val P1 = "11111111-1111-4111-8111-111111111111"
        const val P2 = "22222222-2222-4222-8222-222222222222"

        fun patientJson(id: String, fullName: String) = """
            {
              "id": "$id",
              "caregiver_id": "c1",
              "full_name": "$fullName",
              "preferred_name": "${fullName.substringBefore(' ')}",
              "relation": "parent",
              "country": "US",
              "state": "calm",
              "status_line": "",
              "cameras_paused": false,
              "email_alerts_enabled": false,
              "email_alert_recipients": [],
              "data_collection_enabled": false,
              "created_at": "2026-01-04T11:00:00.000000+00:00",
              "updated_at": "2026-01-04T11:00:00.000000+00:00"
            }
        """.trimIndent()

        fun eventJson(id: String, patientId: String, opened: Boolean) = """
            {
              "id": "$id",
              "patient_id": "$patientId",
              "category": "safety",
              "source": "preset",
              "icon_name": "figure.fall",
              "title": "Possible fall",
              "location": "Kitchen",
              "occurred_at": "2026-07-30T18:20:11.482910+00:00",
              "has_clip": false,
              "narrative": "",
              "opened": $opened,
              "reviewed": false,
              "created_at": "2026-07-30T18:20:13.100000+00:00"
            }
        """.trimIndent()

        fun caregiver(displayName: String) = Caregiver(
            id = "c1",
            displayName = displayName,
            email = "ada@memoria.test",
            createdAt = Instant.fromEpochSeconds(0),
            updatedAt = Instant.fromEpochSeconds(0),
        )
    }

    /**
     * A fake backend the tests reconfigure between calls, so a refresh can
     * answer differently from the bootstrap that preceded it — which is the
     * only way to test that the roster follows the cache rather than a snapshot
     * taken once.
     */
    private class Backend {

        /** Body for `GET /v1/patients`. */
        var roster: String = "[]"

        /** Status for `GET /v1/patients`, so a refresh can fail. */
        var rosterStatus: HttpStatusCode = HttpStatusCode.OK

        /** Events served for every patient. */
        var events: String = "[]"

        /** Body for `POST /v1/patients`. */
        var created: String = ""

        /** Body for `GET /v1/patients/{id}/photo-url`. */
        var photoUrl: String =
            """{"url":"https://signed.test/photo.jpg","expires_in_seconds":600}"""

        var photoStatus: HttpStatusCode = HttpStatusCode.OK

        /** How many times `GET /v1/patients` was asked for. */
        var rosterRequests: Int = 0
            private set

        /**
         * When set, every roster request waits on it. Lets a test observe the
         * in-flight window instead of guessing at it.
         */
        var rosterGate: CompletableDeferred<Unit>? = null

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        when {
                            path == "/v1/patients" && request.method.value == "POST" ->
                                respond(created, HttpStatusCode.Created, json)
                            path == "/v1/patients" -> {
                                rosterRequests++
                                rosterGate?.await()
                                respond(roster, rosterStatus, json)
                            }
                            path.endsWith("/events") -> respond("""{"items":$events}""", headers = json)
                            path.endsWith("/photo-url") -> respond(photoUrl, photoStatus, json)
                            // Cameras and detectors are fanned out by bootstrap
                            // and refresh; the roster reads neither.
                            else -> respond("[]", headers = json)
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(NemaraJson.json) }
                },
                baseUrl = BASE,
                tokenProvider = { "test-jwt" },
            ),
        )
    }

    private val backend = Backend()
    private val caregiverFlow = MutableStateFlow<Caregiver?>(null)

    private fun viewModel() = RosterViewModel(backend.api, caregiverFlow)

    /**
     * `viewModelScope` dispatches on Main. Unconfined so a `launch` runs up to
     * its first real suspension inline, which is what lets the in-flight
     * assertions below be exact rather than timing-dependent.
     */
    @Before fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDownMain() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Initial load
    // -----------------------------------------------------------------------

    /**
     * `env.api.patients` and `env.api.eventsByPatient` in one value. The events
     * matter as much as the patients: `PatientRow` derives its whole status line
     * from them and reads nothing off `patient.state` (PARITY.md §15).
     */
    @Test fun `bootstrap puts the roster and its events into the state`() = runTest {
        backend.roster = "[${patientJson(P1, "Ada Lovelace")},${patientJson(P2, "Bob Vance")}]"
        backend.events = "[${eventJson("e1", P1, opened = false)}]"
        val vm = viewModel()

        backend.api.bootstrap()

        val state = vm.state.value
        assertEquals(listOf("Ada Lovelace", "Bob Vance"), state.patients.map { it.fullName })
        assertEquals(listOf("e1"), state.eventsByPatient[P1]?.map { it.id })
        assertFalse(state.isRefreshing)
    }

    /**
     * The greeting's name. Null before the caregiver row lands — `rosterFirstName`
     * owns the "Jane" fallback, not this.
     */
    @Test fun `the caregiver name follows the auth flow, including arriving late`() = runTest {
        val vm = viewModel()

        assertNull(vm.state.value.caregiverDisplayName)

        caregiverFlow.value = caregiver("Ada Lovelace")

        assertEquals("Ada Lovelace", vm.state.value.caregiverDisplayName)
    }

    // -----------------------------------------------------------------------
    // The empty-roster condition
    // -----------------------------------------------------------------------

    /**
     * `RosterView.swift:14` switches on `api.patients.isEmpty` **and nothing
     * else** — not on a loading flag, which is why `RosterState` has none. A
     * caregiver whose last patient was deleted on another device gets the empty
     * state back, so the state has to follow the cache down as well as up.
     */
    @Test fun `an emptied roster empties the state`() = runTest {
        backend.roster = "[${patientJson(P1, "Ada Lovelace")}]"
        val vm = viewModel()
        backend.api.bootstrap()
        assertEquals(1, vm.state.value.patients.size)

        backend.roster = "[]"
        vm.refresh()
        vm.state.first { !it.isRefreshing }

        assertTrue(vm.state.value.patients.isEmpty())
    }

    /**
     * A write through the client — not a hand-poked flow — reaches the state.
     * This is the Realtime path in miniature: `createPatient` appends to the
     * cache exactly as a Realtime INSERT handler does.
     */
    @Test fun `a patient created through the client appears in the state`() = runTest {
        val vm = viewModel()
        backend.created = patientJson(P1, "Ada Lovelace")

        backend.api.createPatient(
            CreatePatientRequest(
                fullName = "Ada Lovelace",
                preferredName = "Ada",
                relation = Patient.Relation.PARENT,
                country = "US",
            ),
        )

        assertEquals(listOf("Ada Lovelace"), vm.state.value.patients.map { it.fullName })
    }

    // -----------------------------------------------------------------------
    // Pull to refresh
    // -----------------------------------------------------------------------

    @Test fun `refresh raises the spinner while it is in flight and lowers it after`() = runTest {
        backend.roster = "[${patientJson(P1, "Ada Lovelace")}]"
        val gate = CompletableDeferred<Unit>()
        backend.rosterGate = gate
        val vm = viewModel()

        vm.refresh()
        assertTrue(vm.state.value.isRefreshing)

        gate.complete(Unit)
        vm.state.first { !it.isRefreshing }

        assertEquals(1, vm.state.value.patients.size)
    }

    /**
     * iOS's `.refreshable` closure catches the error and prints it under
     * `#if DEBUG`, so a failed drag-down says nothing and leaves the last-known
     * roster on screen. `RosterState` has no error field because of that.
     *
     * If the catch were dropped, the exception would escape `viewModelScope`
     * and fail this test rather than reaching a caregiver — which is the point
     * of asserting it here.
     */
    @Test fun `a failed refresh is silent, keeps the roster and clears the spinner`() = runTest {
        backend.roster = "[${patientJson(P1, "Ada Lovelace")}]"
        val vm = viewModel()
        backend.api.bootstrap()

        backend.rosterStatus = HttpStatusCode.Unauthorized
        vm.refresh()
        vm.state.first { !it.isRefreshing }

        assertEquals(1, vm.state.value.patients.size)
    }

    /**
     * A second drag while one refresh is in flight would double the whole
     * per-patient fan-out for no new data.
     */
    @Test fun `a refresh started while one is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        backend.rosterGate = gate
        val vm = viewModel()

        vm.refresh()
        vm.refresh()
        gate.complete(Unit)
        vm.state.first { !it.isRefreshing }

        // Two refreshes would have sent two roster requests.
        assertEquals(1, backend.rosterRequests)
    }

    // -----------------------------------------------------------------------
    // Photo URLs
    // -----------------------------------------------------------------------

    /**
     * The avatar's signed URL comes from `GET /v1/patients/{id}/photo-url`, and
     * a patient with no photo is a 404 that the API group already turns into
     * null — the roster must not error on the common case.
     */
    @Test fun `resolvePhotoUrl returns the signed url, and null when there is none`() = runTest {
        val vm = viewModel()

        assertEquals("https://signed.test/photo.jpg", vm.resolvePhotoUrl(P1))

        backend.photoStatus = HttpStatusCode.NotFound
        backend.photoUrl = ""
        assertNull(vm.resolvePhotoUrl(P1))
    }
}
