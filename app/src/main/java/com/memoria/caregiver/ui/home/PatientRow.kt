package com.memoria.caregiver.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.DetectionEvent
import com.memoria.caregiver.data.model.Patient
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraCardTone
import com.memoria.caregiver.ui.components.NemaraPulse
import com.memoria.caregiver.ui.components.PatientAvatar
import com.memoria.caregiver.ui.theme.FrauncesFamily
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra

/**
 * The patient-level pause flag (`cameras_paused`) no longer gates detection or
 * display — those follow each camera's own state — so the roster no longer
 * paints a misleading "Monitoring paused" treatment for it. Pinned to `false`
 * so the styling branches below collapse to the normal state without
 * restructuring the row.
 *
 * (Comment and value both carried from `PatientRow.swift:14-18`. PARITY.md §15
 * says to port the pinned-false branch as-is, so the paused branches stay in
 * the file: they are how the treatment comes back if the flag is ever rewired,
 * and deleting them would make the two files stop matching line for line.)
 *
 * A top-level `val` rather than a `const val` on purpose — a `const` would let
 * the compiler fold every branch away and warn about each one, which is noise
 * for code that is deliberately dormant.
 */
private val PATIENT_ROW_IS_PAUSED: Boolean = false

/** iOS: `NemaraCard(padding: 14)`. Not a spacing token — 14 sits between md and lg. */
private val RowCardPadding = 14.dp

/** iOS: `PatientAvatar(patient: patient, size: 48)`. */
private val RowAvatarSize = 48.dp

/** iOS: `.opacity(isPaused ? 0.55 : 1)` on the avatar alone, not on the row. */
private const val PausedAvatarAlpha = 0.55f

/** iOS: `NemaraPulse(color: NemaraColor.terra, size: 6)` — smaller than the 8 default. */
private val StatusPulseSize = 6.dp

/** iOS: `Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))`. */
private val ChevronSize = 14.dp

/**
 * Roster row card — Avatar + name/age + status line (pulse on critical) +
 * last-event + chevron.
 *
 * Critical state paints the entire card with a terra-soft tint and a terra
 * border; offline state mutes the status text. Otherwise the row is a default
 * Card with hairline borders.
 *
 * **Everything shown here is derived from [events], not from the patient
 * record.** `patient.state` / `statusLine` / `lastEventAt` are not
 * server-maintained yet (PARITY.md §15), so the row computes the status line
 * and the escalation from the events the app already has. iOS reads them off
 * `env.api.eventsByPatient[patient.id]`; passing them in is the hoisted
 * equivalent and is what makes the row testable without an environment.
 *
 * [resolvePhotoUrl] is threaded to [PatientAvatar] for the same reason its own
 * doc gives: Compose has no `AsyncImage`, so the URL lookup is injected rather
 * than imported.
 */
@Composable
fun PatientRow(
    patient: Patient,
    events: List<DetectionEvent>,
    resolvePhotoUrl: suspend (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val isPaused = PATIENT_ROW_IS_PAUSED
    val unopened = unopenedEvents(events)
    val unopenedCount = unopened.size
    val hasUnopenedSafety = patientRowHasUnopenedSafety(unopened, isPaused)

    val statusColor: Color = when {
        isPaused || unopenedCount == 0 -> Ink3
        hasUnopenedSafety -> Terra
        else -> Sky
    }

    NemaraCard(
        modifier = modifier,
        tone = when {
            isPaused -> NemaraCardTone.Alt
            hasUnopenedSafety -> NemaraCardTone.Terra
            else -> NemaraCardTone.Default
        },
        padding = RowCardPadding,
        borderColor = if (hasUnopenedSafety) Terra else Line,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PatientAvatar(
                patientId = patient.id,
                avatarLetter = patient.avatarLetter,
                photoStorageKey = patient.photoStorageKey,
                resolvePhotoUrl = resolvePhotoUrl,
                // The reason PatientAvatar takes a modifier at all: this is the
                // one call site that decorates it (PatientRow.swift:41-42).
                modifier = Modifier.alpha(if (isPaused) PausedAvatarAlpha else 1f),
                size = RowAvatarSize,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    // iOS: HStack(alignment: .firstTextBaseline). The age sits
                    // on the name's baseline, not centred against it — the two
                    // differ by ~3dp at 19pt vs 16pt and it is visible.
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = patient.preferredName,
                        modifier = Modifier.alignByBaseline(),
                        // NemaraFont.serif(size: 19, weight: .regular). There is
                        // no 19pt serif token — iOS reaches for the serif()
                        // builder here rather than a named style, so the size and
                        // weight are stated at the call site the same way.
                        fontFamily = FrauncesFamily,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Normal,
                        // Per-call-site kerning, per DECISIONS.md D-008.
                        // iOS: .kerning(-0.3)
                        letterSpacing = (-0.3).sp,
                        color = Ink,
                    )
                    Text(
                        // Verbatim: the comma and space belong to the string on
                        // iOS too, so the age reads as ", 82" hung off the name.
                        text = ", ${patient.age}",
                        modifier = Modifier.alignByBaseline(),
                        style = NemaraType.bodyLarge,
                        color = Ink3,
                    )
                    if (isPaused) {
                        PausedPill(modifier = Modifier.alignByBaseline())
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasUnopenedSafety) {
                        NemaraPulse(color = Terra, size = StatusPulseSize)
                    }
                    Text(
                        text = if (isPaused) {
                            "Monitoring paused"
                        } else {
                            patientRowStatusText(unopenedCount)
                        },
                        style = NemaraType.monoSmall,
                        // Per-call-site tracking, per DECISIONS.md D-008.
                        // iOS: .tracking(0.4)
                        letterSpacing = 0.4.sp,
                        color = statusColor,
                        // SwiftUI's .lineLimit(1) truncates at the tail with an
                        // ellipsis; maxLines alone would hard-clip the glyph.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Icon(
                imageVector = ChevronRightGlyph,
                contentDescription = null,
                tint = if (hasUnopenedSafety) Terra else Ink3,
                modifier = Modifier.size(ChevronSize),
            )
        }
    }
}

/**
 * The dormant "PAUSED" badge. Reachable only if [PATIENT_ROW_IS_PAUSED] is
 * rewired, and kept for exactly that reason.
 *
 * Hand-rolled rather than `NemaraChip(tone = Neutral)`, which is the same
 * mono-9-bold pill on the same PaperDim fill but with `Ink2` text and 8/3
 * padding. iOS specifies `Ink3` and 7/2 here, and a chip is not close enough to
 * substitute.
 */
@Composable
private fun PausedPill(modifier: Modifier = Modifier) {
    Text(
        text = "PAUSED",
        modifier = modifier
            // iOS: .padding(.leading, 2) applied outside the pill, on top of the
            // HStack's own 6pt spacing.
            .padding(start = 2.dp)
            .clip(CircleShape)
            .background(PaperDim)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        // NemaraFont.mono(size: 9, weight: .bold) — the monoMicro token.
        style = NemaraType.monoMicro,
        // Per-call-site tracking, per DECISIONS.md D-008. iOS: .tracking(0.6)
        letterSpacing = 0.6.sp,
        color = Ink3,
    )
}
