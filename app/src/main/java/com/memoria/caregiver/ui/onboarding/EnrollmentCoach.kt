package com.memoria.caregiver.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Terra

/**
 * The guidance text, the no-camera placeholder and the milestone haptics shared
 * by both enrollment dials. Spec: PARITY.md §27 and
 * `Features/Onboarding/Enrollment/EnrollmentCoach.swift`.
 */

/**
 * A fixed instruction plus one debounced caption.
 *
 * Guidance is visual: the dial's pose needle shows direction and how far, and
 * colours itself through the states. So the headline never changes — it cannot
 * jitter — and the caption speaks up only for the states the caregiver has to
 * act on. The caption's dwell filtering happens in the coverage model, which is
 * why [caption] arrives here already settled.
 *
 * The caption's row is always laid out, empty or not, so text appearing does not
 * shove the dial upward.
 */
@Composable
fun EnrollmentGuidanceBanner(
    headline: String,
    caption: String?,
    modifier: Modifier = Modifier,
    /** True when drawn over the live rear-camera video: light on dark. */
    onDark: Boolean = false,
) {
    // iOS: .animation(.easeInOut(duration: 0.2), value: caption)
    val captionAlpha by animateFloatAsState(
        targetValue = if (caption == null) 0f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "enrollment-caption",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (onDark) Color.Black.copy(alpha = 0.4f) else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = headline,
            style = NemaraType.title3,
            fontWeight = FontWeight.SemiBold,
            color = if (onDark) Color.White else Ink,
            textAlign = TextAlign.Center,
        )
        // Captions only appear when the caregiver needs to DO something, so they
        // get attention weight and colour rather than a faint grey footnote.
        Text(
            text = caption ?: " ",
            modifier = Modifier.alpha(captionAlpha),
            style = NemaraType.bodySmallStrong,
            color = if (onDark) Color.White.copy(alpha = 0.95f) else Terra,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Shown in place of the preview when there is no camera feed.
 *
 * The denied state offers a way into system settings, which — paired with the
 * foreground permission re-check the enrollment host does — lets the caregiver
 * grant access and come back to a working camera without restarting the app.
 * Opening settings needs an Intent, so it is reported through [onOpenSettings]
 * rather than done here.
 */
@Composable
fun EnrollmentPlaceholder(
    authorized: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !authorized -> {
                // The lens is knocked out to the surface behind the glyph, which
                // is Card in both dials' placeholders.
                CameraFillGlyph(size = 30.dp, color = Ink3, knockout = Card)
                Text("Camera access needed", style = NemaraType.bodySmall, color = Ink3)
                Text(
                    text = "Open Settings",
                    modifier = Modifier.plainClickable(onClick = onOpenSettings),
                    style = NemaraType.bodySmallStrong,
                    color = Terra,
                )
            }

            error != null -> Text(
                text = error,
                modifier = Modifier.padding(24.dp),
                style = NemaraType.caption,
                color = Terra,
                textAlign = TextAlign.Center,
            )

            else -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Terra,
                strokeWidth = 2.dp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Milestone haptics
// ---------------------------------------------------------------------------

/**
 * The two enrollment cues.
 *
 * Kept out of the coverage model — as on iOS — so the model stays headless and
 * testable, and kept out of the camera so a frame callback never touches the
 * vibrator.
 */
enum class EnrollmentHaptic { ArcCaptured, Completed }

/**
 * Which cue, if any, a change in the coverage counts should fire.
 *
 * **The arc buzz is suppressed on the capture that also completes**, so
 * completion is a single clean cue rather than a buzz followed instantly by a
 * second one. On iOS that falls out of the property being updated before the
 * change handler runs; here it is stated outright.
 */
fun enrollmentHaptic(
    previousCovered: Int,
    covered: Int,
    previousFrontal: Int,
    frontal: Int,
    wasComplete: Boolean,
    isComplete: Boolean,
): EnrollmentHaptic? = when {
    isComplete && !wasComplete -> EnrollmentHaptic.Completed
    isComplete -> null
    covered > previousCovered || frontal > previousFrontal -> EnrollmentHaptic.ArcCaptured
    else -> null
}

/**
 * Fires [enrollmentHaptic]'s verdict as the counts change.
 *
 * Mapping: iOS's medium impact for an arc becomes a discrete tick, and its
 * success notification becomes Confirm. Android has no impact-strength API, so
 * the pairing is by intent — one is "that registered", the other is "you're
 * done".
 */
@Composable
fun EnrollmentMilestoneHaptics(coveredCount: Int, frontalCount: Int, isComplete: Boolean) {
    val haptics = LocalHapticFeedback.current
    var previousCovered by remember { mutableStateOf(coveredCount) }
    var previousFrontal by remember { mutableStateOf(frontalCount) }
    var wasComplete by remember { mutableStateOf(isComplete) }

    LaunchedEffect(coveredCount, frontalCount, isComplete) {
        val cue = enrollmentHaptic(
            previousCovered = previousCovered,
            covered = coveredCount,
            previousFrontal = previousFrontal,
            frontal = frontalCount,
            wasComplete = wasComplete,
            isComplete = isComplete,
        )
        when (cue) {
            EnrollmentHaptic.ArcCaptured ->
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            EnrollmentHaptic.Completed ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            null -> Unit
        }
        previousCovered = coveredCount
        previousFrontal = frontalCount
        wasComplete = isComplete
    }
}
