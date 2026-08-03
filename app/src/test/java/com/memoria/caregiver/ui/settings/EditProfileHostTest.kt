package com.memoria.caregiver.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import com.memoria.caregiver.data.model.Caregiver
import com.memoria.caregiver.data.remote.ApiError
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `EditProfileHost` — the part `EditProfileScreenTest` cannot see.
 *
 * The screen's own gates are already covered pure in `SettingsUiStateTest` and
 * rendered in `EditProfileScreenTest`. What is checked here is the host's three
 * jobs, each of which was a way for a working screen to be reachable and still
 * wrong: it hydrates from the signed-in row, it sends **only** the fields that
 * changed, and it routes the email through the confirmation flow rather than
 * through the PATCH.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class EditProfileHostTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Recorder {
        val updates = mutableListOf<Pair<String?, String?>>()
        val emailChanges = mutableListOf<String>()
        val written = mutableListOf<Caregiver>()
        var dismissals = 0
        var updateThrows: Throwable? = null
        var emailThrows: Throwable? = null
    }

    private fun setUp(
        me: Caregiver? = caregiver(),
        recorder: Recorder = Recorder(),
    ): Recorder {
        compose.setContent {
            MemoriaTheme {
                EditProfileHost(
                    caregiver = me,
                    onDismiss = { recorder.dismissals++ },
                    updateMe = { name, phone ->
                        recorder.updates += name to phone
                        recorder.updateThrows?.let { throw it }
                        caregiver(displayName = name ?: "Aryaman Darda")
                    },
                    requestEmailChange = {
                        recorder.emailChanges += it
                        recorder.emailThrows?.let { e -> throw e }
                    },
                    onCaregiverUpdated = { recorder.written += it },
                )
            }
        }
        return recorder
    }

    /** The three fields, in the screen's order: name, email, phone. */
    private fun field(index: Int) = compose.onAllNodes(hasSetTextAction())[index]

    @Test
    fun `fills the fields from the signed-in caregiver`() {
        setUp(caregiver(displayName = "Aryaman Darda", email = "a@memoria.care"))

        compose.onNodeWithText("Aryaman Darda").assertIsDisplayed()
        compose.onNodeWithText("a@memoria.care").assertIsDisplayed()
    }

    @Test
    fun `Save sends only the name when only the name changed`() {
        val recorder = setUp(caregiver(displayName = "Aryaman Darda"))

        field(0).performTextReplacement("Ary Darda")
        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, recorder.updates.size)
        assertEquals("Ary Darda", recorder.updates[0].first)
        // Null, not the unchanged number. Sending it back would still be a write
        // the caregiver did not ask for, and iOS sends nil.
        assertNull(recorder.updates[0].second)
    }

    @Test
    fun `Save sends only the phone when only the phone changed`() {
        val recorder = setUp(caregiver(phone = "+15551234567"))

        field(2).performTextReplacement("+15559998888")
        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, recorder.updates.size)
        assertNull(recorder.updates[0].first)
        assertEquals("+15559998888", recorder.updates[0].second)
    }

    @Test
    fun `an email change goes through the confirmation flow, not the PATCH`() {
        val recorder = setUp(caregiver(email = "old@memoria.care"))

        field(1).performTextReplacement("new@memoria.care")
        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()
        compose.waitForIdle()

        // The whole point of the split: the address is NOT in the PATCH, because
        // it only becomes real once the caregiver clicks the link.
        assertTrue(recorder.updates.isEmpty())
        assertEquals(listOf("new@memoria.care"), recorder.emailChanges)
    }

    @Test
    fun `the updated row is written back so the screen behind refreshes`() {
        val recorder = setUp(caregiver(displayName = "Aryaman Darda"))

        field(0).performTextReplacement("Ary Darda")
        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, recorder.written.size)
        assertEquals("Ary Darda", recorder.written[0].displayName)
    }

    @Test
    fun `a successful save leaves`() {
        val recorder = setUp(caregiver(displayName = "Aryaman Darda"))

        field(0).performTextReplacement("Ary Darda")
        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, recorder.dismissals)
    }

    @Test
    fun `a failed save stays open and shows the API's own sentence`() {
        val recorder = Recorder()
        recorder.updateThrows = ApiError.ServerError(code = null, serverMessage = "Couldn't reach Memoria.")
        setUp(caregiver(displayName = "Aryaman Darda"), recorder)

        field(0).performTextReplacement("Ary Darda")
        compose.onNodeWithTag(EDIT_PROFILE_SAVE_TAG).performClick()
        compose.waitForIdle()

        // Verbatim, not reworded: the copy is the product's.
        compose.onNodeWithText("Couldn't reach Memoria.").assertExists()
        assertEquals(0, recorder.dismissals)
    }

    @Test
    fun `Cancel leaves without writing anything`() {
        val recorder = setUp()

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, recorder.dismissals)
        assertTrue(recorder.updates.isEmpty())
        assertTrue(recorder.emailChanges.isEmpty())
    }

    @Test
    fun `Back leaves rather than reaching the Activity`() {
        var fellThrough = 0
        val recorder = Recorder()
        compose.setContent {
            MemoriaTheme {
                Box(Modifier.fillMaxSize()) {
                    BackHandler { fellThrough++ }
                    EditProfileHost(
                        caregiver = caregiver(),
                        onDismiss = { recorder.dismissals++ },
                        updateMe = { _, _ -> caregiver() },
                        requestEmailChange = {},
                        onCaregiverUpdated = {},
                    )
                }
            }
        }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(1, recorder.dismissals)
        assertEquals(0, fellThrough)
    }
}
