package com.memoria.caregiver.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.model.FaceEnrollmentSummary
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.model.UpdatePatientRequest
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import com.memoria.caregiver.ui.live.PeopleBlurStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Produces [PatientSettingsState] for [PatientSettingsScreen] and runs every
 * action that screen can start. Port of the `@State` on `PatientSettingsView`
 * plus the four cards' own `@State` and mutations —
 * `PatientSettingsView.swift`, `PatientEmailAlertsCard.swift`,
 * `AudioCaptureCard.swift`, `DataCollectionCard.swift`, `PeopleBlurCard.swift`,
 * PARITY.md §24.
 *
 * ## Why one object holds four cards' state
 *
 * On iOS each card is its own `View` with its own `@State` and reads
 * `env.api.patients` for itself. Compose has no equivalent of "a leaf view that
 * quietly owns I/O", and `PatientSettingsUiState.kt` already made the call: the
 * screen is pure, so every busy flag and every error sentence is hoisted. They
 * stay in *separate* pairs here ([CardMutationState] per card) for the reason
 * PARITY.md §24 gives — the cards' copy and failure behavior differ, and one
 * shared pair would put an email failure under the audio toggle.
 *
 * ## Derived, never snapshotted
 *
 * The patient, the account email and the blur preference are read off their live
 * sources every time any of them changes, exactly as iOS's computed properties
 * re-read `env.api.patients` on each redraw. A PATCH writes the canonical row
 * back into the client's cache, so the toggles snap back on failure on their own
 * — nothing here mirrors server state into a local field, and nothing needs
 * rolling back.
 *
 * ## What is deliberately NOT here
 *
 * - **The photo picker, the enrollment cover and the feedback screen.** Those
 *   present something, which is the host's job — `CameraDetailViewModel` leaves
 *   its two sheets to the host on the same grounds, and iOS drives all three
 *   from `@State` booleans on the view. Only [onDeleted] is taken as a callback,
 *   because it is the one action that *finishes* by leaving the screen.
 * - **The face-embedding gallery** (`listFaceEmbeddings` / `deleteFaceEmbedding`).
 *   This screen shows the summary card only; iOS lists individual embeddings in
 *   the enrollment flow, and [PatientSettingsState] has no field for them.
 */
