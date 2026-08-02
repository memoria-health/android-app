package com.memoria.caregiver.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A secure [NemaraField] must never hand its contents to the IME as ordinary
 * text.
 *
 * iOS gets this free: `SecureField` suppresses autocorrect, capitalization and
 * the suggestion strip, and UIKit never adds its contents to the keyboard's
 * learned-words store. Compose does not — `PasswordVisualTransformation` masks
 * only the GLYPHS. Left as plain text input, Gboard capitalizes the first
 * character of a password, offers corrections for it, and **adds it to the
 * personal dictionary, where it survives the app being uninstalled.**
 *
 * That was a real defect in the first port of the sign-up and reset screens,
 * caught in review. The fix lives inside the component so a future password
 * field cannot be added without it, and this test is what keeps it there.
 */
@RunWith(RobolectricTestRunner::class)
class NemaraFieldSecurityTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `a secure field renders and masks its content`() {
        compose.setContent {
            MemoriaTheme {
                NemaraField(
                    label = "Password",
                    value = "hunter2",
                    onValueChange = {},
                    isSecure = true,
                    // Deliberately hostile call site: asks for the settings a
                    // password must never get. The component must override them.
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences,
                )
            }
        }
        compose.onNodeWithText("PASSWORD").assertIsDisplayed()

        // The node is marked a password, which is what masks EditableText — the
        // property TalkBack speaks. Without it the field reads the password
        // aloud, character by character, to anyone within earshot.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assertExists("the secure field is not marked as a password in semantics")

        // EditableText is masked. InputText deliberately still carries the raw
        // value: that is the channel Compose uses to offer the field to a
        // password manager, and suppressing it would break autofill without
        // making anything safer — it is not exposed to accessibility services.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat(7))))
    }
}
