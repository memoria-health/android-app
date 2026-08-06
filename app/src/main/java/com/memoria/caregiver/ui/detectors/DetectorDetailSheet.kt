package com.memoria.caregiver.ui.detectors

import android.text.format.DateFormat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.plainSelectable
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.CardAlt
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import com.memoria.caregiver.ui.theme.TerraSoft
import com.memoria.caregiver.ui.theme.Warning
import java.time.LocalTime
import com.memoria.caregiver.ui.theme.SELECTABLE_CATEGORIES

/**
 * What a detector actually does, in the caregiver's language, and a way to
 * change it in plain words.
 * Spec: `Features/Patient/Settings/DetectorDetailSheet.swift`, PARITY.md §17.
 *
 * Reads top to bottom as: what you asked for → what Memoria looks for → refine →
 * how it alerts → category → sensitivity → where it runs → when it runs → Save.
 *
 * **The refine box is a dry run.** "Re-generate" previews what the criteria
 * would become and the card wears a `PREVIEW · NOT SAVED` pill; nothing is
 * persisted until Save. The backend re-infers alert trigger, active window and
 * rooms from the change, so the staged pickers are re-synced from the preview
 * response — [StagedEdits.syncedFrom] is that, and skipping it leaves the
 * pickers silently disagreeing with the criteria above them.
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. Every round trip belongs
 * to the host: the two-step Save ([DetectorDetailState.savePlan]), the
 * post-refine poll and the compile poll ([DetectorDetailTiming]) are network
 * loops, and this screen only reports the taps.
 *
 * **No button here logs analytics, and that is deliberate.** Every button on the
 * Swift is a plain SwiftUI `Button` rather than a `NemaraButton`, so none of them
 * are in the funnel on iOS. Routing them through Android's `NemaraButton` — which
 * logs from inside, by design — would add events iOS does not have. Same
 * reasoning as `SupportButton`. The in-flight spinner on Save and Re-generate is
 * the second reason: `NemaraButton` has no slot for one.
 *
 * @param onFeedbackChange the refine box was typed in. The host applies
 *   [DetectorDetailState.withFeedback], which drops any preview on screen —
 *   that preview was compiled from different words.
 * @param onStagedChange a picker moved. The screen builds the next
 *   [StagedEdits] with that type's own transitions rather than the host
 *   re-deriving them.
 * @param onTurnOn the draft banner's "Turn on". iOS PATCHes `is_on` immediately;
 *   it is not a staged edit and does not wait for Save.
 */
@Composable
fun DetectorDetailSheet(
    state: DetectorDetailState,
    modifier: Modifier = Modifier,
    onFeedbackChange: (String) -> Unit,
    onStagedChange: (StagedEdits) -> Unit,
    onRegenerate: () -> Unit,
    onTurnOn: () -> Unit,
    onSave: () -> Unit,
    onDone: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // iOS holds this in `@State` on the view for the same reason: it is about
    // this screen's own presentation and never outlives it. Same as
    // CameraDetailScreen's confirmation dialogs.
    var showSensitivityInfo by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(Paper)) {
        NavigationBar(title = state.detector.name, onDone = onDone)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // iOS also has `.scrollDismissesKeyboard(.interactively)` and a
                // keyboard-toolbar "Done". Compose has no equivalent of either,
                // and Android's own back gesture already dismisses the keyboard,
                // so the platform supplies the affordance rather than the screen
                // growing chrome iOS does not have.
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HeaderRow(state.detector)
            state.error?.let { ErrorBanner(it) }
            if (state.detector.showsAccuracyCaveat) AccuracyCaveatNotice()
            if (state.isCustom) CustomDetectorBetaNotice()
            if (!state.detector.isOn) DraftBanner(onTurnOn)
            if (state.isCustom) AskedForCard(state.detector.customPrompt)
            CriteriaCard(state)
            val refinements = state.detector.refinements
            if (!refinements.isNullOrEmpty()) RefinementsCard(refinements)
            if (state.isCustom) {
                ChangeBox(
                    state = state,
                    onFeedbackChange = onFeedbackChange,
                    // iOS drops the keyboard before it fires the dry run.
                    onRegenerate = { focusManager.clearFocus(); onRegenerate() },
                )
            }
            HowItAlerts(state, onStagedChange)
            CategoryCard(state.staged, onStagedChange)
            SensitivityCard(
                staged = state.staged,
                onStagedChange = onStagedChange,
                onShowInfo = { showSensitivityInfo = true },
            )
            RoomsCard(state, onStagedChange)
            WhenCard(state.staged, onStagedChange)
            SaveBar(state) { focusManager.clearFocus(); onSave() }
        }
    }

    if (showSensitivityInfo) {
        SensitivityInfoPresenter(onDismiss = { showSensitivityInfo = false })
    }
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

