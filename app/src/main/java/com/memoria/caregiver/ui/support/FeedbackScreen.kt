package com.memoria.caregiver.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraCardTone
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.NemaraMark
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * Combined feedback screen — founders card, three-part form, screenshot
 * attachments. Spec: `Features/Feedback/FeedbackView.swift`, PARITY.md §28.
 *
 * Pushed from Patient settings' "Feedback & support" row. Self-contained, no
 * nested navigation.
 *
 * Pure over [FeedbackState] plus callbacks, per `ui/auth/AuthUiState.kt`. Three
 * things the Swift does inline sit outside the screen here: posting the
 * feedback, decoding picked images into [FeedbackAttachment]s, and launching
 * the system photo picker. The first two are I/O. The third is an activity
 * result, which would make every test of this screen depend on an
 * `ActivityResultRegistry`; `rememberFeedbackScreenshotPicker` in
 * `FeedbackAttachments.kt` holds the picker configuration so the host supplies
 * [onAddScreenshots] without re-deriving it.
 *
 * **"What's not working" is the only required field.** The rating and "what do
 * you like" are optional and say so on screen.
 */
@Composable
fun FeedbackScreen(
    state: FeedbackState,
    modifier: Modifier = Modifier,
    onRatingChange: (Int) -> Unit,
    onWhatsLovedChange: (String) -> Unit,
    onWhatsNotWorkingChange: (String) -> Unit,
    onAddScreenshots: () -> Unit,
    onRemoveAttachment: (FeedbackAttachment) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    // iOS attaches `.trackScreen("feedback")` where the view is PRESENTED
    // (PatientShell and PatientSettingsView), not on FeedbackView itself. It is
    // logged here for the same reason ForgotPasswordScreen logs its own: a
    // screen event that lives on the caller is one the next caller forgets.
    val analytics = LocalAnalytics.current
    LaunchedEffect(Unit) { analytics.logScreen("feedback") }

    // `.sensoryFeedback(.success, trigger: submitted)` — a physical "it went
    // through" beyond the visual confirmation. SwiftUI fires on a CHANGE of the
    // trigger, never on its initial value, so the previous value is tracked
    // rather than keying a LaunchedEffect on `submitted` alone.
    val haptics = LocalHapticFeedback.current
    var wasSubmitted by remember { mutableStateOf(state.submitted) }
    LaunchedEffect(state.submitted) {
        if (state.submitted && !wasSubmitted) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        wasSubmitted = state.submitted
    }

    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        FeedbackNavBar(onDismiss = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(top = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoundersCard()

            FeedbackForm(
                state = state,
                onRatingChange = onRatingChange,
                onWhatsLovedChange = onWhatsLovedChange,
                onWhatsNotWorkingChange = onWhatsNotWorkingChange,
                onAddScreenshots = onAddScreenshots,
                onRemoveAttachment = onRemoveAttachment,
                onSubmit = onSubmit,
                onDismiss = onDismiss,
            )

            Text(
                text = "THANK YOU · MEMORIA v0.1",
                modifier = Modifier.padding(top = Spacing.xxs, bottom = Spacing.md),
                style = NemaraType.eyebrow,
                // NOT NemaraEyebrow: that component hardcodes 0.6 tracking and
                // uppercases its input, which would turn "v0.1" into "V0.1".
                // The Swift uses a plain Text at 0.5.
                letterSpacing = 0.5.sp,
                color = Ink3,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Founders card
// ---------------------------------------------------------------------------

@Composable
private fun FoundersCard() {
    NemaraCard(
        // SwiftUI's Spacer() in the header row makes the card take the full
        // proposed width; a Compose Column wraps, so it is asked for.
        modifier = Modifier.fillMaxWidth(),
        tone = NemaraCardTone.Terra,
        padding = Spacing.md,
        // `borderColor: .clear` — the terra tint carries the card on its own.
        borderColor = Color.Transparent,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FOUNDERS_STACK_SPACING)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NemaraMark(size = FOUNDERS_MARK_SIZE)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    NemaraEyebrow("FROM THE FOUNDERS", color = Terra)
                    Text(
                        text = "We read every message.",
                        style = NemaraType.title3,
                        // Per-call-site kerning, DECISIONS.md D-008. iOS: .kerning(-0.3)
                        letterSpacing = (-0.3).sp,
                        color = Ink,
                    )
                }
            }
            Text(
                text = "Tell us how we can improve. We respond to all inquiries " +
                    "within 2 business days. Feel free to also text us at 716 602 1101.",
                style = NemaraType.bodySmall,
                lineHeight = BODY_SMALL_LINE_HEIGHT_PLUS_5,
                color = Ink2,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The form
// ---------------------------------------------------------------------------

@Composable
private fun FeedbackForm(
    state: FeedbackState,
    onRatingChange: (Int) -> Unit,
    onWhatsLovedChange: (String) -> Unit,
    onWhatsNotWorkingChange: (String) -> Unit,
    onAddScreenshots: () -> Unit,
    onRemoveAttachment: (FeedbackAttachment) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        horizontalAlignment = Alignment.Start,
    ) {
        NemaraEyebrow("FEEDBACK")

        NemaraCard(modifier = Modifier.fillMaxWidth(), padding = CARD_PADDING) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {

                MultilineField(
                    value = state.whatsNotWorking,
                    onValueChange = onWhatsNotWorkingChange,
                    placeholder = "What should we fix or improve…",
                    fieldTag = FEEDBACK_NOT_WORKING_TAG,
                ) {
                    // The one field with no "optional" marker — it is required.
                    NemaraEyebrow("WHAT'S NOT WORKING / COULD BE IMPROVED?")
                }

                MultilineField(
                    value = state.whatsLoved,
                    onValueChange = onWhatsLovedChange,
                    placeholder = "What's landing well for you…",
                    fieldTag = FEEDBACK_LOVED_TAG,
                ) {
                    OptionalEyebrow("WHAT DO YOU LIKE ABOUT MEMORIA?")
                }

                RatingScale(rating = state.rating, onRatingChange = onRatingChange)

                if (!state.submitted) {
                    ScreenshotsField(
                        state = state,
                        onAddScreenshots = onAddScreenshots,
                        onRemoveAttachment = onRemoveAttachment,
                    )
                }

                // `withAnimation(.spring(response: 0.4, dampingFraction: 0.8))`
                // wraps `submitted = true`, and the block carries
                // `.transition(.opacity.combined(with: .scale(scale: 0.96)))`.
                //
                // SwiftUI's response/dampingFraction map onto Compose's
                // stiffness/dampingRatio directly: stiffness = (2π / response)²,
                // so 0.4s response is 246.7.
                AnimatedVisibility(
                    visible = state.submitted,
                    enter = fadeIn(SUBMITTED_SPRING) +
                        scaleIn(SUBMITTED_SPRING, initialScale = SUBMITTED_SCALE),
                    exit = fadeOut(SUBMITTED_SPRING) +
                        scaleOut(SUBMITTED_SPRING, targetScale = SUBMITTED_SCALE),
                ) {
                    SubmittedConfirmation(onDismiss = onDismiss)
                }

                if (!state.submitted) {
                    // Rendered verbatim — this is `feedbackSubmitErrorMessage`,
                    // which already carries the platform's own sentence.
                    state.submitError?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth(),
                            style = NemaraType.caption,
                            color = Terra,
                        )
                    }

                    val enabled = !state.isSubmitting && state.canSubmit
                    NemaraButton(
                        title = if (state.isSubmitting) "Sending…" else "Send feedback",
                        // NemaraButton never dims itself when disabled, matching
                        // NemaraButton.swift. This 0.5 is the call site's own
                        // `.opacity(...)`, applied alongside `.disabled(...)`.
                        modifier = Modifier
                            .padding(top = SEND_BUTTON_TOP_PADDING)
                            .alpha(if (enabled) 1f else DISABLED_OPACITY),
                        variant = NemaraButtonVariant.Primary,
                        size = NemaraButtonSize.Lg,
                        block = true,
                        icon = PaperplaneFill,
                        enabled = enabled,
                        onClick = onSubmit,
                    )
                }
            }
        }
    }
}

