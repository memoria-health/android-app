package com.memoria.caregiver.ui.detectors

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.plainSelectable
import com.memoria.caregiver.ui.components.plainToggleable
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
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft
import java.time.LocalTime

/**
 * Two-stage custom-detector creation.
 * Spec: Features/Patient/Settings/AddDetectorView.swift, PARITY.md §17.
 *
 * Stage 1 collects a name, an optional note, and a plain-English prompt. Stage 2
 * reviews what `POST /v1/detectors/infer` deduced — trigger, schedule window for
 * absence detectors, category, icon, and the plain-language plan — all editable,
 * before Create.
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. The two round trips and
 * the create-as-draft hand-off into the detail sheet belong to the host; the
 * bodies it sends are built by [applyingInference] and [toCreateDetectorRequest]
 * so the serialization rules stay in one place.
 *
 * Sensitivity, duration and episode gap are inferred and sent silently. They are
 * not rendered on this screen on either platform — the detail sheet is where
 * they become editable.
 */
@Composable
fun AddDetectorScreen(
    state: AddDetectorState,
    modifier: Modifier = Modifier,
    onNameChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onAlertTriggerChange: (Detector.AlertTrigger) -> Unit,
    onCategoryChange: (Detector.Category) -> Unit,
    onIconChange: (String) -> Unit,
    onCadenceChange: (Detector.ScheduleConfig.Cadence) -> Unit,
    onToggleDay: (Int) -> Unit,
    onWindowStartChange: (LocalTime) -> Unit,
    onWindowEndChange: (LocalTime) -> Unit,
    onDeadlineChange: (LocalTime) -> Unit,
    onGenerate: () -> Unit,
    onCreate: () -> Unit,
    /** "← Edit" — back to stage 1 with everything inferred so far preserved. */
    onBackToEntry: () -> Unit,
    onClose: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier.fillMaxSize().background(Paper)) {
        Header(onClose = onClose)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // iOS: .dismissKeyboardOnTap(). The prompt is multi-line, so
                // Return inserts a line break rather than dismissing, and a tap
                // outside is the way out.
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            state.error?.let { ErrorBanner(it) }
            Intro()
            LabelledTextField(
                label = "NAME",
                value = state.name,
                onValueChange = onNameChange,
                placeholder = "e.g. Medication check",
            )
            LabelledTextField(
                label = "NOTE (OPTIONAL)",
                value = state.note,
                onValueChange = onNoteChange,
                placeholder = "e.g. Mom's afternoon pills",
            )
            PromptInput(value = state.prompt, onValueChange = onPromptChange)
            if (state.stage == AddDetectorStage.Review) {
                InferredSection(
                    state = state,
                    onAlertTriggerChange = onAlertTriggerChange,
                    onCategoryChange = onCategoryChange,
                    onIconChange = onIconChange,
                    onCadenceChange = onCadenceChange,
                    onToggleDay = onToggleDay,
                    onWindowStartChange = onWindowStartChange,
                    onWindowEndChange = onWindowEndChange,
                    onDeadlineChange = onDeadlineChange,
                )
            }
        }

        Footer(
            state = state,
            onGenerate = onGenerate,
            onCreate = onCreate,
            onBackToEntry = onBackToEntry,
        )
    }
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun Header(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NemaraGlyph(
            symbol = "xmark",
            size = 18.dp,
            color = Ink,
            modifier = Modifier
                .plainClickable(onClick = onClose)
                // A drawn xmark. Without a name the only way out of the sheet
                // announces as an unnamed button.
                .semantics { contentDescription = "Close" },
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "New detector",
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        // Balances the close button so the title stays centred.
        NemaraGlyph(symbol = "xmark", size = 18.dp, color = Ink, modifier = Modifier.alpha(0f))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
private fun ErrorBanner(message: String) {
    Text(
        text = message,
        style = NemaraType.caption,
        color = Terra,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(TerraSoft)
            .padding(10.dp),
    )
}

@Composable
private fun Intro() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NemaraEyebrow("CUSTOM DETECTION", color = Terra)
        Text(
            text = "What should Memoria watch for?",
            style = NemaraType.title1,
            // iOS: .kerning(-0.7). Per DECISIONS.md D-008 this is the call site.
            letterSpacing = (-0.7).sp,
            color = Ink,
        )
        Text(
            text = "Describe it in plain words. Memoria figures out the rest — " +
                "including whether it's a live alert or a daily/weekly check.",
            style = NemaraType.bodySmall,
            color = Ink2,
            // iOS: .lineSpacing(2) on 13pt — 13 * 1.21 + 2.
            lineHeight = 17.7.sp,
        )
    }
}

