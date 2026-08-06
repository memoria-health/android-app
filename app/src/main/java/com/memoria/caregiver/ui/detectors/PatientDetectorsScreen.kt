package com.memoria.caregiver.ui.detectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.ui.cameras.PlusVector
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraChip
import com.memoria.caregiver.ui.components.NemaraChipTone
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing

/**
 * The "Detectors" sub-tab: every detector the patient has, grouped into built-in
 * and custom, each with its on/off toggle, plus the two ways to add another.
 * Spec: `Features/Patient/Detectors/PatientDetectorsView.swift`, FINDINGS.md
 * F-14.
 *
 * **This tab is patient-wide.** The per-camera assignment toggles look similar
 * and are a different thing entirely; they live on `CameraDetailScreen`.
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. Three things iOS does
 * inline are the host's here, because neither the navigation graph nor the
 * network is this file's to own:
 *
 * - the two `.sheet`s at the bottom of the Swift ([onAddCustomDetector] and
 *   [onOpenLibrary]), which present `AddDetectorView` and `DetectorLibraryView`
 *   and carry the `add_detector` / `detector_library` screen events with them,
 *   so whoever presents them owns those events;
 * - the detail sheet, which `DetectorRow.swift` presents from its own `@State`
 *   ([onEditDetector]) — see the note on [onEditDetector] for why no whole-row
 *   tap opens it;
 * - the toggle and the delete, which the Swift row fires straight at
 *   `env.api`.
 *
 * There is deliberately no screen event of its own: `PatientDetectorsView` has
 * no `.trackScreen`, matching `CameraListScreen`.
 *
 * @param onAddCustomDetector "Add a custom detector". iOS:
 *   `showingAddDetector = true`.
 * @param onOpenLibrary "Add from detection library". iOS: `showingLibrary = true`.
 * @param onToggleDetector a row's switch moved, carrying the NEW value. Nothing
 *   here moves optimistically: the switch renders `detector.isOn` from the
 *   client's cache, so a PATCH that fails simply leaves it where it was, which
 *   is iOS's behaviour too.
 * @param onEditDetector open the detail sheet for this detector. **The row has
 *   no whole-row tap on either platform** — the sheet is reached through the
 *   row's overflow menu, "Edit detector", which is what `DetectorRow.swift` sets
 *   `showingDetail` from.
 * @param onDeleteDetector the delete was confirmed in the row's own dialog.
 * @param onLoadDoorConfigs the wandering row asks for every sensor's exit-door
 *   config. One patient-wide fetch with no detector argument, as iOS's
 *   `wanderingDoors(forPatient:)` is.
 */
@Composable
fun PatientDetectorsScreen(
    state: PatientDetectorsState,
    modifier: Modifier = Modifier,
    onAddCustomDetector: () -> Unit,
    onOpenLibrary: () -> Unit,
    onToggleDetector: (Detector, Boolean) -> Unit,
    onEditDetector: (Detector) -> Unit,
    onDeleteDetector: (Detector) -> Unit,
    onLoadDoorConfigs: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            // Inside the scroll, as SwiftUI's padding on the VStack is: it
            // scrolls with the content rather than insetting the viewport.
            .padding(top = Spacing.sm, bottom = ListBottomInset),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        // Both add buttons sit at the TOP, unlike the Cameras tab's single one
        // at the bottom: a patient can have 15+ detectors, and the Swift moved
        // these up so adding another does not mean scrolling past all of them.
        // The BETA marker is stuck ONTO this button rather than sitting on
        // its own line, and repeats on the CUSTOM section header. Per-row it
        // repeated the same thing on every custom detector. iOS matches.
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
            NemaraButton(
                title = "Add a custom detector",
                variant = NemaraButtonVariant.Secondary,
                block = true,
                icon = PlusVector,
                onClick = onAddCustomDetector,
            )
            // Sits ON the top edge, inset from the corner rather than
            // hanging off it. Matches the iOS overlay's (-14, -11).
            BetaBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-14).dp, y = (-11).dp),
            )
        }
        NemaraButton(
            title = "Add from detection library",
            variant = NemaraButtonVariant.Secondary,
            block = true,
            icon = GridVector,
            onClick = onOpenLibrary,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )

        if (state.detectors.isEmpty()) {
            EmptyCard()
        } else {
            DetectorSections(
                state = state,
                onToggleDetector = onToggleDetector,
                onEditDetector = onEditDetector,
                onDeleteDetector = onDeleteDetector,
                onLoadDoorConfigs = onLoadDoorConfigs,
            )
        }
    }
}

/** iOS: `VStack(spacing: 14)`, between Spacing.sm (12) and Spacing.md (16). */
private val CardGap = 14.dp

/**
 * iOS: `.padding(.bottom, 40)` on the VStack — room for the floating tab bar.
 *
 * Smaller than the Cameras tab's 120 because that one hangs its inset off the
 * last button while this one is on the whole stack. Both are the Swift's own
 * numbers.
 */
private val ListBottomInset = 40.dp

/**
 * Caption line height, 15.31sp. Same derivation as `CameraListScreen`'s: Inter
 * 18pt's natural 1.21x on 11pt is 13.31, plus SwiftUI's `.lineSpacing(2)`.
 */