/**
 * `.navigationTitle(detector.name)` inline with a trailing "Done".
 *
 * Compose has no navigation bar, so it is drawn the way `TermsGateScreen`,
 * `CountryPickerSheet` and `WanderingDoorPickerScreen` draw theirs
 * (DIFFERENCES.md D9).
 */
@Composable
private fun NavigationBar(title: String, onDone: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest, as the three siblings above do.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
            maxLines = 1,
        )
        Text(
            text = "Done",
            modifier = Modifier.align(Alignment.CenterEnd).plainClickable(onClick = onDone),
            style = NemaraType.bodyLarge,
            color = Ink2,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
private fun HeaderRow(detector: Detector) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(PaperDim),
            contentAlignment = Alignment.Center,
        ) {
            NemaraGlyph(symbol = detector.iconName, size = 17.dp, color = Ink2, hole = PaperDim)
        }
        Text(
            text = detector.description,
            modifier = Modifier.weight(1f),
            style = NemaraType.bodySmall,
            color = Ink2,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(TerraSoft)
            .padding(10.dp),
        style = NemaraType.caption,
        color = Terra,
    )
}

// ---------------------------------------------------------------------------
// Draft banner — create-as-draft
// ---------------------------------------------------------------------------

/**
 * A freshly created detector lands here OFF so the caregiver reviews the
 * compiled criteria and turns it on deliberately.
 */
@Composable
private fun DraftBanner(onTurnOn: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // iOS: NemaraColor.warning.opacity(0.12), composited against the
            // parent rather than applied as a layer alpha so the text stays
            // fully opaque. Same treatment as AccuracyCaveatNotice.
            .background(Warning.copy(alpha = 0.12f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("This detector is off", style = NemaraType.bodySmallStrong, color = Ink)
            Text(
                text = "Review what Memoria will look for, then turn it on.",
                style = NemaraType.caption,
                color = Ink2,
            )
        }
        SheetButton(
            title = "Turn on",
            prominent = true,
            onClick = onTurnOn,
        )
    }
}

// ---------------------------------------------------------------------------
// What you asked for / what Memoria looks for
// ---------------------------------------------------------------------------

@Composable
private fun AskedForCard(customPrompt: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardAlt)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NemaraEyebrow("WHAT YOU ASKED FOR")
        Text(
            text = if (!customPrompt.isNullOrEmpty()) customPrompt else "(none)",
            style = NemaraType.bodySmall,
            color = Ink,
            // iOS: .lineSpacing(2) on 13pt Inter — 13 * 1.21 + 2.
            lineHeight = 17.7.sp,
        )
    }
}

/**
 * The app's one AI-generated surface: terra outline plus a soft glow, so it
 * reads as intelligent and distinct from the plain setting cards below it.
 */
