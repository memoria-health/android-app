package com.memoria.caregiver.data.realtime

import android.util.Log
import com.memoria.caregiver.BuildConfig
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.Patient

/*
 * The three things this unit needs from the rest of the app, declared here so
 * the Realtime half can be built and tested without the HTTP half existing yet.
 *
 * On iOS all of this is one type: `NemaraAPIClient` holds the URLSession, the
 * observable cache, the Supabase channels and the poll. PARITY.md §10 says not
 * to split that into repository + store, and this does not: [RealtimeCache] is
 * not a second source of truth, it is the *name* of the cache the API client
 * already owns. `NemaraApiClient` implements it over its own StateFlows:
 *
 *     override var patients: List<Patient>
 *         get() = _patients.value
 *         set(value) { _patients.value = value }
 *
 * Nothing in this package stores a copy of a row.
 */

/**
 * The slice of the API client's observable cache that Realtime pushes and the
 * 1-second poll write into.
 *
 * Whole-collection assignment rather than fine-grained mutators, because that
 * is what the Swift does (`camerasByPatient[pid] = arr.sorted { … }`) and
 * because a `StateFlow` only emits when the value it holds is replaced.
 *
 * **Single-threaded by contract.** iOS marks the client `@MainActor`, so every
 * handler and every poll pass is serialized on one actor. [RealtimeStore]
 * reproduces that by confining all of its work to one dispatcher; there are no
 * locks here and none are needed as long as that holds.
 */
interface RealtimeCache {
    var patients: List<Patient>
    var camerasByPatient: Map<String, List<Camera>>
    var detectorsByPatient: Map<String, List<Detector>>
    var eventsByPatient: Map<String, List<DetectionEvent>>
}

/**
 * `GET /v1/patients/{id}/cameras` **without touching the cache** — the Swift
 * `fetchCameras(forPatient:)`, which is deliberately separate from
 * `loadCameras` so the poll can compare fresh rows against cached ones and
 * write back only the difference.
 *
 * Throws on failure; the poll swallows it and retries on the next tick.
 */
fun interface CameraFetcher {
    suspend fun fetchCameras(patientId: String): List<Camera>
}

/**
 * Where the `#if DEBUG` prints in the Swift go.
 *
 * Injected rather than called directly so the handlers stay plain JVM code with
 * no Android framework on the path — which is what lets the decode-failure
 * canary (see [RealtimeHandlers]) be asserted in a test instead of taken on
 * faith.
 */
fun interface RealtimeLog {
    fun debug(message: String)
}

/** Release builds print nothing, matching `#if DEBUG`. */
object AndroidRealtimeLog : RealtimeLog {
    private const val TAG = "NemaraRealtime"

    override fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
