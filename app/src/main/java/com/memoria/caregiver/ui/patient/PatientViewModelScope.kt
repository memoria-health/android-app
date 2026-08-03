package com.memoria.caregiver.ui.patient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Gives everything inside one open patient its own [ViewModelStore], cleared
 * when the caregiver leaves that patient.
 *
 * ## What this fixes
 *
 * The patient tabs create their ViewModels with `viewModel(key = "…:$patientId")`.
 * There is no `NavHost` around the signed-in surface, so without this the
 * nearest `ViewModelStoreOwner` is the **Activity**, and a keyed ViewModel in an
 * Activity store is only cleared when the Activity is destroyed. Consequences,
 * all of which were live:
 *
 * - A caregiver with five patients who opened each one accumulated four
 *   ViewModels per patient, every one holding an eager `stateIn` collector on
 *   the shared cache flows for the rest of the session.
 * - Worse, every surviving `EventsListViewModel` kept collecting the deep-link
 *   router. On the next notification tap **all of them** fired, each missed its
 *   cache, and each issued its own authenticated `GET /v1/events/{id}` — for
 *   patients the caregiver was not looking at.
 * - They also survived sign-out, so a store belonging to the previous
 *   caregiver was still collecting when the next person signed in. The cache is
 *   emptied on sign-out, so no data leaked, but the objects should not be there.
 *
 * iOS has none of this: `PatientEventsView`'s `@State` dies with the cover.
 *
 * ## Why a plain owner rather than a NavHost
 *
 * A `NavHost` would give per-destination stores for free, but the patient
 * bundle is deliberately **not** a route — it replaces the whole surface
 * including the tab bar, which is why iOS presents it as a `fullScreenCover`
 * (see `MainShell`). Introducing a graph just to get a store would mean
 * modelling the bundle as a destination and re-inheriting the two-tab-bars
 * problem.
 */
@Composable
fun PatientViewModelScope(
    patientId: String,
    content: @Composable () -> Unit,
) {
    // Keyed on the patient: switching patients gets a fresh store, and the old
    // one is cleared by the DisposableEffect below rather than lingering.
    val owner = remember(patientId) { PatientStoreOwner() }

    DisposableEffect(owner) {
        onDispose {
            // Calls onCleared() on every ViewModel in the store, which cancels
            // each viewModelScope — the polls, the collectors, all of it.
            owner.viewModelStore.clear()
        }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        content()
    }
}

/**
 * The smallest possible [ViewModelStoreOwner]: a store and nothing else.
 *
 * It deliberately does NOT implement `HasDefaultViewModelProviderFactory` or
 * `SavedStateRegistryOwner`. Every ViewModel here is created with an explicit
 * factory, and none of them use `SavedStateHandle` — a patient's tab state is
 * transient and iOS does not restore it either.
 */
private class PatientStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}
