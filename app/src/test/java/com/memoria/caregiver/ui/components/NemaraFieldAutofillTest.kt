package com.memoria.caregiver.ui.components

import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.KeyboardType
import com.memoria.caregiver.ui.theme.MemoriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What [NemaraField] offers a password manager. Spec: FINDINGS.md F-31.
 *
 * **The failure this guards is a caregiver who cannot sign in.** Before this,
 * no field in the app declared a content type, so Android autofill offered
 * nothing on any screen — a caregiver using a generated password had to read it
 * off another device and type it in. iOS never had the problem: `SecureField`
 * is a password field to UIKit and `.keyboardType(.emailAddress)` is an address
 * field, so the Swift declares nothing and still gets filled.
 *
 * These assert the declaration rather than the fill, because the fill belongs
 * to the platform's autofill service. The declaration is the part this app
 * owns, and it is the part that was missing.
 */
@RunWith(RobolectricTestRunner::class)
class NemaraFieldAutofillTest {

    @get:Rule val compose = createComposeRule()

    /**
     * The editor, not the placeholder. Both carry the word "probe" while the
     * field is empty, and only the editor accepts text — so that is what it is
     * found by.
     */
    private fun field() = compose.onNode(hasSetTextAction())

    private fun declaredContentType(): ContentType? =
        field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentType)

    /**
     * The Android autofill hint strings the declaration resolves to — the exact
     * thing handed to a password manager.
     *
     * Read reflectively because `ContentType.plus` returns a fresh instance of
     * an internal class with no `equals`, so `EmailAddress + Username` never
     * compares equal to another `EmailAddress + Username`. Asserting on the
     * hints is also the more honest test: it names what a manager actually sees
     * rather than which object the framework happened to build.
     */
    private fun declaredHints(): Set<*>? {
        val value = declaredContentType() ?: return null
        val getter = value.javaClass.getMethod("getAndroidAutofillHints")
        getter.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return getter.invoke(value) as Set<*>
    }

    private fun render(
        isSecure: Boolean = false,
        keyboardType: KeyboardType = KeyboardType.Text,
        contentType: ContentType? = null,
    ) {
        compose.setContent {
            MemoriaTheme {
                NemaraField(
                    label = "Field",
                    value = "",
                    onValueChange = {},
                    placeholder = "probe",
                    isSecure = isSecure,
                    keyboardType = keyboardType,
                    contentType = contentType,
                )
            }
        }
    }

    @Test
    fun `an email field is offered as both an address and a username`() {
        render(keyboardType = KeyboardType.Email)
        assertEquals(setOf("emailAddress", "username"), declaredHints())
    }

    /**
     * This one is not [NemaraField]'s own doing and the test says so on purpose:
     * Compose derives the password hint from `KeyboardType.Password`, which the
     * secure branch sets for the F-18 reasons (keep the IME from learning the
     * password). So the assertion is a guard on F-18 — remove that keyboard
     * type and this field silently stops being fillable as well as starting to
     * end up in the personal dictionary.
     */
    @Test
    fun `a secure field is offered as a password`() {
        render(isSecure = true)
        assertEquals(setOf("password"), declaredHints())
    }

    /**
     * Sign-up and reset pass this. Without it a manager offers back the password
     * the caregiver already has instead of offering to make a new one.
     */
    @Test
    fun `an explicit content type wins over the derived one`() {
        render(isSecure = true, contentType = ContentType.NewPassword)
        assertEquals(setOf("newPassword"), declaredHints())
    }

    /**
     * A name or a room is not a credential. Declaring one would drag unrelated
     * fields into the manager's save prompt.
     */
    @Test
    fun `an ordinary text field declares nothing`() {
        render()
        assertNull(declaredContentType())
    }

    /**
     * The reveal toggle on the login screen flips `isSecure`, so the derived
     * default would drop the declaration the moment a caregiver taps "show".
     * LoginScreen states it instead; this pins that stating it works when the
     * field is not masked.
     */
    @Test
    fun `a revealed password is still offered as a password`() {
        render(isSecure = false, contentType = ContentType.Password)
        assertEquals(setOf("password"), declaredHints())
    }

    /** The F-18 password semantics must survive the addition above them. */
    @Test
    fun `a secure field is still marked as a password to the accessibility tree`() {
        render(isSecure = true)
        field().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }
}
