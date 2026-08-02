package com.memoria.caregiver.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraSoft

/**
 * "Would you like to add facial detection?" — shown after the patient is created
 * and the detectors are picked. Spec: `NewPatientView.swift`'s private
 * `FaceDetectionConsentView`.
 *
 * All three options leave the screen. [onMaybeLater] and [onDontAskAgain] both
 * end the flow; the difference is that "Don't ask me again" is recorded per
 * patient (see [FaceConsentPrefs]) for a reminder banner that does not exist
 * yet, exactly as on iOS.
 *
 * The welcome tour's `.tutorialTarget(.faceProceed)` marker is not ported, as
 * elsewhere in this phase: annotation with no behavior.
 */
@Composable
fun FaceDetectionConsentScreen(
    modifier: Modifier = Modifier,
    onProceed: () -> Unit,
    onMaybeLater: () -> Unit,
    onDontAskAgain: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(TerraSoft),
                contentAlignment = Alignment.Center,
            ) {
                FaceIdGlyph(size = 28.dp, color = Terra)
            }
            Text(
                text = "Would you like to add facial detection?",
                style = NemaraType.title1,
                // Per-call-site kerning, per DECISIONS.md D-008. iOS: .kerning(-0.6)
                letterSpacing = (-0.6).sp,
                color = Ink,
            )
            Text(
                text = "Adding facial detection lets Memoria be more accurate, " +
                    "so alerts are more timely and consistent.",
                style = NemaraType.body,
                lineHeight = BodyLineHeightPlus3,
                color = Ink2,
            )
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NemaraButton(
                title = "Proceed",
                variant = NemaraButtonVariant.Primary,
                block = true,
                onClick = onProceed,
            )
            NemaraButton(
                title = "Maybe later",
                variant = NemaraButtonVariant.Secondary,
                block = true,
                onClick = onMaybeLater,
            )
            NemaraButton(
                title = "Don't ask me again",
                variant = NemaraButtonVariant.Ghost,
                block = true,
                onClick = onDontAskAgain,
            )
        }
    }
}

/**
 * Body line height, 19.94sp. Inter's natural line height is 1.2100 x the point
 * size (the derivation `WelcomeScreen.kt` documents), so the body token's 14sp
 * is 16.94, and SwiftUI's `.lineSpacing(3)` makes it 19.94.
 */
private val BodyLineHeightPlus3 = 19.94.sp