/** An eyebrow followed by the italic "optional" marker. Two of the three fields carry it. */
@Composable
private fun OptionalEyebrow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NemaraEyebrow(text)
        Text(
            text = "optional",
            // Inter ships no italic cut here, so Compose synthesises the slant
            // the same way UIKit would for a family without one.
            style = NemaraType.caption.copy(fontStyle = FontStyle.Italic),
            color = Ink3,
        )
    }
}

// ---------------------------------------------------------------------------
// Text fields
// ---------------------------------------------------------------------------

/**
 * A `TextEditor` in a paper-dim well with a placeholder behind it.
 *
 * **Not `NemaraField`** — that component is a single-line field on a `Card`
 * background with its own eyebrow. This is the Swift's hand-built
 * `ZStack(alignment: .topLeading) { placeholder; TextEditor }`.
 *
 * The Swift pads the editor 8 horizontal / 4 vertical and the placeholder 12 on
 * all sides, with a comment explaining that `TextEditor` adds its own text
 * container inset on top — the goal being a caret that lines up with the
 * placeholder. `BasicTextField` has no such inset, so the arithmetic collapses
 * and both take 12. Porting the 8/4 literally would misalign the two by the
 * exact amount the Swift comment exists to fix.
 */
@Composable
private fun MultilineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fieldTag: String,
    label: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EDITOR_MIN_HEIGHT)
                .clip(RoundedCornerShape(Radius.md))
                .background(PaperDim),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    modifier = Modifier.padding(EDITOR_TEXT_INSET),
                    style = NemaraType.bodySmall,
                    color = Ink3,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(EDITOR_TEXT_INSET)
                    .testTag(fieldTag),
                textStyle = NemaraType.bodySmall.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                // A TextEditor is multi-line and its return key inserts a
                // newline; singleLine = false plus the default ImeAction is
                // that behavior. Capitalization follows UIKit's default for a
                // prose field.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rating
