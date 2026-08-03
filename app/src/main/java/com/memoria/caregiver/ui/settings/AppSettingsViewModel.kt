package com.memoria.caregiver.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Produces [AppSettingsState] for [AppSettingsScreen], and runs the two account
 * actions that screen can start. Port of the `@State` on `AppSettingsView` plus
 * its `deleteAccount()`.
 *
 * The caregiver and the patient list are read straight off the two live sources
 * — `env.auth.currentCaregiver` and `env.api.patients` — so a profile edit or a
 * patient added on another device lands here without a reload. Only the
 * deletion flow's own two values are held locally, because they exist for the
 * duration of one tap and nothing outside this screen can observe them.
 */
class AppSettingsViewModel(
    api: NemaraApiClient,
    /**
     * `env.auth.currentCaregiver`. Taken as the flow, and the two account
     * actions as functions, so this can be exercised without a Supabase client
     * — the same seam `AppEnvironment` uses to keep auth and the API client
     * apart. The secondary constructor below is the one the app calls.
     */
    caregiver: StateFlow<Caregiver?>,
    private val onSignOut: suspend () -> Unit,
    private val onDeleteAccount: suspend () -> Unit,
) : ViewModel() {

    constructor(api: NemaraApiClient, auth: SupabaseAuthService) : this(
        api = api,
        caregiver = auth.currentCaregiver,
        onSignOut = auth::signOut,
        // Not `api.deleteAccount()`: the auth service calls that through its own
        // `onDeleteAccount` hook and then tears the dead session down. Going
        // straight to the API would delete the account server-side and leave the
        // app holding a JWT for a user that no longer exists.
        onDeleteAccount = auth::deleteAccount,
    )

    private val _isDeleting = MutableStateFlow(false)
    private val _deleteError = MutableStateFlow<String?>(null)

    val state: StateFlow<AppSettingsState> = combine(
        caregiver,
        api.patientsFlow,
        _isDeleting,
        _deleteError,
    ) { me, patients, isDeleting, deleteError ->
        AppSettingsState(
            caregiver = me,
            patients = patients,
            isDeleting = isDeleting,
            deleteError = deleteError,
        )
    }.stateIn(
        scope = viewModelScope,
        // See RosterViewModel for why this is Eagerly rather than
        // WhileSubscribed: the upstreams are in-memory StateFlows, and the
        // default value is the placeholder caregiver card.
        started = SharingStarted.Eagerly,
        initialValue = AppSettingsState(),
    )

    fun signOut() {
        viewModelScope.launch { onSignOut() }
    }

    /**
     * The irreversible delete. `AppSettingsScreen` only calls this from the
     * confirmation alert's Delete button.
     *
     * On success this screen is gone: `currentCaregiver` becomes null and the
     * root swaps to the welcome flow, so there is no success state to write.
     * On failure the caregiver stays signed in and reads [AppSettingsState
     * .deleteError].
     */
    fun deleteAccount() {
        // `.disabled(isDeleting)` guards the row, but the callback is a plain
        // function and a second delete would race the first.
        if (_isDeleting.value) return
        _isDeleting.value = true
        viewModelScope.launch {
            try {
                onDeleteAccount()
            } catch (e: Throwable) {
                _deleteError.value = messageFor(e)
            } finally {
                // iOS resets this in a `defer` for the same reason: a thrown
                // error must not leave the row permanently disabled.
                _isDeleting.value = false
            }
        }
    }

    /** The alert's OK button. Swift clears `deleteError` to dismiss it. */
    fun dismissDeleteError() {
        _deleteError.value = null
    }

    /**
     * Verbatim from [ApiError.errorDescription], which is what Swift's
     * `error.localizedDescription` resolves to for the errors this path throws.
     * That copy is the product's and is pinned by test; rewording it here would
     * be a silent product change.
     *
     * The last sentence is a last resort for a failure that carries no message
     * at all. Every error this path can actually throw is an [ApiError], and all
     * nine of those carry one.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is ApiError -> e.errorDescription
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Couldn't delete your account."
    }
}
