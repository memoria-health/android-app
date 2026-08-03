package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.analytics.RecordingAnalytics
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings controls FINDINGS.md F-31 names one by one: the Enroll link, the
 * recipient Remove, Add, and Send test email.
 *
 * All four are `Text` with a tap on Android and `Button` on iOS. A screen
 * reader met them as ordinary words in a sentence — "Remove" sitting after an
 * email address reads as a heading, not as the control that deletes it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsControlRoleTest {

    @get:Rule val compose = createComposeRule()

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun renderPatientSettings(state: PatientSettingsState) {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    PatientSettingsScreen(
                        state = state,
                        resolvePhotoUrl = { null },
                        onPickPhoto = {},
                        onRemovePhoto = {},
                        onEnroll = {},
                        onEmailAlertsEnabledChange = {},
                        onEmailDraftChange = {},
                        onEmailRecipientsChange = {},
                        onEmailValidationError = {},
                        onSendTestEmail = {},
                        onPeopleBlurChange = {},
                        onAudioCaptureChange = {},
                        onDataCollectionChange = {},
                        onOpenFeedback = {},
                        onRemovePatient = {},
                    )
                }
            }
        }
    }

    private fun renderEmailAlerts() {
        compose.setContent {
            CompositionLocalProvider(LocalAnalytics provides RecordingAnalytics()) {
                MemoriaTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PatientEmailAlertsCard(
                            patient = patient().copy(
                                emailAlertsEnabled = true,
                                emailAlertRecipients = listOf("jane@home.com"),
                            ),
                            accountEmail = "me@home.com",
                            state = EmailAlertsState(draft = "bob@home.com"),
                            onEnabledChange = {},
                            onDraftChange = {},
                            onRecipientsChange = {},
                            onValidationError = {},
                            onSendTestEmail = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `enroll announces as a button`() {
        renderPatientSettings(PatientSettingsState(patient = patient()))
        compose.onNodeWithText("Enroll").performScrollTo().assert(isButton())
    }

    @Test
    fun `the feedback row announces as a button`() {
        renderPatientSettings(PatientSettingsState(patient = patient()))
        compose.onNodeWithText("Feedback & support").performScrollTo().assert(isButton())
    }

    @Test
    fun `removing a recipient announces as a button`() {
        renderEmailAlerts()
        compose.onNodeWithText("Remove").performScrollTo().assert(isButton())
    }

    @Test
    fun `adding a recipient announces as a button`() {
        renderEmailAlerts()
        compose.onNodeWithText("Add").performScrollTo().assert(isButton())
    }

    @Test
    fun `send test email announces as a button`() {
        renderEmailAlerts()
        compose.onNodeWithText("Send test email").performScrollTo().assert(isButton())
    }
}
