package com.memoria.caregiver.ui.detectors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.DetectorLibraryEntry
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraCardTone
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra

/**
 * The detection-library picker — the prebuilt detectors for the patient's cohort
 * (the backend derives child vs adult from the date of birth), with a search bar
 * and multi-select.
 * Spec: Features/Patient/Detectors/DetectorLibraryView.swift, PARITY.md §22.
 *
 * One screen, two modes. See [DetectorLibraryMode].
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. In particular the
 * onboarding **reconcile is the host's job**: this screen reports the taps and
 * exposes [DetectorLibraryState.keysToAdd] and
 * [DetectorLibraryState.keysToRemove] so the host does not re-derive them, but
 * the adds, the deletes, and the fail-loud on a missing detector row all happen
 * above it.
 */
@Composable
fun DetectorLibraryScreen(
    state: DetectorLibraryState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onToggleEntry: (DetectorLibraryEntry) -> Unit,
    onRetry: () -> Unit,
    /** Cancel in add-mode, Skip in onboarding. */
    onLeadingAction: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {
        Header(title = state.leadingActionTitle, onLeadingAction = onLeadingAction)

        // The three body states share the space between the fixed header and the
        // fixed footer, so each takes the weight rather than the whole column.
        when {
            state.isLoading -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Terra)
            }

            state.loadError != null -> LoadFailure(message = state.loadError, onRetry = onRetry)

            else -> Column(Modifier.fillMaxWidth().weight(1f)) {
                SearchBar(query = state.query, onQueryChange = onQueryChange)
                EntryList(state = state, onToggleEntry = onToggleEntry)
            }
        }

        Footer(state = state, onSubmit = onSubmit)
    }
}

/** Load failed: the sentence from the API, verbatim, over a Retry. */
@Composable
private fun ColumnScope.LoadFailure(message: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Couldn't load the detection library.", style = NemaraType.bodySemibold, color = Ink)
            Text(text = message, style = NemaraType.caption, color = Ink3, textAlign = TextAlign.Center)
            NemaraButton(title = "Retry", variant = NemaraButtonVariant.Secondary, onClick = onRetry)
        }
    }
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun Header(title: String, onLeadingAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = NemaraType.bodySmall,
            color = Ink3,
            modifier = Modifier.plainClickable(onClick = onLeadingAction),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Detection library",
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            // iOS: .kerning(-0.2). Per DECISIONS.md D-008 this is the call site.
            letterSpacing = (-0.2).sp,
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        // Balances the leading button so the title stays centred. It must keep
        // the same text to keep the same width, and it is cleared from the
        // semantics tree so screen readers — and node lookups — see one Cancel,
        // not two. SwiftUI drops zero-opacity views from accessibility for the
        // same reason.
        Text(
            text = title,
            style = NemaraType.bodySmall,
            modifier = Modifier.alpha(0f).clearAndSetSemantics {},
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Card)
            .border(1.dp, Line, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NemaraGlyph(symbol = "magnifyingglass", size = 14.dp, color = Ink3)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search detectors", style = NemaraType.bodyLarge, color = Ink4)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = NemaraType.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                singleLine = true,
                // iOS: .autocorrectionDisabled(). Detector names are product
                // nouns, and autocorrect mangles a half-typed one.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            NemaraGlyph(
                symbol = "xmark.circle.fill",
                size = 15.dp,
                color = Ink3,
                hole = Card,
                modifier = Modifier.plainClickable { onQueryChange("") },
            )
        }
    }
}

