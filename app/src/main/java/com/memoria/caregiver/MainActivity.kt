package com.memoria.caregiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

/**
 * Placeholder host. Phase 1 Task 15 replaces this body with the component
 * gallery; Phase 2 replaces it with the real navigation graph.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Memoria")
        }
    }
}