@Composable
private fun CriteriaCard(state: DetectorDetailState) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // iOS: .shadow(color: terra.opacity(0.18), radius: 10, y: 2).
            // Compose's shadow takes an elevation and derives blur, offset and
            // alpha from it, so the elevation is chosen to land near a 10pt blur
            // and the tint carries the terra. An approximation, like
            // SupportButton's — it wants the screenshot pass.
            .shadow(CriteriaGlowElevation, shape, ambientColor = Terra, spotColor = Terra)
            .clip(shape)
            .background(CardAlt)
            .border(1.dp, Terra.copy(alpha = 0.4f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NemaraEyebrow("✦ WHAT MEMORIA LOOKS FOR", color = Terra)
            Spacer(Modifier.weight(1f))
            if (state.previewCriteria != null) PreviewPill()
        }

        val generating = state.generatingLabel
        when {
            generating != null -> GeneratingCriteria(generating)

            state.shownCriteria.isNotEmpty() -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.shownCriteria.forEach { line ->
                    Text(text = line, style = NemaraType.bodySmall, color = Ink, lineHeight = 17.7.sp)
                }
            }

            // Compiled but no bulleted summary yet — a neutral note. Echoing
            // "what you asked for" here read as repetition.
            else -> Text(
                text = "Memoria is finalizing this detector's details. Check back in a moment.",
                style = NemaraType.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = Ink3,
            )
        }
    }
}

/**
 * The dry-run badge.
 *
 * Hand-rolled rather than `NemaraChip`, which is fixed at mono 9 Bold / 0.6
 * tracking / 8 by 3 padding. The Swift asks for mono 8.5 Bold / 0.5 tracking /
 * 7 by 2, and at this size those are visible differences. Same call the sibling
 * `DetectorLibraryScreen` makes for its ADDED badge. The colours ARE the chip's
 * neutral tone, so only the metrics diverge.
 */
@Composable
private fun PreviewPill() {
    Text(
        text = "PREVIEW · NOT SAVED",
        modifier = Modifier
            .clip(CircleShape)
            .background(PaperDim)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = NemaraType.monoMicro.copy(fontSize = 8.5.sp),
        // iOS: .tracking(0.5). Per DECISIONS.md D-008 this is the call site.
        letterSpacing = 0.5.sp,
        color = Ink2,
    )
}

/**
 * Shimmer skeleton plus a pulsing sparkle while criteria are (re)generating —
 * the "AI is thinking" feel instead of a bare spinner.
 *
 * The animated value is read inside `graphicsLayer`'s lambda, not in the
 * composable body, so it invalidates the draw phase only. Reading it in the body
 * would recompose this subtree on every frame for as long as the wait lasts, the
 * hazard `NemaraPulse` documents.
 */
@Composable
private fun GeneratingCriteria(label: String) {
    val transition = rememberInfiniteTransition(label = "criteria")
    // iOS: .easeInOut(duration: 0.8).repeatForever(autoreverses: true), started
    // by flipping one @State flag in .onAppear. An infinite transition is the
    // equivalent of the flag: it starts with the composition and stops with it.
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "criteriaPulse",
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SparklesGlyph(
                size = 12.dp,
                color = Terra,
                // iOS: .opacity(pulse ? 0.4 : 1).
                modifier = Modifier.graphicsLayer { alpha = 1f - 0.6f * pulse.value },
            )
            Text(
                text = label,
                style = NemaraType.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = Ink2,
            )
        }
        ShimmerBarTrailingInsets.forEach { inset ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = inset)
                    .height(10.dp)
                    // iOS: .opacity(pulse ? 0.35 : 0.85).
                    .graphicsLayer { alpha = 0.85f - 0.5f * pulse.value }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Line),
            )
        }
    }
}

