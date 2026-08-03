package com.memoria.caregiver.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Card
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
 * Step 2 of 2 of the review flow: correct / not correct / not sure, plus
 * optional free-text notes. Spec:
 * `Features/Patient/Events/EventReviewView.swift`, PARITY.md §15.
 *
 * Pure over [EventReviewState] plus callbacks, as `EventsUiState.kt` requires of
 * every screen in this package. The submit itself is
 * `EventsListViewModel.submitReview`, which is optimistic: it writes the
 * reviewed row into the cache, closes the whole modal stack, and only then
 * PATCHes. So [onSubmit] here is expected to *leave* this screen — nothing on it
 * shows progress or failure, and there is deliberately no in-flight state to
 * render. A failure surfaces later, as the banner above the filter chips on the
 * events list.
 *
 * **[onSubmit] carries no arguments.** The host already knows the state it
 * passed in, and handing the choice back through the callback would create a
 * second copy that could disagree with the one on screen.
 *
 * One control iOS does not draw: the back chevron. `EventReviewView` is pushed
 * onto a `NavigationStack` and gets the system back button for free, whereas
 * Compose has no navigation bar at all — every screen in this port draws its
 * own. See `EventDetailScreen.DetailNavBar`, which this mirrors down to the step
 * counter.
 */
@Composable
fun EventReviewScreen(
    state: EventReviewState,
    onChoiceChange: (DetectionEvent.ReviewChoice) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // iOS attaches `.trackScreen("event_review")` where the view is PUSHED, in
    // EventDetailView's footer. Logged here for the reason FeedbackScreen gives:
    // a screen event that lives on the caller is one the next caller forgets.
    //
    // Keyed on the event id rather than Unit, exactly as EventDetailScreen is: a
    // host that swaps the reviewed event in place would otherwise log the first
    // one and then go silent.
    val analytics = LocalAnalytics.current
    LaunchedEffect(state.event.id) { analytics.logScreen("event_review") }

    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        ReviewNavBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // iOS also has `.scrollDismissesKeyboard(.interactively)` and
                // `.dismissKeyboardOnTap()`. Compose has no equivalent of
                // either, and Android's own back gesture already dismisses the
                // keyboard, so the platform supplies the affordance rather than
                // the screen growing chrome iOS does not have. Same call as
                // DetectorDetailSheet, recorded in DIFFERENCES.md D2.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                // The 80dp bottom inset is iOS clearing its own pinned footer,
                // which overlaps the scroll view there. Here the footer is a
                // sibling in a Column and takes its own height out of the
                // scrollable area, so the inset is only breathing room — but it
                // is kept, because it is also what stops the notes field ending
                // flush against the rule above the button.
                .padding(top = 6.dp, bottom = FOOTER_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Intro(event = state.event)

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ChoiceRow(
                    state = state,
                    key = DetectionEvent.ReviewChoice.CORRECT,
                    label = "Correct",
                    tone = Action,
                    soft = ActionSoft,
                    icon = "checkmark",
                    onSelect = onChoiceChange,
                )
                ChoiceRow(
                    state = state,
                    key = DetectionEvent.ReviewChoice.WRONG,
                    label = "Not correct",
                    tone = Terra,
                    soft = TerraSoft,
                    icon = "xmark",
                    onSelect = onChoiceChange,
                )
                ChoiceRow(
                    state = state,
                    key = DetectionEvent.ReviewChoice.UNSURE,
                    label = "Not sure",
                    tone = Ink2,
                    soft = PaperDim,
                    icon = "ellipsis",
                    onSelect = onChoiceChange,
                )
            }

            NotesField(notes = state.notes, onNotesChange = onNotesChange)
        }

        Footer(onSubmit = onSubmit)
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun Intro(event: DetectionEvent) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NemaraEyebrow("YOUR REVIEW HELPS MEMORIA LEARN", color = Terra)
        Text(
            text = reviewSubjectQuestion(event),
            style = NemaraType.title1,
            // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-0.7)
            letterSpacing = (-0.7).sp,
            color = Ink,
        )
        Text(
            text = "Your answer is private to this profile and tunes their detectors over time.",
            style = NemaraType.bodySmall,
            lineHeight = BODY_SMALL_LINE_HEIGHT_PLUS_2,
            color = Ink2,
        )
    }
}

