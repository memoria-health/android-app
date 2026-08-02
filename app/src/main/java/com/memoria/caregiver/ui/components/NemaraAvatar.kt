package com.memoria.caregiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.FrauncesFamily
import com.memoria.caregiver.ui.theme.Ink
import java.text.BreakIterator

/**
 * The letter is scaled to 42% of the avatar diameter. Straight from
 * NemaraAvatar.swift (`size * 0.42`) — the serif cap-height at Fraunces
 * SemiBold sits optically centred in the circle at this ratio.
 */
private const val AvatarLetterSizeRatio = 0.42f

/**
 * iOS applies `.kerning(-0.5)` to the initial, an absolute point value. Per
 * DECISIONS.md D-008 kerning is a per-call-site concern, and this is that call
 * site. Written as `.sp` like every other kerning call site in the port.
 */
private val AvatarLetterKerning = (-0.5).sp

/**
 * Hue derivation constants, verbatim from NemaraAvatar.swift.
 *
 * 47 is coprime with 360, so consecutive letters land far apart on the wheel
 * instead of in a gradient — which is the whole point: two patients whose names
 * start with A and B must be distinguishable at a glance.
 */
private const val AvatarHueMultiplier = 47
private const val AvatarHueWheelDegrees = 360
private const val AvatarSaturation = 0.18f
private const val AvatarBrightness = 0.86f

/** iOS draws the ring with `strokeBorder(lineWidth: 2)`. */
private val AvatarRingWidth = 2.dp

/**
 * How an avatar photo URL is turned into pixels.
 *
 * **This exists because SwiftUI hands `AsyncImage` to `NemaraAvatar` for free
 * and Compose has no equivalent.** Loading an image from a URL needs a library
 * (Coil), which this module does not declare, so the renderer is injected once
 * at the app root instead of being imported here. The receiver gets the URL and
 * is expected to draw an image that fills its box with `ContentScale.Crop`, to
 * match iOS's `.scaledToFill()`.
 *
 * The default draws nothing, which is exactly the `.empty` / `.failure` branches
 * of the Swift `AsyncImage` phase switch: `Color.clear`, with the initials
 * placeholder still visible underneath. **Until an app-root provider is wired
 * up, patient photos do not render and every avatar shows its serif initial.**
 * That is a deliberate seam, not a silent bug.
 *
 * TODO(REQ-22): wire an app-root provider before any screen shows a real
 * patient. See DIFFERENCES.md D8.
 */
val LocalAvatarPhoto: ProvidableCompositionLocal<@Composable (String) -> Unit> =
    staticCompositionLocalOf { { _: String -> } }

/**
 * The displayed initial: the first character of [raw], uppercased.
 *
 * Swift is `String(letter.prefix(1)).uppercased()`, and `prefix(1)` on a Swift
 * String takes one **grapheme cluster**, not one UTF-16 unit. Kotlin's
 * `take(1)` takes one `Char`, which would split a surrogate pair (an emoji
 * would render as a replacement box) and would drop the combining mark off a
 * decomposed accented letter. BreakIterator gives the Swift boundary.
 *
 * `uppercase()` with no locale is locale-invariant, matching Swift's
 * non-localized `uppercased()`.
 */
internal fun avatarInitial(raw: String): String {
    if (raw.isEmpty()) return ""
    val boundaries = BreakIterator.getCharacterInstance()
    boundaries.setText(raw)
    val end = boundaries.following(0)
    val firstCluster = if (end == BreakIterator.DONE) raw else raw.substring(0, end)
    return firstCluster.uppercase()
}

/**
 * The background hue in degrees, `(scalar * 47) % 360` from NemaraAvatar.swift.
 *
 * `scalar` is the first Unicode scalar of the **already-uppercased** initial —
 * iOS derives the hue from `self.letter`, the normalized value, so "m" and "M"
 * must produce the same tone. `codePointAt(0)` is the Kotlin equivalent of
 * Swift's `unicodeScalars.first`; `String.get(0)` would return half a surrogate
 * pair and give a different hue for the same character.
 *
 * Swift's `?? 0` fallback for an empty string is preserved: an empty initial is
 * hue 0, not a crash.
 */
