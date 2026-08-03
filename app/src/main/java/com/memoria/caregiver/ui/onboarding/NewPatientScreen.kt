package com.memoria.caregiver.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.components.CountryPicker
import com.memoria.caregiver.ui.components.NemaraAvatar
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraInk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Just page 1" of patient onboarding — the basic profile. Spec:
 * `Features/Onboarding/NewPatientView.swift`.
 *
 * Presented from the roster's `+` and the empty-roster CTA. Pure state-in /
 * callbacks-out: creating the patient, uploading the photo and presenting the
 * post-create enrollment flow are all the host's, because the photo can only be
 * uploaded once `createPatient` has returned an id.
 *
 * The welcome tour's three markers on this screen (`.tutorialTarget(.nameField)`,
 * `.tutorialTarget(.submitPatient)` and the `.tutorialOverlay(.newPatientForm)`
 * host) are not ported here, matching `AddCameraScreen`: they are annotation for
 * a subsystem this phase does not build, with no behavior of their own.
 */
@Composable
fun NewPatientScreen(
    state: NewPatientState,
    modifier: Modifier = Modifier,
    /** Injectable so the picker's year range and default day are testable. */
    today: LocalDate = LocalDate.now(),
    onClose: () -> Unit,
    onFullNameChange: (String) -> Unit,
    onDateOfBirthChange: (LocalDate) -> Unit,
    onGenderChange: (String) -> Unit,
    onRelationChange: (Patient.Relation) -> Unit,
    onCountryChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        Header(onClose = onClose)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Intro()

            NemaraField(
                label = "Name",
                value = state.fullName,
                onValueChange = onFullNameChange,
                placeholder = "Margaret O'Brien",
                capitalization = KeyboardCapitalization.Words,
            )

            DateOfBirthAndGender(
                state = state,
                today = today,
                onDateOfBirthChange = onDateOfBirthChange,
                onGenderChange = onGenderChange,
            )

            RelationshipPicker(selected = state.relation, onSelect = onRelationChange)

            // The pick decides data residency server-side and is immutable once
            // the patient exists.
            Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
                NemaraEyebrow("COUNTRY")
                CountryPicker(code = state.country, onCodeChange = onCountryChange)
            }

            PhotoRow(state = state, onPickPhoto = onPickPhoto)
        }

        Footer(state = state, onSubmit = onSubmit)
    }
}

/** Gap between an eyebrow and the control under it. iOS: `VStack(spacing: 6)`. */
private val LabelGap = 6.dp

/**
 * Intro line height, 17.73sp. Same derivation as `EmptyRosterScreen`'s: Inter's
 * natural line height is 1.2100 x the point size, so the bodySmall token's 13sp
 * is 15.73, and SwiftUI's `.lineSpacing(2)` makes it 17.73. Compose's
 * `lineHeight` is absolute where SwiftUI's only affects gaps between lines, so
 * Compose also distributes about 1sp above the first line.
 */
private val BodySmallLineHeightPlus2 = 17.73.sp

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun Header(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .bottomHairline()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .testTag(NewPatientCloseTestTag)
                .plainClickable(onClick = onClose)
                // A drawn xmark. Without a name the only way out of the sheet
                // announces as an unnamed button.
                .semantics { contentDescription = "Close" },
        ) {
            XmarkGlyph(size = 18.dp, color = Ink)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "New patient",
            style = NemaraType.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-0.2)
            letterSpacing = (-0.2).sp,
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        // Invisible twin, so the title sits centered against the close button.
        Box(Modifier.size(18.dp))
    }
}

@Composable
private fun Intro() {
    Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
        NemaraEyebrow("BASIC PROFILE", color = Terra)
        Text(
            text = "Tell us about your patient.",
            style = NemaraType.title1,
            // iOS: .kerning(-0.8)
            letterSpacing = (-0.8).sp,
            color = Ink,
        )
        Text(
            text = "Just the basics. Their feeds and events appear here " +
                "automatically once you're added as a caregiver.",
            modifier = Modifier.padding(top = 2.dp),
            style = NemaraType.bodySmall,
            lineHeight = BodySmallLineHeightPlus2,
            color = Ink2,
        )
    }
}

