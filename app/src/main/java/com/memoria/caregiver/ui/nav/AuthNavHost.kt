package com.memoria.caregiver.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.data.AppConfig
import com.memoria.caregiver.legal.LegalConstants
import com.memoria.caregiver.ui.auth.AuthViewModel
import com.memoria.caregiver.ui.auth.ForgotPasswordScreen
import com.memoria.caregiver.ui.auth.LoginScreen
import com.memoria.caregiver.ui.auth.PdfPageLoader
import com.memoria.caregiver.ui.auth.ResetPasswordScreen
import com.memoria.caregiver.ui.auth.ResetPasswordState
import com.memoria.caregiver.ui.auth.SignUpScreen
import com.memoria.caregiver.ui.auth.SignUpState
import com.memoria.caregiver.ui.auth.TermsGateScreen
import com.memoria.caregiver.ui.auth.TermsGateState
import com.memoria.caregiver.ui.auth.WelcomeScreen

/**
 * The signed-out flow, and the gate into the signed-in one.
 *
 * Port of `App/RootView.swift`: when there is no caregiver, Welcome → Login /
 * SignUp; otherwise the main app. iOS calls `restoreSession()` in a `.task` on
 * the root, which is the [LaunchedEffect] here — a cold launch with a cached
 * session lands straight on a populated roster rather than the welcome screen.
 *
 * Over both of those sits the password-reset screen, when a recovery link is
 * open. It is a cover rather than a route, and the recovery code it was reached
 * with lives in the view model rather than in a navigation argument — see
 * DECISIONS.md, "Reset password is a cover, not a route".
 *
 * Deliberately NOT ported: the two-tick dismiss/present dance around
 * `fullScreenCover` (DIFFERENCES.md D1). Navigation Compose can swap a
 * destination in one pass.
 */
@Composable
fun AuthNavHost(
    viewModel: AuthViewModel,
    signedInContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val caregiver by viewModel.currentCaregiver.collectAsState()
    val reset by viewModel.reset.collectAsState()

    // iOS: `.task { await env.auth.restoreSession() }` on RootView.
    LaunchedEffect(Unit) { viewModel.restoreSession() }

    Box(modifier.fillMaxSize()) {
        if (caregiver != null) {
            signedInContent()
        } else {
            SignedOutGraph(viewModel = viewModel, navController = navController)
        }

        // The recovery link, drawn OVER whichever of the two is up — iOS presents
        // `ResetPasswordView` as a `fullScreenCover` on RootView, so it appears
        // over the welcome screen when signed out and over the dashboard when
        // signed in, and what is underneath stays alive. A Navigation Compose
        // destination could do neither: it lives inside the signed-out graph, so
        // a caregiver who is already signed in would tap the link and reach
        // nothing, and entering the route would tear down whatever they were on.
        //
        // A local `val` because a delegated `by` property is not a smart-cast
        // subject — the null check below has to hold for the body.
        val session = reset
        if (session != null) {
            ResetPasswordDestination(
                state = session.state,
                confirm = session.confirm,
                isLinkValid = session.isLinkValid,
                onPasswordChange = viewModel::onResetPasswordChange,
                onConfirmChange = viewModel::onResetConfirmChange,
                onSubmit = viewModel::submitPasswordReset,
                onClose = viewModel::closePasswordReset,
            )
        }
    }
}

/**
 * Welcome → Login / Sign up / Forgot password. Split out of [AuthNavHost] so
 * that function reads as the two layers it now is: the app, and the cover.
 */