@Composable
private fun RefinementsCard(refinements: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("CHANGES YOU'VE ASKED FOR")
        refinements.forEach { refinement ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "•",
                    modifier = Modifier.alignByBaseline(),
                    // The Swift Text carries no font, so it inherits the
                    // environment's body size — 17pt. No sans token is 17.
                    style = NemaraType.bodyLarge,
                    color = Ink3,
                )
                Text(
                    text = refinement,
                    modifier = Modifier.alignByBaseline(),
                    style = NemaraType.caption,
                    color = Ink2,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ask for a change → Re-generate previews
// ---------------------------------------------------------------------------

@Composable
private fun ChangeBox(
    state: DetectorDetailState,
    onFeedbackChange: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("ASK FOR A CHANGE")
        Text(
            text = "Tell Memoria in your own words, e.g. “don't count eating as " +
                "choking”. Preview it, then Save to apply.",
            style = NemaraType.caption,
            color = Ink3,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Card)
                .padding(10.dp),
        ) {
            if (state.feedback.isEmpty()) {
                Text("What should work differently?", style = NemaraType.bodySmall, color = Ink4)
            }
            BasicTextField(
                value = state.feedback,
                onValueChange = onFeedbackChange,
                textStyle = NemaraType.bodySmall.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                // iOS: TextField(axis: .vertical).lineLimit(2...4) — it opens two
                // lines tall and grows to four. Return inserts a line break, so
                // there is no Return-key "Done" here.
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = FeedbackMinHeight),
            )
        }
        SheetButton(
            title = if (state.regenerating) "Re-generating…" else "Re-generate",
            prominent = false,
            block = true,
            busy = state.regenerating,
            enabled = state.canRegenerate,
            onClick = onRegenerate,
        )
    }
}

// ---------------------------------------------------------------------------
// How it alerts
// ---------------------------------------------------------------------------

/**
 * The alert TIMING: a read-only line for the current trigger, plus a discrete
 * picker for sustained custom activities — as it happens, when it's over, or if
 * it lasts too long. Instant and absence detectors have no edge to choose.
 */
@Composable
private fun HowItAlerts(state: DetectorDetailState, onStagedChange: (StagedEdits) -> Unit) {
    val staged = state.staged
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("HOW IT ALERTS")
        Text(
            text = "${staged.alertTrigger.caregiverLabel}. ${staged.alertTrigger.caregiverSub}",
            modifier = Modifier.fillMaxWidth(),
            style = NemaraType.bodySmall,
            color = Ink2,
        )

        if (state.showsTimingChoice) {
            FlowChips {
                listOf(
                    Detector.AlertTrigger.LEADING,
                    Detector.AlertTrigger.TRAILING,
                    Detector.AlertTrigger.DURATION,
                ).forEach { trigger ->
                    Chip(
                        label = trigger.caregiverShort,
                        selected = staged.alertTrigger == trigger,
                        onClick = { onStagedChange(staged.copy(alertTrigger = trigger)) },
                    )
                }
            }
            if (staged.alertTrigger == Detector.AlertTrigger.DURATION) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Alert once it's continued past",
                        style = NemaraType.bodySmall,
                        color = Ink3,
                    )
                    DurationField(
                        durationSeconds = staged.durationSeconds,
                        onInput = { onStagedChange(staged.withDurationInput(it)) },
                    )
                    Text("minutes", style = NemaraType.bodySmall, color = Ink3)
                }
                if (staged.durationSeconds == null) {
                    Text(
                        text = "Set a time so I know when it's gone on too long.",
                        style = NemaraType.caption,
                        color = Warning,
                    )
                }
            }
        }
    }
}

/** Minutes in, seconds out. The clamping lives in [durationSecondsFromInput]. */
@Composable
private fun DurationField(durationSeconds: Int?, onInput: (String) -> Unit) {
    val text = durationMinutesText(durationSeconds)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Card)
            // iOS: .frame(width: 44).padding(6).background(...) — the 44 sizes
            // the field and the padding sits outside it, so the pill is 56 wide.
            .padding(6.dp)
            .width(44.dp),
    ) {
        if (text.isEmpty()) {
            // The Swift TextField carries no font, so it renders at the
            // environment's 17pt. No sans token is 17; bodyLarge is the nearest.
            Text("min", style = NemaraType.bodyLarge, color = Ink4)
        }
        BasicTextField(
            value = text,
            onValueChange = onInput,
            textStyle = NemaraType.bodyLarge.copy(color = Ink),
            cursorBrush = SolidColor(Terra),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(DurationFieldTag),
        )
    }
}

// ---------------------------------------------------------------------------
// Category / sensitivity / rooms / when
// ---------------------------------------------------------------------------

