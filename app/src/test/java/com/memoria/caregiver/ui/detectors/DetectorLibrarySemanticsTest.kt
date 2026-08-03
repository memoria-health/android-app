package com.memoria.caregiver.ui.detectors

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.data.model.DetectorLibraryEntry
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a detection-library row announces. Spec: FINDINGS.md F-31.
 *
 * The row's whole state is a `checkmark.circle.fill` swapped for an empty
 * `circle` at the trailing edge. As a plain clickable it announced as a button,
 * so a caregiver picking detectors heard the same words for one they had
 * already added and one they had not — on the screen whose only job is choosing
 * between them.
 */
@RunWith(RobolectricTestRunner::class)
class DetectorLibrarySemanticsTest {

    @get:Rule val compose = createComposeRule()

    private val fx = DetectorScreenFixtures

    private val entries = listOf(
        fx.entry("fall", name = "Fall", recommended = true),
        fx.entry("stove", name = "Stove left on"),
    )

    private fun setUp(selected: Set<String>) {
        compose.setContent {
            MemoriaTheme {
                DetectorLibraryScreen(
                    state = DetectorLibraryState(
                        mode = DetectorLibraryMode.Add,
                        entries = entries,
                        selectedKeys = selected,
                        isLoading = false,
                    ),
                    onQueryChange = {},
                    onToggleEntry = { _: DetectorLibraryEntry -> },
                    onRetry = {},
                    onLeadingAction = {},
                    onSubmit = {},
                )
            }
        }
    }

    @Test
    fun `a library row announces as a checkbox`() {
        setUp(selected = emptySet())
        compose.onNodeWithText("Fall").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }

    @Test
    fun `an unpicked detector reads off`() {
        setUp(selected = emptySet())
        compose.onNodeWithText("Fall").performScrollTo().assertIsOff()
    }

    @Test
    fun `a picked detector reads on`() {
        setUp(selected = setOf("fall"))
        compose.onNodeWithText("Fall").performScrollTo().assertIsOn()
    }

    /** One row changing state must not change its neighbour's. */
    @Test
    fun `only the picked row reads on`() {
        setUp(selected = setOf("fall"))
        compose.onNodeWithText("Stove left on").performScrollTo().assertIsOff()
    }
}
