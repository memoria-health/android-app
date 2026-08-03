package com.memoria.caregiver.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.memoria.caregiver.data.auth.SupabaseAuthService
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.data.remote.NemaraApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Hosts [EditProfileScreen]: owns the state, runs the save, and leaves.
 *
 * Spec: `Features/AppSettings/EditProfileView.swift`, which is presented as a
 * sheet from both the roster avatar and the Settings caregiver card. There is no
 * ViewModel because there is nothing to survive the screen — iOS keeps the same
 * six values in `@State`, and every one of them dies with the sheet.
 *
 * The save is two independent calls in the Swift's order, and the order is not
 * cosmetic: name and phone go through `PATCH /v1/me` and land immediately, while
 * the email goes through Supabase's confirmation-link flow and does **not**.
 * Running the PATCH first means a failure to send the confirmation mail still
 * leaves the name change saved, which is what iOS does.
 */
@Composable
fun EditProfileHost(
    caregiver: Caregiver?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** `env.api.updateMe`. Taken as a function so this can be exercised without a client. */
    updateMe: suspend (displayName: String?, phone: String?) -> Caregiver,
    /** `env.auth.requestEmailChange`. */
    requestEmailChange: suspend (String) -> Unit,
    /** `env.auth.currentCaregiver = updated`. */
    onCaregiverUpdated: (Caregiver) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Seeded WITH the caregiver rather than folded in afterwards. The screen
    // reports its appear from a `LaunchedEffect`, which runs after this
    // composition, and `hydrated()` is a no-op when the row is null — so a state
    // that starts empty and receives the caregiver one frame later would leave
    // the three fields permanently blank. That is also iOS's rule (hydration
    // never re-runs), which only works there because the row is already in the
    // environment when the sheet is built. It is already here too.
    var state by remember { mutableStateOf(EditProfileState(caregiver = caregiver)) }

    // Keeps the row fresh afterwards — a Realtime update to the caregiver, or
    // this screen's own save landing. Only the row moves; the typed fields and
    // `isHydrated` are left exactly as they are.
    LaunchedEffect(caregiver) { state = state.copy(caregiver = caregiver) }

    // Android's universal exit. Cancel and Back have to mean the same thing, or
    // Back falls through to the Activity and quits the app mid-edit —
    // FINDINGS.md F-25.
    BackHandler(onBack = onDismiss)

    EditProfileScreen(
        state = state,
        modifier = modifier,
        onNameChange = { state = state.copy(name = it) },
        onEmailChange = { state = state.copy(email = it) },
        onPhoneChange = { state = state.copy(phone = it) },
        // iOS's `.onAppear { hydrate() }`. The screen reports the appear; the
        // rule about what hydration does lives in `SettingsUiState.kt` and is
        // applied here, because a pure screen may not rewrite its own state.
        onAppear = { state = state.hydrated() },
        onCancel = onDismiss,
        onSave = {
            // `canSave` already carries `!isSaving`, but the callback is a plain
            // function and a second tap would race the first.
            if (state.isSaving) return@EditProfileScreen
            state = state.copy(isSaving = true, errorMessage = null)
            scope.launch {
                try {
                    // Snapshotted BEFORE the first suspension and re-read from
                    // the snapshot afterwards. Reading `state` again after the
                    // await would pick up whatever the caregiver typed while the
                    // request was in flight and send a different edit than the
                    // one that was validated — FINDINGS.md F-12.
                    val submitted = state
                    if (submitted.nameChanged || submitted.phoneChanged) {
                        val updated = updateMe(
                            if (submitted.nameChanged) submitted.trimmedName else null,
                            if (submitted.phoneChanged) {
                                submitted.trimmedPhone.ifEmpty { null }
                            } else {
                                null
                            },
                        )
                        onCaregiverUpdated(updated)
                    }
                    if (submitted.emailChanged) {
                        requestEmailChange(submitted.trimmedEmail)
                    }
                    onDismiss()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Throwable) {
                    // The sheet stays open on failure, as iOS's does: the
                    // caregiver has unsaved typing in it.
                    state = state.copy(errorMessage = editProfileErrorFor(e))
                } finally {
                    // iOS drops this in a `defer` for the same reason: a thrown
                    // error must not leave Save permanently disabled.
                    state = state.copy(isSaving = false)
                }
            }
        },
    )
}

/**
 * Convenience overload that reads the two services directly. The app calls this
 * one; the parameterised version above is the seam tests use.
 */
@Composable
fun EditProfileHost(
    caregiver: Caregiver?,
    api: NemaraApiClient,
    auth: SupabaseAuthService,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EditProfileHost(
        caregiver = caregiver,
        onDismiss = onDismiss,
        modifier = modifier,
        updateMe = { displayName, phone -> api.updateMe(displayName, phone) },
        requestEmailChange = auth::requestEmailChange,
        onCaregiverUpdated = auth::updateCurrentCaregiver,
    )
}

/**
 * Verbatim from [ApiError.errorDescription], which is what Swift's
 * `error.localizedDescription` resolves to for the errors this path throws.
 *
 * The last resort covers a failure carrying no message at all. Everything this
 * path can actually throw — an [ApiError] from the PATCH, an auth error from the
 * email change — carries one.
 */
internal fun editProfileErrorFor(e: Throwable): String = when (e) {
    is ApiError -> e.errorDescription
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