/**
 * One of the three answers.
 *
 * The selected look is four changes at once — filled leading tile, tinted label,
 * tinted card, filled radio — which is what makes the choice readable at arm's
 * length. Note that the two knocked-out marks stay [TerraInk] whichever tone is
 * active, because they sit on a filled tone disc rather than on the card.
 */
@Composable
private fun ChoiceRow(
    state: EventReviewState,
    key: DetectionEvent.ReviewChoice,
    label: String,
    tone: Color,
    soft: Color,
    icon: String,
    onSelect: (DetectionEvent.ReviewChoice) -> Unit,
) {
    val on = state.choice == key
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (on) soft else Card)
            .border(SELECTION_BORDER_WIDTH, if (on) tone else Line, shape)
            .testTag(reviewChoiceTestTag(key))
            .plainClickable { onSelect(key) }
            .padding(horizontal = Spacing.md, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(LEADING_TILE_SIZE)
                // iOS: RoundedRectangle(cornerRadius: 10), which is Radius.md.
                .clip(RoundedCornerShape(Radius.md))
                .background(if (on) tone else PaperDim),
            contentAlignment = Alignment.Center,
        ) {
            EventGlyph(
                name = icon,
                size = LEADING_GLYPH_SIZE,
                tint = if (on) TerraInk else Ink3,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = NemaraType.bodySemibold,
                color = if (on) tone else Ink,
            )
            Text(
                text = reviewChoiceSubtext(state.event, key),
                style = NemaraType.caption,
                color = Ink3,
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(RADIO_SIZE)
                .clip(CircleShape)
                .background(if (on) tone else Color.Transparent)
                .border(SELECTION_BORDER_WIDTH, if (on) tone else Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                EventGlyph(
                    name = "checkmark",
                    size = RADIO_TICK_SIZE,
                    tint = TerraInk,
                    // iOS: .font(.system(size: 9, weight: .black)). A 9pt tick
                    // needs the heaviest stroke on the ladder to read at all.
                    weight = GlyphWeight.Black,
                )
            }
        }
    }
}

/**
 * The optional notes well.
 *
 * The Swift pads the editor 8 and the placeholder 12, with `TextEditor` adding
 * its own text-container inset on top so the caret lands under the placeholder.
 * `BasicTextField` has no such inset, so the arithmetic collapses and both take
 * 12 — the same call `FeedbackScreen.MultilineField` documents at length.
 * Porting the 8 literally would misalign the two by exactly the amount the
 * Swift's own comment exists to fix.
 */
@Composable
private fun NotesField(notes: String, onNotesChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraEyebrow("ANYTHING TO ADD?")
            Text(
                text = "optional",
                // Inter ships no italic cut, so Compose synthesises the slant
                // the same way UIKit would for a family without one.
                style = NemaraType.caption.copy(fontStyle = FontStyle.Italic),
                color = Ink3,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EDITOR_MIN_HEIGHT)
                .clip(RoundedCornerShape(Radius.md))
                .background(PaperDim),
        ) {
            if (notes.isEmpty()) {
                Text(
                    text = "E.g. she tripped on the rug between the couch and coffee table…",
                    modifier = Modifier.padding(EDITOR_TEXT_INSET),
                    style = NemaraType.bodySmall,
                    color = Ink3,
                )
            }
            BasicTextField(
                value = notes,
                onValueChange = onNotesChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(EDITOR_TEXT_INSET)
                    .testTag(EVENT_REVIEW_NOTES_TAG),
                textStyle = NemaraType.bodySmall.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                // A TextEditor is multi-line and its return key inserts a
                // newline; singleLine = false plus the default ImeAction is that
                // behavior. Capitalization follows UIKit's default for prose.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            )
        }
    }
}

/**
 * The pinned submit bar.
 *
 * [NemaraButton] rather than a hand-built control, because iOS uses
 * `NemaraButton` here — unlike step 1's footer, which is a bare `NavigationLink`
 * and therefore logs nothing. Using the shared component keeps the
 * "Submit review" button event that iOS already has.
 */