@Composable
private fun EntryList(
    state: DetectorLibraryState,
    onToggleEntry: (DetectorLibraryEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // First-run intro — onboarding only, where the caregiver is brand new and
        // will not know this list exists elsewhere or that custom detectors are a
        // thing.
        if (state.mode == DetectorLibraryMode.Onboarding) {
            NemaraCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                tone = NemaraCardTone.Terra,
                padding = 14.dp,
                borderColor = Color.Transparent,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Add a few pre-built detections", style = NemaraType.bodySemibold, color = Ink)
                    Text(
                        text = "Pick any of these to start. You can always come back here later — " +
                            "and you can also create your own custom detectors.",
                        style = NemaraType.bodySmall,
                        color = Ink2,
                        // iOS: .lineSpacing(3) on a 13pt Inter line. SwiftUI adds
                        // the 3 on TOP of the font's own line height, which for
                        // this cut is ~1.21x the size, so the absolute Compose
                        // value is 13 * 1.21 + 3.
                        lineHeight = 18.7.sp,
                    )
                }
            }
        }

        if (state.filteredEntries.isEmpty()) {
            Text(
                text = "No detectors match “${state.query}”.",
                style = NemaraType.bodySmall,
                color = Ink3,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
            )
        }
        if (state.recommendedEntries.isNotEmpty()) {
            Section("RECOMMENDED", state, state.recommendedEntries, onToggleEntry)
        }
        if (state.otherEntries.isNotEmpty()) {
            Section("MORE DETECTORS", state, state.otherEntries, onToggleEntry)
        }

        // Honest empty-camera note. Matches the backend: detectors start running
        // when the first camera is paired.
        Text(
            text = "These start watching as soon as you add a camera.",
            style = NemaraType.caption,
            color = Ink3,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun Section(
    title: String,
    state: DetectorLibraryState,
    rows: List<DetectorLibraryEntry>,
    onToggleEntry: (DetectorLibraryEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow(title, modifier = Modifier.padding(horizontal = 16.dp))
        NemaraCard(modifier = Modifier.padding(horizontal = 16.dp), padding = 0.dp) {
            rows.forEachIndexed { index, entry ->
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(start = 52.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Line),
                    )
                }
                EntryRow(
                    entry = entry,
                    locked = state.isLocked(entry),
                    selected = state.isSelected(entry),
                    onToggle = { onToggleEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: DetectorLibraryEntry,
    locked: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A locked row is inert, not merely styled differently: iOS disables
            // the whole button so the tap never reaches `toggle`.
            .plainClickable(enabled = !locked, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            NemaraGlyph(symbol = entry.iconName, size = 16.dp, color = Terra)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = NemaraType.bodySemibold, color = Ink)
            // iOS: .lineSpacing(1) on 11pt — 11 * 1.21 + 1.
            Text(entry.description, style = NemaraType.caption, color = Ink3, lineHeight = 14.3.sp)
            if (entry.showsAccuracyCaveat) {
                AccuracyCaveatNotice(compact = true, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        if (locked) {
            Text(
                text = "ADDED",
                // iOS: NemaraFont.mono(size: 9, weight: .semibold) — mono 9
                // SemiBold, which is neither monoSmall (10) nor monoMicro (9 Bold).
                style = NemaraType.monoSmall.copy(fontSize = 9.sp),
                letterSpacing = 0.5.sp,
                color = Ink3,
            )
        } else {
            NemaraGlyph(
                symbol = if (selected) "checkmark.circle.fill" else "circle",
                size = 20.dp,
                color = if (selected) Terra else Ink3,
                hole = Card,
            )
        }
    }
}

@Composable
private fun Footer(state: DetectorLibraryState, onSubmit: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.submitError?.let {
            Text(
                text = it,
                style = NemaraType.bodySmall,
                color = Terra,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val inert = state.isSubmitting || state.footerDisabled
        NemaraButton(
            title = state.footerTitle,
            variant = NemaraButtonVariant.Primary,
            block = true,
            enabled = !inert,
            onClick = onSubmit,
            // NemaraButton never dims itself, matching iOS. This 0.5 is the call
            // site's own `.opacity(...)`, which sits alongside `.disabled(...)`.
            modifier = Modifier.alpha(if (inert) 0.5f else 1f),
        )
    }
}