@Composable
private fun Footer(
    state: AddDetectorState,
    onGenerate: () -> Unit,
    onCreate: () -> Unit,
    onBackToEntry: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.stage == AddDetectorStage.Entry) {
            // No .opacity at this call site on iOS, so the button stays at full
            // strength and is simply inert — see NemaraButton's own doc.
            NemaraButton(
                title = "✦ Generate",
                variant = NemaraButtonVariant.Primary,
                size = NemaraButtonSize.Lg,
                block = true,
                enabled = state.canGenerate,
                onClick = onGenerate,
            )
        } else {
            NemaraButton(
                title = "Create detector",
                variant = NemaraButtonVariant.Primary,
                size = NemaraButtonSize.Lg,
                block = true,
                enabled = state.canCreate,
                onClick = onCreate,
            )
            Text(
                text = "← Edit",
                style = NemaraType.bodySmall,
                color = Ink3,
                modifier = Modifier.plainClickable(onClick = onBackToEntry),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Entry inputs
// ---------------------------------------------------------------------------

/**
 * Eyebrow over a single-line field.
 *
 * **Not `NemaraField`**, and iOS does not use its own `NemaraField` here either.
 * The shared component darkens its border from `Line` to `Ink` only once the
 * value is non-empty; this screen's fields are outlined in `Ink` at 1.5 the
 * whole time, empty or not. Routing them through the component would change how
 * an untouched create form looks.
 */
@Composable
private fun LabelledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(Radius.md)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NemaraEyebrow(label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Card)
                .border(1.5.dp, Ink, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = NemaraType.body, color = Ink4)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = NemaraType.body.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PromptInput(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(Radius.md)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NemaraEyebrow("WHAT TO DETECT")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp)
                .clip(shape)
                .background(Card)
                .border(1.5.dp, Ink, shape)
                // Swift pads the TextEditor by 8 and the placeholder by 14,
                // because UIKit's TextEditor carries its own ~5pt text inset.
                // BasicTextField has no such inset, so both sit at 14 here and
                // the placeholder still lands exactly on the first character.
                .padding(14.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = "e.g. check if my mom takes her medication between 11am and 5pm every day",
                    style = NemaraType.body,
                    color = Ink3,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = NemaraType.body.copy(color = Ink),
                cursorBrush = SolidColor(Terra),
                // Multi-line on purpose: Return inserts a line break, so there is
                // no Return-key "Done" here.
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Inferred review
// ---------------------------------------------------------------------------

@Composable
private fun InferredSection(
    state: AddDetectorState,
    onAlertTriggerChange: (Detector.AlertTrigger) -> Unit,
    onCategoryChange: (Detector.Category) -> Unit,
    onIconChange: (String) -> Unit,
    onCadenceChange: (Detector.ScheduleConfig.Cadence) -> Unit,
    onToggleDay: (Int) -> Unit,
    onWindowStartChange: (LocalTime) -> Unit,
    onWindowEndChange: (LocalTime) -> Unit,
    onDeadlineChange: (LocalTime) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(Line))
        NemaraEyebrow("✦ INFERRED — ADJUST ANYTHING", color = Terra)

        // What the detector will actually be configured to do, in the
        // caregiver's language, visible before it exists. Absent when preview
        // composition failed server-side — an empty card would read as "nothing".
        state.plan?.let { plan ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardAlt)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NemaraEyebrow("WHAT THIS WILL DO")
                PlanRow("Watches", plan.watches)
                PlanRow("Alerts", plan.fires)
                PlanRow("Checks", plan.checks)
                if (plan.listens) PlanRow("Sound", "Also listens, not just watches")
            }
        }

        // Sustained activities can pick the edge; instant and absence detectors
        // have exactly one moment, so the choice is replaced by a sentence.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NemaraEyebrow("HOW IT ALERTS")
            if (state.showsTriggerChoice) {
                listOf(
                    Detector.AlertTrigger.LEADING,
                    Detector.AlertTrigger.TRAILING,
                    Detector.AlertTrigger.DURATION,
                ).forEach { trigger ->
                    TriggerCard(
                        trigger = trigger,
                        selected = state.alertTrigger == trigger,
                        onSelect = { onAlertTriggerChange(trigger) },
                    )
                }
            } else {
                Text(
                    text = "${state.alertTrigger.caregiverLabel}. ${state.alertTrigger.caregiverSub}",
                    style = NemaraType.bodySmall,
                    color = Ink2,
                )
            }
        }

        if (state.showsScheduleEditor) {
            ScheduleEditor(
                state = state,
                onCadenceChange = onCadenceChange,
                onToggleDay = onToggleDay,
                onWindowStartChange = onWindowStartChange,
                onWindowEndChange = onWindowEndChange,
                onDeadlineChange = onDeadlineChange,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NemaraEyebrow("CATEGORY")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryCard(
                    label = "Safety",
                    tone = Terra,
                    soft = TerraSoft,
                    selected = state.category == Detector.Category.SAFETY,
                    onSelect = { onCategoryChange(Detector.Category.SAFETY) },
                    modifier = Modifier.weight(1f),
                )
                CategoryCard(
                    label = "Routine",
                    tone = Sky,
                    soft = SkySoft,
                    selected = state.category == Detector.Category.ROUTINE,
                    onSelect = { onCategoryChange(Detector.Category.ROUTINE) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NemaraEyebrow("ICON")
            IconPicker(icon = state.icon, onIconChange = onIconChange)
        }
    }
}

@Composable
private fun PlanRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            // iOS uses .system(size:) here rather than a NemaraFont token. The
            // app's sans IS Inter, and rendering Roboto in a page of Inter would
            // be the visible divergence, so these map onto the matching tokens:
            // 12/semibold and 13/regular.
            style = NemaraType.labelStrong,
            color = Ink3,
            modifier = Modifier.width(56.dp).alignByBaseline(),
        )
        Text(
            text = value,
            style = NemaraType.bodySmall,
            color = Ink,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun TriggerCard(
    trigger: Detector.AlertTrigger,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) TerraSoft else Card)
            .border(1.5.dp, if (selected) Terra else Line, shape)
            // The card draws a filled radio glyph, so it announces as one.
            .plainSelectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NemaraGlyph(
            symbol = if (selected) "largecircle.fill.circle" else "circle",
            // The Swift Image carries no .font, so it inherits the environment's
            // body size — 17pt.
            size = 17.dp,
            color = if (selected) Terra else Ink3,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(trigger.caregiverLabel, style = NemaraType.bodySmallStrong, color = Ink)
            Text(trigger.caregiverSub, style = NemaraType.caption, color = Ink3)
        }
    }
}

