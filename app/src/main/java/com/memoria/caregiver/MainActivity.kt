package com.memoria.caregiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.memoria.caregiver.ui.theme.MemoriaTheme
import com.memoria.caregiver.ui.theme.Paper

/**
 * Placeholder host. Phase 1 Task 15 replaces this body with the component
 * gallery; Phase 2 replaces it with the real navigation graph.
 *
 * Two things here are conventions for every screen in phases 2-8, not
 * placeholder detail:
 *
 * 1. **Edge-to-edge is enforced, not chosen.** At targetSdk 35, Android 15+
 *    ignores `android:statusBarColor` and draws content under the system bars
 *    whether or not the app opts in. So the app opts in explicitly and paints
 *    the bars itself, rather than shipping a layout that happens to work on
 *    Android 14 and overlaps the status bar on 15.
 * 2. **Insets are consumed at the root** via `safeDrawingPadding()`. That is the
 *    Android equivalent of SwiftUI's implicit safe area, which every ported
 *    screen assumes. See DECISIONS.md D-010; screens that deliberately bleed
 *    under a bar (live video) opt out locally.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light scrims on both bars: the app is light-mode only, so dark system
        // icons on Paper is the only correct combination.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Paper.toArgb(), Paper.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MemoriaTheme {
                // Paper, not the Material `surface` role. Surface defaults to
                // colorScheme.surface, which is Card — the RAISED card token.
                // iOS backs the app with Paper and puts Card on cards, so a
                // default Surface would flatten that distinction everywhere.
                // Caught on the first emulator screenshot: the body read
                // lighter than the status bar.
                Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        Text("Memoria")
                    }
                }
            }
        }
    }
}