// ---------------------------------------------------------------------------

/**
 * The five-step "how is Memoria going?" scale, 1-5, optional.
 *
 * The colour ramp runs rust to olive and is written out literally, exactly as
 * the Swift does. Note that step 1 happens to equal the `Terra` token — the
 * Swift still spells it out, because these five are one ramp and swapping one
 * for a semantic token would invite the next reader to "consistently" replace
 * the other four with tokens that do not exist.
 */
@Composable
private fun RatingScale(rating: Int, onRatingChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OptionalEyebrow("HOW IS MEMORIA GOING?")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(PaperDim)
                .padding(Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            for (n in 1..5) {
                val selected = n <= rating
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(RATING_CELL_HEIGHT)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(if (selected) RATING_PALETTE[n - 1] else Color.Transparent)
                        .plainClickable { onRatingChange(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$n",
                        style = NemaraType.bodySmallStrong,
                        color = if (selected) TerraInk else Ink3,
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "NOT GREAT", style = NemaraType.monoSmall, color = Ink3)
            Spacer(Modifier.weight(1f))
            Text(text = "LOVE IT", style = NemaraType.monoSmall, color = Ink3)
        }
    }
}

// ---------------------------------------------------------------------------
// Screenshots
// ---------------------------------------------------------------------------

@Composable
private fun ScreenshotsField(
    state: FeedbackState,
    onAddScreenshots: () -> Unit,
    onRemoveAttachment: (FeedbackAttachment) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraEyebrow("SCREENSHOTS")
            Text(
                text = "optional",
                style = NemaraType.caption.copy(fontStyle = FontStyle.Italic),
                color = Ink3,
            )
            Spacer(Modifier.weight(1f))
            if (state.isLoadingAttachments) {
                Text(text = "Adding…", style = NemaraType.monoSmall, color = Ink3)
            } else if (state.attachments.isNotEmpty()) {
                Text(
                    text = "${state.attachments.size}/$FEEDBACK_MAX_ATTACHMENTS",
                    style = NemaraType.monoSmall,
                    color = Ink3,
                )
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (state.isLoadingAttachments) {
                // One spinner tile per just-picked item, so the pick registers
                // immediately instead of leaving a dead pause while the images
                // are decoded and re-encoded.
                repeat(state.placeholderCount) { LoadingTile() }
            } else {
                state.attachments.forEach { attachment ->
                    Thumbnail(attachment = attachment, onRemove = onRemoveAttachment)
                }
                if (state.attachments.size < FEEDBACK_MAX_ATTACHMENTS) {
                    AddScreenshotTile(onClick = onAddScreenshots)
                }
            }
        }
    }
}

@Composable
private fun LoadingTile() {
    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(RoundedCornerShape(Radius.sm))
            .background(PaperDim),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(SPINNER_SIZE),
            color = Ink3,
            strokeWidth = SPINNER_STROKE,
        )
    }
}

@Composable
private fun AddScreenshotTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.sm)
    Column(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(shape)
            .background(PaperDim)
            .dashedBorder(color = Line, cornerRadius = Radius.sm)
            .testTag(FEEDBACK_ADD_SCREENSHOT_TAG)
            .plainClickable(onClick),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhotoBadgePlusGlyph(size = ADD_GLYPH_SIZE, color = Ink3)
        Text(text = "Add", style = NemaraType.caption, color = Ink3)
    }
}

