package com.memoria.caregiver.ui.detectors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.memoria.caregiver.ui.theme.Card

/**
 * Canvas-drawn stand-ins for the SF Symbols the three detector screens name.
 *
 * There is no material-icons dependency in the version catalog and the porting
 * rules forbid adding one, so every symbol is drawn with `Canvas` — the same
 * approach `CountryPicker.kt` and `LoginScreen.kt` already take for their five
 * glyphs. The geometry is expressed as fractions of a square box so one
 * definition serves every point size the screens ask for.
 *
 * **Two groups live here.**
 *
 * 1. *Chrome* — the fixed symbols the screens themselves name (magnifier,
 *    checkmark, ellipsis, and so on).
 * 2. *Detector icons* — the twenty-symbol curated set the backend allows in
 *    `icon_name` (see [DetectorIconOptions]). These arrive on the wire as SF
 *    Symbol names, so the symbol name stays the key on both platforms even
 *    though the drawing is ours.
 *
 * **These drawings are functional, not final.** They are simplified line marks
 * that read at 14-20dp; they are not redraws of Apple's artwork, which is not
 * licensable off-platform. A designer pass is expected. What must not change is
 * the *set* and the *keys*: the wire sends `figure.walk.motion`, so
 * `figure.walk.motion` must resolve to a walking mark here.
 *
 * Some SF Symbols are "filled" variants where the inner shape is knocked out of
 * a solid disc rather than stroked. Compose has no cheap knock-out, so those are
 * drawn as a filled shape plus the inner mark painted in [hole], which the call
 * site sets to whatever surface sits behind the glyph.
 */

/** Stroke weight as a fraction of the glyph box, for SF Symbol `.semibold`. */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

/**
 * Detector icons are drawn a touch lighter than chrome. They carry more internal
 * detail at the same box size, and a semibold stroke closes up the gaps.
 */
private const val DETECTOR_STROKE_RATIO = 0.10f

/**
 * The curated icon set, in the order the picker lists them. Matches the iOS
 * `AddDetectorView.iconOptions`, which in turn matches the backend's
 * `_ALLOWED_ICONS`.
 */
val DetectorIconOptions: List<String> = listOf(
    "eye", "figure.fall", "figure.walk.motion", "door.left.hand.open",
    "pills", "fork.knife", "bed.double", "shower", "tv", "drop", "flame",
    "exclamationmark.triangle", "heart", "person", "cup.and.saucer",
    "moon", "sun.max", "clock", "bell", "car",
)

/**
 * Caregiver-facing labels for the curated set.
 *
 * The raw symbol names are developer strings and read as gibberish to a
 * caregiver, so the picker shows these while still storing and sending the
 * symbol name. **These labels are product copy** and are identical to iOS —
 * only the underlying glyph names are platform-specific (PARITY.md §17).
 */
private val DetectorIconLabels: Map<String, String> = mapOf(
    "eye" to "Watching",
    "figure.fall" to "Fall",
    "figure.walk.motion" to "Walking",
    "door.left.hand.open" to "Door",
    "pills" to "Medication",
    "fork.knife" to "Eating",
    "bed.double" to "Bed / Sleep",
    "shower" to "Shower",
    "tv" to "TV",
    "drop" to "Water",
    "flame" to "Stove / Fire",
    "exclamationmark.triangle" to "Warning",
    "heart" to "Health",
    "person" to "Person",
    "cup.and.saucer" to "Drink",
    "moon" to "Night",
    "sun.max" to "Daytime",
    "clock" to "Time",
    "bell" to "Alert",
    "car" to "Outing",
)

/**
 * Friendly display name for an icon symbol. Falls back to stripping the common
 * prefixes and suffix and title-casing, so an inferred icon outside the curated
 * set still never shows raw dotted developer text.
 *
 * The word split drops empty pieces, matching Swift's `split(separator:)`, which
 * omits empty subsequences by default — otherwise `"a..b"` would produce a
 * stray empty word and a double space in the label.
 */