@Composable
private fun CategoryCard(
    label: String,
    tone: Color,
    soft: Color,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) soft else Card)
            .border(1.5.dp, if (selected) tone else Line, shape)
            // One of a set of exclusive categories.
            .plainSelectable(selected = selected, onClick = onSelect)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The swatch is always the category's tone; only the label follows the
        // selection.
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(tone))
        Text(label, style = NemaraType.bodySmallStrong, color = if (selected) tone else Ink)
    }
}

@Composable
private fun IconPicker(icon: String, onIconChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.md)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Card)
                .border(1.5.dp, Line, shape)
                .plainClickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraGlyph(symbol = icon, size = 17.dp, color = Ink2)
            // The stored value is the symbol name; the caregiver only ever sees
            // the label.
            Text(detectorIconLabel(icon), style = NemaraType.body, color = Ink)
            Spacer(Modifier.weight(1f))
            NemaraGlyph(symbol = "chevron.up.chevron.down", size = 12.dp, color = Ink3)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DetectorIconOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(detectorIconLabel(option), style = NemaraType.body, color = Ink) },
                    leadingIcon = { NemaraGlyph(option, 16.dp, Ink2) },
                    onClick = { expanded = false; onIconChange(option) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Absence schedule
// ---------------------------------------------------------------------------

@Composable
private fun ScheduleEditor(
    state: AddDetectorState,
    onCadenceChange: (Detector.ScheduleConfig.Cadence) -> Unit,
    onToggleDay: (Int) -> Unit,
    onWindowStartChange: (LocalTime) -> Unit,
    onWindowEndChange: (LocalTime) -> Unit,
    onDeadlineChange: (LocalTime) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(PaperDim)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CadenceSegment(
                label = "Daily",
                selected = state.cadence == Detector.ScheduleConfig.Cadence.DAILY,
                onSelect = { onCadenceChange(Detector.ScheduleConfig.Cadence.DAILY) },
                modifier = Modifier.weight(1f),
            )
            CadenceSegment(
                label = "Weekly",
                selected = state.cadence == Detector.ScheduleConfig.Cadence.WEEKLY,
                onSelect = { onCadenceChange(Detector.ScheduleConfig.Cadence.WEEKLY) },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.cadence == Detector.ScheduleConfig.Cadence.WEEKLY) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NemaraEyebrow("ON DAYS")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WhenScopeDayInitials.forEachIndexed { index, initial ->
                        DayCircle(
                            label = initial,
                            selected = state.days.contains(index),
                            onToggle = { onToggleDay(index) },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NemaraEyebrow("BETWEEN")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimeField(time = state.windowStart, onChange = onWindowStartChange)
                Text("and", style = NemaraType.bodySmall, color = Ink3)
                TimeField(time = state.windowEnd, onChange = onWindowEndChange)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NemaraEyebrow("ALERT IF NOT SEEN BY")
            TimeField(time = state.deadline, onChange = onDeadlineChange)
        }

        if (state.timesUncertain) {
            Text(
                text = "Memoria wasn't sure of the exact times — please confirm them.",
                style = NemaraType.caption,
                color = Terra,
            )
        }
    }
}

@Composable
private fun CadenceSegment(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radius.sm)
    Box(modifier.clip(shape).background(PaperDim).padding(2.dp)) {
        Text(
            text = label,
            style = NemaraType.bodySmallStrong,
            color = if (selected) Ink else Ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) Card else Color.Transparent)
                // A segment of a segmented control announces its selected state.
                .plainSelectable(selected = selected, role = Role.Tab, onClick = onSelect)
                .padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun DayCircle(label: String, selected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (selected) Ink else Card)
            .border(if (selected) 0.dp else 1.dp, Line, CircleShape)
            // Each day is independently on or off, and the only cue is the fill.
            .plainToggleable(checked = selected, onToggle = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = NemaraType.bodySmallStrong, color = if (selected) Paper else Ink2)
    }
}