@Composable
private fun Thumbnail(
    attachment: FeedbackAttachment,
    onRemove: (FeedbackAttachment) -> Unit,
) {
    Box {
        Image(
            bitmap = attachment.image,
            contentDescription = null,
            modifier = Modifier
                .size(TILE_SIZE)
                .clip(RoundedCornerShape(Radius.sm)),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(REMOVE_BADGE_PADDING)
                .semantics { contentDescription = "Remove screenshot" }
                .plainClickable { onRemove(attachment) },
        ) {
            XmarkCircleFillGlyph(size = REMOVE_BADGE_SIZE)
        }
    }
}

// ---------------------------------------------------------------------------
// Sent confirmation
// ---------------------------------------------------------------------------

@Composable
private fun SubmittedConfirmation(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CONFIRMATION_VERTICAL_PADDING),
        verticalArrangement = Arrangement.spacedBy(FOUNDERS_STACK_SPACING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(CONFIRMATION_DISC_SIZE)
                .clip(CircleShape)
                .background(TerraSoft),
            contentAlignment = Alignment.Center,
        ) {
            CheckmarkCircleFillGlyph(
                size = CONFIRMATION_GLYPH_SIZE,
                tint = Terra,
                knockout = TerraSoft,
            )
        }
        Text(text = "Feedback sent", style = NemaraType.title3, color = Ink)
        Text(
            text = "Thank you — we read every message and respond within 2 business days.",
            style = NemaraType.bodySmall,
            lineHeight = BODY_SMALL_LINE_HEIGHT_PLUS_3,
            color = Ink2,
            textAlign = TextAlign.Center,
        )
        NemaraButton(
            title = "Done",
            modifier = Modifier.padding(top = 2.dp),
            variant = NemaraButtonVariant.Secondary,
            size = NemaraButtonSize.Md,
            onClick = onDismiss,
        )
    }
}

// ---------------------------------------------------------------------------
// Navigation bar
// ---------------------------------------------------------------------------

/**
 * `.navigationTitle("Feedback")` inline, back button hidden, one leading
 * chevron. Compose has no navigation bar to configure, so the bar height and
 * the leading inset are judgement rather than ported values — the same category
 * as the country-picker chrome in DIFFERENCES.md D9.
 *
 * The Swift also pins the bar to `paper` explicitly, because iOS otherwise
 * swaps to an opaque system material once content scrolls under it. Compose has
 * no such swap; painting the whole screen `paper` is the same result.
 */
@Composable
private fun FeedbackNavBar(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Feedback",
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest, exactly as CountryPicker's sheet title does.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                // 2dp, matching the auth screens, so the chevron does not jump
                // between pushes.
                .padding(start = 2.dp)
                .size(NAV_BAR_HEIGHT)
                .testTag(FEEDBACK_BACK_TAG)
                .plainClickable(onDismiss)
                // Drawn chevron, no symbol name to inherit. See LoginScreen.
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            ChevronLeftGlyph(size = CHEVRON_SIZE, color = Ink)
        }
    }
}

// ---------------------------------------------------------------------------
// Shared modifiers
// ---------------------------------------------------------------------------

/** `.buttonStyle(.plain)` — tap with no ripple, which every control here uses. */
@Composable
internal fun Modifier.plainClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        // Every one of these is a SwiftUI `Button` on iOS, and a button tells a
        // screen reader that it is a button. Without a role TalkBack reads the
        // label as ordinary text: the caregiver hears the words and is never
        // told they can be tapped. Set inside the helper rather than at each
        // call site so a new caller cannot be added without it. Controls that
        // are a CHOICE rather than an action use `plainSelectable` or
        // `plainToggleable`, which carry the role their state needs.
        role = Role.Button,
        onClick = onClick,
    )
}

/**
 * The Add tile's dashed outline: `strokeBorder(line, lineWidth: 1, dash: [4, 3])`.
 *
 * `Modifier.border` cannot dash, so it is drawn. `strokeBorder` insets the
 * stroke fully inside the shape while `drawRoundRect` centres it on the path,
 * so the rect is inset by half the width to land in the same place.
 */
private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp,
    width: Dp = 1.dp,
    on: Dp = 4.dp,
    off: Dp = 3.dp,
): Modifier = drawBehind {
    val strokePx = width.toPx()
    val inset = strokePx / 2f
    val radiusPx = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(
            width = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx())),
        ),
    )
}

