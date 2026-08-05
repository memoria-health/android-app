package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `PatientEmailAlertsCard` behavior. Spec:
 * `Features/Patient/Settings/PatientEmailAlertsCard.swift`, PARITY.md §24.
 *
 * The rules worth pinning are all about what reaches the server: the recipients
 * array is replaced wholesale on every add and remove, so a bug that sends only
 * the delta silently wipes the list.
 */
@RunWith(RobolectricTestRunner::class)
class PatientEmailAlertsCardTest {

    @get:Rule val compose = createComposeRule()

    private val account = "me@home.com"

    private fun setUp(
        patient: Patient,
        state: EmailAlertsState = EmailAlertsState(),
        analytics: RecordingAnalytics = RecordingAnalytics(),
        onEnabledChange: (Boolean) -> Unit = {},
        onRecipientsChange: (List<String>) -> Unit = {},
        onValidationError: (String) -> Unit = {},
        onSendTestEmail: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides analytics) {
                MemoriaTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PatientEmailAlertsCard(
                            patient = patient,
                            accountEmail = account,
                            state = state,
                            onEnabledChange = onEnabledChange,
                            onDraftChange = {},
                            onRecipientsChange = onRecipientsChange,
                            onValidationError = onValidationError,
                            onSendTestEmail = onSendTestEmail,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `adding sends the whole array, not just the new address`() {
        var sent: List<String>? = null
        setUp(
            patient = patient().copy(emailAlertRecipients = listOf("jane@home.com")),
            state = EmailAlertsState(draft = "bob@home.com"),
            onRecipientsChange = { sent = it },
        )

        compose.onNodeWithText("Add").performScrollTo().performClick()

        assertEquals(listOf("jane@home.com", "bob@home.com"), sent)
    }

    @Test
    fun `removing sends the whole array minus the removed address`() {
        var sent: List<String>? = null
        setUp(
            patient = patient().copy(
                emailAlertRecipients = listOf("jane@home.com", "bob@home.com"),
            ),
            onRecipientsChange = { sent = it },
        )

        compose.onAllNodesWithText("Remove")[0].performScrollTo().performClick()

        assertEquals(listOf("bob@home.com"), sent)
    }

    /**
     * A malformed address must never reach the server: the card reports the
     * rejection sentence and sends nothing.
     */
    @Test
    fun `a malformed draft reports an error and sends nothing`() {
        var sent: List<String>? = null
        var reported: String? = null
        setUp(
            patient = patient(),
            state = EmailAlertsState(draft = "jane"),
            onRecipientsChange = { sent = it },
            onValidationError = { reported = it },
        )

        compose.onNodeWithText("Add").performScrollTo().performClick()

        assertNull(sent)
        assertEquals("That doesn't look like a valid email address.", reported)
    }

    /**
     * One `busy` flag gates every mutation on this card, so a PATCH already in
     * flight cannot be raced by a second one.
     */
    @Test
    fun `nothing can be sent while a mutation is in flight`() {
        var sent: List<String>? = null
        var tests = 0
        setUp(
            patient = patient().copy(emailAlertRecipients = listOf("jane@home.com")),
            state = EmailAlertsState(draft = "bob@home.com", busy = true),
            onRecipientsChange = { sent = it },
            onSendTestEmail = { tests++ },
        )

        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.onAllNodesWithText("Remove")[0].performScrollTo().performClick()

        assertNull(sent)
        // The button's label is replaced by a spinner while busy, so there is
        // nothing to tap — which is the point.
        compose.onNodeWithText("Send test email").assertDoesNotExist()
        assertEquals(0, tests)
    }

    @Test
    fun `the toggle logs its new value under the email alerts label`() {
        val analytics = RecordingAnalytics()
        var enabled: Boolean? = null
        setUp(
            patient = patient().copy(emailAlertsEnabled = false),
            analytics = analytics,
            onEnabledChange = { enabled = it },
        )

        // The caption this used to assert on moved behind an ⓘ in the July
        // declutter. What the test is actually about — that flipping the
        // toggle reports the new value — is below and untouched.

        // The toggle carries no text, so it is selected by its semantics role.
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        ).performScrollTo().performClick()

        assertEquals(true, enabled)
        assertEquals(
            listOf(RecordingAnalytics.Entry("toggle", "email_alerts.toggle", "on")),
            analytics.entries,
        )
    }
}