private val CaptionLineHeight = 15.31.sp

/**
 * No detectors at all — which for a real patient means the onboarding library
 * step was skipped, since presets are seeded there.
 *
 * It points at the custom button rather than the library one because describing
 * what to watch for in plain words is the thing a caregiver cannot discover from
 * a catalog.
 */
@Composable
private fun EmptyCard() {
    NemaraCard(
        // As on the Cameras tab: SwiftUI proposes the container width to the
        // card's content and the caption inside wraps into it, while NemaraCard
        // sizes to its content, so the width has to be asked for.
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        padding = 18.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "No detectors yet", style = NemaraType.bodySemibold, color = Ink)
            Text(
                text = "Add a custom detector to describe in plain language what " +
                    "Memoria should watch for.",
                style = NemaraType.caption,
                color = Ink3,
                lineHeight = CaptionLineHeight,
            )
        }
    }
}

@Composable
private fun DetectorSections(
    state: PatientDetectorsState,
    onToggleDetector: (Detector, Boolean) -> Unit,
    onEditDetector: (Detector) -> Unit,
    onDeleteDetector: (Detector) -> Unit,
    onLoadDoorConfigs: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            // iOS: `.lastTextBaseline`. The eyebrow opts in; the chip has its own
            // vertical padding and reports no baseline, so it falls back to
            // Bottom — the same fallback SwiftUI makes, per RosterLargeTitle.
            verticalAlignment = Alignment.Bottom,
        ) {
            NemaraEyebrow(text = "DETECTORS", modifier = Modifier.alignBy(LastBaseline))
            Spacer(Modifier.weight(1f))
            NemaraChip(text = state.activeCountLabel, tone = NemaraChipTone.Action)
        }

        DetectorSection(
            title = "BUILT-IN",
            rows = state.presets,
            emptyHint = "No built-in detectors yet.",
            state = state,
            onToggleDetector = onToggleDetector,
            onEditDetector = onEditDetector,
            onDeleteDetector = onDeleteDetector,
            onLoadDoorConfigs = onLoadDoorConfigs,
        )
        DetectorSection(
            title = "CUSTOM",
            showsBeta = true,
            rows = state.customs,
            emptyHint = "None yet. Add a custom detector with the button above.",
            state = state,
            onToggleDetector = onToggleDetector,
            onEditDetector = onEditDetector,
            onDeleteDetector = onDeleteDetector,
            onLoadDoorConfigs = onLoadDoorConfigs,
        )
    }
}

/**
 * One grouped section. An empty section says so rather than vanishing, so a
 * caregiver with no custom detectors still learns the group exists and how to
 * fill it.
 *
 * iOS also marks the first row of the first section with
 * `.tutorialTarget(.firstDetector)` for the welcome tour's "this is a detector"
 * pause. Not ported: `TutorialManager` has no Android counterpart at all, and a
 * marker with nothing reading it would be dead weight. Same call as the other
 * `.tutorialTarget` sites (see `NewPatientScreen.kt`).
 */
@Composable
private fun DetectorSection(
    title: String,
    rows: List<Detector>,
    showsBeta: Boolean = false,
    emptyHint: String,
    state: PatientDetectorsState,
    onToggleDetector: (Detector, Boolean) -> Unit,
    onEditDetector: (Detector) -> Unit,
    onDeleteDetector: (Detector) -> Unit,
    onLoadDoorConfigs: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraEyebrow(text = title)
            if (showsBeta) NemaraChip(text = "BETA", tone = NemaraChipTone.Terra)
        }

        if (rows.isEmpty()) {
            NemaraCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                padding = 14.dp,
            ) {
                Text(text = emptyHint, style = NemaraType.caption, color = Ink3)
            }
        } else {
            NemaraCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                padding = 0.dp,
            ) {
                rows.forEachIndexed { index, detector ->
                    DetectorRow(
                        state = state.rowState(detector, isLast = index == rows.size - 1),
                        onToggleOn = { onToggleDetector(detector, it) },
                        onEdit = { onEditDetector(detector) },
                        onDelete = { onDeleteDetector(detector) },
                        onLoadDoorConfigs = onLoadDoorConfigs,
                    )
                }
            }
        }
    }
}

/**
 * `square.grid.2x2`, on the "Add from detection library" button.
 *
 * A vector rather than a `NemaraGlyph`, because `NemaraButton` takes an
 * `ImageVector` for its leading icon and the Canvas glyphs cannot be one — the
 * same reason `SensorSymbols.kt` builds `PlusVector` and `QrCodeVector`. The
 * plus itself is imported from there rather than drawn again.
 */
private val GridVector: ImageVector by lazy {
    fun ImageVector.Builder.square(x: Float, y: Float) {
        path(fill = SolidColor(Color.White)) {
            moveTo(x, y); lineTo(x + 8f, y); lineTo(x + 8f, y + 8f); lineTo(x, y + 8f); close()
        }
    }
    ImageVector.Builder(
        name = "square.grid.2x2",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        square(3f, 3f)
        square(13f, 3f)
        square(3f, 13f)
        square(13f, 13f)
    }.build()
}
