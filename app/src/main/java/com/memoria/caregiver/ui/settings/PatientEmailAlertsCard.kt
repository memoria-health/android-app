package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.NemaraToggle
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.CardAlt
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.components.InfoDot
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra

/**
 * Per-patient email-alerts card. Spec:
 * `Features/Patient/Settings/PatientEmailAlertsCard.swift`, PARITY.md §24.
 *
 * Master toggle, then the recipient list for THIS patient. The owning
 * caregiver's account email always receives this patient's alerts when the
 * toggle is on and is shown read-only at the top; everything below it is
 * editable. **The recipients array is sent in full on every add and remove**,
 * because the server replaces the whole array rather than patching it — which is
 * why both paths go through [onRecipientsChange] with a complete list.
 *
 * A single [EmailAlertsState.busy] flag covers the toggle, the add, the remove
 * and the test send, exactly as the Swift's one `@State var busy` does. That is
 * why adding a recipient puts the spinner in the "Send test email" button: it
 * looks like a bug and it is the existing behavior on iOS.
 *
 * Validation is not done here — see [addRecipient], which returns either the
 * rejection sentence or the next array — so the copy and the rules stay pure and
 * testable without a composition.
 */
@Composable
fun PatientEmailAlertsCard(
    patient: Patient?,
    accountEmail: String,
    state: EmailAlertsState,
    onEnabledChange: (Boolean) -> Unit,
    onDraftChange: (String) -> Unit,
    onRecipientsChange: (List<String>) -> Unit,
    onValidationError: (String) -> Unit,
    onSendTestEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = patient?.emailAlertsEnabled ?: false
    val recipients = patient?.emailAlertRecipients ?: emptyList()

    val submitDraft = {
        when (val result = addRecipient(state.draft, accountEmail, recipients)) {
            is AddRecipientResult.Ignored -> Unit
            is AddRecipientResult.Rejected -> onValidationError(result.message)
            is AddRecipientResult.Accepted -> onRecipientsChange(result.recipients)
        }
    }

    NemaraCard(
        modifier = modifier.padding(horizontal = 16.dp),
        padding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MasterRow(
                enabled = enabled,
                busy = state.busy,
                onEnabledChange = onEnabledChange,
            )

            state.errorText?.let { message ->
                Text(
                    text = message,
                    style = NemaraType.caption,
                    color = Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            RecipientsBlock(
                accountEmail = accountEmail,
                recipients = recipients,
                draft = state.draft,
                busy = state.busy,
                // iOS: `.opacity(enabled ? 1 : 0.55)` on the whole block.
                //
                // Applied to each leaf's colour rather than as a layer alpha,
                // because SwiftUI's `.opacity` on a container composites every
                // leaf separately unless `.compositingGroup()` is used — and
                // these rows stack a border over a background. A Compose
                // `Modifier.alpha` would flatten the subtree first and produce a
                // measurably darker hairline.
                dim = if (enabled) 1f else 0.55f,
                onDraftChange = onDraftChange,
                onSubmitDraft = submitDraft,
                onRemove = { email -> onRecipientsChange(removeRecipient(email, recipients)) },
            )

            // SwiftUI: `Divider().background(NemaraColor.line)`. Compose has no
            // Divider that draws its own separator colour on top of a
            // background, so this is the one-pixel rule in Line.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Line),
            )

            TestRow(
                busy = state.busy,
                testStatus = state.testStatus,
                onSendTestEmail = onSendTestEmail,
            )
        }
    }
}

@Composable
private fun MasterRow(
    enabled: Boolean,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tile = if (enabled) ActionSoft else PaperDim
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            EnvelopeFillGlyph(
                size = 16.dp,
                color = if (enabled) Action else Ink3,
                knockout = tile,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Send email alerts for this patient",
                    style = NemaraType.bodySemibold,
                    color = Ink,
                    modifier = Modifier.weight(1f, fill = false),
                )
                InfoDot(
                    "When on, every detector event emails your account address plus " +
                        "every recipient below.",
                )
            }
        }

        NemaraToggle(
            isOn = enabled,
            onToggle = onEnabledChange,
            track = "email_alerts.toggle",
            enabled = !busy,
        )
    }
}

