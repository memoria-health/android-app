package com.memoria.caregiver.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.components.NemaraCamFeedPlaceholder
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * Step 1 of 2 of the review flow: the title block, the archived clip, and
 * Memoria's narrative of what it saw. Spec:
 * `Features/Patient/Events/EventDetailView.swift`, PARITY.md §15.
 *
 * **The clip is a slot, not something this screen builds.** iOS creates its
 * `AVPlayer` **once** when the URL resolves and holds it in state; an inline
 * player was rebuilt on every re-render and the clip "closed" mid-watch at ~20s.
 * The Android equivalent is to hold the ExoPlayer instance outside composition,
 * which is a host concern — and there is no media dependency in the version
 * catalog anyway. So [clipPlayer] is supplied by the caller and this screen only
 * decides *whether* to show it.
 *
 * Two clip flags are behaviour, not decoration:
 * - `clipSuppressed` is **terminal**. No polling, no spinner, a final statement.
 *   The copy is deliberately reason-neutral: it must not claim privacy (private
 *   rooms do get clips now) and must not hide failures.
 * - `clipCensored` puts a dignity cover over the video that only a deliberate
 *   "View video" tap removes. **Per-open, never persisted** — see
 *   [censoredRevealed] below. Do not add a "don't ask again".
 */
@Composable
fun EventDetailScreen(
    state: EventDetailState,
    onBack: () -> Unit,
    onReviewEvent: () -> Unit,
    modifier: Modifier = Modifier,
    clipPlayer: @Composable (url: String) -> Unit,
) {
    val analytics = LocalAnalytics.current
    // iOS: PatientEventsView attaches .trackScreen("event_detail") to this view
    // when it presents the sheet. Kept with the screen so the event cannot be
    // lost by a host that forgets to attach it.
    val event = state.event

    // Keyed on the event id, not Unit. iOS presents this as a sheet and gets a
    // fresh view identity per event, so it logs one screen view per event
    // opened. Keyed on Unit, a host that swaps the presented event in place —
    // a deep link arriving while the sheet is open, or next/previous
    // navigation — logs the first event and then goes silent.
    LaunchedEffect(event.id) { analytics.logScreen("event_detail") }
    val isSafety = event.category == Detector.Category.SAFETY
    val tone = if (isSafety) Terra else Sky
    val toneSoft = if (isSafety) TerraSoft else SkySoft
    val categoryLabel = if (isSafety) "SAFETY" else "ROUTINE"

    // Deliberately a plain `remember`, deliberately KEYED ON THE EVENT ID, and
    // deliberately not hoisted into EventDetailState. The dignity cover must be
    // back for every event, every time.
    //
    // `rememberSaveable` would survive process death and a hoisted flag would
    // survive a re-present — either turns a dignity-first default into a
    // one-time prompt. **An unkeyed `remember` has the same effect across a
    // single presentation**: iOS gets a fresh view identity per event, so
    // revealing one censored clip and then moving to another re-covers it,
    // whereas without the key the second event would open already uncovered.
    // That is someone's parent on screen without the deliberate tap that is the
    // entire point of the cover.
    var censoredRevealed by remember(event.id) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        DetailNavBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(top = 6.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- title block ----
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            // iOS: RoundedRectangle(cornerRadius: 8). No radius
                            // token is 8 — sm is 6 and md is 10.
                            .clip(RoundedCornerShape(8.dp))
                            .background(toneSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        EventGlyph(name = event.iconName, size = 13.dp, tint = tone)
                    }
                    Text(
                        text = categoryLabel,
                        style = NemaraType.monoSmall.copy(fontWeight = FontWeight.Bold),
                        // iOS: .tracking(0.7)
                        letterSpacing = 0.7.sp,
                        color = tone,
                    )
                }
                Text(
                    text = event.title,
                    style = NemaraType.title1,
                    // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-0.8)
                    letterSpacing = (-0.8).sp,
                    color = Ink,
                )
                Text(
                    text = "${event.location} · ${event.time}",
                    style = NemaraType.bodySmall,
                    color = Ink2,
                )
            }

            // ---- clip ----
            ClipView(
                state = state,
                censoredRevealed = censoredRevealed,
                onRevealCensored = { censoredRevealed = true },
                clipPlayer = clipPlayer,
            )

            // ---- narrative ----
            NemaraCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EventGlyph(name = "sparkles", size = 12.dp, tint = Ink3)
                        NemaraEyebrow("WHAT MEMORIA SAW")
                    }
                    Text(
                        text = event.narrative,
                        // iOS: NemaraFont.bodySmallStrong.weight(.regular) — the
                        // .weight(.regular) undoes the "Strong", leaving Inter 13
                        // regular, which is exactly bodySmall.
                        style = NemaraType.bodySmall,
                        color = Ink,
                        lineHeight = NarrativeLineHeight,
                    )
                }
            }
        }

        ReviewFooter(onReviewEvent = onReviewEvent)
    }
}

/**
 * 20.73sp. Derivation as in `WelcomeScreen.kt`: SwiftUI's `.lineSpacing(5)` adds
 * 5 points to the gap, and Inter's natural line height is 1.2100 x the point
 * size, so 13 x 1.2100 = 15.73, plus 5.
 */
private val NarrativeLineHeight = 20.73.sp

/**
 * iOS's inline navigation bar: a hand-supplied back chevron (the system one is
 * hidden), the "Event detail" title, and the step counter. Drawn here the same
 * way `TermsGateScreen` draws its own, because Compose has no navigation bar.
 */