fun detectorIconLabel(symbol: String): String {
    DetectorIconLabels[symbol]?.let { return it }
    val cleaned = symbol
        .replace("figure.", "")
        .replace("person.", "")
        .replace(".fill", "")
        .replace(".", " ")
    val words = cleaned.split(" ")
        .filter { it.isNotEmpty() }
        // Swift: `$0.prefix(1).uppercased() + $0.dropFirst()`. Uppercasing the
        // one-character String (not the Char) keeps Swift's full case mapping,
        // where a single character can uppercase into two.
        .map { it.substring(0, 1).uppercase() + it.substring(1) }
    return if (words.isEmpty()) symbol else words.joinToString(" ")
}

/**
 * Draws [symbol] on a square [size] box.
 *
 * @param hole the surface behind the glyph, used to knock the inner mark out of
 *   the SF "filled" variants. Defaults to `Card`, the surface these glyphs sit
 *   on most often.
 */
@Composable
internal fun NemaraGlyph(
    symbol: String,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    hole: Color = Card,
) {
    Canvas(modifier.size(size)) { drawGlyph(symbol, color, hole) }
}

private fun DrawScope.drawGlyph(symbol: String, color: Color, hole: Color) {
    val w = size.width
    val chrome = w * SEMIBOLD_STROKE_RATIO
    val fine = w * DETECTOR_STROKE_RATIO
    when (symbol) {
        // ---- chrome -------------------------------------------------------
        "magnifyingglass" -> {
            val s = w * 0.12f
            drawCircle(color, w * 0.28f, Offset(w * 0.42f, w * 0.42f), style = Stroke(s, cap = StrokeCap.Round))
            seg(w, color, s, 0.63f, 0.63f, 0.88f, 0.88f)
        }
        "xmark" -> {
            seg(w, color, chrome, 0.16f, 0.16f, 0.84f, 0.84f)
            seg(w, color, chrome, 0.84f, 0.16f, 0.16f, 0.84f)
        }
        "xmark.circle.fill" -> {
            drawCircle(color, w * 0.5f, Offset(w * 0.5f, w * 0.5f))
            seg(w, hole, w * 0.10f, 0.32f, 0.32f, 0.68f, 0.68f)
            seg(w, hole, w * 0.10f, 0.68f, 0.32f, 0.32f, 0.68f)
        }
        "checkmark.circle.fill" -> {
            drawCircle(color, w * 0.5f, Offset(w * 0.5f, w * 0.5f))
            poly(w, hole, w * 0.10f, 0.28f, 0.52f, 0.44f, 0.68f, 0.73f, 0.34f)
        }
        "circle" -> drawCircle(color, w * 0.5f - chrome / 2f, Offset(w * 0.5f, w * 0.5f), style = Stroke(chrome))
        "largecircle.fill.circle" -> {
            drawCircle(color, w * 0.5f - chrome / 2f, Offset(w * 0.5f, w * 0.5f), style = Stroke(chrome))
            drawCircle(color, w * 0.27f, Offset(w * 0.5f, w * 0.5f))
        }
        "ellipsis" -> {
            val r = w * 0.09f
            drawCircle(color, r, Offset(w * 0.16f, w * 0.5f))
            drawCircle(color, r, Offset(w * 0.50f, w * 0.5f))
            drawCircle(color, r, Offset(w * 0.84f, w * 0.5f))
        }
        "slider.horizontal.3" -> {
            val s = w * 0.10f
            seg(w, color, s, 0.06f, 0.26f, 0.94f, 0.26f)
            seg(w, color, s, 0.06f, 0.50f, 0.94f, 0.50f)
            seg(w, color, s, 0.06f, 0.74f, 0.94f, 0.74f)
            drawCircle(color, w * 0.11f, Offset(w * 0.70f, w * 0.26f))
            drawCircle(color, w * 0.11f, Offset(w * 0.32f, w * 0.50f))
            drawCircle(color, w * 0.11f, Offset(w * 0.62f, w * 0.74f))
        }
        "trash" -> {
            val s = w * 0.10f
            seg(w, color, s, 0.10f, 0.24f, 0.90f, 0.24f)
            seg(w, color, s, 0.38f, 0.24f, 0.42f, 0.12f)
            seg(w, color, s, 0.62f, 0.24f, 0.58f, 0.12f)
            seg(w, color, s, 0.42f, 0.12f, 0.58f, 0.12f)
            poly(w, color, s, 0.20f, 0.24f, 0.24f, 0.90f, 0.76f, 0.90f, 0.80f, 0.24f)
            seg(w, color, s, 0.40f, 0.38f, 0.42f, 0.76f)
            seg(w, color, s, 0.60f, 0.38f, 0.58f, 0.76f)
        }
        "chevron.up.chevron.down" -> {
            val s = w * 0.13f
            poly(w, color, s, 0.20f, 0.36f, 0.50f, 0.10f, 0.80f, 0.36f)
            poly(w, color, s, 0.20f, 0.64f, 0.50f, 0.90f, 0.80f, 0.64f)
        }
        "exclamationmark.circle.fill" -> {
            drawCircle(color, w * 0.5f, Offset(w * 0.5f, w * 0.5f))
            seg(w, hole, w * 0.12f, 0.5f, 0.24f, 0.5f, 0.58f)
            drawCircle(hole, w * 0.07f, Offset(w * 0.5f, w * 0.76f))
        }
        "exclamationmark.triangle.fill" -> {
            drawPath(trianglePath(w), color)
            seg(w, hole, w * 0.11f, 0.5f, 0.36f, 0.5f, 0.64f)
            drawCircle(hole, w * 0.065f, Offset(w * 0.5f, w * 0.80f))
        }

        // ---- curated detector icons ---------------------------------------
        "eye" -> {
            val p = Path().apply {
                moveTo(w * 0.04f, w * 0.50f)
                quadraticTo(w * 0.50f, w * 0.10f, w * 0.96f, w * 0.50f)
                quadraticTo(w * 0.50f, w * 0.90f, w * 0.04f, w * 0.50f)
                close()
            }
            drawPath(p, color, style = Stroke(fine, join = StrokeJoin.Round))
            drawCircle(color, w * 0.13f, Offset(w * 0.5f, w * 0.5f))
        }
        "figure.fall" -> {
            drawCircle(color, w * 0.11f, Offset(w * 0.22f, w * 0.26f))
            seg(w, color, fine, 0.34f, 0.44f, 0.78f, 0.62f)
            seg(w, color, fine, 0.50f, 0.50f, 0.40f, 0.82f)
            seg(w, color, fine, 0.68f, 0.58f, 0.72f, 0.88f)
            seg(w, color, fine, 0.38f, 0.40f, 0.20f, 0.60f)
        }
        "figure.walk.motion" -> {
            drawCircle(color, w * 0.10f, Offset(w * 0.54f, w * 0.16f))
            seg(w, color, fine, 0.54f, 0.28f, 0.50f, 0.56f)
            seg(w, color, fine, 0.50f, 0.56f, 0.38f, 0.90f)
            seg(w, color, fine, 0.50f, 0.56f, 0.72f, 0.86f)
            seg(w, color, fine, 0.54f, 0.36f, 0.76f, 0.44f)
            seg(w, color, fine, 0.04f, 0.30f, 0.20f, 0.30f)
            seg(w, color, fine, 0.02f, 0.52f, 0.22f, 0.52f)
        }
        "door.left.hand.open" -> {
            val s = fine
            seg(w, color, s, 0.10f, 0.92f, 0.90f, 0.92f)
            poly(w, color, s, 0.24f, 0.08f, 0.24f, 0.92f, 0.74f, 0.92f, 0.74f, 0.16f)
            seg(w, color, s, 0.24f, 0.08f, 0.74f, 0.16f)
            drawCircle(color, w * 0.055f, Offset(w * 0.34f, w * 0.54f))
        }
        "pills" -> {
            drawCircle(color, w * 0.24f - fine / 2f, Offset(w * 0.28f, w * 0.30f), style = Stroke(fine))
            seg(w, color, fine, 0.13f, 0.42f, 0.43f, 0.18f)
            val capsule = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(Offset(w * 0.46f, w * 0.52f), Size(w * 0.48f, w * 0.36f)),
                        CornerRadius(w * 0.18f),
                    ),
                )
            }
            drawPath(capsule, color, style = Stroke(fine))
            seg(w, color, fine, 0.70f, 0.52f, 0.70f, 0.88f)
        }
        "fork.knife" -> {
            seg(w, color, fine, 0.24f, 0.08f, 0.24f, 0.92f)
            seg(w, color, fine, 0.12f, 0.08f, 0.12f, 0.38f)
            seg(w, color, fine, 0.36f, 0.08f, 0.36f, 0.38f)
            seg(w, color, fine, 0.12f, 0.38f, 0.36f, 0.38f)
            seg(w, color, fine, 0.76f, 0.46f, 0.76f, 0.92f)
            val blade = Path().apply {
                moveTo(w * 0.76f, w * 0.46f)
                quadraticTo(w * 0.96f, w * 0.34f, w * 0.82f, w * 0.08f)
                quadraticTo(w * 0.64f, w * 0.24f, w * 0.76f, w * 0.46f)
                close()
            }
            drawPath(blade, color, style = Stroke(fine, join = StrokeJoin.Round))
        }
        "bed.double" -> {
            seg(w, color, fine, 0.06f, 0.22f, 0.06f, 0.86f)
            seg(w, color, fine, 0.06f, 0.78f, 0.94f, 0.78f)
            seg(w, color, fine, 0.94f, 0.78f, 0.94f, 0.86f)
            val mattress = Path().apply {
                moveTo(w * 0.06f, w * 0.56f)
                lineTo(w * 0.94f, w * 0.56f)
                lineTo(w * 0.94f, w * 0.78f)
            }
            drawPath(mattress, color, style = Stroke(fine, join = StrokeJoin.Round))
            drawRoundedBox(w, color, fine, 0.14f, 0.38f, 0.44f, 0.56f)
            drawRoundedBox(w, color, fine, 0.52f, 0.38f, 0.82f, 0.56f)
        }
        "shower" -> {
            seg(w, color, fine, 0.68f, 0.06f, 0.68f, 0.30f)
            seg(w, color, fine, 0.36f, 0.30f, 0.96f, 0.30f)
            seg(w, color, fine, 0.44f, 0.48f, 0.44f, 0.60f)
            seg(w, color, fine, 0.66f, 0.50f, 0.66f, 0.62f)
            seg(w, color, fine, 0.88f, 0.48f, 0.88f, 0.60f)
            seg(w, color, fine, 0.55f, 0.74f, 0.55f, 0.86f)
            seg(w, color, fine, 0.77f, 0.74f, 0.77f, 0.86f)
        }
        "tv" -> {
            drawRoundedBox(w, color, fine, 0.06f, 0.16f, 0.94f, 0.72f)
            seg(w, color, fine, 0.30f, 0.90f, 0.70f, 0.90f)
            seg(w, color, fine, 0.50f, 0.72f, 0.50f, 0.90f)
        }
        "drop" -> {
            val p = Path().apply {
                moveTo(w * 0.50f, w * 0.06f)
                quadraticTo(w * 0.92f, w * 0.50f, w * 0.78f, w * 0.74f)
                quadraticTo(w * 0.50f, w * 1.04f, w * 0.22f, w * 0.74f)
                quadraticTo(w * 0.08f, w * 0.50f, w * 0.50f, w * 0.06f)
                close()
            }
            drawPath(p, color, style = Stroke(fine, join = StrokeJoin.Round))
        }
        "flame" -> {
            val p = Path().apply {
                moveTo(w * 0.50f, w * 0.04f)
                quadraticTo(w * 0.86f, w * 0.36f, w * 0.80f, w * 0.62f)
                quadraticTo(w * 0.74f, w * 0.94f, w * 0.44f, w * 0.94f)
                quadraticTo(w * 0.16f, w * 0.92f, w * 0.20f, w * 0.62f)
                quadraticTo(w * 0.24f, w * 0.44f, w * 0.38f, w * 0.44f)
                quadraticTo(w * 0.34f, w * 0.24f, w * 0.50f, w * 0.04f)
                close()
            }
            drawPath(p, color, style = Stroke(fine, join = StrokeJoin.Round))
        }
        "exclamationmark.triangle" -> {
            drawPath(trianglePath(w), color, style = Stroke(fine, join = StrokeJoin.Round))
            seg(w, color, fine, 0.5f, 0.38f, 0.5f, 0.64f)
            drawCircle(color, fine * 0.62f, Offset(w * 0.5f, w * 0.80f))
        }
        "heart" -> {
            val p = Path().apply {
                moveTo(w * 0.50f, w * 0.90f)
                quadraticTo(w * 0.04f, w * 0.56f, w * 0.16f, w * 0.28f)
                quadraticTo(w * 0.32f, w * 0.04f, w * 0.50f, w * 0.30f)
                quadraticTo(w * 0.68f, w * 0.04f, w * 0.84f, w * 0.28f)
                quadraticTo(w * 0.96f, w * 0.56f, w * 0.50f, w * 0.90f)
                close()
            }
            drawPath(p, color, style = Stroke(fine, join = StrokeJoin.Round))
        }
        "person" -> {
            drawCircle(color, w * 0.20f - fine / 2f, Offset(w * 0.5f, w * 0.28f), style = Stroke(fine))
            val shoulders = Path().apply {
                moveTo(w * 0.14f, w * 0.94f)
                quadraticTo(w * 0.50f, w * 0.52f, w * 0.86f, w * 0.94f)
            }
            drawPath(shoulders, color, style = Stroke(fine, cap = StrokeCap.Round))
        }
        "cup.and.saucer" -> {
            poly(w, color, fine, 0.16f, 0.28f, 0.24f, 0.70f, 0.62f, 0.70f, 0.70f, 0.28f)
            seg(w, color, fine, 0.16f, 0.28f, 0.70f, 0.28f)
            val handle = Path().apply {
                moveTo(w * 0.70f, w * 0.36f)
                quadraticTo(w * 0.96f, w * 0.42f, w * 0.68f, w * 0.58f)
            }
            drawPath(handle, color, style = Stroke(fine, cap = StrokeCap.Round))
            seg(w, color, fine, 0.06f, 0.86f, 0.86f, 0.86f)
        }
        "moon" -> {
            val p = Path().apply {
                arcTo(Rect(Offset(w * 0.14f, w * 0.08f), Size(w * 0.72f, w * 0.84f)), 70f, 220f, true)
                arcTo(Rect(Offset(w * 0.36f, w * 0.02f), Size(w * 0.76f, w * 0.78f)), 200f, -160f, false)
                close()
            }
            drawPath(p, color)
        }
        "sun.max" -> {
            drawCircle(color, w * 0.24f - fine / 2f, Offset(w * 0.5f, w * 0.5f), style = Stroke(fine))
            seg(w, color, fine, 0.50f, 0.02f, 0.50f, 0.14f)
            seg(w, color, fine, 0.50f, 0.86f, 0.50f, 0.98f)
            seg(w, color, fine, 0.02f, 0.50f, 0.14f, 0.50f)
            seg(w, color, fine, 0.86f, 0.50f, 0.98f, 0.50f)
            seg(w, color, fine, 0.16f, 0.16f, 0.25f, 0.25f)
            seg(w, color, fine, 0.75f, 0.75f, 0.84f, 0.84f)
            seg(w, color, fine, 0.84f, 0.16f, 0.75f, 0.25f)
            seg(w, color, fine, 0.25f, 0.75f, 0.16f, 0.84f)
        }
        "clock" -> {
            drawCircle(color, w * 0.46f - fine / 2f, Offset(w * 0.5f, w * 0.5f), style = Stroke(fine))
            seg(w, color, fine, 0.50f, 0.26f, 0.50f, 0.52f)
            seg(w, color, fine, 0.50f, 0.52f, 0.70f, 0.62f)
        }
        "bell" -> {
            val p = Path().apply {
                moveTo(w * 0.16f, w * 0.72f)
                quadraticTo(w * 0.28f, w * 0.66f, w * 0.28f, w * 0.44f)
                quadraticTo(w * 0.28f, w * 0.14f, w * 0.50f, w * 0.14f)
                quadraticTo(w * 0.72f, w * 0.14f, w * 0.72f, w * 0.44f)
                quadraticTo(w * 0.72f, w * 0.66f, w * 0.84f, w * 0.72f)
                close()
            }
            drawPath(p, color, style = Stroke(fine, join = StrokeJoin.Round))
            seg(w, color, fine, 0.50f, 0.04f, 0.50f, 0.14f)
            val clapper = Path().apply {
                moveTo(w * 0.40f, w * 0.80f)
                quadraticTo(w * 0.50f, w * 0.94f, w * 0.60f, w * 0.80f)
            }
            drawPath(clapper, color, style = Stroke(fine, cap = StrokeCap.Round))
        }
        "car" -> {
            val body = Path().apply {
                moveTo(w * 0.04f, w * 0.68f)
                lineTo(w * 0.10f, w * 0.44f)
                lineTo(w * 0.26f, w * 0.24f)
                lineTo(w * 0.74f, w * 0.24f)
                lineTo(w * 0.90f, w * 0.44f)
                lineTo(w * 0.96f, w * 0.68f)
                lineTo(w * 0.04f, w * 0.68f)
                close()
            }
            drawPath(body, color, style = Stroke(fine, join = StrokeJoin.Round))
            drawCircle(color, w * 0.10f, Offset(w * 0.26f, w * 0.74f))
            drawCircle(color, w * 0.10f, Offset(w * 0.74f, w * 0.74f))
        }

        // An icon the backend allowed but this set does not know. A neutral
        // rounded box is a visible "unknown", which beats a blank cell and beats
        // guessing at a meaning the caregiver would then trust.
        else -> drawRoundedBox(w, color, fine, 0.10f, 0.10f, 0.90f, 0.90f)
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers. All coordinates are fractions of the square box.
// ---------------------------------------------------------------------------