@Composable
private fun CategoryCard(staged: StagedEdits, onStagedChange: (StagedEdits) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("CATEGORY")
        FlowChips {
            SELECTABLE_CATEGORIES.forEach { category ->
                Chip(
                    label = categoryLabel(category),
                    selected = staged.category == category,
                    onClick = { onStagedChange(staged.copy(category = category)) },
                )
            }
        }
    }
}

/**
 * Editable for ANY detector regardless of on/off state. The old inline row
 * picker only appeared when the detector was ON, so an OFF draft could not be
 * tuned before it was turned on.
 */
@Composable
private fun SensitivityCard(
    staged: StagedEdits,
    onStagedChange: (StagedEdits) -> Unit,
    onShowInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraEyebrow("SENSITIVITY")
            InfoCircleGlyph(
                size = 12.dp,
                color = Ink3,
                modifier = Modifier
                    .semantics { contentDescription = "What does sensitivity mean?" }
                    .plainClickable(onClick = onShowInfo),
            )
        }
        FlowChips {
            listOf(
                Detector.Sensitivity.LOW,
                Detector.Sensitivity.STANDARD,
                Detector.Sensitivity.HIGH,
            ).forEach { level ->
                Chip(
                    label = level.displayName,
                    selected = staged.sensitivity == level,
                    onClick = { onStagedChange(staged.copy(sensitivity = level)) },
                )
            }
        }
        Text(text = sensitivityHint(staged.sensitivity), style = NemaraType.caption, color = Ink3)
    }
}

/**
 * Editable for every detector, presets included, so built-ins can be re-scoped
 * to rooms too. What a preset looks FOR stays Memoria's; only the prompt and the
 * refine surface above are custom-only.
 */
@Composable
private fun RoomsCard(state: DetectorDetailState, onStagedChange: (StagedEdits) -> Unit) {
    val staged = state.staged
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("WHERE IT RUNS")
        FlowChips {
            Chip(
                label = "All rooms",
                selected = staged.allRooms,
                onClick = { onStagedChange(staged.withAllRooms()) },
                role = Role.Checkbox,
            )
            state.roomOptions.forEach { room ->
                Chip(
                    label = room,
                    selected = !staged.allRooms && staged.selectedRooms.contains(room),
                    // Deselecting the last room snaps back to All rooms — an
                    // empty scope would mean no camera at all.
                    onClick = { onStagedChange(staged.withRoomToggled(room)) },
                    // Several rooms can be on at once. See Chip's `role`.
                    role = Role.Checkbox,
                )
            }
        }
        if (state.roomOptions.isEmpty()) {
            Text(
                text = "No cameras yet. This runs everywhere once you pair one.",
                style = NemaraType.caption,
                color = Ink3,
            )
        }
    }
}

@Composable
private fun WhenCard(staged: StagedEdits, onStagedChange: (StagedEdits) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NemaraEyebrow("WHEN IT RUNS")
        FlowChips {
            Chip(
                label = "Always",
                selected = staged.whenMode == WhenMode.Always,
                onClick = { onStagedChange(staged.copy(whenMode = WhenMode.Always)) },
            )
            Chip(
                label = "Specific hours",
                selected = staged.whenMode == WhenMode.Specific,
                onClick = { onStagedChange(staged.copy(whenMode = WhenMode.Specific)) },
            )
        }
        if (staged.whenMode == WhenMode.Specific) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WindowTimeField(
                    value = staged.whenStart,
                    onChange = { onStagedChange(staged.copy(whenStart = it)) },
                )
                Text("to", style = NemaraType.bodySmall, color = Ink3)
                WindowTimeField(
                    value = staged.whenEnd,
                    onChange = { onStagedChange(staged.copy(whenEnd = it)) },
                )
            }
        }
    }
}