@Composable
private fun DetailNavBar(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Event detail",
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest: Inter 16 semibold. Same choice as TermsGateScreen.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        // A 44dp touch target around a 22dp glyph. iOS deliberately keeps this a
        // plain toolbar button and makes the glyph bigger and bolder rather than
        // wrapping it in an oversized frame, which fought the toolbar's layout
        // and broke the tap outright.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .testTag(EventDetailBackTestTag)
                .plainClickable(onBack)
                // Drawn chevron, no symbol name to inherit. See LoginScreen.
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            EventGlyph(
                name = "chevron.backward",
                size = 22.dp,
                tint = Ink,
                weight = GlyphWeight.Bold,
            )
        }
        Text(
            text = "1 / 2",
            modifier = Modifier.align(Alignment.CenterEnd),
            style = NemaraType.monoSmall,
            // iOS: .tracking(0.5)
            letterSpacing = 0.5.sp,
            color = Ink3,
        )
    }
}

/** Test handle for the back chevron, which carries no text to select by. */
const val EventDetailBackTestTag: String = "event_detail.back"

/**
 * The 16:9 clip well: video, skeleton, terminal placard, or dignity cover.
 *
 * The `player == nil` fallback in the Swift has no counterpart here. There, the
 * URL resolving and the player existing were two separate facts; here the host
 * only reports [ClipState.Loaded] once it has a player to draw, so the state and
 * the player cannot disagree.
 */
@Composable
private fun ClipView(
    state: EventDetailState,
    censoredRevealed: Boolean,
    onRevealCensored: () -> Unit,
    clipPlayer: @Composable (url: String) -> Unit,
) {
    val event = state.event
    val clip = state.clip
    val shape = RoundedCornerShape(Radius.lg)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .border(1.dp, Line, shape),
    ) {
        when {
            event.clipSuppressed -> ClipSuppressedPlacard()

            clip is ClipState.Loaded -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription = "Event clip from ${event.location}"
                        },
                ) {
                    clipPlayer(clip.url)
                }
                if (event.clipCensored && !censoredRevealed) {
                    CensoredOverlay(onReveal = onRevealCensored)
                }
            }

            else -> ClipPlaceholder(label = clipPlaceholderLabel(clip).orEmpty())
        }
    }
}

/**
 * Skeleton shown while the signed URL is being fetched or the archival has not
 * finished. Mirrors the live-cam placeholder so the detail view stays
 * recognizable across the three states.
 */
@Composable
private fun ClipPlaceholder(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NemaraCamFeedPlaceholder(modifier = Modifier.fillMaxSize(), hue = 30f)
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EventGlyph(
                name = "film",
                size = 22.dp,
                tint = TerraInk.copy(alpha = 0.85f),
            )
            Text(
                text = label,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                style = NemaraType.monoSmall,
                // iOS: .tracking(0.4)
                letterSpacing = 0.4.sp,
                color = TerraInk,
            )
        }
    }
}

/**
 * Shown in place of the clip when `clip_suppressed` is stamped: there is no clip
 * and there never will be. The wording covers pipeline terminals (stale window,
 * encode or censor failure) as well as legacy privacy-mode rows, so it is
 * reason-neutral on purpose — it must not claim privacy and must not hide
 * failures.
 */
@Composable
private fun ClipSuppressedPlacard() {
    Box(
        Modifier.fillMaxSize().background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EventGlyph(
                name = "eye.slash.fill",
                size = 22.dp,
                tint = TerraInk.copy(alpha = 0.85f),
            )
            Text(
                text = "No clip for this event",
                style = NemaraType.bodySmallStrong,
                color = TerraInk,
            )
            Text(
                text = "The insight was captured, but video of this moment couldn’t be saved.",
                modifier = Modifier.padding(horizontal = Spacing.lg),
                style = NemaraType.caption,
                color = TerraInk.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The dignity cover over a server-censored clip. One plain sentence, no category
 * jargon — the video beneath is the censored rendition either way.
 */
@Composable
private fun CensoredOverlay(onReveal: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Ink.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EventGlyph(
                name = "eye.slash.fill",
                size = 22.dp,
                tint = TerraInk.copy(alpha = 0.85f),
            )
            Text(
                text = "Sensitive moment",
                style = NemaraType.bodySmallStrong,
                color = TerraInk,
            )
            Text(
                text = "This video has been censored to maintain dignity.",
                modifier = Modifier.padding(horizontal = Spacing.xl),
                style = NemaraType.caption,
                color = TerraInk.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "View video",
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(CircleShape)
                    .background(TerraInk)
                    .plainClickable(onReveal)
                    .padding(horizontal = 18.dp, vertical = Spacing.xs)
                    .semantics { contentDescription = "View the censored video" },
                style = NemaraType.bodySmallStrong,
                color = Ink,
            )
        }
    }
}

/**
 * The pinned footer CTA into step 2.
 *
 * Hand-built rather than `NemaraButton`, because iOS hand-builds it too: this is
 * a bare `NavigationLink` label, so it fires **no** analytics event, and it
 * carries `.tracking(-0.1)` which the shared button has no way to express.
 * Reaching for `NemaraButton` here would add an event iOS never logs.
 */
@Composable
private fun ReviewFooter(onReviewEvent: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Paper)) {
        // iOS: .overlay(NemaraColor.line.frame(height: 1), alignment: .top)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = Spacing.sm, bottom = 14.dp),
        ) {
            Text(
                text = "Review this event →",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(Action)
                    .plainClickable(onReviewEvent)
                    .padding(vertical = Spacing.md),
                style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                // iOS: .tracking(-0.1)
                letterSpacing = (-0.1).sp,
                color = ActionInk,
                textAlign = TextAlign.Center,
            )
        }
    }
}