@Composable
private fun SignedOutGraph(
    viewModel: AuthViewModel,
    navController: NavHostController,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onSignUp = { navController.navigate(Routes.SIGN_UP) },
                onLogin = { navController.navigate(Routes.LOGIN) },
            )
        }

        composable(Routes.LOGIN) {
            val state by viewModel.login.collectAsState()
            LoginScreen(
                state = state,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onKeepSignedInChange = viewModel::onKeepSignedInChange,
                onShowPasswordChange = viewModel::onShowPasswordChange,
                onSubmit = viewModel::submitLogin,
                onForgotPassword = { navController.navigate(Routes.FORGOT_PASSWORD) },
                onCreateAccount = {
                    // popUpTo so Welcome → Login → Create account does not stack
                    // two auth screens a caregiver has to back out of twice.
                    navController.navigate(Routes.SIGN_UP) {
                        popUpTo(Routes.WELCOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.FORGOT_PASSWORD) {
            val state by viewModel.forgot.collectAsState()
            ForgotPasswordScreen(
                state = state,
                onEmailChange = viewModel::onForgotEmailChange,
                onSubmit = {
                    viewModel.submitForgotPassword(AppConfig.supabasePasswordResetRedirect)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SIGN_UP) {
            val state by viewModel.signUp.collectAsState()
            val context = LocalContext.current
            // Application context, not the Activity's: the loader only reaches
            // `assets` and `cacheDir`, both of which the application supplies,
            // and holding the Activity in a remembered object is how leaks start.
            val pdfPages = remember(context) { PdfPageLoader(context.applicationContext) }

            SignUpDestination(
                state = state,
                onDisplayNameChange = viewModel::onSignUpDisplayNameChange,
                onEmailChange = viewModel::onSignUpEmailChange,
                onPhoneChange = viewModel::onSignUpPhoneChange,
                onCountryChange = viewModel::onSignUpCountryChange,
                onPasswordChange = viewModel::onSignUpPasswordChange,
                onAcceptTerms = viewModel::onSignUpTermsAccepted,
                onSubmit = {
                    viewModel.submitSignUp(
                        // The screen keeps the confirmation field in its own
                        // `remember` and never exposes it (see the note on
                        // `SignUpScreen`), so the host has no copy to pass. It
                        // does not need one: `signUpCanSubmit` already required
                        // confirmation == password before the button could fire,
                        // so the password IS the confirmed value by the time this
                        // runs, and the confirmation is never sent anywhere.
                        //
                        // Read live off the flow rather than from `state`, which
                        // is a composition snapshot that can be one frame behind
                        // the last keystroke. A stale password here would fail the
                        // ViewModel's re-check and silently drop the submit.
                        confirmPassword = viewModel.signUp.value.password,
                        tosVersion = LegalConstants.currentTosVersion,
                    )
                },
                onNavigateToLogin = {
                    // Same popUpTo reasoning as Login's "Create an account": the
                    // two screens link to each other, so without this a caregiver
                    // bouncing between them stacks a back button per bounce.
                    navController.navigate(Routes.LOGIN) { popUpTo(Routes.WELCOME) }
                },
                onBack = { navController.popBackStack() },
                pageSource = pdfPages::load,
            )
        }
    }
}

/**
 * The sign-up form and the consent gate that opens over it.
 *
 * Split out of the `composable(SIGN_UP)` block so it can be rendered in a test
 * without a Supabase-backed [AuthViewModel], and pure over its inputs for the
 * reason every auth screen is (AuthUiState.kt).
 *
 * ## The gate is drawn over the form, not navigated to
 *
 * iOS presents `TermsConsentView` as a `.sheet` from `SignUpView`, so the form
 * stays alive underneath and the caregiver comes back to exactly what they had
 * typed. A separate Navigation Compose destination would not: leaving the route
 * tears down the form's composition, and `SignUpScreen` deliberately keeps the
 * confirm-password field in a plain `remember` (never `rememberSaveable`, so the
 * plaintext never reaches the saved-state Bundle). That field is typed *just
 * above* the terms row, so a caregiver would review the terms and return to a
 * blank confirmation and a disabled button with nothing saying why — the same
 * shape of dead end as the loop this flow replaces.
 *
 * **The gate blocks; it is not dismissible into a signed-up state.** Cancel and
 * Back both return to the form *without* agreeing, which leaves `acceptedTerms`
 * false and the "Create account" button disabled. There is no path to an account
 * that skips it, and none that accepts without scrolling both documents.
 */
@Composable
internal fun SignUpDestination(
    state: SignUpState,
    onDisplayNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onCountryChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAcceptTerms: () -> Unit,
    onSubmit: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onBack: () -> Unit,
    pageSource: suspend (String) -> List<ImageBitmap>,
) {
    var reviewingTerms by rememberSaveable { mutableStateOf(false) }
    // Whether BOTH documents have been scrolled to the end during THIS viewing.
    // iOS keeps the equivalent in the sheet's own `@State`, so re-opening to
    // "review again" starts from an unreviewed, disabled button even though the
    // form's agreement stands. Resetting on open reproduces that.
    var reviewedBothDocuments by rememberSaveable { mutableStateOf(false) }

    SignUpScreen(
        state = state,
        onDisplayNameChange = onDisplayNameChange,
        onEmailChange = onEmailChange,
        onPhoneChange = onPhoneChange,
        onCountryChange = onCountryChange,
        onPasswordChange = onPasswordChange,
        onReviewTerms = {
            reviewedBothDocuments = false
            reviewingTerms = true
        },
        onSubmit = onSubmit,
        onNavigateToLogin = onNavigateToLogin,
        onBack = onBack,
    )

    if (reviewingTerms) {
        // Android's universal exit, and the gate is a conditional branch rather
        // than a route, so nothing else handles it. Without this, Back — and the
        // edge swipe, which is how most people close a screen — would fall
        // through to Navigation Compose and pop the whole sign-up route, or off
        // the welcome screen and out of the app (FINDINGS.md F-25).
        //
        // Back is Cancel: it returns to the form WITHOUT agreeing.
        BackHandler { reviewingTerms = false }

        TermsGateScreen(
            // A fresh, empty form state. `TermsConsentView` has no error surface
            // and nothing here is async; handing it the sign-up form's state
            // would replay a stale sign-up error over the documents, which iOS
            // never does.
            state = TermsGateState(canAccept = reviewedBothDocuments),
            onCancel = { reviewingTerms = false },
            onAllDocumentsReviewed = { reviewedBothDocuments = true },
            onAgree = {
                onAcceptTerms()
                // iOS runs `onAgree()` and then dismisses; dismissal is the
                // host's job here, so it does both.
                reviewingTerms = false
            },
            // The gate is opaque and covers the form, but covering it visually is
            // not covering it for touch: Compose hit-tests lower siblings wherever
            // the top one has no pointer input, so a tap on the gate's helper-text
            // strip would reach whatever form field happens to sit behind it and
            // raise the keyboard over the documents. A pointer-input node across
            // the whole gate ends hit testing there. It consumes nothing, so the
            // gate's own controls, which are hit first, still work.
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent() } }
                },
            pageSource = pageSource,
        )
    }
}

/**
 * The password-reset screen as it is actually presented: over the app, with the
 * two things a cover needs on Android that iOS gets from the platform.
 *
 * Split out of [AuthNavHost] for the same reason [SignUpDestination] is — so a
 * test can drive it in states the offline harness cannot reach, `isLinkValid`
 * above all, which only a real code exchange sets.
 *
 * **The recovery code is not a parameter.** It stays in the view model and never
 * enters the composition: the screen has no use for it, and a credential in a
 * composable's arguments is a credential in whatever inspects, saves or dumps
 * that composition.
 */
@Composable
internal fun ResetPasswordDestination(
    state: ResetPasswordState,
    confirm: String,
    isLinkValid: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    val analytics = LocalAnalytics.current
    // iOS: `.trackScreen("reset_password")` on the cover, so it is logged by the
    // presenter rather than by the view. A fixed label — the code it was reached
    // with is a credential and never goes into an analytics event.
    LaunchedEffect(Unit) { analytics.logScreen("reset_password") }

    // Android's universal exit, and this screen is a conditional branch rather
    // than a route, so nothing else handles it. Without this, Back — and the
    // edge swipe, which is how most people close a screen — would fall through
    // to the navigation host underneath and pop the welcome screen off, quitting
    // the app from the middle of a password reset (FINDINGS.md F-25).
    //
    // Back is Close, which is the only exit iOS offers here too.
    BackHandler(onBack = onClose)

    ResetPasswordScreen(
        state = state,
        confirm = confirm,
        isLinkValid = isLinkValid,
        onPasswordChange = onPasswordChange,
        onConfirmChange = onConfirmChange,
        onSubmit = onSubmit,
        onClose = onClose,
        // The screen is opaque, but covering something visually is not covering
        // it for touch: Compose hit-tests lower siblings wherever the top one has
        // no pointer input, so a tap on the blank strip beside "Verifying link…"
        // would reach the sign-in form underneath. Same guard, same reason, as
        // the terms gate above. It consumes nothing, so the screen's own controls
        // — hit first — still work.
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent() } }
            },
    )
}
