package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.live.PeopleBlurAvailability
import com.memoria.caregiver.ui.components.NemaraToggle
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.components.InfoDot
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.PaperDim

/**
 * Per-patient "Blur people" toggle. Spec:
 * `Features/Patient/Settings/PeopleBlurCard.swift`, PARITY.md §24.
 *
 * **The one card of the four that is device-local.** iOS writes
 * `PeopleBlurStore` (a `UserDefaults`-backed set of patient ids), not the
 * server: flipping it swaps this device's live tiles between the normal video
 * and the on-device person-blur renderer, and changes nothing about recording,
 * insights, or what any other device sees. There is therefore no busy state and
 * no failure state — the flip cannot fail, which is exactly why PARITY.md §24
 * forbids folding these three cards into one component with a flag.
 *
 * The store itself lives outside this package, so the card takes the value and
 * reports the flip.
 *
 * The `Spacer()` the Swift puts before the toggle is expressed as `weight(1f)`
 * on the text column: in a `Row` that both pushes the toggle to the trailing
 * edge and gives the copy the remaining width to wrap into, which is what
 * SwiftUI's `Spacer()` plus `fixedSize(horizontal: false, vertical: true)` does
 * together.
 */
@Composable
fun PeopleBlurCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False while no person-detection model ships (REQ-23). The switch is then
     * shown off and non-interactive, with copy saying so.
     *
     * Deliberately still VISIBLE rather than hidden: the caregiver should be
     * able to see that the control exists and is coming. What must not happen is
     * the switch reading as on while nothing is blurred — see HARDENING.md H-12.
     */
    available: Boolean = PeopleBlurAvailability.IS_AVAILABLE,
) {
    // A stored preference cannot make the feature real. If the model is absent
    // the card renders off whatever the store says, so the switch never
    // contradicts the video.
    val effectivelyOn = enabled && available
    NemaraCard(
        // iOS applies `.padding(.horizontal, 16)` to the card itself, so it
        // travels with the card rather than being the parent stack's job.
        modifier = modifier.padding(horizontal = 16.dp),
        padding = 14.dp,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (effectivelyOn) ActionSoft else PaperDim),
                contentAlignment = Alignment.Center,
            ) {
                PersonGlyph(
                    size = 16.dp,
                    color = if (effectivelyOn) Action else Ink3,
                    filled = effectivelyOn,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Blur people", style = NemaraType.bodySemibold, color = Ink)
                    InfoDot(
                        "Blurs people in the live feed on this device only. The room " +
                            "stays visible; recording and what others see are unchanged.",
                    )
                }
                // The unavailable notice STAYS a visible caption, and does not
                // become an InfoDot with the rest.
                //
                // The declutter's argument is that a caption explaining what a
                // working control does is noise once the caregiver has read it
                // once. This sentence is not that: it says the control does
                // nothing at all in this build (REQ-23 — no person detector is
                // vendored). Hiding "this doesn't work yet" behind a tap would
                // leave a toggle that looks live and isn't, which is the one
                // thing a privacy control must never do.
                if (!available) {
                    Text(
                        // Says what is true, and does not promise a date.
                        text = "Not available in this version yet. When it arrives, it " +
                            "will blur anyone in the live feed on this device only.",
                        style = NemaraType.caption,
                        color = Ink3,
                        lineHeight = CaptionLineHeight,
                    )
                }
            }

            NemaraToggle(
                isOn = effectivelyOn,
                onToggle = onEnabledChange,
                track = "people_blur.toggle",
                enabled = available,
            )
        }
    }
}