private fun DrawScope.seg(w: Float, color: Color, stroke: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
    drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), stroke, StrokeCap.Round)
}

/** Round-capped segments through the flattened `x, y` pairs. */
private fun DrawScope.poly(w: Float, color: Color, stroke: Float, vararg xy: Float) {
    var i = 0
    while (i + 3 < xy.size) {
        seg(w, color, stroke, xy[i], xy[i + 1], xy[i + 2], xy[i + 3])
        i += 2
    }
}

private fun DrawScope.drawRoundedBox(
    w: Float,
    color: Color,
    stroke: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(w * left, w * top),
        size = Size(w * (right - left), w * (bottom - top)),
        cornerRadius = CornerRadius(w * 0.10f),
        style = Stroke(stroke, join = StrokeJoin.Round),
    )
}

private fun trianglePath(w: Float): Path = Path().apply {
    moveTo(w * 0.50f, w * 0.08f)
    lineTo(w * 0.98f, w * 0.90f)
    lineTo(w * 0.02f, w * 0.90f)
    close()
}

// ---------------------------------------------------------------------------
// Tap
// ---------------------------------------------------------------------------

/**
 * SwiftUI's `.buttonStyle(.plain)` — a tap with no ripple and no indication.
 *
 * Every tappable label across these three screens is a plain SwiftUI `Button`,
 * so none of them ripple. Defined once here rather than per screen: three
 * private copies is how one of them quietly acquires an indication.
 */
@Composable
internal fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}
