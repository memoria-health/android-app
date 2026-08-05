package com.memoria.caregiver.data.remote

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.model.CreateDetectorRequest
import com.memoria.caregiver.data.model.CreatePairingCodeResponse
import com.memoria.caregiver.data.model.CreatePatientRequest
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.EventFilter
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.data.model.ProvisionSensorCameraResponse
import com.memoria.caregiver.data.model.UpdateCameraRequest
import com.memoria.caregiver.data.model.UpdateDetectorRequest
import com.memoria.caregiver.data.model.UpdateEventRequest
import com.memoria.caregiver.data.model.UpdatePatientRequest
import com.memoria.caregiver.data.realtime.CameraFetcher
import com.memoria.caregiver.data.realtime.CameraLiveState
import com.memoria.caregiver.data.realtime.RealtimeCache
import com.memoria.caregiver.data.realtime.RealtimeStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.memoria.caregiver.data.model.CreateCaregiverRequest
import com.memoria.caregiver.data.model.SupportRequest
import com.memoria.caregiver.data.model.SupportResponse
import com.memoria.caregiver.data.model.UpdateCaregiverRequest

/**
 * The backend API. Port of `Services/NemaraAPIClient.swift`.
 *
 * **iOS deliberately puts the HTTP client and the observable cache in ONE
 * object**, and the Swift comment explains why: an earlier project split them
 * into repository + store and ended up with two sources of truth that drifted.
 * That structure is preserved. The endpoints live here; the cache and its
 * refetch paths arrive in Phase 3 with the roster, because there is nothing to
 * cache until then.
 *
 * Do not "modernise" this into a repository layer per resource. The comment on
 * the Swift is a scar, not a style preference.
 */