/**
 * The Android stand-in for SwiftUI's compact `DatePicker(.hourAndMinute)`.
 *
 * iOS renders a tappable time capsule that opens a wheel in place; Compose has
 * no inline equivalent, so this opens the platform time picker, exactly as
 * `AddDetectorScreen`'s absence-window fields do. **Its Cancel and Done labels
 * have no iOS counterpart** — the SwiftUI picker commits as the wheel moves and
 * needs no buttons.
 *
 * The displayed clock follows the device's 12/24-hour setting, as the iOS picker
 * follows the locale. Display only: [DetectorDetailState.computedWhen] always
 * serializes `HH:MM` on a 24-hour clock, which is what the backend stores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowTimeField(value: HourMinute, onChange: (HourMinute) -> Unit) {
    var showing by remember { mutableStateOf(false) }
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val shape = RoundedCornerShape(Radius.sm)

    Text(
        text = if (is24Hour) value.toHHMM() else formatScopeTime(LocalTime.of(value.hour, value.minute)),
        modifier = Modifier
            .clip(shape)
            .background(Card)
            .border(1.dp, Line, shape)
            .plainClickable { showing = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = NemaraType.body,
        color = Ink,
    )

    if (showing) {
        val picker = rememberTimePickerState(
            initialHour = value.hour,
            initialMinute = value.minute,
            is24Hour = is24Hour,
        )
        AlertDialog(
            onDismissRequest = { showing = false },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    showing = false
                    onChange(HourMinute(picker.hour, picker.minute))
                }) {
                    Text("Done", style = NemaraType.bodySmallStrong, color = Terra)
                }
            },
            dismissButton = {
                TextButton(onClick = { showing = false }) {
                    Text("Cancel", style = NemaraType.bodySmall, color = Ink2)
                }
            },
            containerColor = Card,
        )
    }
}

// ---------------------------------------------------------------------------
// Save
// ---------------------------------------------------------------------------

/** The one state button. Inert while saving, while clean, and while invalid. */
@Composable
private fun SaveBar(state: DetectorDetailState, onSave: () -> Unit) {
    SheetButton(
        title = if (state.saving) "Saving…" else "Save",
        prominent = true,
        block = true,
        busy = state.saving,
        enabled = state.canSave,
        verticalPadding = 12.dp,
        onClick = onSave,
    )
}

// ---------------------------------------------------------------------------
// Sensitivity info
// ---------------------------------------------------------------------------

/**
 * `.sheet { SensitivityInfoSheet().presentationDetents([.medium]) }`.
 *
 * `skipPartiallyExpanded = false` is the `.medium` detent: the sheet opens at
 * part height and can be dragged up, which is what that detent set gives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensitivityInfoPresenter(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        // iOS page sheets show no grabber; the close button is the affordance,
        // and swipe-down still dismisses.
        dragHandle = null,
        containerColor = Paper,
    ) {
        SensitivityInfoSheet(onDismiss = onDismiss)
    }
}

/**
 * What Low / Standard / High actually do — the confidence-versus-noise trade-off
 * — so caregivers can tune intentionally. Lives beside the detail sheet now that
 * sensitivity is edited here rather than on the list row.
 *
 * Internal rather than private (the Swift's is file-private) so it can be tested
 * without driving a modal sheet's animation, the same reason `HelpChatSheet` is.
 */
