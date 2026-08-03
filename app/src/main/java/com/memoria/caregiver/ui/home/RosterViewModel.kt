package com.memoria.caregiver.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Produces [RosterState] for [RosterScreen]. Port of what `RosterView` reads off
 * `@Environment(AppEnvironment.self)`.
 *
 * iOS has no object here at all: the view reads `env.api.patients`,
 * `env.api.eventsByPatient` and `env.auth.currentCaregiver` directly, and
 * `@Observable` redraws it when any of them changes. [state] is that same read,
 * combined into one value — so a Realtime INSERT that lands in the client's
 * cache reaches the screen with nothing else plumbed in between.
 *
 * **Derived, never snapshotted.** Copying the client's collections into a `var`
 * at construction would look identical on first paint and then quietly stop
 * updating, which is the exact failure the observable cache exists to prevent.
 */
class RosterViewModel(
    private val api: NemaraApiClient,
    /**
     * `env.auth.currentCaregiver`. Taken as the flow rather than the service so
     * this can be exercised without a Supabase client — the same seam
     * `AppEnvironment` uses to keep auth and the API client apart. The secondary
     * constructor below is the one the app calls.
     */
    caregiver: StateFlow<Caregiver?>,
) : ViewModel() {

    constructor(api: NemaraApiClient, auth: SupabaseAuthService) :
        this(api, auth.currentCaregiver)

    /**
     * Pull-to-refresh only. iOS gets the spinner's lifetime for free from
     * `.refreshable`'s suspending closure; `PullToRefreshBox` wants a flag, so
     * this is held here and folded into the state.
     */
    private val _isRefreshing = MutableStateFlow(false)

    val state: StateFlow<RosterState> = combine(
        api.patientsFlow,
        api.eventsByPatientFlow,
        caregiver,
        _isRefreshing,
    ) { patients, eventsByPatient, me, isRefreshing ->
        RosterState(
            patients = patients,
            caregiverDisplayName = me?.displayName,
            eventsByPatient = eventsByPatient,
            isRefreshing = isRefreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly, not WhileSubscribed: every upstream is already a StateFlow
        // held in memory, so staying hot costs one data class per change and
        // nothing else — while WhileSubscribed would hand the first frame after
        // a configuration change the empty default, which on this screen is not
        // a blank list but the whole "Add your first patient" screen.
        started = SharingStarted.Eagerly,
        initialValue = RosterState(),
    )

    // Note what is NOT read above: the client's `isLoading`.
    // `RosterView.swift:14` switches on `api.patients.isEmpty` alone, which is
    // why RosterState has no loading field. A caregiver whose roster is still
    // loading sees the empty state for that moment on iOS, and sees it here.
    // Gating the empty state on loading would be a new behaviour, not a fix.

    /**
     * Pull-to-refresh: the roster plus every per-patient sub-collection.
     *
     * **The error is swallowed, deliberately.** iOS's `.refreshable` closure
     * catches and prints it under `#if DEBUG`, and [RosterState] has nowhere to
     * put a message because of that. This is an escape hatch for a Realtime row
     * change that went missing, not a primary load path — a failed drag-down
     * leaves the last-known roster on screen rather than replacing it with an
     * error.
     */
    fun refresh() {
        // A second drag while one refresh is in flight would double the fan-out
        // for no new data. iOS cannot reach this state because SwiftUI owns the
        // gesture; here the callback is just a function anyone can call.
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                api.refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // As above: silent on both platforms.
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * The signed photo URL for one patient's avatar, or null when there is none.
     *
     * `RosterScreen` takes this as a parameter rather than a state field because
     * it is a per-row fetch with its own lifetime — iOS reaches
     * `env.api.patientPhotoURL(...)` from inside `PatientAvatar`'s `.task`. The
     * URL is short-lived (~10 min) and is meant to go straight to the image
     * loader, so nothing caches it here either.
     *
     * A 404 is already null inside the API group, and every other failure is
     * swallowed by `resolvePatientPhotoUrl` on the way to the avatar, which
     * falls back to the serif initial.
     */
    suspend fun resolvePhotoUrl(patientId: String): String? =
        api.patientsDirect.patientPhotoUrl(patientId)
}
