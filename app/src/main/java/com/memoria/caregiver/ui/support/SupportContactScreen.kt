package com.memoria.caregiver.ui.support

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra

/**
 * The "Contact support" sheet from the Settings tab. Spec:
 * `Features/AppSettings/SupportContactView.swift`, PARITY.md §28.
 *
 * A different surface from the floating help button in `SupportButton.kt`, and
 * deliberately so: that one is a chat widget that takes one line and promises a
 * text back, this one is a subject-and-body form for something longer. Both post
 * to `POST /v1/support`, which emails the founders with `replyTo` set to the
 * caregiver's account email.
 *
 * Pure over [SupportContactState] plus callbacks, per `SupportUiState.kt`. The
 * send and the 800ms pause before the sheet closes belong to
 * [SupportContactViewModel]; this screen never learns that the pause exists,
 * only that [SupportContactState.isSending] is still true during it.
 *
 * **Only the message is required.** The subject is labelled "Optional" and an
 * empty one is posted as an empty string, which the backend fills in.
 */
@Composable
fun SupportContactScreen(
    state: SupportContactState,
    onSubjectChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // iOS attaches `.trackScreen("support_contact")` where the sheet is
    // PRESENTED, in AppSettingsView. Logged here for the reason FeedbackScreen
    // gives: a screen event that lives on the caller is one the next caller
    // forgets.
    // Screen tracking is NOT here. `ModalOverlay(screen = "support_contact")` logs it at
    // the point this is presented, the way iOS's `.trackScreen()` does, so a
    // screen cannot ship untracked and no view is counted twice. See
    // ModalOverlay and HARDENING.md H-10.

    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        ContactNavBar(
            canSend = state.canSend,
            isSending = state.isSending,
            onCancel = onCancel,
            onSend = onSend,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // iOS also has `.scrollDismissesKeyboard(.interactively)` and
                // `.dismissKeyboardOnTap()`. Compose has neither, and Android's
                // back gesture already dismisses the keyboard, so the platform
                // supplies the affordance rather than the screen growing chrome
                // iOS does not have. DIFFERENCES.md D2.
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            NemaraField(
                label = "SUBJECT",
                value = state.subject,
                onValueChange = onSubjectChange,
                modifier = Modifier.fillMaxWidth(),
                // The placeholder is the whole "you may leave this blank"
                // affordance — there is no eyebrow marker as on the feedback
                // form. Carried verbatim rather than expanded.
                placeholder = "Optional",
                capitalization = KeyboardCapitalization.Sentences,
            )

            MessageField(
                message = state.message,
                onMessageChange = onMessageChange,
            )

            // Mutually exclusive on iOS: an `if let error … else if let success`.
            // Sending clears both first, so the pair can never both be set — but
            // the exclusivity is written out here anyway, because it is what
            // stops a stale failure sitting under a fresh confirmation.
            val error = state.errorMessage
            val success = state.successMessage
            if (error != null) {
                // The platform's own sentence, rendered verbatim.
                Text(
                    text = error,
                    modifier = Modifier.fillMaxWidth(),
                    style = NemaraType.caption,
                    color = Danger,
                )
            } else if (success != null) {
                // The backend's echoed status, rendered verbatim.
                Text(
                    text = success,
                    modifier = Modifier.fillMaxWidth(),
                    style = NemaraType.caption,
                    color = Ink2,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The message well
// ---------------------------------------------------------------------------

/**
 * A tall `TextEditor` on a card background with a placeholder behind it.
 *
 * **Not [NemaraField]** — that component is single-line. What is borrowed from
 * it is the border rule, which is not focus-driven: it darkens from `Line` to
 * `Ink` when the field is NON-EMPTY. The Swift spells that rule out inline here
 * rather than reaching for the component, and it is the same rule.
 *
 * The Swift pads the editor 10/8 and the placeholder 18/16, because UIKit's
 * `UITextView` adds its own container inset and line-fragment padding on top so
 * the caret lands under the placeholder. `BasicTextField` adds neither, so both
 * take the placeholder's 18/16 — the same call `FeedbackScreen.MultilineField`
 * documents. Porting the 10/8 literally would leave the caret up and to the left
 * of the placeholder it is supposed to replace.
 */
@Composable
private fun MessageField(message: String, onMessageChange: (String) -> Unit) {
    val shape = RoundedCornerShape(Radius.md)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NemaraEyebrow("MESSAGE")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EDITOR_MIN_HEIGHT)
                .clip(shape)
                .background(Card)
                .border(
                    width = EDITOR_BORDER_WIDTH,
                    // Non-empty, not focused. See the doc above.
                    color = if (message.isEmpty()) Line else Ink,
                    shape = shape,
                ),
        ) {
            if (message.isEmpty()) {
                Text(
                    text = "Tell us what's on your mind.",
                    modifier = Modifier.padding(
                        horizontal = EDITOR_H_INSET,
                        vertical = EDITOR_V_INSET,
                    ),
                    style = NemaraType.bodyLarge,
                    color = Ink3,
                )
            }
            BasicTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EDITOR_H_INSET, vertical = EDITOR_V_INSET)
                    .testTag(SUPPORT_CONTACT_MESSAGE_TAG),
                textStyle = NemaraType.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                // A TextEditor is multi-line and its return key inserts a
                // newline; singleLine = false plus the default ImeAction is that
                // behavior. The Swift sets no capitalization here, so this takes
                // UIKit's default for a prose field.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Navigation bar
// ---------------------------------------------------------------------------

/**
 * `.navigationTitle("Contact support")` inline, with "Cancel" leading and either
 * "Send" or a spinner trailing. Drawn by hand the same way `HelpChatSheet` draws
 * its own, because Compose has no navigation bar to configure.
 *
 * The send control is **not** dimmed when it cannot fire — it recolours from
 * `Ink` to `Ink3`, which is what `.foregroundStyle(canSend ? ink : ink3)` does,
 * and the same "inert, not greyed" treatment `NemaraButton` uses everywhere else
 * in this app.
 */
@Composable
private fun ContactNavBar(
    canSend: Boolean,
    isSending: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = NAV_BAR_VERTICAL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Contact support",
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest, as every other hand-drawn bar here does.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        Text(
            text = "Cancel",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .plainClickable(onCancel),
            style = NemaraType.bodyLarge,
            // `.foregroundStyle(NemaraColor.ink2)` — softer than the title.
            color = Ink2,
        )
        if (isSending) {
            // `ProgressView().controlSize(.small)`, at the size SupportButton's
            // composer uses for the same control.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(SENDING_SPINNER_SIZE)
                    .semantics { contentDescription = "Sending" },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SENDING_SPINNER_SIZE),
                    color = Ink3,
                    strokeWidth = SENDING_SPINNER_STROKE,
                )
            }
        } else {
            Text(
                text = "Send",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .testTag(SUPPORT_CONTACT_SEND_TAG)
                    .plainClickable { if (canSend) onSend() },
                style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (canSend) Ink else Ink3,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Test hooks
// ---------------------------------------------------------------------------

/** The message editor; its placeholder is a sibling node, not its text. */
const val SUPPORT_CONTACT_MESSAGE_TAG: String = "support_contact.message"

/**
 * The send control. Tagged as well as labelled because it swaps places with a
 * spinner, and a test that asserts the swap needs to name the thing that left.
 */
const val SUPPORT_CONTACT_SEND_TAG: String = "support_contact.send"

// ---------------------------------------------------------------------------
// Tuning
// ---------------------------------------------------------------------------

/** `.frame(minHeight: 180)` on the message well. */
private val EDITOR_MIN_HEIGHT = 180.dp

/** `lineWidth: 1.5` on the message border, matching NemaraField's. */
private val EDITOR_BORDER_WIDTH = 1.5.dp

/** See [MessageField] — the placeholder's 18/16, matched by the editor. */
private val EDITOR_H_INSET = 18.dp
private val EDITOR_V_INSET = 16.dp

/** The inline nav bar's vertical inset, matching the help chat sheet. */
private val NAV_BAR_VERTICAL_PADDING = 12.dp

/** `ProgressView().controlSize(.small)`. */
private val SENDING_SPINNER_SIZE = 16.dp
private val SENDING_SPINNER_STROKE = 2.dp
