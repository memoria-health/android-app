package com.memoria.caregiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra

/**
 * Labeled text field — an eyebrow above a card-tinted box.
 * Spec: DesignSystem/Components/NemaraField.swift.
 *
 * The border state is the subtle part and it is **not** focus-driven: it darkens
 * from `Line` to `Ink` when the field is NON-EMPTY, not when it is focused. The
 * design renders both empty and filled examples and treats filled as the
 * "engaged" tier. Wiring this to focus instead — the obvious Compose instinct —
 * would look right in isolation and wrong on a filled form.
 *
 * Border width is 1.5dp, heavier than the 1dp used on cards and buttons.
 *
 * The caret is Terra explicitly. It would inherit Terra from
 * `colorScheme.primary` anyway (see Theme.kt), but iOS sets it at this call site
 * and stating it here keeps the field correct if the scheme is ever changed.
 */
@Composable
fun NemaraField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isSecure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(Radius.md)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NemaraEyebrow(label)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Card)
                .border(
                    width = 1.5.dp,
                    // Non-empty, not focused. See the class doc.
                    color = if (value.isEmpty()) Line else Ink,
                    shape = shape,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = NemaraType.bodyLarge,
                        color = Ink4,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = NemaraType.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Terra),
                    singleLine = true,
                    visualTransformation =
                        if (isSecure) PasswordVisualTransformation() else VisualTransformation.None,
                    // A secure field OVERRIDES whatever the call site asked for.
                    //
                    // iOS gets this free: SecureField suppresses autocorrect,
                    // capitalization and the suggestion strip, and UIKit never
                    // puts its contents in the keyboard's learned-words store.
                    // Compose's BasicTextField does not — PasswordVisualTransformation
                    // only masks the GLYPHS. Left as ordinary text input, Gboard
                    // capitalizes the first character of a password, offers
                    // corrections for it, and adds it to the personal dictionary,
                    // where it survives the app being uninstalled.
                    //
                    // Enforced here rather than at each call site so a future
                    // password field cannot be added without it. KeyboardType
                    // .Password is what signals "do not learn this" to the IME.
                    keyboardOptions = if (isSecure) {
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        )
                    } else {
                        KeyboardOptions(
                            keyboardType = keyboardType,
                            capitalization = capitalization,
                            // iOS relabels Return to "Done" and dismisses on
                            // submit, rather than floating a toolbar above the
                            // keyboard.
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = keyboardType != KeyboardType.Email,
                        )
                    },
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        // Marks the node as a password to the accessibility
                        // tree. PasswordVisualTransformation masks the glyphs on
                        // screen but leaves the RAW text in semantics, so
                        // without this TalkBack reads the password aloud and any
                        // accessibility service can harvest it. iOS's
                        // SecureField is a password field to UIKit end to end.
                        .then(if (isSecure) Modifier.semantics { password() } else Modifier),
                )
            }

            trailing?.invoke()
        }

        if (hint != null) {
            Text(text = hint, style = NemaraType.caption, color = Ink3)
        }
    }
}

/**
 * A single password rule and whether the current value satisfies it.
 *
 * Pulled out as pure logic so the five rules are Tier-1 testable without
 * composing anything.
 *
 * **The character classes are ASCII, matching iOS exactly.** iOS uses the
 * regexes `[A-Z]`, `[a-z]`, `\d` and `[^A-Za-z0-9]`, all ASCII-only. Kotlin's
 * `Char.isLetterOrDigit()` is Unicode-aware, so writing the symbol rule that way
 * would classify an accented letter as a letter on Android and as a symbol on
 * iOS — the same password satisfying five rules on one platform and four on the
 * other. Written out by range instead.
 */
data class PasswordRule(val label: String, val isSatisfied: Boolean)

private fun Char.isAsciiAlphanumeric(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

fun passwordRules(value: String): List<PasswordRule> = listOf(
    PasswordRule("At least 8 characters", value.length >= 8),
    PasswordRule("One uppercase letter", value.any { it in 'A'..'Z' }),
    PasswordRule("One lowercase letter", value.any { it in 'a'..'z' }),
    PasswordRule("One number", value.any { it in '0'..'9' }),
    PasswordRule("One symbol", value.any { !it.isAsciiAlphanumeric() }),
)