class PatientSettingsViewModel(
    private val api: NemaraApiClient,
    private val patientId: String,
    /**
     * `env.auth.currentCaregiver`. Taken as the flow, and the blur preference as
     * a flow plus a setter, so this is constructible without a Supabase client
     * or a `SharedPreferences` — the seam `RosterViewModel` and
     * `AppSettingsViewModel` already use. The secondary constructor below is the
     * one the app calls.
     */
    caregiver: StateFlow<Caregiver?>,
    blurredPatientIds: StateFlow<Set<String>>,
    private val setPeopleBlurEnabled: (Boolean) -> Unit,
    /**
     * Leave the screen after the patient row is gone. iOS calls `dismiss()` at
     * the end of `deletePatient()`; here the navigation graph owns the back
     * stack, so this is how the pop is asked for.
     *
     * Nothing else on this screen leaves: every other action edits a row the
     * caregiver is still looking at.
     */
    private val onDeleted: () -> Unit,
) : ViewModel() {

    constructor(
        api: NemaraApiClient,
        patientId: String,
        auth: SupabaseAuthService,
        peopleBlur: PeopleBlurStore,
        onDeleted: () -> Unit,
    ) : this(
        api = api,
        patientId = patientId,
        caregiver = auth.currentCaregiver,
        blurredPatientIds = peopleBlur.enabledIds,
        setPeopleBlurEnabled = { on -> peopleBlur.setEnabled(on, patientId) },
        onDeleted = onDeleted,
    )

    /**
     * Everything this screen owns rather than reads, in one flow rather than
     * eleven.
     *
     * Every write goes through [MutableStateFlow.update], which re-reads the
     * current value inside the block and retries if it changed — so a write can
     * never carry a value that was read before an intervening suspension. That
     * is the shape FINDINGS.md F-12 was, and it matters here because several of
     * these actions genuinely overlap: the enrollment poll runs for up to eighty
     * seconds underneath every toggle on the screen.
     */
    private data class Local(
        val enrollmentSummary: FaceEnrollmentSummary? = null,
        val isUploadingPhoto: Boolean = false,
        val photoUploadError: String? = null,
        val isDeleting: Boolean = false,
        val audioCapture: CardMutationState = CardMutationState(),
        val dataCollection: CardMutationState = CardMutationState(),
        val evaluationMode: CardMutationState = CardMutationState(),
        val emailAlerts: EmailAlertsState = EmailAlertsState(),
    )

    private val _local = MutableStateFlow(Local())

    val state: StateFlow<PatientSettingsState> = combine(
        api.patientsFlow,
        caregiver,
        blurredPatientIds,
        _local,
    ) { patients, me, blurred, local ->
        PatientSettingsState(
            // Null while the patient is not in the roster — iOS's
            // `first(where:)` returning nil. Every card degrades to its
            // documented default and the remove button disables.
            patient = patients.firstOrNull { it.id == patientId },
            // iOS: `env.auth.currentCaregiver?.email ?? "your account"`. The
            // literal is product copy in the recipients row.
            accountEmail = me?.email ?: "your account",
            enrollmentSummary = local.enrollmentSummary,
            peopleBlurEnabled = patientId in blurred,
            isUploadingPhoto = local.isUploadingPhoto,
            photoUploadError = local.photoUploadError,
            isDeleting = local.isDeleting,
            audioCapture = local.audioCapture,
            dataCollection = local.dataCollection,
            emailAlerts = local.emailAlerts,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly, for the reason RosterViewModel gives: every upstream is an
        // in-memory StateFlow, so staying hot costs one data class per change —
        // while WhileSubscribed would hand the first frame after a configuration
        // change the default, which here is a patient-less screen with a
        // disabled remove button and a "Patient identity" loading card.
        started = SharingStarted.Eagerly,
        initialValue = PatientSettingsState(),
    )

    // -----------------------------------------------------------------------
    // First-open data-collection consent
    // -----------------------------------------------------------------------

    private val _showDataCollectionConsent = MutableStateFlow(
        // iOS: `.onAppear { if patient?.dataCollectionDecidedAt == nil {
        // showConsent = true } }` on `PatientShell`. Null means the caregiver
        // has never answered, and the backend stamps `decided_at` on any PATCH
        // carrying `data_collection_enabled`, so both "Share recordings" and
        // "Not now" stop it coming back.
        //
        // Evaluated ONCE, here, and never re-derived. A flow that simply tracked
        // `decidedAt == null` would re-raise the sheet the instant a caregiver
        // swiped it away without answering, which is an unclosable prompt. This
        // object lives exactly as long as one visit to the patient, so "once at
        // construction" is the same "once per appearance" iOS gets. The host
        // holds a patient row already loaded, which is why reading the cache
        // synchronously here is enough.
        api.patientsFlow.value
            .firstOrNull { it.id == patientId }
            ?.dataCollectionDecidedAt == null,
    )

    /**
     * Whether the host should be presenting `DataCollectionConsentSheet`.
     *
     * Separate from [state] because presenting is the host's, the same way
     * `EventsListViewModel.openEvent` is separate — and because
     * [PatientSettingsState] has no field for it: on iOS the sheet belongs to
     * `PatientShell`, not to `PatientSettingsView`.
     *
     * The sheet's two buttons call [setDataCollectionEnabled], which lowers this
     * on success only. A failed PATCH leaves the sheet up with the message in
     * `state.dataCollection.errorText`, rather than recording a phantom decision
     * (PARITY.md §24).
     */
    val showDataCollectionConsent: StateFlow<Boolean> = _showDataCollectionConsent.asStateFlow()

    /** Swiped away without answering. Nothing was decided, so nothing is sent. */
    fun dismissDataCollectionConsent() {
        _showDataCollectionConsent.value = false
    }

    // -----------------------------------------------------------------------
    // Face enrollment
    // -----------------------------------------------------------------------

    /**
     * The reload token behind [runEnrollmentPoll], which is iOS's
     * `@State reloadToken` bumped by the re-enrollment cover's completion.
     */
    private val _enrollmentReloadToken = MutableStateFlow(0)

    /**
     * The identity card's summary, polled while the backend is still computing
     * embeddings. Port of `.task(id: reloadToken) { await loadEnrollmentSummary() }`.
     *
     * **A suspend function, not something started in `init`** — the same call
     * `EventsListViewModel.runFeedPoll` makes. iOS's `.task` starts when the
     * view appears and cancels when it leaves; launched from the Settings tab's
     * `LaunchedEffect(Unit)`, cancellation lines up exactly, whereas an `init`
     * would keep polling from behind the Live tab.
     *
     * `collectLatest` is `.task(id:)`: [reloadEnrollmentSummary] bumps the token,
     * which cancels the poll in flight and starts a fresh one — rather than
     * leaving a detached, un-cancellable second poll writing the same field.
     *
     * The attempt budget, the interval and the swallow-and-continue behavior all
     * live in [pollFaceEnrollmentSummary]; nothing about them is re-decided here.
     */
    suspend fun runEnrollmentPoll() {
        _enrollmentReloadToken.collectLatest {
            pollFaceEnrollmentSummary(
                load = { api.enrollment.faceEnrollmentSummary(patientId) },
                // Nothing is logged on this path, ever. The summary counts how
                // many face photos a named person with dementia has on file,
                // which is biometric detail about them — see the file comment on
                // `EnrollmentApi`.
                onSummary = { summary -> _local.update { it.copy(enrollmentSummary = summary) } },
            )
        }
    }

    /**
     * Re-run the poll from the top. The host calls this when the enrollment flow
     * it presented reports done, which is iOS's `reloadToken += 1` in
     * `FaceEnrollmentView`'s completion — without it the card sits on the counts
     * it read before the caregiver added photos.
     */
    fun reloadEnrollmentSummary() {
        _enrollmentReloadToken.update { it + 1 }
    }

    // -----------------------------------------------------------------------
    // Profile photo
    // -----------------------------------------------------------------------

    /**
     * Upload the photo the caregiver picked, already decoded and re-encoded.
     *
     * The bytes arrive finished because decoding a picked `Uri` needs a
     * `ContentResolver` and the downscale needs a `Bitmap` — both the host's, for
     * the reason `FeedbackAttachments.kt` states about screenshots. iOS does that
     * work in `uploadPickedPhoto` before this same call.
     *
     * **The updated row is written into the client's cache here**, which is the
     * one place this object reaches into it. `NemaraApiClient` has no caching
     * wrapper for the photo endpoints (unlike `updatePatient`), while the Swift
     * client does replace the row — so without this the avatar would keep drawing
     * the old key until the next roster load. The fetch is awaited FIRST and the
     * read-modify-write below it has no suspension inside it, which is the
     * discipline `loadDetectors` documents for exactly this hazard.
     */
    /**
     * The pick failed before there were any bytes to upload — the stream would
     * not open, or the file is not an image this platform decodes.
     *
     * iOS reports both through the same `photoUploadError` the upload itself
     * uses (`uploadPickedPhoto`'s two `guard`s), so the caregiver reads one
     * sentence in one place whether the photo died on the device or on the wire.
     * The two messages are in `NewPatientPhoto.kt`, verbatim from the Swift.
     *
     * Deliberately does not touch [Local.isUploadingPhoto]: nothing was ever
     * uploading, and raising it here would leave the badge disabled after a bad
     * pick.
     */
    fun reportPhotoPickFailure(message: String) {
        _local.update { it.copy(photoUploadError = message) }
    }

    fun uploadPhoto(imageData: ByteArray, contentType: String) {
        if (_local.value.isUploadingPhoto) return
        _local.update { it.copy(isUploadingPhoto = true, photoUploadError = null) }
        viewModelScope.launch {
            try {
                // Through the client, not patientsDirect: the client owns the
                // cache swap. This ViewModel did it itself while the client had
                // no wrapper, which is the two-places-one-mutation shape
                // DIFFERENCES.md D15 warns about.
                api.uploadPatientPhoto(patientId, imageData, contentType)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _local.update { it.copy(photoUploadError = messageFor(e)) }
            } finally {
                // iOS clears this in a `defer`: a thrown error must not leave the
                // camera badge permanently disabled.
                _local.update { it.copy(isUploadingPhoto = false) }
            }
        }
    }

    /**
     * "Remove photo". iOS clears the error first and does **not** raise the
     * uploading flag, so the badge stays tappable throughout.
     *
     * The cache write is the delete's half of the note on [uploadPhoto]: the
     * endpoint returns no body, so the row is cleared in place.
     */
    fun removePhoto() {
        _local.update { it.copy(photoUploadError = null) }
        viewModelScope.launch {
            try {
                // The client clears photoStorageKey in place — the delete
                // returns no body, so the row is edited rather than replaced.
                api.deletePatientPhoto(patientId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _local.update { it.copy(photoUploadError = messageFor(e)) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Privacy
    // -----------------------------------------------------------------------

    /**
     * "Blur people". Device-local and synchronous — there is no request, so no
     * busy flag and no failure state, which is why `PeopleBlurCard` has neither.
     */
    fun setPeopleBlur(enabled: Boolean) {
        setPeopleBlurEnabled(enabled)
    }

    /**
     * The facilities audio kill.
     *
     * **The failure copy is a fixed sentence, not the API's.** `AudioCaptureCard`
     * renders its error *in place of* the body copy, so it has to read like body
     * copy; the other two cards show `ApiError.errorDescription` verbatim on
     * their own line. That difference is PARITY.md §24's, not an oversight.
     */
    fun setAudioCapture(enabled: Boolean) {
        runCardMutation(
            read = { it.audioCapture },
            write = { local, mutation -> local.copy(audioCapture = mutation) },
            failure = { "Couldn't update audio setting. Try again." },
            body = UpdatePatientRequest(audioCaptureEnabled = enabled),
        )
    }

    // -----------------------------------------------------------------------
    // Data collection
    // -----------------------------------------------------------------------

    /**
     * The research opt-in, from the Settings card **or** from the first-open
     * consent sheet — one PATCH path, because they send the same field and the
     * backend stamps `data_collection_decided_at` on either value.
     *
     * Success is what takes the consent sheet down: a decision was recorded, so
     * the prompt has nothing left to ask. A failure deliberately leaves it up.
     */
    /**
     * Evaluation mode. Unlike data collection there is no consent sheet to
     * dismiss on success: the setting is ON by default and the explainer is
     * always reachable from the card's info button, so a flip is just a flip.
     */
    fun setEventGateEnabled(enabled: Boolean) {
        runCardMutation(
            read = { it.evaluationMode },
            write = { local, mutation -> local.copy(evaluationMode = mutation) },
            failure = { messageFor(it) },
            body = UpdatePatientRequest(eventGateEnabled = enabled),
        )
    }

    fun setDataCollectionEnabled(enabled: Boolean) {
        runCardMutation(
            read = { it.dataCollection },
            write = { local, mutation -> local.copy(dataCollection = mutation) },
            failure = { messageFor(it) },
            body = UpdatePatientRequest(dataCollectionEnabled = enabled),
            onSuccess = { _showDataCollectionConsent.value = false },
        )
    }

    // -----------------------------------------------------------------------
    // Email alerts
    // -----------------------------------------------------------------------

    /** The master toggle. */
    fun setEmailAlertsEnabled(enabled: Boolean) {
        runEmailMutation(UpdatePatientRequest(emailAlertsEnabled = enabled))
    }

    /**
     * Typing in the "add another email" field. Never guarded on busy: the field
     * stays editable while a request is in flight, as it does on iOS.
     */
    fun setEmailDraft(text: String) {
        _local.update { it.copy(emailAlerts = it.emailAlerts.copy(draft = text)) }
    }

    /**
     * A rejected address. The screen runs the pure [addRecipient] check and hands
     * back its sentence; iOS sets the same `error` without sending anything and
     * without touching the draft, so the caregiver can fix the typo.
     */
    fun showEmailValidationError(message: String) {
        _local.update { it.copy(emailAlerts = it.emailAlerts.copy(errorText = message)) }
    }

    /**
     * PATCH the whole recipients array — the server replaces the column
     * wholesale, so both the add and the remove send a complete list.
     *
     * **The draft is cleared only when this was an add**, which is the one place
     * the two paths differ on iOS: `add()` ends with `draft = ""` and `remove(_:)`
     * leaves it alone. The screen collapses both into this one callback, so the
     * add is identified by what the array actually gained — the normalized draft
     * appearing in [next] and not in the list it replaces. Clearing on every
     * success would throw away a half-typed address whenever someone removed a
     * recipient.
     */
    fun setEmailRecipients(next: List<String>) {
        val before = currentPatient()?.emailAlertRecipients.orEmpty()
        val typed = _local.value.emailAlerts.draft.trim().lowercase()
        val isAdd = typed.isNotEmpty() && typed !in before && typed in next
        runEmailMutation(
            body = UpdatePatientRequest(emailAlertRecipients = next),
            onSuccess = {
                if (isAdd) {
                    _local.update { it.copy(emailAlerts = it.emailAlerts.copy(draft = "")) }
                }
            },
        )
    }

    /**
     * "Send test email". The status line beside the button is the server's own
     * `status` string, shown verbatim.
     *
     * Both the error and the previous status are cleared before the send, so a
     * second tap cannot leave last time's answer on screen next to this time's
     * spinner.
     */
    fun sendTestEmail() {
        if (_local.value.emailAlerts.busy) return
        _local.update {
            it.copy(emailAlerts = it.emailAlerts.copy(busy = true, errorText = null, testStatus = null))
        }
        viewModelScope.launch {
            try {
                val response = api.patientsDirect.sendPatientTestEmail(patientId)
                _local.update {
                    it.copy(emailAlerts = it.emailAlerts.copy(testStatus = response.status))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _local.update { it.copy(emailAlerts = it.emailAlerts.copy(errorText = messageFor(e))) }
            } finally {
                _local.update { it.copy(emailAlerts = it.emailAlerts.copy(busy = false)) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Remove patient
    // -----------------------------------------------------------------------

    /**
     * Delete the patient and leave. Only reachable from the screen's
     * confirmation dialog.
     *
     * **Failures are swallowed and the screen still leaves**, which is iOS's
     * `try? await …; dismiss()`. A 404 is already success inside the client (the
     * row is gone, which is what was asked for), and for anything else the next
     * pull-to-refresh resyncs the roster — whereas staying on a settings screen
     * for a patient the caregiver just confirmed removing is a dead end with no
     * recovery on it. That is also why [PatientSettingsState] has no delete-error
     * field.
     *
     * The client removes the patient and all three sub-collections from the cache
     * itself, so the roster behind this screen is already correct when
     * [onDeleted] runs.
     */
    fun deletePatient() {
        val local = _local.value
        if (local.isDeleting) return
        // iOS: `guard let id = patient?.id else { return }`. The button is
        // disabled without a patient, but the callback is a plain function.
        if (currentPatient() == null) return
        _local.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            try {
                runCatching { api.deletePatient(patientId) }
                onDeleted()
            } finally {
                _local.update { it.copy(isDeleting = false) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * The signed URL for this patient's avatar, or null when there is none.
     *
     * A screen parameter rather than a state field for the reason
     * `RosterViewModel.resolvePhotoUrl` gives: it is a per-avatar fetch with its
     * own lifetime, and the URL expires in about ten minutes, so caching it here
     * would hand the image loader a dead link.
     */
    suspend fun resolvePhotoUrl(patientId: String): String? =
        api.patientsDirect.patientPhotoUrl(patientId)

    /**
     * The two server-backed toggle cards differ only in which pair of
     * busy/error fields they own and which sentence a failure produces, so the
     * shape they share is written once.
     *
     * [read] and [write] are the card's slot in [Local]; the busy flag is raised
     * synchronously, before the launch, because it is what the card's
     * `.disabled(busy)` reads and a flag raised inside the coroutine would be
     * raised a dispatch too late.
     *
     * Nothing here writes the patient row back by hand: `api.updatePatient`
     * replaces it in the cache, [state] re-derives, and a failed toggle therefore
     * snaps back on its own because it was never bound to anything but server
     * state.
     */
    private fun runCardMutation(
        read: (Local) -> CardMutationState,
        write: (Local, CardMutationState) -> Local,
        failure: (Throwable) -> String,
        body: UpdatePatientRequest,
        onSuccess: () -> Unit = {},
    ) {
        if (read(_local.value).busy) return
        _local.update { write(it, read(it).copy(busy = true, errorText = null)) }
        viewModelScope.launch {
            try {
                api.updatePatient(patientId, body)
                onSuccess()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _local.update { write(it, read(it).copy(errorText = failure(e))) }
            } finally {
                // iOS drops the flag in a `defer`, not on the success path: a
                // failed PATCH must not leave the toggle disabled for good.
                _local.update { write(it, read(it).copy(busy = false)) }
            }
        }
    }

    /**
     * The email card's three PATCHes. One [EmailAlertsState.busy] flag covers the
     * toggle, the add, the remove and the test send, exactly as the Swift's
     * single `@State var busy` does — which is why adding a recipient puts the
     * spinner in the "Send test email" button. That looks like a bug and is the
     * shipped behavior on iOS.
     *
     * The test status is deliberately left alone: iOS clears it only in
     * `sendTest()`, so the last send's answer stays on screen while a recipient
     * is added, and the same reading of `testStatus` holds on both platforms.
     */
    private fun runEmailMutation(
        body: UpdatePatientRequest,
        onSuccess: () -> Unit = {},
    ) {
        if (_local.value.emailAlerts.busy) return
        _local.update { it.copy(emailAlerts = it.emailAlerts.copy(busy = true, errorText = null)) }
        viewModelScope.launch {
            try {
                api.updatePatient(patientId, body)
                onSuccess()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Throwable) {
                _local.update { it.copy(emailAlerts = it.emailAlerts.copy(errorText = messageFor(e))) }
            } finally {
                _local.update { it.copy(emailAlerts = it.emailAlerts.copy(busy = false)) }
            }
        }
    }

    /**
     * The patient as the cache has it right now.
     *
     * Read off the client rather than off [state] so an action is correct before
     * the shared flow's first emission, which is the same reasoning
     * `CameraDetailViewModel.currentCamera` gives — and iOS's `patient` is
     * likewise a direct lookup in `env.api.patients`.
     */
    private fun currentPatient(): Patient? =
        api.patientsFlow.value.firstOrNull { it.id == patientId }

    /**
     * Verbatim from [ApiError.errorDescription], which is what Swift's
     * `error.localizedDescription` resolves to for everything these paths throw.
     * That copy is the product's; paraphrasing it here would be a silent product
     * change.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }
}
