package com.memoria.caregiver.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.memoria.caregiver.data.AppConfig
import com.memoria.caregiver.ui.auth.AuthViewModel
import com.memoria.caregiver.ui.auth.ForgotPasswordScreen
import com.memoria.caregiver.ui.auth.LoginScreen
import com.memoria.caregiver.ui.auth.WelcomeScreen

/**
 * The signed-out flow, and the gate into the signed-in one.
 *
 * Port of `App/RootView.swift`: when there is no caregiver, Welcome → Login /
 * SignUp; otherwise the main app. iOS calls `restoreSession()` in a `.task` on
 * the root, which is the [LaunchedEffect] here — a cold launch with a cached
 * session lands straight on a populated roster rather than the welcome screen.
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

    // iOS: `.task { await env.auth.restoreSession() }` on RootView.
    LaunchedEffect(Unit) { viewModel.restoreSession() }

    if (caregiver != null) {
        Box(modifier.fillMaxSize()) { signedInContent() }
        return
    }

    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME,
        modifier = modifier.fillMaxSize(),
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

        // TODO(phase-2): SignUpScreen and TermsGateScreen. Both are built and
        // tested; wiring them needs the terms-version plumbing and the
        // confirm-password hoist, which is a separate change from proving
        // sign-in works end to end.
        composable(Routes.SIGN_UP) {
            WelcomeScreen(
                onSignUp = { navController.popBackStack() },
                onLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
    }
}
