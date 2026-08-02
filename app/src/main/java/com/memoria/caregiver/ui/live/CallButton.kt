package com.memoria.caregiver.ui.live

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.TerraInk

/**
 * The phone-call button overlaid on the live camera tile. Spec:
 * `Features/Patient/Live/CallButton.swift`.
 *
 * Three visual states: idle (near-black, tap to start), connecting (spinner,
 * disabled), active (red, tap to end).
 *
 * LiveKit-agnostic, exactly as the Swift is — the parent owns the call lifecycle
 * and passes in the resolved state and the tap callback, which is what keeps this
 * reusable for a future surface such as calling back from an event detail.
 *
 * Deliberately built from `Modifier.clickable` rather than material3's
 * `IconButton`, for the reason `NemaraButton` records: the material components
 * bring their own container color, ripple shape and disabled dimming, and iOS's
 * `.buttonStyle(.plain)` has none of those. A disabled button on iOS is visually
 * identical and simply inert.
 */
@Composable
internal fun CallButton(
    state: TalkCallState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background = when (state) {
        TalkCallState.IDLE -> Action
        TalkCallState.CONNECTING -> Ink2
        TalkCallState.ACTIVE -> SystemRed
    }
    val label = when (state) {
        TalkCallState.IDLE -> "Start call"
        TalkCallState.CONNECTING -> "Connecting"
        TalkCallState.ACTIVE -> "End call"
    }

    // 1.06x scale ping every ~1.4s while a call is live. Cheap visual
    // reassurance the mic is hot; ignored entirely when idle.
    val scale = activeCallPulseScale(active = state == TalkCallState.ACTIVE)

    Box(
        modifier = modifier
            .size(CallButtonSize)
            // SwiftUI's .scaleEffect leaves layout alone and only grows the
            // drawing, which is what a graphicsLayer does here — the button
            // keeps its 64dp slot through the whole pulse.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // iOS: .shadow(color: ink.opacity(0.25), radius: 8, x: 0, y: 4).
            // Compose's elevation shadow takes a spread rather than a blur
            // radius, so the radius is halved to land on a comparable falloff.
            .shadow(elevation = 4.dp, shape = CircleShape, ambientColor = Ink, spotColor = Ink)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                // `case .connecting: break` on iOS, plus `.disabled(state == .connecting)`.
                enabled = talkButtonAction(state) != TalkButtonAction.NONE,
                onClick = onTap,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TalkCallState.IDLE -> LiveGlyph(
                name = "phone.fill",
                size = 26.dp,
                tint = TerraInk,
                strokeRatio = LiveGlyphSemiboldStroke,
            )
            TalkCallState.CONNECTING -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = TerraInk,
                strokeWidth = 2.dp,
            )
            // iOS uses .white here, not terraInk — the two differ, and on a red
            // fill the pure white is the one that was chosen.
            TalkCallState.ACTIVE -> LiveGlyph(
                name = "phone.down.fill",
                size = 24.dp,
                tint = Color.White,
                strokeRatio = LiveGlyphSemiboldStroke,
            )
        }
    }
}

/** iOS: `private let size: CGFloat = 64`. */
internal val CallButtonSize = 64.dp

/**
 * `Color(.systemRed)`. iOS reads it from UIKit rather than from the palette, so
 * it is not a Nemara token; the light-mode sRGB value is used, since the app is
 * light-mode only.
 *
 * Used in two places on this screen: this button and the ON AIR pill.
 */
internal val SystemRed = Color(0xFFFF3B30)

/** iOS `_ActiveCallPulse`: `.easeInOut(duration: 0.7).repeatForever(autoreverses: true)`. */
@Composable
private fun activeCallPulseScale(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "activeCall")
    val scale = transition.animateFloat(
        initialValue = 1f,
        targetValue = ActiveCallPulseScale,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ActiveCallPulseDurationMillis),
            // autoreverses: true — the button breathes rather than snapping back.
            repeatMode = RepeatMode.Reverse,
        ),
        label = "activeCallScale",
    )
    return scale.value
}

/** iOS: `.scaleEffect(active && on ? 1.06 : 1.0)`. */
internal const val ActiveCallPulseScale = 1.06f

/** iOS: `.easeInOut(duration: 0.7)`, autoreversing, so a full cycle is ~1.4s. */
internal const val ActiveCallPulseDurationMillis = 700