@Composable
private fun RecipientsBlock(
    accountEmail: String,
    recipients: List<String>,
    draft: String,
    busy: Boolean,
    dim: Float,
    onDraftChange: (String) -> Unit,
    onSubmitDraft: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val rowShape = RoundedCornerShape(Radius.sm)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("RECIPIENTS", color = Ink3.dimmed(dim))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(rowShape)
                .background(PaperDim.dimmed(dim))
                .border(1.dp, Line.dimmed(dim), rowShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = accountEmail,
                style = NemaraType.bodySmall,
                color = Ink.dimmed(dim),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "YOUR ACCOUNT",
                // iOS: NemaraFont.mono(size: 9, weight: .bold) — monoMicro.
                style = NemaraType.monoMicro,
                // Per-call-site kerning, per DECISIONS.md D-008. iOS: .tracking(0.6)
                letterSpacing = 0.6.sp,
                color = Ink3.dimmed(dim),
            )
        }

        for (recipient in recipients) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(rowShape)
                    .background(Card.dimmed(dim))
                    .border(1.dp, Line.dimmed(dim), rowShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recipient,
                    style = NemaraType.bodySmall,
                    color = Ink.dimmed(dim),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Remove",
                    style = NemaraType.bodySmallStrong,
                    color = Terra.dimmed(dim),
                    modifier = Modifier.plainClickable(enabled = !busy) { onRemove(recipient) },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fieldBorder = if (draft.isEmpty()) Line else Ink
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(rowShape)
                    .background(Card.dimmed(dim))
                    .border(1.dp, fieldBorder.dimmed(dim), rowShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "add another email",
                        // The Swift uses a bare `TextField("add another email",
                        // …)`, so the placeholder is UIKit's own
                        // `placeholderText` grey. Ink4 is what NemaraField uses
                        // for the same job, so the app has one placeholder
                        // colour instead of two.
                        style = NemaraType.bodyLarge,
                        color = Ink4.dimmed(dim),
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    // iOS: NemaraFont.bodyLarge.weight(.regular) — 16 regular.
                    textStyle = NemaraType.bodyLarge.copy(color = Ink.dimmed(dim)),
                    cursorBrush = SolidColor(Terra.dimmed(dim)),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        capitalization = KeyboardCapitalization.None,
                        // iOS: .submitLabel(.done) with .onSubmit { add() }.
                        imeAction = ImeAction.Done,
                        // iOS: .autocorrectionDisabled(true).
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmitDraft() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                text = "Add",
                style = NemaraType.bodySmallStrong,
                color = Ink.dimmed(dim),
                modifier = Modifier
                    .clip(rowShape)
                    .background(CardAlt.dimmed(dim))
                    .border(1.dp, Line.dimmed(dim), rowShape)
                    .plainClickable(enabled = !busy && draft.trim().isNotEmpty()) {
                        onSubmitDraft()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TestRow(
    busy: Boolean,
    testStatus: String?,
    onSendTestEmail: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .background(Action)
                .plainClickable(enabled = !busy, onClick = onSendTestEmail)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                // iOS: ProgressView().controlSize(.small), which inherits the
                // app-wide .tint(terra) — so a terra spinner on the near-black
                // button. Compose's default is colorScheme.primary, which
                // Theme.kt also points at Terra; stated explicitly so the button
                // stays right if the scheme is ever changed.
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Terra,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = "Send test email",
                    style = NemaraType.bodySemibold,
                    color = ActionInk,
                )
            }
        }

        testStatus?.let { status ->
            Text(
                text = status,
                style = NemaraType.bodySmall,
                color = Ink2,
                // iOS: .lineLimit(2), with a trailing Spacer(minLength: 0)
                // giving the status the rest of the row. `fill = false` is that
                // Spacer: take what is needed, up to the remaining width.
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Multiply a token's alpha, reproducing SwiftUI's per-leaf `.opacity`.
 *
 * Every palette colour is fully opaque, so this is just `copy(alpha = factor)`
 * — written as a multiply so it stays correct if a translucent token is ever
 * passed in.
 */
private fun Color.dimmed(factor: Float): Color =
    if (factor >= 1f) this else copy(alpha = alpha * factor)