// ---------------------------------------------------------------------------
// Glyphs
// ---------------------------------------------------------------------------
//
// Five SF Symbols are named in the Swift: "chevron.left", "checkmark.circle.fill",
// "paperplane.fill", "photo.badge.plus" and "xmark.circle.fill". There is no
// material-icons dependency in this project's version catalog and the porting
// rules forbid adding one, so all five are drawn — the same approach
// CountryPicker.kt and LoginScreen.kt take.
//
// The two ".fill" circle symbols are monochrome fills on iOS, which means the
// circle is painted in the tint and the glyph inside is PUNCHED OUT to whatever
// sits behind. That is why both take a knockout colour rather than drawing the
// glyph in a contrasting ink.

/** Stroke weight as a fraction of the glyph box, for SF Symbol `.semibold`. */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

@Composable
private fun ChevronLeftGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        val inset = stroke / 2f
        drawPolyline(
            points = listOf(
                Offset(w * 0.70f, inset),
                Offset(w * 0.30f, w / 2f),
                Offset(w * 0.70f, w - inset),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/** `checkmark.circle.fill` — [tint] disc with the tick knocked out in [knockout]. */
@Composable
private fun CheckmarkCircleFillGlyph(size: Dp, tint: Color, knockout: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(color = tint, radius = w / 2f, center = Offset(w / 2f, w / 2f))
        drawPolyline(
            points = listOf(
                Offset(w * 0.28f, w * 0.52f),
                Offset(w * 0.44f, w * 0.68f),
                Offset(w * 0.73f, w * 0.34f),
            ),
            color = knockout,
            strokeWidth = w * 0.11f,
        )
    }
}

/**
 * `xmark.circle.fill` at `.foregroundStyle(.white, .black.opacity(0.5))` — a
 * hierarchical style, so the primary layer (the cross) is white and the
 * secondary layer (the disc) is black at half alpha.
 */
@Composable
private fun XmarkCircleFillGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(
            color = Color.Black.copy(alpha = REMOVE_BADGE_DISC_ALPHA),
            radius = w / 2f,
            center = Offset(w / 2f, w / 2f),
        )
        val stroke = w * 0.11f
        drawLine(
            color = Color.White,
            start = Offset(w * 0.33f, w * 0.33f),
            end = Offset(w * 0.67f, w * 0.67f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.67f, w * 0.33f),
            end = Offset(w * 0.33f, w * 0.67f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** `photo.badge.plus` — a picture frame with a plus badge at the trailing bottom. */
@Composable
private fun PhotoBadgePlusGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.09f
        val inset = stroke / 2f
        // The frame, trimmed on the right so the badge reads as sitting outside it.
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, w * 0.18f),
            size = Size(w * 0.72f - stroke, w * 0.58f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = stroke),
        )
        // The photograph inside: a small sun and a hill.
        drawCircle(color = color, radius = w * 0.05f, center = Offset(w * 0.21f, w * 0.34f))
        drawPolyline(
            points = listOf(
                Offset(w * 0.09f, w * 0.68f),
                Offset(w * 0.30f, w * 0.45f),
                Offset(w * 0.55f, w * 0.68f),
            ),
            color = color,
            strokeWidth = stroke,
        )
        // The plus badge.
        val cx = w * 0.80f
        val cy = w * 0.80f
        val arm = w * 0.14f
        drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke, StrokeCap.Round)
    }
}

/**
 * `paperplane.fill`, as an [ImageVector] because that is what [NemaraButton]
 * accepts. Two filled triangles sharing the nose and the fold, which is how the
 * SF Symbol is built.
 */