@Composable
internal fun SensitivityInfoSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Paper)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sensitivity", style = NemaraType.title2, color = Ink)
                Text(
                    text = "How sure Memoria needs to be before it alerts you. Higher " +
                        "catches more, but with more false alarms.",
                    style = NemaraType.bodySmall,
                    color = Ink2,
                    // iOS: .lineSpacing(3) on 13pt Inter — 13 * 1.21 + 3.
                    lineHeight = 18.7.sp,
                )
            }

            SensitivityLevel(
                name = "Low",
                tag = "Fewer alerts",
                body = "Only fires on the clearest, most obvious signals. Best when you " +
                    "want to avoid false alarms and only hear about the strongest events, " +
                    "at the cost of missing subtler ones.",
            )
            SensitivityLevel(
                name = "Standard",
                tag = "Recommended",
                body = "A balanced baseline that works well for most rooms and detectors. " +
                    "Start here.",
            )
            SensitivityLevel(
                name = "High",
                tag = "More alerts",
                body = "Fires on weaker or more ambiguous signals, so it catches subtle " +
                    "events others miss, but expect more false alarms to review.",
            )

            Text(
                text = "You can change this anytime per detector; it only affects this sensor.",
                modifier = Modifier.padding(top = 2.dp),
                style = NemaraType.caption,
                color = Ink3,
            )
        }
        NemaraGlyph(
            symbol = "xmark.circle.fill",
            size = 26.dp,
            color = Ink3,
            hole = Paper,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                // The Swift sets no accessibility label, because SwiftUI derives
                // one from the SF Symbol's name for free. A Canvas drawing has
                // no name to derive from, so this restores what iOS already
                // gives a screen reader rather than adding copy of its own.
                .semantics { contentDescription = "Close" }
                .plainClickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun SensitivityLevel(name: String, tag: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = NemaraType.bodySemibold, color = Ink)
            Text(
                text = tag.uppercase(),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(TerraSoft)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                // iOS: NemaraFont.mono(size: 9, weight: .semibold) — mono 9
                // SemiBold, which is neither monoSmall (10) nor monoMicro (9 Bold).
                style = NemaraType.monoSmall.copy(fontSize = 9.sp),
                letterSpacing = 0.5.sp,
                color = Terra,
            )
        }
        Text(text = body, style = NemaraType.bodySmall, color = Ink2, lineHeight = 18.7.sp)
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/**
 * A selectable pill. Selected chips are solid ink, matching the web dashboard's
 * selected settings pills.
 */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    /**
     * `RadioButton` for the exclusive sets — category, sensitivity, trigger,
     * when — and `Checkbox` for the rooms, where several can be on at once.
     * Announcing rooms as radio buttons would tell a caregiver that picking a
     * second room drops the first, which is the opposite of what happens.
     */
    role: Role = Role.RadioButton,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Ink else PaperDim)
            .border(1.dp, if (selected) Ink else Color.Transparent, CircleShape)
            // Selectable, not clickable: solid ink is the ONLY thing that marks
            // a chosen chip, so a screen reader that only hears "button" hears
            // nothing about the setting's actual value.
            .plainSelectable(selected = selected, role = role, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        // iOS: NemaraFont.bodySmall.weight(.medium). No token is 13 Medium —
        // bodyMedium is 14 — so the weight is applied to bodySmall here.
        style = NemaraType.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = if (selected) Paper else Ink2,
    )
}

/**
 * The three plain SwiftUI buttons this sheet uses: `.borderedProminent` tinted
 * terra (Turn on, Save) and `.bordered` tinted ink2 (Re-generate).
 *
 * Not `NemaraButton`, for two reasons, both of them parity. It has no slot for
 * the in-flight spinner these carry, and it logs an analytics event from inside
 * — which the Swift's plain buttons do not, so routing through it would add
 * events iOS does not have. See this file's header.
 *
 * Unlike `NemaraButton`, this DOES dim while disabled: SwiftUI's own button
 * styles dim automatically, so the alpha carries iOS behavior rather than adding
 * polish. Same measured value `WanderingDoorPickerScreen` uses for its disabled
 * toolbar button.
 */
@Composable
private fun SheetButton(
    title: String,
    prominent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    block: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    verticalPadding: Dp = 10.dp,
) {
    val shape = RoundedCornerShape(Radius.md)
    val background = if (prominent) Terra else Ink2.copy(alpha = 0.12f)
    val foreground = if (prominent) TerraInk else Ink2
    Row(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .alpha(if (enabled) 1f else DisabledButtonAlpha)
            .clip(shape)
            .background(background)
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = foreground,
                strokeWidth = 2.dp,
            )
        }
        Text(text = title, style = NemaraType.bodySmallStrong, color = foreground)
    }
}

/**
 * Minimal wrapping chip layout, so chips flow to the next line instead of
 * clipping. A direct port of the Swift's private `FlowChips: Layout` — children
 * are measured unconstrained (`sizeThatFits(.unspecified)`), packed left to
 * right, and wrapped when the next one would overrun.
 *
 * Written out rather than reached for from `FlowRow` because the Swift is itself
 * a hand-written layout with these exact rules, and this way the two can be read
 * against each other.
 */
