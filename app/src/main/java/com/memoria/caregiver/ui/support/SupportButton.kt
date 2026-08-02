package com.memoria.caregiver.ui.support

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra
import kotlinx.coroutines.launch

/**
 * The perpetual floating help button and the chat sheet it opens.
 * Spec: `Features/Support/SupportButton.swift`, PARITY.md §28.
 *
 * Tapping opens a chat-styled sheet that posts to `/v1/support`, which emails
 * the founders the caregiver's name, phone and message so they can reply from
 * Messages. That is why sign-up makes a phone number mandatory.
 *
 * **Applied to more than one surface on purpose.** iOS attaches
 * `supportButtonOverlay()` to `RootView` AND to `PatientShell`, because a
 * `.fullScreenCover` covers the *presenter's* overlay; the expanded live view
 * deliberately omits it. Whatever hosts these composables must reproduce that:
 * the button belongs on the main surface and on the patient shell, and not on
 * the expanded live view.
 */

// ---------------------------------------------------------------------------
// The button
// ---------------------------------------------------------------------------

/**
 * The 56dp terra disc with a speech bubble in it.
 *
 * Not a [com.memoria.caregiver.ui.components.NemaraButton]: the Swift is a bare
 * `Button` with `.buttonStyle(.plain)`, so no analytics event fires on the tap.
 * Adding one here would put an event in the funnel that iOS does not have.
 */
@Composable
fun SupportButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(BUTTON_SIZE)
            // `.shadow(color: ink.opacity(0.18), radius: 8, y: 3)`.
            //
            // Compose's shadow takes an elevation and derives blur, offset and
            // alpha from it — there is no radius or y to set — so the elevation
            // is chosen to land near an 8pt blur and the tint colours carry the
            // warm ink. This is an approximation and wants the screenshot pass.
            .shadow(BUTTON_SHADOW_ELEVATION, CircleShape, ambientColor = Ink, spotColor = Ink)
            .clip(CircleShape)
            .background(Terra)
            .semantics { contentDescription = "Get help" }
            .testTag(SUPPORT_BUTTON_TAG)
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        MessageFillGlyph(size = BUTTON_GLYPH_SIZE, color = ActionInk)
    }
}

/**
 * Pins [SupportButton] to the bottom-trailing corner, above the tab bar, and
 * presents the chat sheet while [HelpChatState.isPresented].
 *
 * The Swift's `supportButtonOverlay()` is a `View` extension applying a
 * bottom-trailing `overlay`; a `BoxScope` receiver is the Compose shape of the
 * same thing, and it keeps the button a footprint-only overlay rather than a
 * gesture layer over the whole surface — which on iOS swallowed tab taps.
 *
 * @param onOpen the button was tapped. iOS keeps `showSheet` in `@State` here;
 *   it is hoisted so the host owns the send, which is I/O.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.SupportButtonOverlay(
    state: HelpChatState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    SupportButton(
        onClick = onOpen,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = OVERLAY_TRAILING_PADDING, bottom = OVERLAY_BOTTOM_PADDING),
    )

    if (state.isPresented) {
        val sheetState = rememberModalBottomSheetState(
            // An iOS `.sheet` over a NavigationStack presents at near-full
            // height immediately. Same reasoning as CountryPicker's sheet.
            skipPartiallyExpanded = true,
        )
        val scope = rememberCoroutineScope()

        fun close() {
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                if (!sheetState.isVisible) onDismiss()
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            // iOS page sheets show no grabber; the leading button is the
            // affordance, and swipe-down still dismisses.
            dragHandle = null,
            // The Swift sets `.background(NemaraColor.paper)` on the sheet's own
            // stack, unlike the country picker which keeps system chrome.
            containerColor = Paper,
        ) {
            HelpChatSheet(
                state = state,
                onMessageChange = onMessageChange,
                onSend = onSend,
                onDismiss = { close() },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

/**
 * Chat-widget styled help sheet: two greeting bubbles up top and a composer
 * pinned to the bottom, so it reads as a live chat rather than a "leave us a
 * message" form.
 *
 * It takes exactly ONE message. Once [HelpChatState.didSend] is true the
 * composer locks and the leading toolbar button relabels from "Cancel" to
 * "Done" — the reply arrives by text, not in here.
 *
 * Public rather than private (the Swift's `HelpChatSheet` is file-private) so
 * it can be tested without driving a modal sheet's animation.
 */
