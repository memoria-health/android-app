@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.analytics.LocalAnalytics
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.CardAlt
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
import com.memoria.caregiver.ui.theme.TerraSoft
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Add a new sensor under one patient. Spec:
 * `Features/Patient/Cameras/AddCameraSheet.swift`.
 *
 * A segmented picker over the two device classes, both branded "Memoria
 * Sensor":
 *
 * - **Phone** — a spare handset running the MemoriaSensor app. The caregiver
 *   names a room, picks an icon, taps Generate code, and the sheet shows a
 *   6-digit code with a live countdown. The placeholder camera row appears in
 *   the patient's list immediately (the generate call reloads cameras), and
 *   Realtime flips it paired when the wall phone enters the code.
 * - **Memoria™ Sensor** — the purpose-built Raspberry Pi hardware, set up by a
 *   linear 4-step wizard.
 *
 * **The wizard is not in this unit.** Its step 2 joins the sensor's setup Wi-Fi
 * and leans on iOS auto-presenting a captive portal, which Android has no
 * equivalent for; that interaction has to be designed, not translated
 * (QUESTIONS.md Q5, DIFFERENCES.md D6). Where the Swift mounts
 * `SensorSetupWizard`, this screen renders the [sensorSetup] slot and stops.
 * The slot is a composable rather than a "go to the wizard" callback because
 * iOS mounts the wizard *inside* this sheet — routing away from it here would
 * invent navigation the Swift does not have.
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. Both round trips —
 * `POST /v1/patients/{id}/cameras/pairing-code` and the camera-cache watch that
 * detects pairing — belong to the host.
 *
 * No screen event is logged for the sheet itself: the Swift attaches
 * `.trackScreen("add_camera")` where the sheet is *presented*, so that event
 * belongs to whoever presents it — the same split `CameraListScreen` documents.
 * `sensor_setup` IS logged here, because the Swift attaches it inside this file.
 *
 * The welcome-tour plumbing in the Swift — the 1s room-name settle debounce,
 * the three `.tutorialTarget` markers, `TutorialManager.notePairingCode` and
 * `.tutorialOverlay(.addCameraSheet)` — is not ported. It is annotation with no
 * behavior ("Tour-only plumbing; no behavior"), and there is no tour on Android
 * to receive it.
 *
 * @param onGenerate mint a code for [AddCameraState.room] and
 *   [AddCameraState.iconName]. Guarded on [AddCameraState.canGenerate]; the host
 *   must send the TRIMMED room, as `generate()` does.
 * @param onRetry "Try again" after a failed request. iOS sets `state =
 *   .composing`, keeping the typed room and chosen icon.
 * @param onDone dismiss the sheet. Fires from the Done button and, on its own,
 *   the moment this sheet's own camera pairs.
 * @param sensorSetup the Pi wizard's slot. See above.
 */
