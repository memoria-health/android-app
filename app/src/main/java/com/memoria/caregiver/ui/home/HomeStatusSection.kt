package com.memoria.caregiver.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.settings.boldRuns
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.TerraSoft
import com.memoria.caregiver.ui.theme.Warning

/**
 * The Home briefing: one honest verdict, the prose under it, and the streak.
 *
 * Port of the rendering half of `Features/Home/HomeStatusView.swift`; the
 * decisions all live in [HomeStatus] and [RosterState], which are pure and
 * tested. This file draws them and owns exactly one piece of state — whether the
 * ⓘ expansion is open.
 *
 * Sits **above** the patient list, as iOS puts it: the answer before the
 * navigation.
 */
@Composable
fun HomeStatusSection(
    state: RosterState,
    modifier: Modifier = Modifier,
) {
    if (state.patients.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        VerdictCard(state)

        Text(
            text = markdown(state.briefingText),
            // iOS: serif 17 regular. The nearest token is eventTitle (17
            // serif) which is SemiBold, so the weight is dropped back.
            style = NemaraType.eventTitle.copy(fontWeight = FontWeight.Normal),
            color = Ink2,
            lineHeight = 27.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        state.streak?.let { (value, label) -> StreakChip(value = value, label = label) }
    }
}

@Composable
private fun VerdictCard(state: RosterState) {
    val verdict = state.verdict
    // iOS pairs a dot colour with a wash of the same colour behind the card.
    // Urgent is the exception: its background is TerraSoft rather than a danger
    // wash, so the most serious state does not read as an error banner.
    val (dot, background) = when (verdict.level) {
        HomeStatus.Level.QUIET -> Success to Success.copy(alpha = 0.12f)
        HomeStatus.Level.ATTENTION, HomeStatus.Level.BLIND -> Warning to Warning.copy(alpha = 0.12f)
        HomeStatus.Level.URGENT -> Danger to TerraSoft
    }

    var showCameraDetail by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(dot)
                    // The dot is the only thing carrying severity visually, and
                    // colour alone is not available to everyone. The level is
                    // said out loud instead of described as a coloured circle.
                    .semantics { contentDescription = severityDescription(verdict.level) },
            ) {}
            Text(
                text = verdict.line,
                // iOS: serif 22 semibold. title2 is serif 22 regular.
                style = NemaraType.title2.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
                modifier = Modifier
                    .weight(1f)
                    // The screen's headline. Heading semantics let a screen
                    // reader jump straight to the answer.
                    .semantics { heading() },
            )
            Text(
                text = "ⓘ",
                style = NemaraType.bodyLarge,
                color = Ink4,
                modifier = Modifier
                    .plainClickable { showCameraDetail = !showCameraDetail }
                    .semantics {
                        contentDescription =
                            if (showCameraDetail) "Hide camera detail" else "Show camera detail"
                    },
            )
        }

        AnimatedVisibility(visible = showCameraDetail) {
            Column(
                modifier = Modifier.padding(top = 4.dp, start = 23.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Exact camera truth, straight from the rows. The report prompt
                // forbids the model from mentioning camera status precisely
                // because this is where it is stated — two sources for one fact
                // is how "3 offline" ends up beside "everything is fine".
                state.cameraDetail.forEach { (name, line) ->
                    Text(
                        text = markdown("**$name** — $line"),
                        style = NemaraType.bodySmall,
                        color = Ink3,
                    )
                }
            }
        }

        if (verdict.sub.isNotEmpty()) {
            Text(
                text = markdown(verdict.sub),
                style = NemaraType.bodyMedium,
                color = Ink3,
                modifier = Modifier.padding(start = 23.dp),
            )
        }
    }
}

@Composable
private fun StreakChip(value: String, label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // iOS: serif 27 semibold. No token is 27; title1 is the nearest
        // serif and carries the size explicitly.
        Text(
            text = value,
            style = NemaraType.title1.copy(fontSize = 27.sp, fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        Text(text = label, style = NemaraType.bodySmall, color = Ink3)
    }
    Spacer(Modifier.size(0.dp))
}

/** What the severity dot means, said rather than shown. */
private fun severityDescription(level: HomeStatus.Level): String = when (level) {
    HomeStatus.Level.URGENT -> "Needs attention"
    HomeStatus.Level.BLIND -> "Not being watched"
    HomeStatus.Level.ATTENTION -> "Something to look at"
    HomeStatus.Level.QUIET -> "All quiet"
}

/**
 * `**bold**` to styled text.
 *
 * `keepUnmatchedMarkers = false`: this text can be written by a language model
 * that was *instructed* to bold names, so a stray marker is a model slip rather
 * than an authored character. iOS is explicit — raw asterisks must never reach a
 * caregiver's screen.
 */
@Composable
private fun markdown(source: String): AnnotatedString {
    val runs = remember(source) { boldRuns(source, keepUnmatchedMarkers = false) }
    return remember(runs) {
        buildAnnotatedString {
            append(runs.text)
            runs.bold.forEach { range ->
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
            }
        }
    }
}