@Composable
fun HelpChatSheet(
    state: HelpChatState,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        HelpChatNavBar(didSend = state.didSend, onDismiss = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(BUBBLE_SPACING),
            horizontalAlignment = Alignment.Start,
        ) {
            Bubble("What do you need help with?", incoming = true)
            Bubble("Send us a note and we'll text you back instantly.", incoming = true)

            if (state.didSend) {
                if (state.sentText.isNotEmpty()) Bubble(state.sentText, incoming = false)
                Bubble("Got it — we'll text you back shortly.", incoming = true)
            }

            // The platform's own sentence, rendered verbatim.
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 4.dp),
                    style = NemaraType.caption,
                    color = Danger,
                )
            }
        }

        Composer(state = state, onMessageChange = onMessageChange, onSend = onSend)
    }
}

/**
 * `.navigationTitle("Memoria support")` inline, with the leading button
 * relabelling once the message has landed.
 */
@Composable
private fun HelpChatNavBar(didSend: Boolean, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_PADDING, vertical = NAV_BAR_VERTICAL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Memoria support",
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest, as CountryPicker's sheet title does.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        Text(
            text = if (didSend) "Done" else "Cancel",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .plainClickable(onDismiss),
            style = NemaraType.bodyLarge,
            // `.foregroundStyle(NemaraColor.ink2)` — softer than the title.
            color = Ink2,
        )
    }
}

/**
 * One chat bubble. Incoming (from Memoria) is a card-coloured bubble on the
 * left; outgoing (the caregiver's own message, echoed after sending) is a terra
 * bubble on the right.
 *
 * The Swift reserves the opposite side with `Spacer(minLength: 44)` and caps
 * the bubble at 280. A 44dp padding on the far side plus a 280dp max width is
 * the same constraint expressed the other way round, and it does not need a
 * greedy spacer that would fight the Row's own sizing.
 */
@Composable
private fun Bubble(text: String, incoming: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (incoming) 0.dp else BUBBLE_GUTTER,
                end = if (incoming) BUBBLE_GUTTER else 0.dp,
            ),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .clip(RoundedCornerShape(BUBBLE_RADIUS))
                .background(if (incoming) Card else Terra)
                .padding(horizontal = BUBBLE_H_PADDING, vertical = BUBBLE_V_PADDING),
            style = NemaraType.bodyLarge,
            color = if (incoming) Ink else ActionInk,
        )
    }
}

/** The iMessage-style composer pinned to the bottom of the sheet. */
@Composable
private fun Composer(
    state: HelpChatState,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            // `.overlay(NemaraColor.line.frame(height: 1), alignment: .top)` —
            // a hairline rule separating the composer from the transcript.
            .drawBehind {
                drawRect(color = Line, size = Size(size.width, 1.dp.toPx()))
            }
            .padding(horizontal = COMPOSER_H_PADDING, vertical = COMPOSER_V_PADDING),
        horizontalArrangement = Arrangement.spacedBy(COMPOSER_SPACING),
        verticalAlignment = Alignment.Bottom,
    ) {
        val capsule = RoundedCornerShape(Radius.pill)
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(capsule)
                .background(Card)
                .border(1.dp, Line, capsule)
                .padding(horizontal = FIELD_H_PADDING, vertical = FIELD_V_PADDING),
        ) {
            if (state.message.isEmpty()) {
                Text(text = "Message…", style = NemaraType.bodyLarge, color = Ink3)
            }
            BasicTextField(
                value = state.message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth().testTag(SUPPORT_COMPOSER_TAG),
                // `.disabled(didSend)` — one message per sheet.
                enabled = !state.didSend,
                textStyle = NemaraType.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                // `.lineLimit(1...5)` — grows to five lines, then scrolls.
                maxLines = COMPOSER_MAX_LINES,
            )
        }

        if (state.isSending) {
            Box(
                modifier = Modifier.size(SEND_CONTROL_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SENDING_SPINNER_SIZE),
                    color = Ink3,
                    strokeWidth = SENDING_SPINNER_STROKE,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .semantics { contentDescription = "Send" }
                    .testTag(SUPPORT_SEND_TAG)
                    .plainClickable { if (state.canSend) onSend() },
            ) {
                ArrowUpCircleFillGlyph(
                    size = SEND_GLYPH_SIZE,
                    tint = if (state.canSend) Terra else Ink3,
                    knockout = Paper,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Glyphs
// ---------------------------------------------------------------------------
//
// "message.fill" and "arrow.up.circle.fill". Drawn rather than imported, for the
// reason FeedbackScreen.kt and CountryPicker.kt give. As there, a monochrome
// ".fill" circle symbol is a tinted disc with the inner glyph punched out to
// whatever sits behind, which is why the arrow takes a knockout colour.

/** `message.fill` — a rounded speech bubble with a tail at the bottom-leading corner. */
@Composable
private fun MessageFillGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, w * 0.10f),
            size = Size(w, w * 0.68f),
            cornerRadius = CornerRadius(w * 0.26f, w * 0.26f),
        )
        // The tail, a small wedge dropping from the lower-left of the bubble.
        drawPath(
            path = Path().apply {
                moveTo(w * 0.14f, w * 0.62f)
                lineTo(w * 0.40f, w * 0.72f)
                lineTo(w * 0.06f, w * 0.94f)
                close()
            },
            color = color,
        )
    }
}

