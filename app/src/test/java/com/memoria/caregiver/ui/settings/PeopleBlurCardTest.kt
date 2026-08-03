package com.memoria.caregiver.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PeopleBlurCard], and specifically the availability gate. Spec: HARDENING.md
 * H-12, REQ-23.
 *
 * **The failure being guarded is not a rendering bug.** A caregiver turns on
 * "Blur people" for a bedroom camera, the switch shows on, and the video is not
 * blurred, because no person-detection model ships yet. A privacy control that
 * silently does nothing is worse than one that is visibly unavailable: the first
 * makes someone believe they are protected while they are not.
 *
 * The card had no tests at all before this file, which is how the gate could
 * have been added, or later removed, with a green suite either way.
 */
@RunWith(RobolectricTestRunner::class)
class PeopleBlurCardTest {

    @get:Rule val compose = createComposeRule()

    private val toggles = mutableListOf<Boolean>()

    private fun render(enabled: Boolean, available: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    PeopleBlurCard(
                        enabled = enabled,
                        onEnabledChange = { toggles += it },
                        available = available,
                    )
                }
            }
        }
    }

    /**
     * The case the gate exists for: the preference says on, the model is absent.
     * The switch must read OFF, because the video is not blurred.
     */
    @Test
    fun `a stored preference cannot make the switch read on while no model ships`() {
        render(enabled = true, available = false)
        switch().assertIsOff()
    }

    @Test
    fun `an unavailable switch cannot be operated`() {
        render(enabled = false, available = false)
        switch().assertIsNotEnabled()
    }

    /**
     * Tapping an unavailable control must not write a preference either. A
     * stored `true` that does nothing is the state this card exists to avoid
     * being able to reach.
     */
    @Test
    fun `tapping an unavailable switch records nothing`() {
        render(enabled = false, available = false)
        switch().performClick()
        assertTrue("no preference should be written", toggles.isEmpty())
    }

    @Test
    fun `an unavailable card says so instead of describing a feature that runs`() {
        render(enabled = true, available = false)
        compose
            .onNodeWithText("Not available in this version yet", substring = true)
            .assertIsDisplayed()
    }

    /**
     * The gate must not become a permanent off switch. When a detector lands,
     * flipping `PeopleBlurAvailability.IS_AVAILABLE` is the only change needed.
     */
    @Test
    fun `an available switch behaves normally`() {
        render(enabled = true, available = true)
        switch().assertIsOn()
    }

    @Test
    fun `an available switch reports the caregiver's choice`() {
        render(enabled = false, available = true)
        switch().performClick()
        assertEquals(listOf(true), toggles)
    }

    /** `NemaraToggle` declares `role = Role.Switch`, which is how it is found. */
    private fun switch() = compose.onNode(
        androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Switch,
        ),
    )
}