@Composable
private fun FlowChips(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val bound = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }

        var x = 0
        var y = 0
        var rowHeight = 0
        val offsets = ArrayList<IntOffset>(placeables.size)
        for (placeable in placeables) {
            if (x + placeable.width > bound && x > 0) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            offsets += IntOffset(x, y)
            x += placeable.width + gap
            rowHeight = maxOf(rowHeight, placeable.height)
        }

        // Swift: `maxWidth == .infinity ? x : maxWidth`. An unbounded proposal
        // shrinks to the packed row instead of claiming infinite width.
        val width = if (constraints.hasBoundedWidth) bound else x
        layout(width.coerceAtLeast(0), y + rowHeight) {
            placeables.forEachIndexed { index, placeable -> placeable.place(offsets[index]) }
        }
    }
}

// ---------------------------------------------------------------------------
// Two glyphs this screen needs that DetectorGlyphs does not carry
// ---------------------------------------------------------------------------
//
// `sparkles` and `info.circle` belong in `DetectorGlyphs.kt` beside the rest of
// the Canvas-drawn SF Symbol stand-ins, and should move there when that file is
// next touched. They are here because this unit does not own it. Same approach
// as everything in that file: simplified line marks that read at 12dp, not
// redraws of Apple's artwork.

/** SF `sparkles` — a large four-point star with a small one at its shoulder. */
@Composable
private fun SparklesGlyph(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        fun star(cx: Float, cy: Float, r: Float, waist: Float) {
            val path = Path().apply {
                moveTo(w * cx, w * (cy - r))
                quadraticTo(w * cx, w * cy, w * (cx + r), w * cy)
                quadraticTo(w * cx, w * cy, w * cx, w * (cy + r))
                quadraticTo(w * cx, w * cy, w * (cx - r), w * cy)
                quadraticTo(w * cx, w * cy, w * cx, w * (cy - r))
                close()
            }
            drawPath(path, color)
            // The waist dot keeps the arms from pinching away at small sizes.
            drawCircle(color, w * waist, Offset(w * cx, w * cy))
        }
        star(0.40f, 0.44f, 0.40f, 0.05f)
        star(0.80f, 0.80f, 0.20f, 0.025f)
    }
}

/** SF `info.circle` — a stroked disc with a dot over a stem. */
@Composable
private fun InfoCircleGlyph(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.12f
        drawCircle(
            color = color,
            radius = w * 0.5f - stroke / 2f,
            center = Offset(w * 0.5f, w * 0.5f),
            style = Stroke(stroke),
        )
        drawCircle(color, w * 0.07f, Offset(w * 0.5f, w * 0.26f))
        drawLine(
            color = color,
            start = Offset(w * 0.5f, w * 0.44f),
            end = Offset(w * 0.5f, w * 0.76f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * iOS: `.shadow(radius: 10)`. Compose derives blur from elevation, so this is
 * scaled off `SupportButton`'s measured pairing of a 6dp elevation to an 8pt
 * blur radius.
 */
private val CriteriaGlowElevation = 7.dp

/**
 * SwiftUI's own dimming for a disabled control. The framework does not publish
 * the value; 0.3 is the measured one, so this approximates a platform default
 * rather than carrying a constant from the Swift. Same as
 * `WanderingDoorPickerScreen`'s disabled toolbar button.
 */
private const val DisabledButtonAlpha = 0.3f

/**
 * Two lines of 13sp Inter, which is `lineLimit(2...4)`'s lower bound. The cut's
 * line height is ~1.21x the size, so 2 * 13 * 1.21, rounded up.
 */
private val FeedbackMinHeight = 32.dp

/** iOS: `.padding(.trailing, [40, 130, 80][i])` on the three shimmer bars. */
private val ShimmerBarTrailingInsets = listOf(40.dp, 130.dp, 80.dp)

/** Lets a test reach the duration input, whose placeholder disappears once set. */
internal const val DurationFieldTag = "detector-detail-duration"
