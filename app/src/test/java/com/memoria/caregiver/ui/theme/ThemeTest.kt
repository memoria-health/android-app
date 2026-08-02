package com.memoria.caregiver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The theme must be light regardless of the system setting. Spec: PARITY.md §9 R8.
 *
 * This is the one risk-register item Phase 1 touches, so it gets a named test.
 * The failure it guards is quiet: someone adds isSystemInDarkTheme() while
 * scaffolding a screen, and every caregiver with dark mode on sees an app the
 * designers never drew. Nothing crashes, no test fails, and it surfaces in a
 * pilot household.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeTest {

    @get:Rule val compose = createComposeRule()

    private fun capture(): Map<String, Color> {
        val captured = mutableStateOf(emptyMap<String, Color>())
        compose.setContent {
            MemoriaTheme {
                val scheme = MaterialTheme.colorScheme
                captured.value = mapOf(
                    "background" to scheme.background,
                    "surface" to scheme.surface,
                    "onBackground" to scheme.onBackground,
                    "primary" to scheme.primary,
                    "secondary" to scheme.secondary,
                    "error" to scheme.error,
                    "outline" to scheme.outline,
                )
            }
        }
        compose.waitForIdle()
        val result by captured
        return result
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `light mode renders the memoria palette`() {
        val scheme = capture()
        assertEquals(Paper, scheme["background"])
        assertEquals(Card, scheme["surface"])
        assertEquals(Ink, scheme["onBackground"])
    }

    @Test
    @Config(qualifiers = "night")
    fun `night mode renders the identical palette`() {
        val scheme = capture()
        assertEquals(Paper, scheme["background"])
        assertEquals(Card, scheme["surface"])
        assertEquals(Ink, scheme["onBackground"])
        assertEquals(Action, scheme["primary"])
        assertEquals(Terra, scheme["secondary"])
        assertEquals(Danger, scheme["error"])
        assertEquals(Line, scheme["outline"])
    }

    /**
     * Material You would derive the scheme from the device wallpaper, which
     * would repaint the brand. Asserting the brand survives on an API level
     * where dynamic color exists (31+) catches an accidental
     * dynamicLightColorScheme() swap.
     */
    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `brand survives on a dynamic-color-capable api level`() {
        val scheme = capture()
        assertEquals(Terra, scheme["secondary"])
        assertEquals(Paper, scheme["background"])
    }
}
