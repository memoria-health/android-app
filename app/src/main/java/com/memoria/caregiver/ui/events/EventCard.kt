package com.memoria.caregiver.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.components.NemaraSolidPill
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft
import kotlinx.coroutines.delay

/**
 * One row in the Events list. Spec: `Features/Patient/Events/EventCard.swift`,
 * PARITY.md §15.
 *
 * Two independent visual axes, both load-bearing:
 * - **background encodes category** — terra-soft for safety, sky-soft for routine
 * - **opacity encodes state** — 62% once opened, 100% while unopened — and the
 *   title's weight shifts with it (regular when opened, semibold when not)
 *
 * The CUSTOM pill appears only for `source == custom`. The REVIEWED pill
 * *replaces* the trailing chevron rather than sitting beside it.
 */
@Composable
fun EventCard(
    event: DetectionEvent,
    modifier: Modifier = Modifier,
) {
    val isSafety = event.category == Detector.Category.SAFETY
    val cardBackground = if (isSafety) TerraSoft else SkySoft
    val categoryBadgeColor = if (isSafety) Terra else Sky
    val categoryLabel = if (isSafety) "SAFETY" else "ROUTINE"

    // SwiftUI's `.opacity(...)` on a container applies to each LEAF, not to the
    // flattened group, unless `.compositingGroup()` intervenes — and
    // compositingGroup appears nowhere in the iOS app (verified for
    // NemaraCamFeedPlaceholder, same finding). Compose's Modifier.alpha is
    // group alpha, so it would composite the card once and dim the result,
    // giving different text colors over the tinted background. Every leaf below
    // therefore carries the factor in its own color instead.
    val dim = if (event.opened) OpenedOpacity else 1f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(cardBackground.dimmed(dim)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Card.copy(alpha = 0.55f).dimmed(dim)),
                contentAlignment = Alignment.Center,
            ) {
                EventGlyph(
                    name = event.iconName,
                    size = 16.dp,
                    tint = categoryBadgeColor.dimmed(dim),
                    weight = GlyphWeight.Regular,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The one place the per-leaf rule is not honoured: the pill
                    // is a shared component whose foreground is fixed inside it,
                    // so the factor can only be applied to the pill as a group.
                    // Two leaves, one of them a 9pt label — the difference is
                    // sub-perceptual, and forking the component would be worse.
                    NemaraSolidPill(
                        text = categoryLabel,
                        background = categoryBadgeColor,
                        modifier = Modifier.alpha(dim),
                    )
                    if (event.source == Detector.Source.CUSTOM) {
                        NemaraSolidPill(
                            text = "CUSTOM",
                            background = Terra,
                            modifier = Modifier.alpha(dim),
                        )
                    }
                }
                Text(
                    text = event.title,
                    // NemaraType.eventTitle is the semibold form. iOS:
                    // NemaraFont.serif(size: 17, weight: opened ? .regular : .semibold).
                    style = if (event.opened) {
                        NemaraType.eventTitle.copy(fontWeight = FontWeight.Normal)
                    } else {
                        NemaraType.eventTitle
                    },
                    // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-0.3)
                    letterSpacing = (-0.3).sp,
                    color = Ink.dimmed(dim),
                )
                Text(
                    text = event.location,
                    style = NemaraType.caption,
                    color = Ink2.dimmed(dim),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ink.copy(alpha = 0.08f).dimmed(dim)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = event.time,
                // JetBrains Mono 11 SemiBold. iOS: NemaraFont.mono(size: 11, weight: .semibold).
                style = NemaraType.monoSmall.copy(fontSize = 11.sp),
                color = Ink2.dimmed(dim),
            )
            Text(
                text = "·",
                // The Swift sets no font on this separator, so it inherits the
                // environment's body font. bodyLarge is the app's nearest.
                style = NemaraType.bodyLarge,
                color = Ink.copy(alpha = 0.18f).dimmed(dim),
            )
            AgoLabel(event = event, color = Ink3.dimmed(dim))

            Spacer(Modifier.weight(1f))

            if (event.reviewed) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Card.copy(alpha = 0.55f).dimmed(dim))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EventGlyph(
                        name = "checkmark",
                        size = 9.dp,
                        tint = Ink3.dimmed(dim),
                        weight = GlyphWeight.Black,
                    )
                    Text(
                        text = "REVIEWED",
                        style = NemaraType.monoMicro,
                        // iOS: .tracking(0.5)
                        letterSpacing = 0.5.sp,
                        color = Ink3.dimmed(dim),
                    )
                }
            } else {
                EventGlyph(
                    name = "chevron.right",
                    size = 13.dp,
                    tint = Ink3.dimmed(dim),
                )
            }
        }
    }
}

/** iOS: `.opacity(event.opened ? 0.62 : 1)`. */
internal const val OpenedOpacity: Float = 0.62f

/** How often the relative-time label re-reads the clock. iOS: `.periodic(by: 60)`. */
internal const val AgoTickMillis: Long = 60_000L

/**
 * The "42 MIN AGO" label, on a 60-second tick.
 *
 * `event.ago` reads the wall clock, which is not a Compose state read — without
 * a ticking source the label freezes at whatever it said on first composition,
 * exactly as it would in SwiftUI without the `TimelineView` this replaces.
 *
 * **Scoped to this leaf on purpose.** It must not reach anything that could
 * re-trigger the events refetch, which is what killed clip playback mid-watch
 * (PARITY.md §15). Recomposing one `Text` cannot.
 */
@Composable
private fun AgoLabel(event: DetectionEvent, color: Color) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AgoTickMillis)
            tick++
        }
    }
    // Keyed on the event too, so a poll that replaces the row with an updated
    // copy re-reads the label immediately rather than at the next tick — the
    // same thing SwiftUI does when the parent re-renders the TimelineView.
    val label = remember(event, tick) { event.ago }

    Text(
        text = label.uppercase(),
        style = NemaraType.monoSmall,
        // iOS: .tracking(0.4)
        letterSpacing = 0.4.sp,
        color = color,
    )
}

/**
 * Fold an inherited SwiftUI `.opacity` into a leaf's own color, which is what
 * per-leaf compositing amounts to: each leaf is drawn at `its own alpha x the
 * inherited factor` over whatever is already there.
 */
internal fun Color.dimmed(factor: Float): Color =
    if (factor >= 1f) this else copy(alpha = alpha * factor)
