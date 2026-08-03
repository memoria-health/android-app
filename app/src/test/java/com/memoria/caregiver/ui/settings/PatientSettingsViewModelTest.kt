package com.memoria.caregiver.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.HttpTransport
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.data.remote.NemaraJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PatientSettingsViewModel] behavior. Spec:
 * `Features/Patient/Settings/PatientSettingsView.swift` and its four cards,
 * plus `DataCollectionConsentSheet.swift` and PARITY.md §24.
 *
 * The real client under a Ktor `MockEngine`, as `CameraDetailViewModelTest` is.
 * The properties worth pinning here — that a delete leaves the screen, that a
 * decision takes the consent prompt down for good, that the audio card's failure
 * sentence is NOT the API's — are properties of this object plus the client
 * together, and a hand-written fake client would define them away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientSettingsViewModelTest {

    private companion object {
        const val PATIENT = "11111111-1111-4111-8111-111111111111"
        const val OTHER = "22222222-2222-4222-8222-222222222222"

        /**
         * `decidedAt = null` is the state the consent sheet keys off, so it is
         * the default here rather than something a test has to remember to ask
         * for.
         */
        fun patientJson(
            id: String = PATIENT,
            preferredName: String = "Peggy",
            audioCaptureEnabled: Boolean? = null,
            emailAlertsEnabled: Boolean = true,
            recipients: List<String> = emptyList(),
            dataCollectionEnabled: Boolean = false,
            decidedAt: String? = null,
            photoStorageKey: String? = null,
        ) = """
            {
              "id": "$id",
              "caregiver_id": "cg-1",
              "full_name": "Margaret Holloway",
              "preferred_name": "$preferredName",
              "relation": "parent",
              "state": "calm",
              "status_line": "All quiet",
              "cameras_paused": false,
              ${audioCaptureEnabled?.let { """"audio_capture_enabled": $it,""" } ?: ""}
              ${decidedAt?.let { """"data_collection_decided_at": "$it",""" } ?: ""}
              ${photoStorageKey?.let { """"photo_storage_key": "$it",""" } ?: ""}
              "email_alerts_enabled": $emailAlertsEnabled,
              "email_alert_recipients": ${recipients.joinToString(",", "[", "]") { "\"$it\"" }},
              "data_collection_enabled": $dataCollectionEnabled,
              "created_at": "2026-01-04T11:00:00.000000+00:00",
              "updated_at": "2026-01-04T11:00:00.000000+00:00"
            }
        """.trimIndent()

        fun summaryJson(selfies: Int = 0, harvested: Int = 0, processing: Int = 0) = """
            {"selfie_count":$selfies,"harvested_count":$harvested,
             "processing_count":$processing,"failed_count":0}
        """.trimIndent()
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private class Backend {

        var patients: String = "[${patientJson()}]"

        /** The row `PATCH /v1/patients/{id}` echoes back. */
        var updated: String = patientJson()
        var updateStatus: HttpStatusCode = HttpStatusCode.OK

        /** Held open, so a busy flag can be observed mid-flight. */
        var updateGate: CompletableDeferred<Unit>? = null

        var summary: String = summaryJson()
        var summaryStatus: HttpStatusCode = HttpStatusCode.OK
        var summaryRequests: Int = 0

        var testEmail: String = """{"status":"Sent to 2 recipients."}"""
        var testEmailStatus: HttpStatusCode = HttpStatusCode.OK

        var photoRow: String = patientJson(photoStorageKey = "$PATIENT.jpg")
        var photoStatus: HttpStatusCode = HttpStatusCode.OK

        var deleteStatus: HttpStatusCode = HttpStatusCode.NoContent

        /** Every request the fake server saw, as "METHOD path". */
        val seen: MutableList<String> = mutableListOf()

        /** Bodies of the PATCHes, in order. Face payloads are never captured. */
        val patchBodies: MutableList<String> = mutableListOf()

        /**
         * Held so the teardown can wait for a request still in flight: Ktor runs
         * the engine on its own dispatcher, so a call that outlives the test body
         * would otherwise fail into whichever test runs next.
         */
        val client: HttpClient = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val method = request.method.value
                seen += "$method $path"
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    path.endsWith("/face-embeddings/summary") -> {
                        summaryRequests++
                        respond(summary, summaryStatus, json)
                    }

                    path.endsWith("/test-email") -> respond(testEmail, testEmailStatus, json)

                    path.endsWith("/photo") && method == "POST" ->
                        respond(photoRow, photoStatus, json)

                    path.endsWith("/photo") && method == "DELETE" ->
                        respond("", photoStatus, json)

                    method == "PATCH" -> {
                        patchBodies += String(request.body.toByteArray())
                        updateGate?.await()
                        respond(updated, updateStatus, json)
                    }

                    method == "DELETE" -> respond("", deleteStatus, json)

                    else -> respond(patients, headers = json)
                }
            },
        ) {
            install(ContentNegotiation) { json(NemaraJson.json) }
        }

        val api: NemaraApiClient = NemaraApiClient(
            HttpTransport(
                client = client,
                baseUrl = "https://backend.test",
                tokenProvider = { "test-jwt" },
            ),
        )
    }

    private lateinit var backend: Backend

    private val caregiverFlow = MutableStateFlow<Caregiver?>(caregiver())

    /**
     * The device-local blur preference, as the flow + setter seam the primary
     * constructor takes. A real `PeopleBlurStore` needs `SharedPreferences`,
     * which is an Android stub on the JVM — the seam exists so this test does not
     * need Robolectric to prove the preference reaches the state.
     */
    private val blurred = MutableStateFlow<Set<String>>(emptySet())

    /** Set by `onDeleted`, which is the screen's pop. */
    private var dismissals = 0

    private val viewModels = mutableListOf<PatientSettingsViewModel>()

    private fun viewModel(): PatientSettingsViewModel {
        val vm = PatientSettingsViewModel(
            api = backend.api,
            patientId = PATIENT,
            caregiver = caregiverFlow,
            blurredPatientIds = blurred,
            setPeopleBlurEnabled = { on ->
                blurred.value = if (on) blurred.value + PATIENT else blurred.value - PATIENT
            },
            onDeleted = { dismissals++ },
        )
        viewModels += vm
        // `stateIn(started = Eagerly)` is already collecting by now, and that
        // coroutine never ends.
        background = vm.viewModelScope.coroutineContext.job.children.toSet()
        return vm
    }

    /**
     * One dispatcher, shared by `Dispatchers.Main` and by `runTest`, so there is
     * exactly one scheduler in play — `CameraDetailViewModelTest` records why: an
     * unconfined dispatcher resumes the `stateIn` sharing coroutine on whichever
     * thread Ktor's `MockEngine` finished on, through an event loop this test
     * cannot drain, and `state.value` then lags its own cache intermittently.
     */
    private val mainDispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        backend = Backend()
        Dispatchers.setMain(mainDispatcher)
    }

    /**
     * Stop everything the view model owns BEFORE Main goes away.
     * `viewModelScope` outlives `runTest` — Android cancels it in `onCleared`
     * and nothing does that here — so the eager sharing coroutine, or the
     * enrollment poll's next pass, would otherwise resume after `resetMain` and
     * fail whichever test happened to be running next.
     *
     * Cancelled and then drained, **not** joined: `join()` would wait forever,
     * because the only thing that runs a [StandardTestDispatcher]'s queue is the
     * scheduler, and after `runTest` returns nobody is driving it. Draining it
     * by hand is what lets the cancellation actually take effect.
     */
    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        mainDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    /** Let Main publish what a direct client call just wrote. */
    private fun idle() = mainDispatcher.scheduler.advanceUntilIdle()

    private var background: Set<Job> = emptySet()

    /**
     * Wait for every coroutine an action started, and for nothing else. The
     * actions are fire-and-forget `viewModelScope.launch`es and `MockEngine`
     * genuinely hops threads, so joining the scope's children is the only exact
     * wait — with the never-ending sharing coroutine excluded, which is what
     * [background] is for.
     */
    private suspend fun ViewModel.settle() {
        while (true) {
            mainDispatcher.scheduler.advanceUntilIdle()
            val pending = viewModelScope.coroutineContext.job.children
                .filterNot { it in background }
                .toList()
            if (pending.isEmpty()) break
            pending.joinAll()
        }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    /** Compares a PATCH body as JSON, so key order and spacing cannot fail it. */
    private fun assertLastPatch(expected: String) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(backend.patchBodies.last()),
        )
    }

    // -----------------------------------------------------------------------
    // Derivation
    // -----------------------------------------------------------------------

    /**
     * Three live sources, one state. The patient is looked up by id, so a row
     * replaced by a PATCH, by Realtime or by the poll reaches the cards with
     * nothing else plumbed in between — and a patient deleted elsewhere turns it
     * null rather than leaving a stale copy on screen.
     */
    @Test fun `the patient, the account email and the blur flag are read off their live sources`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(id = OTHER)},${patientJson(preferredName = "Peggy")}]"
            blurred.value = setOf(PATIENT)
            val vm = viewModel()

            backend.api.loadPatients()
            idle()

            assertEquals("Peggy", vm.state.value.patient?.preferredName)
            assertEquals("aryaman@memoria.care", vm.state.value.accountEmail)
            assertTrue(vm.state.value.peopleBlurEnabled)

            // The blur toggle is device-local and synchronous: no request, no
            // busy flag, and the state follows immediately.
            vm.setPeopleBlur(false)
            idle()
            assertFalse(vm.state.value.peopleBlurEnabled)
            assertEquals(1, backend.seen.size)
        }

    /**
     * `env.auth.currentCaregiver?.email ?? "your account"`. The literal is what
     * the read-only recipients row shows before the caregiver row has loaded, so
     * it is product copy rather than a placeholder.
     */
    @Test fun `a missing caregiver falls back to the account-email literal`() =
        runTest(mainDispatcher) {
            caregiverFlow.value = null
            val vm = viewModel()

            idle()

            assertEquals("your account", vm.state.value.accountEmail)
        }

    // -----------------------------------------------------------------------
    // First-open data-collection consent
    // -----------------------------------------------------------------------

    /**
     * `dataCollectionDecidedAt == nil` is the whole condition: never asked means
     * ask, and the backend stamps the timestamp on either answer.
     */
    @Test fun `the consent prompt is raised only while the patient has never decided`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(decidedAt = null)}]"
            backend.api.loadPatients()

            assertTrue(viewModel().showDataCollectionConsent.value)

            backend.patients = "[${patientJson(decidedAt = "2026-07-01T10:00:00.000000+00:00")}]"
            backend.api.loadPatients()

            assertFalse(viewModel().showDataCollectionConsent.value)
        }

    /**
     * "Not now" is a decision, not a deferral: it PATCHes `false`, the backend
     * stamps `decided_at`, and the prompt is gone. Asserting the body is the
     * point — sending nothing would leave the sheet re-nagging on every visit.
     */
    @Test fun `declining records the decision and takes the prompt down`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(decidedAt = null)}]"
            backend.updated = patientJson(
                dataCollectionEnabled = false,
                decidedAt = "2026-07-01T10:00:00.000000+00:00",
            )
            backend.api.loadPatients()
            val vm = viewModel()
            assertTrue(vm.showDataCollectionConsent.value)

            vm.setDataCollectionEnabled(false)

            vm.settle()

            assertLastPatch("""{"data_collection_enabled":false}""")
            assertFalse(vm.showDataCollectionConsent.value)
            assertNull(vm.state.value.dataCollection.errorText)
        }

    /**
     * A failed PATCH keeps the sheet up rather than recording a phantom
     * decision, and shows [ApiError.errorDescription] verbatim — the card and
     * the sheet share the one mutation, so they share its message.
     */
    @Test fun `a failed decision keeps the prompt up and surfaces the API copy`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(decidedAt = null)}]"
            backend.updateStatus = HttpStatusCode.Forbidden
            backend.updated = ""
            backend.api.loadPatients()
            val vm = viewModel()

            vm.setDataCollectionEnabled(true)

            vm.settle()

            assertTrue(vm.showDataCollectionConsent.value)
            assertEquals(
                ApiError.Forbidden.errorDescription,
                vm.state.value.dataCollection.errorText,
            )
            assertFalse(vm.state.value.dataCollection.busy)
        }

    /**
     * Swiped away without answering. Nothing is sent — and the prompt does not
     * spring back, even though `decidedAt` is still null, because a flow that
     * merely tracked that field would make the sheet unclosable.
     */
    @Test fun `dismissing without deciding sends nothing and does not re-raise`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(decidedAt = null)}]"
            backend.api.loadPatients()
            val vm = viewModel()
            val requests = backend.seen.size

            vm.dismissDataCollectionConsent()
            idle()

            assertFalse(vm.showDataCollectionConsent.value)
            assertEquals(requests, backend.seen.size)
        }

    // -----------------------------------------------------------------------
    // Face enrollment
    // -----------------------------------------------------------------------

    /**
     * The card flips from "in progress" to "complete" on its own, which is the
     * entire reason the poll exists — and it stops as soon as nothing is
     * processing rather than burning the remaining thirty-eight attempts.
     */
    @Test fun `the enrollment poll publishes each summary and stops once nothing is processing`() =
        runTest(mainDispatcher) {
            backend.summary = summaryJson(selfies = 1, processing = 2)
            val vm = viewModel()
            backgroundScope.launch { vm.runEnrollmentPoll() }

            val processing = vm.state.first { it.enrollmentSummary != null }
            assertEquals(
                IdentityCardState.Processing,
                identityCardState(processing.enrollmentSummary),
            )

            // Pass two, two virtual seconds later: the batch has finished.
            backend.summary = summaryJson(selfies = 3, processing = 0)
            advanceTimeBy(2_001)
            val done = vm.state.first { it.enrollmentSummary?.isProcessing == false }
            assertEquals(IdentityCardState.Enrolled, identityCardState(done.enrollmentSummary))

            val requests = backend.summaryRequests
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(requests, backend.summaryRequests)
        }

    /**
     * `reloadToken += 1` after re-enrollment. Without it the card sits on the
     * counts it read before the caregiver added photos.
     */
    @Test fun `reloading restarts the poll and republishes the summary`() =
        runTest(mainDispatcher) {
            backend.summary = summaryJson(selfies = 0, processing = 0)
            val vm = viewModel()
            backgroundScope.launch { vm.runEnrollmentPoll() }
            val first = vm.state.first { it.enrollmentSummary != null }
            assertEquals(IdentityCardState.NotEnrolled, identityCardState(first.enrollmentSummary))

            backend.summary = summaryJson(selfies = 4, processing = 0)
            vm.reloadEnrollmentSummary()

            val second = vm.state.first { it.enrollmentSummary?.selfieCount == 4 }
            assertEquals(IdentityCardState.Enrolled, identityCardState(second.enrollmentSummary))
        }

    /**
     * Best effort, as iOS's `catch { NSLog }` is: a transient failure leaves the
     * card in its previous state instead of blanking it or raising an error the
     * caregiver cannot act on. The card has no error slot at all, so a failure
     * that cleared the summary would silently regress it to "Patient identity".
     */
    @Test fun `a failed summary fetch leaves the card as it was`() = runTest(mainDispatcher) {
        backend.summary = summaryJson(selfies = 2, processing = 1)
        val vm = viewModel()
        backgroundScope.launch { vm.runEnrollmentPoll() }
        vm.state.first { it.enrollmentSummary != null }

        backend.summaryStatus = HttpStatusCode.InternalServerError
        backend.summary = ""
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(2, vm.state.value.enrollmentSummary?.selfieCount)
        assertEquals(
            IdentityCardState.Processing,
            identityCardState(vm.state.value.enrollmentSummary),
        )
    }

    // -----------------------------------------------------------------------
    // The toggle cards
    // -----------------------------------------------------------------------

    /**
     * The audio kill sends the flag, and the state follows the row the server
     * echoed — nothing here writes the toggle's own guess into the cache.
     */
    @Test fun `the audio toggle sends the flag and adopts the row the server returned`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(audioCaptureEnabled = true)}]"
            backend.updated = patientJson(audioCaptureEnabled = false)
            backend.api.loadPatients()
            val vm = viewModel()

            vm.setAudioCapture(false)

            vm.settle()

            assertLastPatch("""{"audio_capture_enabled":false}""")
            assertEquals(false, vm.state.value.patient?.audioCaptureEnabled)
            assertFalse(vm.state.value.audioCapture.busy)
        }

    /**
     * **The audio card's failure sentence is its own, not the API's**, because it
     * renders in place of the body copy and has to read like body copy
     * (PARITY.md §24). The second assertion is the one that matters: the two
     * cards must not converge on one message.
     */
    @Test fun `a failed audio toggle shows the fixed sentence, not the API copy`() =
        runTest(mainDispatcher) {
            backend.updateStatus = HttpStatusCode.InternalServerError
            backend.updated = ""
            backend.api.loadPatients()
            val vm = viewModel()

            vm.setAudioCapture(false)

            vm.settle()

            assertEquals(
                "Couldn't update audio setting. Try again.",
                vm.state.value.audioCapture.errorText,
            )
            assertNull(vm.state.value.dataCollection.errorText)
            assertNull(vm.state.value.emailAlerts.errorText)
            assertFalse(vm.state.value.audioCapture.busy)
        }

    /**
     * `guard !busy else { return }`. The card disables its toggle, but the
     * callback is a plain function and two overlapping PATCHes of the same
     * column would settle on whichever reply landed last.
     */
    @Test fun `a second audio flip while one is in flight is refused`() = runTest(mainDispatcher) {
        backend.api.loadPatients()
        val gate = CompletableDeferred<Unit>()
        backend.updateGate = gate
        val vm = viewModel()

        vm.setAudioCapture(false)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.audioCapture.busy)
        vm.setAudioCapture(true)

        gate.complete(Unit)
        vm.settle()

        assertEquals(1, backend.patchBodies.size)
    }

    // -----------------------------------------------------------------------
    // Email alerts
    // -----------------------------------------------------------------------

    /**
     * The server replaces the column wholesale, so the whole array goes on the
     * wire — and the draft is cleared, because this call added what was typed.
     */
    @Test fun `adding a recipient sends the whole array and clears the draft`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(recipients = listOf("carol@example.com"))}]"
            backend.updated =
                patientJson(recipients = listOf("carol@example.com", "dan@example.com"))
            backend.api.loadPatients()
            val vm = viewModel()
            vm.setEmailDraft("dan@example.com")

            vm.setEmailRecipients(listOf("carol@example.com", "dan@example.com"))

            vm.settle()

            assertLastPatch(
                """{"email_alert_recipients":["carol@example.com","dan@example.com"]}""",
            )
            assertEquals("", vm.state.value.emailAlerts.draft)
            assertEquals(
                listOf("carol@example.com", "dan@example.com"),
                vm.state.value.patient?.emailAlertRecipients,
            )
        }

    /**
     * A removal must NOT clear the draft — iOS clears it in `add()` only, and
     * throwing away a half-typed address because someone removed a different
     * recipient is a plain data loss.
     */
    @Test fun `removing a recipient leaves a half-typed draft alone`() = runTest(mainDispatcher) {
        backend.patients = "[${patientJson(recipients = listOf("carol@example.com"))}]"
        backend.updated = patientJson(recipients = emptyList())
        backend.api.loadPatients()
        val vm = viewModel()
        vm.setEmailDraft("dan@exa")

        vm.setEmailRecipients(emptyList())

        vm.settle()

        assertLastPatch("""{"email_alert_recipients":[]}""")
        assertEquals("dan@exa", vm.state.value.emailAlerts.draft)
    }

    /**
     * A failed add keeps what was typed, so the caregiver can retry without
     * re-entering the address, and shows the API copy verbatim.
     */
    @Test fun `a failed add keeps the draft and surfaces the API copy`() = runTest(mainDispatcher) {
        backend.updateStatus = HttpStatusCode.Unauthorized
        backend.updated = ""
        backend.api.loadPatients()
        val vm = viewModel()
        vm.setEmailDraft("dan@example.com")

        vm.setEmailRecipients(listOf("dan@example.com"))

        vm.settle()

        assertEquals("dan@example.com", vm.state.value.emailAlerts.draft)
        assertEquals(
            ApiError.Unauthorized.errorDescription,
            vm.state.value.emailAlerts.errorText,
        )
        assertFalse(vm.state.value.emailAlerts.busy)
    }

    /**
     * A rejected address never reaches the network: the screen runs the pure
     * check and hands back its sentence, which is shown as-is with the draft
     * intact.
     */
    @Test fun `a validation rejection is shown without sending anything`() =
        runTest(mainDispatcher) {
            backend.api.loadPatients()
            val vm = viewModel()
            vm.setEmailDraft("not-an-email")
            val requests = backend.seen.size

            vm.showEmailValidationError("That doesn't look like a valid email address.")
            idle()

            assertEquals(
                "That doesn't look like a valid email address.",
                vm.state.value.emailAlerts.errorText,
            )
            assertEquals("not-an-email", vm.state.value.emailAlerts.draft)
            assertEquals(requests, backend.seen.size)
        }

    /** The status beside the button is the server's own sentence, never reworded. */
    @Test fun `the test send shows the server's status verbatim`() = runTest(mainDispatcher) {
        backend.testEmail = """{"status":"Test alert sent to 2 recipients."}"""
        backend.api.loadPatients()
        val vm = viewModel()

        vm.sendTestEmail()

        vm.settle()

        assertEquals("POST /v1/patients/$PATIENT/test-email", backend.seen.last())
        assertEquals("Test alert sent to 2 recipients.", vm.state.value.emailAlerts.testStatus)
        assertFalse(vm.state.value.emailAlerts.busy)
    }

    // -----------------------------------------------------------------------
    // Photo
    // -----------------------------------------------------------------------

    /**
     * The upload's response is the updated row, and it has to reach the cache or
     * the avatar keeps drawing the old key until the next roster load. The client
     * has no caching wrapper for this endpoint, so the view model writes it.
     */
    @Test fun `an uploaded photo lands on the patient row`() = runTest(mainDispatcher) {
        backend.photoRow = patientJson(photoStorageKey = "$PATIENT.jpg")
        backend.api.loadPatients()
        val vm = viewModel()
        assertNull(vm.state.value.patient?.photoStorageKey)

        vm.uploadPhoto(byteArrayOf(1, 2, 3), "image/jpeg")

        vm.settle()

        assertEquals("POST /v1/patients/$PATIENT/photo", backend.seen.last())
        assertEquals("$PATIENT.jpg", vm.state.value.patient?.photoStorageKey)
        assertFalse(vm.state.value.isUploadingPhoto)
        assertNull(vm.state.value.photoUploadError)
    }

    /** iOS: `photoUploadError = error.localizedDescription`, shown under the card. */
    @Test fun `a failed upload surfaces the API copy and releases the badge`() =
        runTest(mainDispatcher) {
            backend.photoStatus = HttpStatusCode.NotFound
            backend.photoRow = ""
            backend.api.loadPatients()
            val vm = viewModel()

            vm.uploadPhoto(byteArrayOf(1, 2, 3), "image/jpeg")

            vm.settle()

            assertEquals(ApiError.NotFound.errorDescription, vm.state.value.photoUploadError)
            assertFalse(vm.state.value.isUploadingPhoto)
        }

    /** "Remove photo" clears the key in place; the endpoint returns no body. */
    @Test fun `removing the photo clears the key on the cached row`() = runTest(mainDispatcher) {
        backend.patients = "[${patientJson(photoStorageKey = "$PATIENT.jpg")}]"
        backend.api.loadPatients()
        val vm = viewModel()
        idle()
        assertEquals("$PATIENT.jpg", vm.state.value.patient?.photoStorageKey)

        vm.removePhoto()

        vm.settle()

        assertEquals("DELETE /v1/patients/$PATIENT/photo", backend.seen.last())
        assertNull(vm.state.value.patient?.photoStorageKey)
    }

    // -----------------------------------------------------------------------
    // Remove patient
    // -----------------------------------------------------------------------

    /**
     * Delete is the one action that leaves. The client drops the patient and all
     * three sub-collections from the cache itself, so the roster behind this
     * screen is already correct when the pop is asked for.
     */
    @Test fun `a successful delete empties the patient and leaves the screen`() =
        runTest(mainDispatcher) {
            backend.api.loadPatients()
            val vm = viewModel()

            vm.deletePatient()

            vm.settle()

            assertEquals("DELETE /v1/patients/$PATIENT", backend.seen.last())
            assertNull(vm.state.value.patient)
            assertEquals(1, dismissals)
            assertFalse(vm.state.value.isDeleting)
        }

    /**
     * **A failed delete still leaves**, which is iOS's `try? await …; dismiss()`.
     * There is no error surface for it and no recovery on a settings screen for a
     * patient the caregiver just confirmed removing; the next pull-to-refresh
     * resyncs the roster.
     */
    @Test fun `a failed delete leaves anyway`() = runTest(mainDispatcher) {
        backend.deleteStatus = HttpStatusCode.InternalServerError
        backend.api.loadPatients()
        val vm = viewModel()

        vm.deletePatient()

        vm.settle()

        assertEquals(1, dismissals)
        assertFalse(vm.state.value.isDeleting)
    }

    /** `guard let id = patient?.id else { return }` — nothing to delete, nothing sent. */
    @Test fun `delete does nothing while the patient is not in the roster`() =
        runTest(mainDispatcher) {
            backend.patients = "[${patientJson(id = OTHER)}]"
            backend.api.loadPatients()
            val vm = viewModel()
            idle()

            vm.deletePatient()

            vm.settle()

            assertEquals(0, dismissals)
            assertEquals(0, backend.seen.count { it.startsWith("DELETE") })
        }
}