private val PaperplaneFill: ImageVector by lazy {
    ImageVector.Builder(
        name = "paperplane.fill",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(21.5f, 2.5f)
            lineTo(2.5f, 10.2f)
            lineTo(10.0f, 13.0f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(21.5f, 2.5f)
            lineTo(10.0f, 13.0f)
            lineTo(12.8f, 21.5f)
            close()
        }
    }.build()
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

/** The toolbar back control carries no label on iOS, so tests need a tag. */
const val FEEDBACK_BACK_TAG: String = "feedback.back"

/** The "Add" tile — its "Add" label is too generic to select by alone. */
const val FEEDBACK_ADD_SCREENSHOT_TAG: String = "feedback.addScreenshot"

/** The required field's editor. Its placeholder is a sibling node, not its text. */
const val FEEDBACK_NOT_WORKING_TAG: String = "feedback.whatsNotWorking"

/** The optional "what do you like" editor. */
const val FEEDBACK_LOVED_TAG: String = "feedback.whatsLoved"

// ---------------------------------------------------------------------------
// Tuning
// ---------------------------------------------------------------------------

/** `VStack(spacing: 14)` on the scroll content. Not on the spacing scale. */
private val SECTION_SPACING = 14.dp

/** `VStack(spacing: 10)` inside the founders card and the sent confirmation. */
private val FOUNDERS_STACK_SPACING = 10.dp

/** `NemaraMark(size: 42)` — one step down from the auth screens' 44. */
private val FOUNDERS_MARK_SIZE = 42.dp

/** `NemaraCard(padding: 14)` on the form card. Not on the spacing scale. */
private val CARD_PADDING = 14.dp

/** `.frame(minHeight: 64)` on both text wells. */
private val EDITOR_MIN_HEIGHT = 64.dp

/** See [MultilineField] — the placeholder's `.padding(12)`, matched by the editor. */
private val EDITOR_TEXT_INSET = 12.dp

/** `.frame(height: 36)` on each rating cell. */
private val RATING_CELL_HEIGHT = 36.dp

/**
 * The 1-5 colour ramp, rust through amber to olive. `FeedbackView.swift:249-255`.
 */
private val RATING_PALETTE = listOf(
    Color(0xFFA04331),
    Color(0xFFC26244),
    Color(0xFFC68A3D),
    Color(0xFF8A8E3A),
    Color(0xFF4A5D3A),
)

/** `.frame(width: 64, height: 64)` on every screenshot tile. */
private val TILE_SIZE = 64.dp

/** `ProgressView()` in a 64pt tile — UIKit's medium activity indicator is 20pt. */
private val SPINNER_SIZE = 20.dp
private val SPINNER_STROKE = 2.dp

/** `Image(systemName: "photo.badge.plus").font(.system(size: 18)))`. */
private val ADD_GLYPH_SIZE = 18.dp

/** `xmark.circle.fill` at 18pt with `.padding(3)`. */
private val REMOVE_BADGE_SIZE = 18.dp
private val REMOVE_BADGE_PADDING = 3.dp

/** `.black.opacity(0.5)` behind the remove cross. */
private const val REMOVE_BADGE_DISC_ALPHA = 0.5f

/** `Circle().frame(width: 56, height: 56)` behind the sent tick. */
private val CONFIRMATION_DISC_SIZE = 56.dp

/** `Image(systemName: "checkmark.circle.fill").font(.system(size: 34))`. */
private val CONFIRMATION_GLYPH_SIZE = 34.dp

/** `.padding(.vertical, 10)` on the sent block. */
private val CONFIRMATION_VERTICAL_PADDING = 10.dp

/** `.padding(.top, 6)` on the submit button. */
private val SEND_BUTTON_TOP_PADDING = 6.dp

/** `.opacity(... ? 0.5 : 1)` on the submit button. */
private const val DISABLED_OPACITY = 0.5f

/** `.scale(scale: 0.96)` in the sent block's transition. */
private const val SUBMITTED_SCALE = 0.96f

/**
 * `.spring(response: 0.4, dampingFraction: 0.8)`.
 *
 * SwiftUI's response is the undamped period, so stiffness = (2π / 0.4)² = 246.7
 * in the units Compose's `spring` uses, and dampingFraction is dampingRatio
 * unchanged.
 */
private val SUBMITTED_SPRING = spring<Float>(dampingRatio = 0.8f, stiffness = 246.7f)

/**
 * Inter's natural line height is 1.2100 x the point size (ascent 1984, descent
 * -494, zero line gap, 2048 units per em — the derivation WelcomeScreen.kt
 * documents). At 13sp that is 15.73, so SwiftUI's `.lineSpacing(5)` on the
 * founders paragraph is 20.73 and `.lineSpacing(3)` on the sent copy is 18.73.
 *
 * Compose's `lineHeight` is absolute where SwiftUI's `lineSpacing` only affects
 * the gaps BETWEEN lines, so Compose also distributes roughly 2.5sp above the
 * first line and below the last. Both blocks sit inside padded cards where that
 * is not visible.
 */
private val BODY_SMALL_LINE_HEIGHT_PLUS_5 = 20.73.sp
private val BODY_SMALL_LINE_HEIGHT_PLUS_3 = 18.73.sp

/** UIKit's inline navigation bar height, as ForgotPasswordScreen.kt uses. */
private val NAV_BAR_HEIGHT = 44.dp

/** `Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold))`. */
private val CHEVRON_SIZE = 18.dp