@Composable
private fun Footer(onSubmit: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Paper)) {
        // iOS: .overlay(NemaraColor.line.frame(height: 1), alignment: .top)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        NemaraButton(
            title = "Submit review",
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .padding(top = Spacing.sm, bottom = 14.dp),
            variant = NemaraButtonVariant.Primary,
            size = NemaraButtonSize.Lg,
            block = true,
            onClick = onSubmit,
        )
    }
}

/**
 * iOS's inline navigation bar: the system back chevron it gets for free, the
 * "Review event" title, and the "2 / 2" step counter in the trailing toolbar
 * slot. Drawn the same way `EventDetailScreen.DetailNavBar` draws "1 / 2".
 */
@Composable
private fun ReviewNavBar(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Review event",
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest: Inter 16 semibold, as every other bar here does.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(NAV_BAR_TOUCH_TARGET)
                .testTag(EVENT_REVIEW_BACK_TAG)
                .plainClickable(onBack),
            contentAlignment = Alignment.Center,
        ) {
            EventGlyph(
                name = "chevron.backward",
                size = NAV_BAR_CHEVRON_SIZE,
                tint = Ink,
                weight = GlyphWeight.Bold,
            )
        }
        Text(
            text = "2 / 2",
            modifier = Modifier.align(Alignment.CenterEnd),
            style = NemaraType.monoSmall,
            // iOS: .tracking(0.5)
            letterSpacing = 0.5.sp,
            color = Ink3,
        )
    }
}

// ---------------------------------------------------------------------------
// Test hooks
// ---------------------------------------------------------------------------

/**
 * A choice row. Its label and subtitle are selectable text, but the tappable
 * node is the row around them, so tests need a handle on the row itself.
 */
internal fun reviewChoiceTestTag(choice: DetectionEvent.ReviewChoice): String =
    "event_review.choice.${choice.wire}"

/** The notes editor. Its placeholder is a sibling node, not its text. */
const val EVENT_REVIEW_NOTES_TAG: String = "event_review.notes"

/** The back chevron, which carries no text to select by. */
const val EVENT_REVIEW_BACK_TAG: String = "event_review.back"

// ---------------------------------------------------------------------------
// Tuning
// ---------------------------------------------------------------------------

/** `.padding(.bottom, 80)` on the scroll content. See the call site. */
private val FOOTER_CLEARANCE = 80.dp

/** `.frame(width: 32, height: 32)` on a choice row's leading tile. */
private val LEADING_TILE_SIZE = 32.dp

/** `Image(systemName:).font(.system(size: 14, weight: .semibold))` inside it. */
private val LEADING_GLYPH_SIZE = 14.dp

/** `.frame(width: 20, height: 20)` on the radio. */
private val RADIO_SIZE = 20.dp

/** `Image(systemName: "checkmark").font(.system(size: 9, weight: .black))`. */
private val RADIO_TICK_SIZE = 9.dp

/**
 * `lineWidth: 1.5` on both the card border and the radio ring — heavier than
 * the 1dp used on cards elsewhere, matching `NemaraField`'s engaged border.
 */
private val SELECTION_BORDER_WIDTH = 1.5.dp

/** `.frame(minHeight: 80)` on the notes well. */
private val EDITOR_MIN_HEIGHT = 80.dp

/** See [NotesField] — the placeholder's `.padding(12)`, matched by the editor. */
private val EDITOR_TEXT_INSET = 12.dp

/**
 * 17.73sp. Inter's natural line height is 1.2100 x the point size (the
 * derivation `WelcomeScreen.kt` documents), so 13 x 1.2100 = 15.73, and SwiftUI's
 * `.lineSpacing(2)` adds 2.
 */
private val BODY_SMALL_LINE_HEIGHT_PLUS_2 = 17.73.sp

/** A 44dp touch target around the chevron, as `EventDetailScreen` uses. */
private val NAV_BAR_TOUCH_TARGET = 44.dp

/** `Image(systemName: "chevron.backward")` at the size step 1 uses. */
private val NAV_BAR_CHEVRON_SIZE = 22.dp
