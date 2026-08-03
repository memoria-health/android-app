package com.memoria.caregiver.ui.settings

import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.model.CreatePatientRequest
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.remote.ApiError
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AppSettingsViewModel] behavior. Spec:
 * `Features/AppSettings/AppSettingsView.swift`, PARITY.md §28.
 *
 * The delete runs through the real client on a Ktor [MockEngine], exactly as
 * `AppEnvironment` wires it (`auth.onDeleteAccount = { api.deleteAccount() }`),
 * so the error sentence this asserts is the one the real transport produces
 * rather than one the test invented.
 *
 * Plain JVM: a `ViewModel` needs no Android runtime, and `viewModelScope` needs
 * only the Main dispatcher [setMain] installs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsViewModelTest {

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private companion object {
        const val BASE = "https://backend.test"
        const val P1 = "11111111-1111-4111-8111-111111111111"

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
    }

    /** The fake backend behind the real client. */
    private class Backend {

        var roster: String = "[]"

        var created: String = ""

        /** Status for `DELETE /v1/me`. */
        var deleteStatus: HttpStatusCode = HttpStatusCode.NoContent

        /** When set, `DELETE /v1/me` waits on it so the in-flight window is observable. */
        var deleteGate: CompletableDeferred<Unit>? = null

        var deleteRequests: Int = 0
            private set

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        when {
                            path == "/v1/me" && request.method.value == "DELETE" -> {
                                deleteRequests++
                                deleteGate?.await()
                                respond("", deleteStatus, json)
                            }
                            path == "/v1/patients" && request.method.value == "POST" ->
                                respond(created, HttpStatusCode.Created, json)
                            path == "/v1/patients" -> respond(roster, headers = json)
                            path.endsWith("/events") -> respond("""{"items":[]}""", headers = json)
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
    private var signOuts = 0

    /**
     * Wired the way `AppEnvironment` wires the real one: sign-out is the auth
     * service's, and the delete is the backend call the auth service makes on
     * its way through. The service itself is not constructed here — it needs a
     * live Supabase client — which is why the view model takes these two as
     * functions.
     */
    private fun viewModel() = AppSettingsViewModel(
        api = backend.api,
        caregiver = caregiverFlow,
        onSignOut = { signOuts++ },
        onDeleteAccount = { backend.api.deleteAccount() },
    )

    @Before fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDownMain() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // The card and the roster
    // -----------------------------------------------------------------------

    /**
     * Before bootstrap there is no caregiver and no roster, and the card falls
     * back to the placeholder copy iOS ships — the state must carry the null
     * rather than an empty-string caregiver, because the fallback keys on the
     * *row* being absent (see `caregiverDisplayName`).
     */
    @Test fun `an unloaded settings tab is a null caregiver and no patients`() = runTest {
        val vm = viewModel()

        assertNull(vm.state.value.caregiver)
        assertTrue(vm.state.value.patients.isEmpty())
        assertEquals("Jane Patel", vm.state.value.caregiverDisplayName)
        assertFalse(vm.state.value.isDeleting)
        assertNull(vm.state.value.deleteError)
    }

    @Test fun `the caregiver and the roster reach the state`() = runTest {
        backend.roster = "[${patientJson(P1, "Margaret Holloway")}]"
        val vm = viewModel()

        caregiverFlow.value = caregiver(displayName = "Ada Lovelace")
        backend.api.bootstrap()

        assertEquals("Ada Lovelace", vm.state.value.caregiverDisplayName)
        assertEquals(listOf("Margaret Holloway"), vm.state.value.patients.map { it.fullName })
    }

    /**
     * A write through the client reaches the state with nothing in between —
     * the same path a Realtime INSERT takes into the cache. "Add another
     * patient" leaves this screen up, so the new row has to appear under it.
     */
    @Test fun `a patient created through the client appears in the state`() = runTest {
        val vm = viewModel()
        backend.created = patientJson(P1, "Margaret Holloway")

        backend.api.createPatient(
            CreatePatientRequest(
                fullName = "Margaret Holloway",
                preferredName = "Peggy",
                relation = Patient.Relation.PARENT,
                country = "US",
            ),
        )

        assertEquals(listOf("Margaret Holloway"), vm.state.value.patients.map { it.fullName })
    }

    // -----------------------------------------------------------------------
    // Account deletion
    // -----------------------------------------------------------------------

    /**
     * The alert's sentence is `ApiError.errorDescription`, verbatim. It is
     * product copy pinned by `ApiErrorTest`, so the literal is spelled out here
     * as well as compared: a paraphrase in the view model would still equal
     * `errorDescription` if both were changed together, and the point is that
     * neither may drift alone.
     */
    @Test fun `a failed delete surfaces the api error copy verbatim`() = runTest {
        backend.deleteStatus = HttpStatusCode.Unauthorized
        val vm = viewModel()

        vm.deleteAccount()
        vm.state.first { !it.isDeleting }

        assertEquals(ApiError.Unauthorized.errorDescription, vm.state.value.deleteError)
        assertEquals(
            "Couldn't verify your session with the server. " +
                "Check your connection, or sign in again.",
            vm.state.value.deleteError,
        )
    }

    /** The alert's OK button. `deleteError == null` is its dismissal. */
    @Test fun `dismissing the delete error clears it`() = runTest {
        backend.deleteStatus = HttpStatusCode.Forbidden
        val vm = viewModel()

        vm.deleteAccount()
        vm.state.first { !it.isDeleting }
        assertEquals(ApiError.Forbidden.errorDescription, vm.state.value.deleteError)

        vm.dismissDeleteError()

        assertNull(vm.state.value.deleteError)
    }

    /**
     * `isDeleting` disables the row while the call is in flight, and iOS clears
     * it in a `defer` so a thrown error cannot leave it disabled forever.
     */
    @Test fun `isDeleting is raised in flight and cleared even when the delete fails`() = runTest {
        backend.deleteStatus = HttpStatusCode.InternalServerError
        val gate = CompletableDeferred<Unit>()
        backend.deleteGate = gate
        val vm = viewModel()

        vm.deleteAccount()
        assertTrue(vm.state.value.isDeleting)

        gate.complete(Unit)
        vm.state.first { !it.isDeleting }

        assertFalse(vm.state.value.isDeleting)
        assertEquals(ApiError.ServerError(null, null).errorDescription, vm.state.value.deleteError)
    }

    /**
     * A second tap must not fire a second irreversible delete. The row is
     * disabled while `isDeleting`, but the callback is a plain function.
     */
    @Test fun `a second delete while one is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        backend.deleteGate = gate
        val vm = viewModel()

        vm.deleteAccount()
        vm.deleteAccount()
        gate.complete(Unit)
        vm.state.first { !it.isDeleting }

        assertEquals(1, backend.deleteRequests)
    }

    /**
     * A successful delete writes no success state: `currentCaregiver` goes null
     * and the root swaps to the welcome flow, so this screen is gone.
     */
    @Test fun `a successful delete leaves no error behind`() = runTest {
        val vm = viewModel()

        vm.deleteAccount()
        vm.state.first { !it.isDeleting }

        assertNull(vm.state.value.deleteError)
        assertEquals(1, backend.deleteRequests)
    }

    @Test fun `sign out is forwarded to the auth service`() = runTest {
        val vm = viewModel()

        vm.signOut()

        assertEquals(1, signOuts)
    }
}
