package com.memoria.caregiver.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * A plain captured var, not a MutableState. Writing snapshot state from
     * inside composition is a side effect in composition, which Compose's
     * contract forbids; it is only harmless here because nothing reads it back
     * during composition. Adding such a read — the natural way to extend this
     * helper — would turn it into an infinite recomposition loop.
     */
    private fun capture(): Map<String, Color> {
        var captured: Map<String, Color> = emptyMap()
        compose.setContent {
            MemoriaTheme {
                val scheme = MaterialTheme.colorScheme
                captured = mapOf(
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
        assertTrue("composition produced no scheme", captured.isNotEmpty())
        return captured
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
        assertEquals(Terra, scheme["primary"])
        assertEquals(Action, scheme["secondary"])
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
        assertEquals(Terra, scheme["primary"])
        assertEquals(Paper, scheme["background"])
    }

    /**
     * iOS sets the control accent globally: AccentColor.colorset is #A04331 and
     * App/NemaraCaregiverApp.swift:71 applies .tint(NemaraColor.terra) to the
     * root. Material3's equivalent role is `primary`, which everything tints
     * from — caret, focused field indicator, switches, sliders, selection
     * handles.
     *
     * Pointing `primary` at Action instead would render all of those warm
     * near-black where iOS renders rust. It would not be caught by any CTA
     * check, because NemaraButton resolves its own colors and never reads the
     * scheme.
     */
    @Test
    fun `primary is the terra accent, matching the iOS app-wide tint`() {
        assertEquals(Terra, capture()["primary"])
    }

    /**
     * Material3 defines 36 color roles. Any left unset keeps the Material
     * baseline, which is lavender — and the roles most likely to be hit are the
     * ones stray components read: DropdownMenu and ModalBottomSheet take
     * surfaceContainer / surfaceContainerLow, Snackbar takes inverseSurface,
     * the pickers take surfaceContainerHigh. Phase 5 is full of sheets.
     *
     * Walked by reflection rather than enumerated, so a Material version that
     * ADDS a role fails here instead of silently shipping a lavender surface
     * that nobody notices until a screenshot pass.
     */
    @Test
    fun `every material role comes from the memoria palette`() {
        val palette = setOf(
            Paper, PaperDim, Card, CardAlt,
            Ink, Ink2, Ink3, Ink4, Line,
            Terra, TerraSoft, TerraDeep, TerraInk,
            Action, ActionSoft, ActionInk,
            Sky, SkySoft,
            Success, Warning, Danger, Info,
        )

        var scheme: ColorScheme? = null
        compose.setContent { MemoriaTheme { scheme = MaterialTheme.colorScheme } }
        compose.waitForIdle()

        val roles = ColorScheme::class.memberProperties
            .filter { it.returnType.classifier == Color::class }

        val strays = roles.mapNotNull { role ->
            val value = role.get(scheme!!)
            if (value in palette) null else "${role.name} = $value"
        }

        assertTrue(
            "Material baseline colors leaked into the scheme: $strays",
            strays.isEmpty(),
        )
        // Guards the reflection itself: if the filter silently matched nothing,
        // the assertion above would pass vacuously.
        assertTrue("expected to inspect >= 30 roles, saw ${roles.size}", roles.size >= 30)
    }
}