internal fun avatarHueDegrees(initial: String): Int {
    val scalar = if (initial.isEmpty()) 0 else initial.codePointAt(0)
    return (scalar * AvatarHueMultiplier) % AvatarHueWheelDegrees
}

/**
 * HSL-style derived warm background so each letter gets a distinct pastel tone
 * that still reads as "warm" against the paper canvas. (Comment carried from
 * NemaraAvatar.swift.)
 *
 * SwiftUI's `Color(hue:saturation:brightness:)` is HSB with hue in 0...1;
 * Compose's `Color.hsv` is the same model with hue in degrees. The Swift
 * divides the integer degree count by 360 only to satisfy that API, so passing
 * the degrees straight through is exact — no rounding is introduced.
 *
 * Deliberately not a palette token: it is a computed field of hues, and pinning
 * it to the 22-token palette would collapse every patient to one color.
 */
internal fun avatarBackgroundColor(initial: String): Color =
    Color.hsv(avatarHueDegrees(initial).toFloat(), AvatarSaturation, AvatarBrightness)

/**
 * A circular avatar. Without a [photoUrl] it draws a serif initial (so different
 * patients are still visually distinct via a pastel hue derived from the
 * letter). With a [photoUrl], the image renders on top; the initials placeholder
 * remains visible during load + as the failure fallback so the layout never
 * shifts.
 *
 * That fallback is structural rather than conditional: the initials circle is
 * always composed first and never removed, so "still loading" and "fetch failed"
 * both fall through to it without a state machine — the same trick the Swift
 * plays by returning `Color.clear` from the `.empty` and `.failure` phases.
 *
 * The photo itself is drawn by [LocalAvatarPhoto], because Compose has no
 * built-in URL image loader. It is clipped to a circle and given the full [size]
 * box, matching `.frame(width:size,height:size).clipShape(Circle())`.
 *
 * The ring is the last child rather than a modifier on the outer box, because
 * modifier-drawn borders paint beneath children and a photo would cover it.
 * `Modifier.border` insets the stroke inward like SwiftUI's `strokeBorder`, so
 * the 2dp ring stays inside the [size] box.
 */
@Composable
fun NemaraAvatar(
    letter: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    ring: Color? = null,
    photoUrl: String? = null,
) {
    val initial = remember(letter) { avatarInitial(letter) }
    val background = remember(initial) { avatarBackgroundColor(initial) }

    // `.sp`, so the glyph scales with the user's font setting — because that is
    // what iOS does. SwiftUI's `Font.custom(_:size:)` scales with Dynamic Type;
    // only `Font.custom(_:fixedSize:)` opts out, and NemaraFont.swift uses the
    // scaling form.
    //
    // An earlier draft converted Dp -> Sp here to pin the glyph at 42% of the
    // diameter, with a comment claiming that matched the Swift. It did not: it
    // cancelled the user's font scale, so an accessibility setting that enlarges
    // text everywhere would have had no effect on avatars. The consequence of
    // matching iOS is that at very large font scales the initial can overflow
    // its circle on both platforms — that is the existing product behavior, not
    // something this port gets to quietly change.
    val letterSize = (size.value * AvatarLetterSizeRatio).sp

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().background(background, CircleShape))

        Text(
            text = initial,
            color = Ink,
            fontFamily = FrauncesFamily,
            fontSize = letterSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = AvatarLetterKerning,
        )

        if (photoUrl != null) {
            Box(
                modifier = Modifier.matchParentSize().clip(CircleShape),
                propagateMinConstraints = true,
            ) {
                val renderPhoto = LocalAvatarPhoto.current
                renderPhoto(photoUrl)
            }
        }

        if (ring != null) {
            Box(Modifier.matchParentSize().border(AvatarRingWidth, ring, CircleShape))
        }
    }
}
