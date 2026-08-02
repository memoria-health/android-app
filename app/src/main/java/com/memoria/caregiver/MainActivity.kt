package com.memoria.caregiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.NoOpAnalytics
import com.memoria.caregiver.ui.auth.AuthViewModel
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.nav.AuthNavHost
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.MemoriaTheme
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * The app's single activity.
 *
 * Two things here are conventions for every screen, not incidental:
 *
 * 1. **Edge-to-edge is enforced, not chosen.** At targetSdk 35, Android 15+
 *    ignores `android:statusBarColor` and draws under the system bars whether
 *    or not the app opts in. So it opts in explicitly and paints them itself.
 * 2. **Insets are consumed at the root** with `safeDrawingPadding()`, the
 *    Android equivalent of SwiftUI's implicit safe area that every ported
 *    screen assumes. A screen that deliberately bleeds under a bar — live video
 *    — opts out locally. See DECISIONS.md D-010.
 */
class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light scrims on both bars: the app is light-mode only, so dark system
        // icons on Paper is the only correct combination.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
        )
        super.onCreate(savedInstanceState)

        val env = AppEnvironment(scope = appScope)
        val authViewModel = ViewModelProvider(
            this as ViewModelStoreOwner,
            viewModelFactory { initializer { AuthViewModel(env.auth) } },
        )[AuthViewModel::class.java]

        setContent {
            // The real analytics transport lands in Phase 8. Until then the
            // no-op sink keeps every component's internal logging call valid
            // without inventing a half-built pipeline.
            CompositionLocalProvider(LocalAnalytics provides NoOpAnalytics) {
                MemoriaTheme {
                    // Paper, not the Material `surface` role. Surface defaults to
                    // colorScheme.surface, which is Card — the RAISED card token.
                    // iOS backs the app with Paper and reserves Card for cards.
                    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                        AuthNavHost(
                            viewModel = authViewModel,
                            modifier = Modifier.safeDrawingPadding(),
                            signedInContent = { SignedInPlaceholder(authViewModel) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stands in for the signed-in app until the roster host is wired.
 *
 * It shows the caregiver the backend actually returned, which is the point: it
 * proves sign-in reached `POST /v1/me` and decoded the response, rather than
 * just that Supabase accepted a password.
 */
@androidx.compose.runtime.Composable
private fun SignedInPlaceholder(viewModel: AuthViewModel) {
    val caregiver by viewModel.currentCaregiver.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NemaraEyebrow("Signed in")
        Text(text = caregiver?.displayName ?: "—", style = NemaraType.title1, color = Ink)
        Text(text = caregiver?.email ?: "—", style = NemaraType.body, color = Ink)
        NemaraButton(
            title = "Sign out",
            variant = NemaraButtonVariant.Secondary,
            onClick = { viewModel.signOut() },
        )
    }
}