/**
 * The Android stand-in for SwiftUI's compact `DatePicker`.
 *
 * iOS renders a tappable time field that opens a wheel in place; Compose has no
 * inline equivalent, so this is a field that opens the platform time picker.
 * **Its Cancel and Done labels have no iOS counterpart** — the SwiftUI picker
 * commits as the wheel moves and needs no buttons. They are the minimum chrome a
 * modal picker requires.
 *
 * The displayed clock follows the device's 12/24-hour setting, exactly as the
 * iOS picker follows the locale. That is display only: `formatWhenScope` always
 * serializes AM/PM, because the backend grammar requires it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(time: LocalTime, onChange: (LocalTime) -> Unit) {
    var showing by remember { mutableStateOf(false) }
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val shape = RoundedCornerShape(Radius.sm)

    Text(
        text = if (is24Hour) formatClock24(time) else formatScopeTime(time),
        style = NemaraType.body,
        color = Ink,
        modifier = Modifier
            .clip(shape)
            .background(Card)
            .border(1.dp, Line, shape)
            .plainClickable { showing = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )

    if (showing) {
        val picker = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = is24Hour,
        )
        AlertDialog(
            onDismissRequest = { showing = false },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    showing = false
                    onChange(LocalTime.of(picker.hour, picker.minute))
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

/** `HH:mm`, built by hand so the digits cannot follow a non-ASCII locale. */
private fun formatClock24(time: LocalTime): String {
    val hour = if (time.hour < 10) "0${time.hour}" else "${time.hour}"
    val minute = if (time.minute < 10) "0${time.minute}" else "${time.minute}"
    return "$hour:$minute"
}