// ---------------------------------------------------------------------------
// Date of birth + gender
// ---------------------------------------------------------------------------

@Composable
private fun DateOfBirthAndGender(
    state: NewPatientState,
    today: LocalDate,
    onDateOfBirthChange: (LocalDate) -> Unit,
    onGenderChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The date button carries `.frame(maxWidth: .infinity)` in the Swift and
        // the gender menu does not, so the date column takes the slack.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LabelGap),
        ) {
            NemaraEyebrow("DATE OF BIRTH")
            DateOfBirthField(
                value = state.dateOfBirth,
                today = today,
                onChange = onDateOfBirthChange,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
            NemaraEyebrow("GENDER")
            GenderField(value = state.gender, onChange = onGenderChange)
        }
    }
}

/**
 * Tapping opens the platform date picker.
 *
 * iOS presents a medium-detent sheet holding three wheels — month, day and year
 * — with Cancel and Done. Compose has no wheel picker, so this uses material3's
 * `DatePickerDialog`, the same substitution `DetectorDetailSheet` makes for the
 * time wheels. **Cancel and Done are the Swift's own toolbar labels**, so unlike
 * that case the chrome matches.
 *
 * Two things the wheels needed and a calendar does not: the year range is passed
 * to the picker rather than built as a wheel of years, and the Swift's
 * `clampDOBDay()` — which pulled 31 back to 28 when the month changed — has no
 * counterpart, because a calendar grid cannot offer a day the month lacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateOfBirthField(
    value: LocalDate?,
    today: LocalDate,
    onChange: (LocalDate) -> Unit,
) {
    var showing by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val shape = RoundedCornerShape(Radius.md)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Card)
            .border(1.dp, Line, shape)
            .plainClickable { showing = true }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value?.let { formatDateOfBirth(it, locale) } ?: "Select",
            style = NemaraType.bodyLarge,
            color = if (value == null) Ink3 else Ink,
        )
        Spacer(Modifier.weight(1f))
        CalendarGlyph(size = 13.dp, color = Ink3)
    }

    if (showing) {
        // The Swift seeds its wheels from the current value, falling back to 1
        // January of the year 70 years ago.
        val seed = value ?: defaultDateOfBirth(today)
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = seed.toUtcMillis(),
            yearRange = dateOfBirthYearRange(today),
        )
        DatePickerDialog(
            onDismissRequest = { showing = false },
            confirmButton = {
                TextButton(onClick = {
                    showing = false
                    picker.selectedDateMillis?.let { onChange(it.toUtcLocalDate()) }
                }) {
                    Text("Done", style = NemaraType.bodySmallStrong, color = Terra)
                }
            },
            dismissButton = {
                TextButton(onClick = { showing = false }) {
                    Text("Cancel", style = NemaraType.bodySmall, color = Ink2)
                }
            },
            colors = DatePickerDefaults.colors(containerColor = Card),
        ) {
            DatePicker(
                state = picker,
                title = {
                    Text(
                        text = "Date of birth",
                        modifier = Modifier.padding(start = Spacing.xl, top = Spacing.md),
                        style = NemaraType.bodyLarge,
                        color = Ink,
                    )
                },
            )
        }
    }
}

/** iOS: a `Menu` of the four options. Compose's counterpart is a dropdown. */
@Composable
private fun GenderField(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.md)

    Box {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(Card)
                .border(1.dp, Line, shape)
                .plainClickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.ifEmpty { "Select" },
                style = NemaraType.bodyLarge,
                color = if (value.isEmpty()) Ink3 else Ink,
            )
            Spacer(Modifier.width(Spacing.xs))
            ChevronDownGlyph(size = 12.dp, color = Ink3)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in NewPatientGenders) {
                DropdownMenuItem(
                    text = { Text(option, style = NemaraType.bodySmall, color = Ink) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Relationship
// ---------------------------------------------------------------------------

@Composable
private fun RelationshipPicker(
    selected: Patient.Relation?,
    onSelect: (Patient.Relation) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraEyebrow("YOUR RELATIONSHIP (OPTIONAL)")
        FlowChips(spacing = LabelGap) {
            for (relation in Patient.Relation.entries) {
                val on = relation == selected
                val shape = RoundedCornerShape(Radius.pill)
                Text(
                    text = relation.displayName,
                    modifier = Modifier
                        .clip(shape)
                        .background(if (on) Terra else Color.Transparent)
                        .border(1.dp, if (on) Terra else Line, shape)
                        .plainClickable { onSelect(relation) }
                        .padding(horizontal = 14.dp, vertical = Spacing.xs),
                    style = NemaraType.bodyMedium,
                    color = if (on) TerraInk else Ink2,
                )
            }
        }
    }
}

/**
 * Direct port of the Swift's `FlowLayout`: children are measured unconstrained,
 * packed left to right and wrapped when the next one would overrun. Same
 * approach, and the same reasoning, as `DetectorDetailSheet`'s `FlowChips`.
 */
@Composable
private fun FlowChips(
    modifier: Modifier = Modifier,
    spacing: Dp = Spacing.xs,
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

        val width = if (constraints.hasBoundedWidth) bound else x
        layout(width.coerceAtLeast(0), y + rowHeight) {
            placeables.forEachIndexed { index, placeable -> placeable.place(offsets[index]) }
        }
    }
}

// ---------------------------------------------------------------------------
// Photo
// ---------------------------------------------------------------------------

@Composable
private fun PhotoRow(state: NewPatientState, onPickPhoto: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraEyebrow("A PHOTO (OPTIONAL)")
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val preview = state.photoPreview
            if (preview != null) {
                Image(
                    bitmap = preview,
                    // No name, no identifying detail: this is a patient's face.
                    contentDescription = null,
                    modifier = Modifier.size(AvatarSize).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                NemaraAvatar(
                    letter = state.fullName.firstOrNull()?.toString() ?: "?",
                    size = AvatarSize,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .dashedBorder()
                    .plainClickable(enabled = !state.isSubmitting, onClick = onPickPhoto)
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isLoadingPhoto) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Terra,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = if (preview == null) "+ Upload photo" else "Change photo",
                        style = NemaraType.bodySmall,
                        color = Ink3,
                    )
                }
            }
        }
    }
}