/** `arrow.up.circle.fill` — [tint] disc with the arrow knocked out in [knockout]. */
@Composable
private fun ArrowUpCircleFillGlyph(size: Dp, tint: Color, knockout: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(color = tint, radius = w / 2f, center = Offset(w / 2f, w / 2f))
        val stroke = w * 0.10f
        drawLine(
            color = knockout,
            start = Offset(w / 2f, w * 0.72f),
            end = Offset(w / 2f, w * 0.30f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawPolyline(
            points = listOf(
                Offset(w * 0.31f, w * 0.48f),
                Offset(w / 2f, w * 0.29f),
                Offset(w * 0.69f, w * 0.48f),
            ),
            color = knockout,
            strokeWidth = stroke,
        )
    }
}

/** Round-capped segments through [points]. See CountryPicker.kt for the rationale. */
private fun DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    for (i in 0 until points.size - 1) {
        drawLine(
            color = color,
            start = points[i],
            end = points[i + 1],
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

// ---------------------------------------------------------------------------
// Test hooks
// ---------------------------------------------------------------------------

/** The floating button carries only an accessibility label on iOS. */
const val SUPPORT_BUTTON_TAG: String = "support.button"

/** The composer field; its placeholder is a sibling node, not its text. */
const val SUPPORT_COMPOSER_TAG: String = "support.composer"

/** The send control, which is a glyph with no text. */
const val SUPPORT_SEND_TAG: String = "support.send"

// ---------------------------------------------------------------------------
// Tuning
// ---------------------------------------------------------------------------

/** `.frame(width: 56, height: 56)` on the floating button. */
private val BUTTON_SIZE = 56.dp

/** `Image(systemName: "message.fill").font(.system(size: 21, weight: .semibold))`. */
private val BUTTON_GLYPH_SIZE = 21.dp

/** See [SupportButton] — an approximation of `radius: 8, y: 3`. */
private val BUTTON_SHADOW_ELEVATION = 6.dp

/** `.padding(.trailing, 18)` / `.padding(.bottom, 90)` — clears the tab bar. */
private val OVERLAY_TRAILING_PADDING = 18.dp
private val OVERLAY_BOTTOM_PADDING = 90.dp

/** `.padding(16)` on the transcript, and the nav bar's horizontal inset. */
private val SHEET_PADDING = 16.dp

/** The inline nav bar's vertical inset, matching CountryPicker's sheet. */
private val NAV_BAR_VERTICAL_PADDING = 12.dp

/** `VStack(alignment: .leading, spacing: 10)` in the transcript. */
private val BUBBLE_SPACING = 10.dp

/** `.frame(maxWidth: 280)` on a bubble. */
private val BUBBLE_MAX_WIDTH = 280.dp

/** `Spacer(minLength: 44)` on the far side of a bubble. */
private val BUBBLE_GUTTER = 44.dp

/** `RoundedRectangle(cornerRadius: 18)` — larger than any radius token. */
private val BUBBLE_RADIUS = 18.dp

/** `.padding(.horizontal, 14)` / `.padding(.vertical, 10)` inside a bubble. */
private val BUBBLE_H_PADDING = 14.dp
private val BUBBLE_V_PADDING = 10.dp

/** `.padding(.horizontal, 14)` / `.padding(.vertical, 10)` on the composer bar. */
private val COMPOSER_H_PADDING = 14.dp
private val COMPOSER_V_PADDING = 10.dp

/** `HStack(alignment: .bottom, spacing: 10)`. */
private val COMPOSER_SPACING = 10.dp

/** `.padding(.horizontal, 16)` / `.padding(.vertical, 10)` inside the capsule field. */
private val FIELD_H_PADDING = 16.dp
private val FIELD_V_PADDING = 10.dp

/** `.lineLimit(1...5)`. */
private const val COMPOSER_MAX_LINES = 5

/** `Image(systemName: "arrow.up.circle.fill").font(.system(size: 32))`. */
private val SEND_GLYPH_SIZE = 32.dp

/** `ProgressView().controlSize(.small).frame(width: 34, height: 34)`. */
private val SEND_CONTROL_SIZE = 34.dp
private val SENDING_SPINNER_SIZE = 16.dp
private val SENDING_SPINNER_STROKE = 2.dp