class NemaraApiClient(
    private val http: HttpTransport,
    /**
     * Shared with [com.memoria.caregiver.data.realtime.RealtimeStore]. Two of
     * the three reconnect-window writers live here rather than there
     * (PARITY.md §10): `markEverOnline` after [loadCameras], and
     * `beginReconnectWindow` on the reconnect edge in [updateCamera].
     */
    val live: CameraLiveState = CameraLiveState(),
) : RealtimeCache, CameraFetcher {

    // ---- The HTTP families ----
    //
    // Each holds no state; every cached collection is below. See
    // DIFFERENCES.md D15 for why the calls are grouped and the cache is not.

    private val patientsApi = PatientsApi(http)
    private val camerasApi = CamerasApi(http)
    private val detectorsApi = DetectorsApi(http)
    private val eventsApi = EventsApi(http)

    /** Token minting, talk start/end and clip capture. No cached state. */
    internal val liveKit: LiveKitApi = LiveKitApi(http)

    /**
     * The Home briefing's three calls. Deliberately not wrapped by a cached
     * accessor on this client: none of them has a cache, every one is allowed
     * to fail, and the Home screen owns the fallback chain.
     */
    internal val home: HomeApi = HomeApi(http)

    /** Face embeddings, device tokens and feedback. No cached state. */
    internal val enrollment: EnrollmentApi = EnrollmentApi(http)

    /** Doors, discovery, pairing codes — the camera calls that cache nothing. */
    internal val camerasDirect: CamerasApi get() = camerasApi

    /** Detector inference and the library — no cached state. */
    internal val detectorsDirect: DetectorsApi get() = detectorsApi

    /** Photo upload and URL resolution — no cached state. */
    internal val patientsDirect: PatientsApi get() = patientsApi

    /** Event paging and clip URLs — no cached state. */
    internal val eventsDirect: EventsApi get() = eventsApi

    // ---- The observable cache ----
    //
    // iOS gets these from `@Observable` on a `@MainActor` class. The
    // `RealtimeCache` overrides read and write the same flows, so a Realtime
    // push and a REST load are indistinguishable to a screen — which is the
    // property PARITY.md §10 is protecting.
    //
    // SINGLE-THREADED BY CONTRACT. RealtimeStore confines its handlers and its
    // poll to one dispatcher; every mutation here must arrive on that same one
    // or a read-modify-write below will lose an update.

    private val _patients = MutableStateFlow<List<Patient>>(emptyList())
    val patientsFlow: StateFlow<List<Patient>> = _patients.asStateFlow()

    private val _camerasByPatient = MutableStateFlow<Map<String, List<Camera>>>(emptyMap())
    val camerasByPatientFlow: StateFlow<Map<String, List<Camera>>> = _camerasByPatient.asStateFlow()

    private val _detectorsByPatient = MutableStateFlow<Map<String, List<Detector>>>(emptyMap())
    val detectorsByPatientFlow: StateFlow<Map<String, List<Detector>>> =
        _detectorsByPatient.asStateFlow()

    private val _eventsByPatient = MutableStateFlow<Map<String, List<DetectionEvent>>>(emptyMap())
    val eventsByPatientFlow: StateFlow<Map<String, List<DetectionEvent>>> =
        _eventsByPatient.asStateFlow()

    /** iOS `isLoading`, driven by [bootstrap] and [refresh]. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    override var patients: List<Patient>
        get() = _patients.value
        set(value) { _patients.value = value }

    override var camerasByPatient: Map<String, List<Camera>>
        get() = _camerasByPatient.value
        set(value) { _camerasByPatient.value = value }

    override var detectorsByPatient: Map<String, List<Detector>>
        get() = _detectorsByPatient.value
        set(value) { _detectorsByPatient.value = value }

    override var eventsByPatient: Map<String, List<DetectionEvent>>
        get() = _eventsByPatient.value
        set(value) { _eventsByPatient.value = value }

    /**
     * Set once by `AppEnvironment`, because the dependency is a cycle: the
     * store needs this object as its cache and its camera fetcher, and this
     * object needs the store to open channels and run the poll. iOS has no
     * cycle to break because both halves are the same type.
     */
    var realtime: RealtimeStore? = null

    // ---- Lifecycle ----

    /**
     * First load after sign-in. Port of the Swift `bootstrap()`.
     *
     * Roster first, then cameras, detectors and the first page of events for
     * every patient concurrently. The whole fan-out is awaited and any failure
     * propagates, because landing on a half-populated roster is worse than
     * showing the sign-in error.
     *
     * **Realtime is opened detached, and that is load-bearing.** It is a
     * liveness nicety, not a precondition: the 1-second poll already carries
     * every change. Awaiting the subscribes is what hung sign-in on
     * 2026-07-28 — the Realtime tenant was dead after the database ran out of
     * connections, the subscribe never returned (a hang is not an error, so
     * nothing caught it), and the caregiver sat on a spinner with a perfectly
     * healthy roster already loaded.
     */
    suspend fun bootstrap() {
        _isLoading.value = true
        try {
            loadPatients()
            loadEverythingForAllPatients()
        } finally {
            _isLoading.value = false
        }
        realtime?.let { store ->
            // Detached: a stalled subscribe must never hold sign-in again.
            store.openChannelsDetached()
            store.startStatePolling()
        }
    }

    /**
     * Pull-to-refresh. Same fan-out as [bootstrap], plus one thing bootstrap
     * does not need: dropping sub-collections whose patient is gone. Without
     * it, a patient deleted on another device leaves their cameras, detectors
     * and events resident in memory keyed by an id no longer in the roster.
     */
    suspend fun refresh() {
        _isLoading.value = true
        try {
            loadPatients()
            val liveIds = patients.map { it.id }.toSet()
            camerasByPatient = camerasByPatient.filterKeys { it in liveIds }
            detectorsByPatient = detectorsByPatient.filterKeys { it in liveIds }
            eventsByPatient = eventsByPatient.filterKeys { it in liveIds }
            loadEverythingForAllPatients()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Re-read everything after the app returns to the foreground, where
     * Realtime may have missed rows while suspended.
     *
     * Failures are swallowed per patient, unlike [refresh]: this runs without
     * anyone having asked for it, so a flaky network must not raise an error a
     * caregiver did not trigger. Does nothing when the roster is empty.
     */
    suspend fun resync() {
        if (patients.isEmpty()) return
        coroutineScope {
            patients.forEach { p ->
                launch { runCatching { loadEventsFirstPage(p.id) } }
                launch { runCatching { loadCameras(p.id) } }
                launch { runCatching { loadDetectors(p.id) } }
            }
        }
    }

    /**
     * Sign-out. Stops the poll, closes the channels and empties the cache.
     *
     * The clear is a privacy boundary, not housekeeping: without it the
     * previous caregiver's whole roster stays in memory, and a shared device is
     * the normal case for a care team.
     */
    suspend fun teardown() {
        // clear() runs UNCONDITIONALLY and last. Written as
        // `realtime?.teardown() ?: clear()`, the privacy clear ran only when
        // there was no store — i.e. never in the real app — leaving it entirely
        // dependent on RealtimeStore.teardown() reaching its final line, which
        // sits after unsubscribing a possibly-dead websocket.
        //
        // runCatching for the same reason: a throw out of unsubscribe (or, once
        // REQ-8 lands, out of push de-registration) would otherwise crash
        // sign-out with the caregiver already swapped to the signed-out UI and
        // the previous household still resident in memory.
        runCatching { realtime?.teardown() }
        clear()
    }

    private suspend fun loadEverythingForAllPatients() = coroutineScope {
        patients.map { p ->
            listOf(
                async { loadCameras(p.id) },
                async { loadDetectors(p.id) },
                async { loadEventsFirstPage(p.id) },
            )
        }.flatten().awaitAll()
    }

    /** iOS `isPreparing(_:)` — a camera that has never been seen online. */
    fun isPreparing(camera: Camera): Boolean = live.isPreparing(camera)

    /** iOS `isConnecting(_:)` — inside the 10s reconnect window. */
    fun isConnecting(camera: Camera): Boolean = live.isConnecting(camera)

    // ---- Patients ----

    suspend fun loadPatients() {
        // Server order, deliberately. The Swift does not sort here.
        patients = patientsApi.loadPatients()
    }

    suspend fun createPatient(body: CreatePatientRequest): Patient {
        val created = patientsApi.createPatient(body)
        patients = patients + created
        return created
    }

    suspend fun updatePatient(id: String, body: UpdatePatientRequest): Patient {
        val updated = patientsApi.updatePatient(id, body)
        patients = patients.map { if (it.id == updated.id) updated else it }
        return updated
    }

    /**
     * Flip `camerasPaused` in the cache with no request, so the carousel and the
     * Settings toggle move on the tap rather than after the round trip.
     *
     * iOS writes `env.api.patients[idx].camerasPaused` in place; its arrays are
     * `@Observable`, so a subscript write is a UI update. Kotlin's flows are
     * read-only from outside, so the same write needs a name.
     *
     * The caller owns the rollback: keep the previous value, PATCH, and call
     * this again with the old value on failure. That is what
     * `PatientLiveView.toggleMonitoring` does.
     */
    fun setCamerasPausedLocally(patientId: String, paused: Boolean) {
        patients = patients.map {
            if (it.id == patientId) it.copy(camerasPaused = paused) else it
        }
    }

    /**
     * Flip the local **intent** of every camera currently at [from] to [to], and
     * return the rows as they were **before** the flip.
     *
     * Returning them is the point, and it is a deliberate departure from the
     * Swift's shape. iOS does this as two statements — collect the cameras to
     * PATCH, *then* flip the local copies — and getting them the wrong way round
     * is a real bug it shipped: the PATCH loop filters on
     * `desired_state == connected`, so flipping first left the loop with zero
     * matches, nothing was sent, and the next `loadCameras` brought every camera
     * back. The comment above `disconnectAll` in `PatientLiveView.swift` still
     * describes it as "the disconnect-all does nothing bug".
     *
     * One call cannot be ordered wrongly, so the bug has nowhere to live.
     *
     * Hidden (`disabled`) cameras are never touched, matching iOS's filter: the
     * caregiver has already said they do not want them, and reviving one from a
     * bulk button would override that silently.
     */
    fun flipDesiredStateLocally(
        patientId: String,
        from: Camera.DesiredState,
        to: Camera.DesiredState,
    ): List<Camera> {
        val cams = camerasByPatient[patientId] ?: return emptyList()
        val affected = cams.filter { it.desiredState == from && !it.disabled }
        if (affected.isEmpty()) return emptyList()
        camerasByPatient = camerasByPatient + (
            patientId to cams.map {
                if (it.desiredState == from && !it.disabled) it.copy(desiredState = to) else it
            }
            )
        return affected
    }

    /**
     * A 404 is swallowed inside [PatientsApi.deletePatient]: the row is already
     * gone, which satisfies the caller's intent, and the local cleanup below
     * still has to run either way.
     */
    suspend fun deletePatient(id: String) {
        patientsApi.deletePatient(id)
        patients = patients.filterNot { it.id == id }
        camerasByPatient = camerasByPatient - id
        detectorsByPatient = detectorsByPatient - id
        eventsByPatient = eventsByPatient - id
    }

    // ---- Cameras ----

    /**
     * `GET /v1/patients/{id}/cameras` **without touching the cache** — the
     * `CameraFetcher` the poll uses, so it can compare fresh rows against
     * cached ones and write back only the difference.
     */
    override suspend fun fetchCameras(patientId: String): List<Camera> =
        camerasApi.fetchCameras(patientId)

    /**
     * Loads and caches. The `markEverOnline` pass is the half that is easy to
     * drop: it is what stops an offline camera regressing to "Preparing…"
     * after a relaunch, and it is persisted, so losing it here is not visible
     * until the next cold start.
     */
    suspend fun loadCameras(patientId: String) {
        val cams = camerasApi.fetchCameras(patientId)
        camerasByPatient = camerasByPatient + (patientId to cams)
        cams.filter { it.online }.forEach { live.markEverOnline(it.id) }
    }

    /**
     * The reconnect window opens **only** on the disconnected → connected edge,
     * compared against the cached row before it is replaced. Comparing after
     * the swap always reads equal, which silently disables the "Connecting…"
     * state and leaves the tile blank until frames arrive.
     */
    suspend fun updateCamera(id: String, body: UpdateCameraRequest): Camera {
        val updated = camerasApi.updateCamera(id, body)
        val arr = camerasByPatient[updated.patientId]
        if (arr != null && arr.any { it.id == updated.id }) {
            val previous = arr.first { it.id == updated.id }
            if (previous.desiredState == Camera.DesiredState.DISCONNECTED &&
                updated.desiredState == Camera.DesiredState.CONNECTED
            ) {
                live.beginReconnectWindow(updated.id)
            }
            camerasByPatient = camerasByPatient +
                (updated.patientId to arr.map { if (it.id == updated.id) updated else it })
        }
        return updated
    }

    /** Removes from the first patient that had it, matching the Swift's `break`. */
    suspend fun deleteCamera(id: String) {
        camerasApi.deleteCamera(id)
        val owner = camerasByPatient.entries.firstOrNull { (_, cams) -> cams.any { it.id == id } }
            ?: return
        camerasByPatient = camerasByPatient +
            (owner.key to owner.value.filterNot { it.id == id })
    }

    /** Reload is best-effort: a failed refresh must not fail the unpair. */
    suspend fun unpairCamera(id: String) {
        camerasApi.unpairCamera(id)
        val owner = camerasByPatient.entries.firstOrNull { (_, cams) -> cams.any { it.id == id } }
            ?: return
        runCatching { loadCameras(owner.key) }
    }

    suspend fun createPairingCode(
        patientId: String,
        room: String,
        iconName: String = "tv",
    ): CreatePairingCodeResponse {
        val response = camerasApi.createPairingCode(patientId, room, iconName)
        loadCameras(patientId)
        return response
    }

    suspend fun provisionSensorCamera(
        patientId: String,
        room: String,
        iconName: String = "tv",
        discoveryId: String,
    ): ProvisionSensorCameraResponse {
        val created = camerasApi.provisionSensorCamera(patientId, room, iconName, discoveryId)
        loadCameras(patientId)
        return created
    }

    // ---- Detectors ----

    suspend fun loadDetectors(patientId: String) {
        // Fetch FIRST, into a local, then read-modify-write the map with no
        // suspension in between.
        //
        // Writing this as one expression — `detectorsByPatient +
        // (patientId to detectorsApi.loadDetectors(...))` — reads the map
        // before the fetch suspends, so with two patients loading at once both
        // coroutines read the same pre-fetch map and the second write erases
        // the first. bootstrap() loads every patient concurrently, so a
        // caregiver with two patients saw one of them with no detectors at all.
        // Single-threaded confinement does not save this: a suspension point
        // between the read and the write is an interleaving point.
        //
        // Server order, deliberately. The Swift does not sort detectors.
        val list = detectorsApi.loadDetectors(patientId)
        detectorsByPatient = detectorsByPatient + (patientId to list)
    }

    suspend fun createCustomDetector(patientId: String, body: CreateDetectorRequest): Detector {
        val created = detectorsApi.createCustomDetector(patientId, body)
        detectorsByPatient = detectorsByPatient +
            (patientId to (detectorsByPatient[patientId].orEmpty() + created))
        return created
    }

    suspend fun updateDetector(id: String, body: UpdateDetectorRequest): Detector {
        val updated = detectorsApi.updateDetector(id, body)
        val arr = detectorsByPatient[updated.patientId]
        if (arr != null && arr.any { it.id == updated.id }) {
            detectorsByPatient = detectorsByPatient +
                (updated.patientId to arr.map { if (it.id == updated.id) updated else it })
        }
        return updated
    }

    suspend fun deleteDetector(id: String) {
        detectorsApi.deleteDetector(id)
        val owner = detectorsByPatient.entries.firstOrNull { (_, arr) -> arr.any { it.id == id } }
            ?: return
        detectorsByPatient = detectorsByPatient +
            (owner.key to owner.value.filterNot { it.id == id })
    }

    /**
     * The server skips keys the patient already has, so this can return fewer
     * rows than were requested. Appending the response rather than the request
     * is what keeps the cache honest.
     */
    suspend fun addDetectorsFromLibrary(patientId: String, keys: List<String>): List<Detector> {
        val created = detectorsApi.addDetectorsFromLibrary(patientId, keys)
        detectorsByPatient = detectorsByPatient +
            (patientId to (detectorsByPatient[patientId].orEmpty() + created))
        return created
    }

    /**
     * Refine a detector's plan with the caregiver's feedback.
     *
     * **A preview must never reach the cache.** `preview = true` returns a full
     * `Detector` describing something that does not exist in that form yet;
     * writing it would show the caregiver a saved detector they have not saved.
     * iOS guards with `if !preview` and so does this.
     */
    suspend fun refineDetector(id: String, feedback: String, preview: Boolean = false): Detector {
        val updated = detectorsApi.refineDetector(id, feedback, preview)
        if (!preview) {
            val arr = detectorsByPatient[updated.patientId]
            if (arr != null && arr.any { it.id == updated.id }) {
                detectorsByPatient = detectorsByPatient +
                    (updated.patientId to arr.map { if (it.id == updated.id) updated else it })
            }
        }
        return updated
    }

    // ---- Patient photo ----

    /**
     * Uploads a new photo and swaps the returned row into the cache, so every
     * avatar on screen updates without a refetch.
     */
    suspend fun uploadPatientPhoto(patientId: String, bytes: ByteArray, contentType: String): Patient {
        val updated = patientsApi.uploadPatientPhoto(patientId, bytes, contentType)
        patients = patients.map { if (it.id == updated.id) updated else it }
        return updated
    }

    /**
     * Deletes the photo and clears `photoStorageKey` in place.
     *
     * The server returns no body, so the cached row is edited rather than
     * replaced — exactly as the Swift does. Without it the avatar keeps
     * rendering from a storage key that no longer resolves.
     */
    suspend fun deletePatientPhoto(patientId: String) {
        patientsApi.deletePatientPhoto(patientId)
        patients = patients.map {
            if (it.id == patientId) it.copy(photoStorageKey = null) else it
        }
    }

    // ---- Events ----

    /**
     * Replaces the cached page. Newest first, server order preserved — the
     * Realtime handler inserts new events at index 0 on the same assumption.
     */
    suspend fun loadEventsFirstPage(patientId: String, filter: EventFilter = EventFilter.ALL) {
        val page = eventsApi.loadEventsFirstPage(patientId, filter)
        eventsByPatient = eventsByPatient + (patientId to page.items)
    }

    /**
     * Appends the next page. The cursor must be the one the previous page
     * returned: a null cursor restarts at the top, and appending that would
     * duplicate the whole feed.
     */
    suspend fun loadMoreEvents(
        patientId: String,
        filter: EventFilter = EventFilter.ALL,
        cursor: String,
    ): String? {
        val page = eventsApi.loadEventsPage(patientId, filter, cursor)
        eventsByPatient = eventsByPatient +
            (patientId to (eventsByPatient[patientId].orEmpty() + page.items))
        return page.nextCursor
    }

    suspend fun updateEvent(id: String, body: UpdateEventRequest): DetectionEvent {
        val updated = eventsApi.updateEvent(id, body)
        val arr = eventsByPatient[updated.patientId]
        if (arr != null && arr.any { it.id == updated.id }) {
            eventsByPatient = eventsByPatient +
                (updated.patientId to arr.map { if (it.id == updated.id) updated else it })
        }
        return updated
    }

    // ---- Identity: /v1/me ----

    /**
     * Creates the caregiver row after sign-up. Called from the
     * `onAuthenticated` closure with a display name; the JWT identifies who.
     */
    suspend fun provisionMe(
        displayName: String,
        phone: String? = null,
        tosVersion: String? = null,
        country: String? = null,
    ): Caregiver = http.post(
        "/v1/me",
        CreateCaregiverRequest(
            displayName = displayName,
            phone = phone,
            tosVersion = tosVersion,
            country = country,
        ),
    )

    /** The signed-in caregiver. Called on sign-in and on session restore. */
    suspend fun me(): Caregiver = http.get("/v1/me")

    suspend fun updateMe(displayName: String? = null, phone: String? = null): Caregiver =
        http.patch("/v1/me", UpdateCaregiverRequest(displayName = displayName, phone = phone))

    /**
     * Permanently deletes the account and everything server-side: the Supabase
     * auth user, and by cascade every patient, camera, detector, event and
     * device token, plus the storage objects they referenced.
     *
     * **After this returns the session is dead**, so the caller signs out to
     * reset local state rather than leaving the app holding a JWT for a user
     * that no longer exists.
     */
    suspend fun deleteAccount() = http.delete("/v1/me")

    // ---- Support: /v1/support ----

    /**
     * Forwards a Contact Support message to the team. The backend resolves the
     * caregiver from the JWT and sets reply-to to their account email, so
     * replies land in their inbox.
     */
    suspend fun sendSupport(subject: String, message: String): SupportResponse =
        http.post("/v1/support", SupportRequest(subject = subject, message = message))
}
