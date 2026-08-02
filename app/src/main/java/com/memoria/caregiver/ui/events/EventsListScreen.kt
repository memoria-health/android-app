package com.memoria.caregiver.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * The "Events" sub-tab: title, filter chips with live counts, and the list of
 * event cards. Spec: `Features/Patient/Events/PatientEventsView.swift`,
 * PARITY.md §15.
 *
 * **Everything in the Swift that touches the network stays with the host.** In
 * particular:
 *
 * 1. `PATCH {opened: true}` fires before an unopened event is presented. Here
 *    that is one [onEventTap] the host acts on — it must send the patch when
 *    `!event.opened` *and* present the detail, in that order.
 * 2. The 20-second quiet poll, and the fact that it **pauses while a detail
 *    sheet is open**. That guard is load-bearing: refetching replaces the
 *    events list, which re-renders the open sheet and historically killed clip
 *    playback mid-watch.
 * 3. The APNs deep link (`focusEventId`), including the authoritative
 *    `GET /v1/events/{id}` fallback when the event is not in the loaded page.
 *
 * Screen tracking is the host's too — iOS attaches `.trackScreen("patient.events")`
 * in `PatientShell`, not in this view.
 */
@Composable
fun EventsListScreen(
    state: EventsListState,
    onFilterChange: (EventsFilter) -> Unit,
    onEventTap: (DetectionEvent) -> Unit,
    onDismissReviewError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        TitleRow()

        state.reviewError?.let { message ->
            ReviewErrorBanner(message = message, onDismiss = onDismissReviewError)
        }

        FilterChips(
            active = state.filter,
            counts = state.counts,
            onSelect = onFilterChange,
        )

        val visible = state.visible
        if (visible.isEmpty()) {
            EmptyState(filter = state.filter)
        } else {
            for (event in visible) {
                // Keyed so a poll that reorders or filters the list keeps each
                // row's own state — notably the 60s ago-label tick — attached to
                // its event rather than to its position.
                key(event.id) {
                    EventCard(
                        event = event,
                        modifier = Modifier.plainClickable { onEventTap(event) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        // iOS aligns on .lastTextBaseline, so the serif title and the mono pill
        // sit on a shared baseline rather than on a shared box edge.
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = "Recent events",
            modifier = Modifier.alignByBaseline(),
            style = NemaraType.title2,
            // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-0.4)
            letterSpacing = (-0.4).sp,
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Last 7d",
            modifier = Modifier
                .alignByBaseline()
                .clip(CircleShape)
                .background(PaperDim)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = NemaraType.monoSmall.copy(fontWeight = FontWeight.Bold),
            // iOS: .tracking(0.5)
            letterSpacing = 0.5.sp,
            color = Ink2,
        )
    }
}

/**
 * The three chips, in one pill-shaped tray. Hand-built rather than composed from
 * `NemaraChip`: that component is the mono-9 status badge, and these are Inter
 * 12.5 labels each carrying their own count badge, with a selected state that
 * inverts the whole chip.
 */
@Composable
private fun FilterChips(
    active: EventsFilter,
    counts: Map<EventsFilter, Int>,
    onSelect: (EventsFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(PaperDim)
            .padding(Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (filter in EventsFilter.entries) {
            val on = filter == active
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Ink else Color.Transparent)
                    .plainClickable { onSelect(filter) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = filter.title,
                    // iOS: NemaraFont.sans(size: 12.5, weight: .semibold).
                    style = NemaraType.labelStrong.copy(fontSize = 12.5.sp),
                    color = if (on) Paper else Ink2,
                )
                Text(
                    text = "${counts[filter] ?: 0}",
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (on) Paper.copy(alpha = 0.18f) else PaperDim)
                        // iOS pads this badge horizontally only.
                        .padding(horizontal = 6.dp),
                    style = NemaraType.monoMicro,
                    // iOS: .tracking(0.4)
                    letterSpacing = 0.4.sp,
                    color = if (on) Paper else Ink3,
                )
            }
        }
    }
}

/**
 * Centered "nothing here" block. The copy adapts to the active filter — see
 * [eventsEmptyCopy], where the three variants live so they can be pinned without
 * rendering.
 */
@Composable
private fun EmptyState(filter: EventsFilter) {
    val copy = eventsEmptyCopy(filter)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EventGlyph(name = copy.icon, size = 30.dp, tint = Ink3)
        Text(
            text = copy.title,
            style = NemaraType.title3,
            // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-0.3)
            letterSpacing = (-0.3).sp,
            color = Ink,
        )
        Text(
            text = copy.subtitle,
            modifier = Modifier.padding(horizontal = 28.dp),
            style = NemaraType.bodySmall,
            color = Ink2,
            textAlign = TextAlign.Center,
            lineHeight = EmptySubtitleLineHeight,
        )
    }
}

/**
 * 18.73sp. Derivation as in `WelcomeScreen.kt`: SwiftUI's `.lineSpacing(3)` adds
 * 3 points to the gap, and Inter's natural line height is 1.2100 x the point
 * size, so 13 x 1.2100 = 15.73, plus 3.
 */
private val EmptySubtitleLineHeight = 18.73.sp

/** 17.73sp — same derivation, from `.lineSpacing(2)` on Inter 13. */
private val BannerLineHeight = 17.73.sp

/**
 * The dismissible terra banner for `api.lastReviewError`.
 *
 * The message is the sentence the API layer produced and is rendered verbatim —
 * never reworded, for the same reason `AuthFormState.errorMessage` is not.
 */
@Composable
private fun ReviewErrorBanner(message: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TerraSoft)
            .border(1.dp, Terra.copy(alpha = 0.3f), shape)
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EventGlyph(
            name = "exclamationmark.triangle.fill",
            size = 14.dp,
            tint = Terra,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = NemaraType.bodySmall,
            color = Ink,
            lineHeight = BannerLineHeight,
        )
        // iOS: Spacer(minLength: 8) between the message and the close button.
        Spacer(Modifier.width(Spacing.xs))
        EventGlyph(
            name = "xmark",
            size = 12.dp,
            tint = Ink3,
            modifier = Modifier
                .testTag(EventsListReviewErrorDismissTestTag)
                .plainClickable(onDismiss),
        )
    }
}

/** Test handle for the banner's close glyph, which carries no text. */
const val EventsListReviewErrorDismissTestTag: String = "events.review_error.dismiss"

/**
 * Tap with no ripple and no indication — SwiftUI's `.buttonStyle(.plain)`, which
 * every tappable thing on these screens uses. Same helper as `LoginScreen.kt`.
 */
@Composable
internal fun Modifier.plainClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}