@Composable
fun AddCameraScreen(
    state: AddCameraState,
    modifier: Modifier = Modifier,
    onTabChange: (AddCameraTab) -> Unit,
    onRoomChange: (String) -> Unit,
    onIconChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    sensorSetup: @Composable () -> Unit,
) {
    val analytics = LocalAnalytics.current
    // iOS: `.trackScreen("sensor_setup")` on the wizard, which is mounted and
    // unmounted by the picker — so the event fires each time the tab is chosen,
    // not once per sheet.
    LaunchedEffect(state.tab) {
        if (state.tab == AddCameraTab.Sensor) analytics.logScreen("sensor_setup")
    }

    Column(
        modifier = modifier.fillMaxSize().background(Paper),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetGrabber()
        NemaraEyebrow("ADD A SENSOR")
        SourceTabPicker(
            selected = state.tab,
            onSelect = onTabChange,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        when (state.tab) {
            AddCameraTab.Phone -> PhoneCameraTab(
                state = state,
                onRoomChange = onRoomChange,
                onIconChange = onIconChange,
                onGenerate = onGenerate,
                onRetry = onRetry,
                onDone = onDone,
            )
            AddCameraTab.Sensor -> sensorSetup()
        }
        // iOS: Spacer(minLength: 0) — hold everything to the top of the sheet.
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The 36x4 capsule at the top of both sheets.
 *
 * Drawn rather than left to the host's bottom-sheet chrome: the Swift puts an
 * explicit `Capsule()` in the content of both files, and Android's
 * `ModalBottomSheet` drag handle is a different shape in a different color.
 * Hosts present these screens with `dragHandle = null`, as `CountryPicker` and
 * `SupportButton` already do.
 */
@Composable
private fun SheetGrabber() {
    Box(
        Modifier
            .padding(top = Spacing.xs)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(Line),
    )
}

/**
 * iOS: `Picker("Sensor type", selection:).pickerStyle(.segmented)`.
 *
 * Hand-built, with the same geometry as `TermsGateScreen`'s document picker —
 * a 2dp track inset and a `Radius.sm` thumb inside a `Radius.md` track — for the
 * reason recorded there: the segmented control is system chrome on iOS, and
 * material3's `SegmentedButton` brings its own shape, border and color roles.
 * The two are deliberately identical so the app has one segmented control, not
 * two that drift.
 */
@Composable
private fun SourceTabPicker(
    selected: AddCameraTab,
    onSelect: (AddCameraTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(PaperDim)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (tab in AddCameraTab.entries) {
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (isSelected) Card else Color.Transparent)
                    .plainClickable { onSelect(tab) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    // UISegmentedControl draws the selected segment's label
                    // semibold and the rest regular, both at 13.
                    style = if (isSelected) NemaraType.bodySmallStrong else NemaraType.bodySmall,
                    color = if (isSelected) Ink else Ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Phone tab
// ---------------------------------------------------------------------------

/**
 * iOS: the private `PhoneCameraTab`. Four exclusive states, one 16dp gutter.
 */
@Composable
private fun PhoneCameraTab(
    state: AddCameraState,
    onRoomChange: (String) -> Unit,
    onIconChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    // Auto-dismiss the moment the wall phone finishes pairing, so the caregiver
    // does not have to tap Done. Scoped to the camera this sheet created — see
    // AddCameraState.generatedCameraPaired — so an unrelated camera's update can
    // never close it.
    LaunchedEffect(state.generatedCameraPaired) {
        if (state.generatedCameraPaired) onDone()
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when (val phase = state.phase) {
            PhonePairingPhase.Composing -> ComposeForm(
                state = state,
                onRoomChange = onRoomChange,
                onIconChange = onIconChange,
                onGenerate = onGenerate,
            )
            PhonePairingPhase.Generating -> GeneratingSpinner()
            is PhonePairingPhase.Showing -> CodeDisplay(
                room = state.room,
                code = phase.code,
                expiresAt = phase.expiresAt,
                onDone = onDone,
            )
            is PhonePairingPhase.Error -> ErrorView(message = phase.message, onRetry = onRetry)
        }
    }
}

@Composable
private fun ComposeForm(
    state: AddCameraState,
    onRoomChange: (String) -> Unit,
    onIconChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // "Which one do I pick?" — Phone is a spare handset running the Memoria
        // Sensor app; the dedicated hardware is the other tab.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(TerraSoft)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.Top,
        ) {
            PhoneGlyph(size = 13.dp, color = Terra)
            Text(
                text = "Use this to pair a spare iPhone that has the Memoria " +
                    "Sensor app installed.",
                style = NemaraType.caption,
                color = Ink2,
                // iOS: .lineSpacing(2). Same derivation as CameraListScreen's.
                lineHeight = CaptionLineHeight,
            )
        }

        NemaraField(
            label = "Room",
            value = state.room,
            onValueChange = onRoomChange,
            placeholder = "e.g. Living room",
            // iOS: .autocapitalization(.words) — room names are proper nouns
            // ("Living room", "Mum's room") far more often than sentences.
            capitalization = KeyboardCapitalization.Words,
        )

        IconPicker(selection = state.iconName, onSelect = onIconChange)

        NemaraButton(
            title = "Generate code",
            variant = NemaraButtonVariant.Primary,
            size = NemaraButtonSize.Lg,
            block = true,
            icon = QrCodeVector,
            // NemaraButton does not dim when disabled, matching NemaraButton
            // .swift, and iOS applies no opacity here either — the button stays
            // solid and inert until the room has a name.
            enabled = state.canGenerate,
            modifier = Modifier.padding(top = Spacing.xxs),
            onClick = onGenerate,
        )

        Text(
            text = "Open the MemoriaSensor app on your wall phone and enter the " +
                "code that appears.",
            style = NemaraType.caption,
            color = Ink3,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}

/**
 * The six room icons.
 *
 * The selected tile is `cardAlt` under a 1.5dp `ink2` outline; the rest are
 * `paperDim` with no border. The glyph darkens from `ink3` to `ink` as well, so
 * the selection survives for anyone who cannot see the outline.
 */
@Composable
private fun IconPicker(selection: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Icon", style = NemaraType.bodySmallStrong, color = Ink2)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            for (name in AddCameraIconOptions) {
                val isSelected = name == selection
                val background = if (isSelected) CardAlt else PaperDim
                Box(
                    modifier = Modifier
                        .size(width = 52.dp, height = 44.dp)
                        .clip(shape)
                        .background(background)
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, Ink2, shape) else Modifier,
                        )
                        .plainClickable { onSelect(name) }
                        // The tile's only content is a drawn glyph, so without
                        // this it is an unlabeled tap target. iOS gets the SF
                        // Symbol's own name as the accessibility label here,
                        // because the Swift sets no label of its own.
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    SensorSymbol(
                        name = name,
                        size = 20.dp,
                        color = if (isSelected) Ink else Ink3,
                        knockout = background,
                    )
                }
            }
        }
    }
}

/**
 * iOS: `ProgressView().scaleEffect(1.4).tint(ink2)` in a 200pt-tall well.
 *
 * Compose's indicator is 40dp by default — the same order as a 1.4x-scaled
 * `UIActivityIndicator` — so only the tint is carried, as `WanderingDoorPicker`
 * does for its own spinner.
 */
@Composable
private fun GeneratingSpinner() {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Ink2)
    }
}

