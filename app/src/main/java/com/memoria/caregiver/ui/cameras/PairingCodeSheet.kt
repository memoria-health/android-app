@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.cameras

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import kotlin.time.ExperimentalTime

/**
 * The 6-digit pairing code for one wall phone. Spec:
 * `Features/Patient/Settings/PairingCodeSheet.swift`.
 *
 * iOS presents it from two places with two different loaders:
 *
 * - **create** — first-time pairing, from "Pair a new phone". Inserts a new
 *   `cameras` row carrying the code.
 * - **regenerate** — a re-mint on an EXISTING unpaired camera, from
 *   `CameraDetailScreen`'s "Show pairing code", for when the original sheet was
 *   dismissed before the code was entered on a wall phone.
 *
 * Both return the same response and render exactly this UI, so the split is the
 * host's: this screen takes the outcome ([PairingCodeState.phase]) and nothing
 * about which endpoint produced it.
 *
 * Pure state-in / callbacks-out, per `AuthUiState.kt`. No screen event is logged
 * here — the Swift attaches `.trackScreen("pairing_code")` where the sheet is
 * presented, so the event belongs to the presenter, as `CameraDetailScreen`
 * records.
 *
 * Unlike the add-sensor sheet, this one does NOT close itself when the phone
 * pairs. That is iOS's behavior and it is deliberate: the caregiver reached
 * here from a camera that already exists, so there is nothing to hand them
 * afterwards, and the instructions below say the camera will simply appear.
 */
@Composable
fun PairingCodeSheet(
    state: PairingCodeState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().background(Paper),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The Swift draws its own capsule; hosts present this with the platform
        // drag handle suppressed. See AddCameraScreen's SheetGrabber.
        Box(
            Modifier
                .padding(top = Spacing.xs)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Line),
        )

        Column(
            modifier = Modifier.padding(top = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NemaraEyebrow("PAIR A NEW PHONE")
            Text(text = "for ${state.room}", style = NemaraType.title3, color = Ink)
        }

        // iOS: Spacer(minLength: 12) either side, so the code sits optically
        // centred between the header and the instructions.
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            PairingCodeContent(state.phase)
        }

        Spacer(Modifier.weight(1f))

        Instructions()

        NemaraButton(
            title = "Close",
            variant = NemaraButtonVariant.Secondary,
            size = NemaraButtonSize.Lg,
            block = true,
            modifier = Modifier
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            onClick = onClose,
        )
    }
}

@Composable
private fun PairingCodeContent(phase: PairingCodePhase) {
    when (phase) {
        // iOS: ProgressView().scaleEffect(1.4).tint(ink2). Compose's default
        // indicator is already that size; only the tint is carried.
        PairingCodePhase.Loading -> CircularProgressIndicator(color = Ink2)

        is PairingCodePhase.Ready -> {
            val now = rememberCountdownClock(running = true)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = phase.code,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    style = PairingCodeTextStyle,
                    // Per DECISIONS.md D-008 this is the call site.
                    letterSpacing = PairingCodeTracking,
                    color = Ink,
                )
                Text(
                    text = countdownText(phase.expiresAt, now, PairingCodeExpiredText),
                    style = NemaraType.caption,
                    color = Ink3,
                )
            }
        }

        is PairingCodePhase.Error -> Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Same stroke-weight note as AddCameraScreen's error view: iOS asks
            // for .light at 36pt and SensorSymbol has two fixed weights.
            SensorSymbol(name = "exclamationmark.triangle", size = 36.dp, color = Terra)
            Text(
                text = phase.message,
                modifier = Modifier.padding(horizontal = Spacing.xl),
                style = NemaraType.body,
                color = Ink2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The three steps, numbered as text.
 *
 * The numbers sit in an 18dp leading column so the sentences align, and the
 * rows are baseline-aligned rather than top-aligned — iOS uses
 * `.firstTextBaseline`, and the two sizes (13 semibold against 13 regular)
 * would otherwise sit a hair apart.
 */
@Composable
private fun Instructions() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InstructionRow("1.", "Open MemoriaSensor on the wall phone.")
        InstructionRow("2.", "Enter the code above when prompted.")
        InstructionRow("3.", "The camera will appear here once it's paired.")
    }
}

@Composable
private fun InstructionRow(index: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = index,
            modifier = Modifier.width(18.dp).alignByBaseline(),
            style = NemaraType.bodySmallStrong,
            color = Ink3,
        )
        Text(
            text = text,
            modifier = Modifier.alignByBaseline(),
            style = NemaraType.bodySmall,
            color = Ink2,
        )
    }
}