/** iOS: `NemaraAvatar(size: 68)` and a 68pt preview circle. */
private val AvatarSize = 68.dp

/**
 * The upload target's dashed outline: 1.5pt stroke, 4-on 3-off, at the field
 * radius. Drawn rather than `Modifier.border`, which takes no dash.
 */
private fun Modifier.dashedBorder(): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    val radius = CornerRadius(Radius.md.toPx())
    drawRoundRect(
        color = Line,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = radius,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
            ),
        ),
    )
}

// ---------------------------------------------------------------------------
// Footer
// ---------------------------------------------------------------------------

@Composable
private fun Footer(state: NewPatientState, onSubmit: () -> Unit) {
    val ready = state.canSubmit && !state.isSubmitting
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .topHairline()
            .padding(horizontal = 14.dp)
            .padding(top = Spacing.sm, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        state.submitError?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = NemaraType.bodySmall,
                color = Terra,
                textAlign = TextAlign.Center,
            )
        }
        NemaraButton(
            title = if (state.isSubmitting) "Adding…" else "Add patient",
            // iOS dims the button itself; NemaraButton never dims on its own
            // (see its class doc), so the call site does it, as the Swift does.
            modifier = Modifier.alpha(if (ready) 1f else 0.5f),
            variant = NemaraButtonVariant.Primary,
            block = true,
            // Tracked under one name so the label flip to "Adding…" does not
            // split the funnel.
            track = "Add patient",
            enabled = ready,
            onClick = onSubmit,
        )
    }
}

/** Test handle for the close button, which carries no text to select by. */
const val NewPatientCloseTestTag: String = "new_patient.close"

// ---------------------------------------------------------------------------
// Date conversion
// ---------------------------------------------------------------------------
//
// material3's DatePicker speaks epoch milliseconds at UTC midnight, which is the
// same convention `dateOfBirthInstant` uses, so nothing here can shift a birth
// date across a day boundary.

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