/**
 * The code, its countdown, and Done.
 *
 * This state survives until the caregiver dismisses the sheet: the placeholder
 * camera row is already in the patient's list, so leaving here does not lose
 * anything, and the code can still be entered on the wall phone afterwards from
 * the camera's own pairing sheet.
 */
@Composable
private fun CodeDisplay(
    room: String,
    code: String,
    expiresAt: Instant,
    onDone: () -> Unit,
) {
    val now = rememberCountdownClock(running = true)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "for $room", style = NemaraType.title3, color = Ink)
        Text(
            text = code,
            style = PairingCodeTextStyle,
            // Per DECISIONS.md D-008 this is the call site. iOS: .tracking(8).
            letterSpacing = PairingCodeTracking,
            color = Ink,
        )
        Text(
            text = countdownText(expiresAt, now, AddCameraExpiredText),
            style = NemaraType.title3,
            color = Ink2,
        )
        NemaraButton(
            title = "Done",
            variant = NemaraButtonVariant.Secondary,
            size = NemaraButtonSize.Lg,
            block = true,
            modifier = Modifier.padding(top = Spacing.xs),
            onClick = onDone,
        )
    }
}

/**
 * The request failed. The API's own sentence, over a Try again that returns to
 * the form with the typed room and chosen icon intact.
 */
@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // iOS asks for .light weight at 36pt; SensorSymbol draws every glyph at
        // one of two fixed stroke ratios, so this one is a step heavier than the
        // Swift. Recorded rather than special-cased: the file that owns the
        // stroke ratios belongs to another unit.
        SensorSymbol(name = "exclamationmark.triangle", size = 36.dp, color = Terra)
        Text(
            text = message,
            style = NemaraType.body,
            color = Ink2,
            textAlign = TextAlign.Center,
        )
        NemaraButton(
            title = "Try again",
            variant = NemaraButtonVariant.Secondary,
            size = NemaraButtonSize.Md,
            onClick = onRetry,
        )
    }
}

/**
 * `iphone`, on the phone-tab hint row.
 *
 * `SensorSymbols.kt` draws `iphone.slash` but not the plain handset, and that
 * file belongs to another unit, so the body is repeated here at the same
 * proportions (the identical rounded rect, without the cancel bar) plus the
 * earpiece slot. It belongs beside the rest of the vocabulary once that file
 * moves to `ui/components`, which its own doc already calls for.
 */
@Composable
private fun PhoneGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.095f
        drawRoundRect(
            color = color,
            topLeft = Offset(0.26f * w, 0.05f * w),
            size = Size(0.48f * w, 0.90f * w),
            cornerRadius = CornerRadius(0.10f * w, 0.10f * w),
            style = Stroke(stroke, join = StrokeJoin.Round),
        )
        drawLine(
            color = color,
            start = Offset(0.44f * w, 0.15f * w),
            end = Offset(0.56f * w, 0.15f * w),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
