package com.memoria.caregiver.ui.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The SF Symbols the two camera screens name, drawn with Canvas.
 *
 * There is no material-icons dependency in this project's version catalog and
 * the porting rules forbid adding one, so every symbol is drawn — the approach
 * CountryPicker.kt and LoginScreen.kt already take for their glyphs. Each is
 * laid out on a square box of the SF Symbol's point size with round caps and
 * joins, matching SF Symbol terminals, and coordinates are fractions of that
 * box so one definition serves every size.
 *
 * **This file should not stay in this package.** Camera rows, detector
 * assignment rows, the detector library, the add-detector icon picker and the
 * event list all render `icon_name` from the backend, so the twenty curated
 * pictograms below are shared vocabulary and belong in `ui/components` beside
 * NemaraChip. This unit may only write under its own package, so they live here
 * for now; lifting them out is a mechanical move plus a package change.
 *
 * An unrecognized name draws NOTHING, which is what `Image(systemName:)` does
 * with a name iOS does not know. The backend constrains `icon_name` to the
 * curated set (`_ALLOWED_ICONS`), so a blank tile means the contract drifted and
 * should be visible rather than papered over with a stand-in glyph.
 */

/** Stroke weight as a fraction of the glyph box, for SF Symbol `.semibold`. */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

/**
 * Pictograms carry more internal detail than a chevron, so they are drawn a
 * step lighter — at the semibold ratio a 14dp bed or gear fills in solid.
 */
private const val PICTOGRAM_STROKE_RATIO = 0.095f

/**
 * Draws the SF Symbol called [name] at [size], in [color].
 *
 * @param knockout the color of the surface behind the glyph. Only `.fill`
 *   symbols use it: SF renders their inner detail as a hole, so on Android it
 *   has to be painted in the background's own color. Getting it wrong shows as
 *   a checkmark or a moon in the wrong shade, never as a missing shape.
 */
@Composable
internal fun SensorSymbol(
    name: String,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    knockout: Color = Color.Transparent,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val chrome = w * SEMIBOLD_STROKE_RATIO
        val fine = w * PICTOGRAM_STROKE_RATIO
        when (name) {
            // ---- Chrome: named literally in the two Swift files ----
            "chevron.right" -> {
                val i = chrome / 2f
                poly(color, chrome, 0.30f to i / w, 0.70f to 0.5f, 0.30f to 1f - i / w)
            }
            "chevron.down" -> {
                val i = chrome / 2f
                poly(color, chrome, i / w to 0.30f, 0.5f to 0.70f, 1f - i / w to 0.30f)
            }
            "lock.shield.fill" -> {
                fill(color) {
                    moveTo(0.5f * w, 0.04f * w)
                    lineTo(0.93f * w, 0.19f * w)
                    lineTo(0.93f * w, 0.50f * w)
                    quadraticTo(0.93f * w, 0.84f * w, 0.5f * w, 0.97f * w)
                    quadraticTo(0.07f * w, 0.84f * w, 0.07f * w, 0.50f * w)
                    lineTo(0.07f * w, 0.19f * w)
                    close()
                }
                arc(knockout, w * 0.09f, 0.5f, 0.44f, 0.12f, 180f, 180f)
                roundFill(knockout, 0.33f, 0.44f, 0.34f, 0.26f, 0.06f)
            }
            "gearshape" -> {
                ring(color, fine, 0.5f, 0.5f, 0.29f)
                ring(color, fine, 0.5f, 0.5f, 0.13f)
                for (step in 0 until 8) {
                    val a = Math.toRadians(step * 45.0)
                    val cos = Math.cos(a).toFloat()
                    val sin = Math.sin(a).toFloat()
                    line(
                        color, fine * 1.6f,
                        0.5f + cos * 0.27f, 0.5f + sin * 0.27f,
                        0.5f + cos * 0.46f, 0.5f + sin * 0.46f,
                    )
                }
            }
            "antenna.radiowaves.left.and.right" -> {
                dot(color, 0.5f, 0.5f, 0.10f)
                arc(color, fine, 0.5f, 0.5f, 0.24f, 135f, 90f)
                arc(color, fine, 0.5f, 0.5f, 0.42f, 135f, 90f)
                arc(color, fine, 0.5f, 0.5f, 0.24f, 315f, 90f)
                arc(color, fine, 0.5f, 0.5f, 0.42f, 315f, 90f)
            }
            "wifi.slash" -> {
                wifi(color, fine)
                slash(color, chrome)
            }
            "waveform" -> waveformBars(color, fine)
            "waveform.slash" -> {
                waveformBars(color, fine)
                slash(color, chrome)
            }
            "iphone.slash" -> {
                roundRing(color, fine, 0.26f, 0.05f, 0.48f, 0.90f, 0.10f)
                slash(color, chrome)
            }
            "trash" -> {
                line(color, fine, 0.10f, 0.24f, 0.90f, 0.24f)
                poly(color, fine, 0.36f to 0.24f, 0.36f to 0.11f, 0.64f to 0.11f, 0.64f to 0.24f)
                poly(color, fine, 0.20f to 0.28f, 0.27f to 0.92f, 0.73f to 0.92f, 0.80f to 0.28f)
            }
            "qrcode" -> {
                for ((x, y) in listOf(0.04f to 0.04f, 0.58f to 0.04f, 0.04f to 0.58f)) {
                    roundRing(color, fine, x, y, 0.38f, 0.38f, 0.04f)
                    roundFill(color, x + 0.13f, y + 0.13f, 0.12f, 0.12f, 0.02f)
                }
                roundFill(color, 0.58f, 0.58f, 0.14f, 0.14f, 0.02f)
                roundFill(color, 0.80f, 0.58f, 0.14f, 0.14f, 0.02f)
                roundFill(color, 0.58f, 0.80f, 0.14f, 0.14f, 0.02f)
                roundFill(color, 0.80f, 0.80f, 0.14f, 0.14f, 0.02f)
            }
            "checkmark.circle.fill" -> {
                dot(color, 0.5f, 0.5f, 0.5f)
                poly(knockout, w * 0.11f, 0.27f to 0.52f, 0.43f to 0.68f, 0.73f to 0.33f)
            }
            "circle" -> ring(color, w * 0.075f, 0.5f, 0.5f, 0.5f - 0.0375f)

            // ---- Curated pictograms: backend `icon_name`, shared vocabulary ----
            "eye" -> {
                stroke(color, fine) {
                    moveTo(0.04f * w, 0.5f * w)
                    quadraticTo(0.5f * w, 0.10f * w, 0.96f * w, 0.5f * w)
                    quadraticTo(0.5f * w, 0.90f * w, 0.04f * w, 0.5f * w)
                    close()
                }
                dot(color, 0.5f, 0.5f, 0.14f)
            }
            "figure.fall" -> {
                dot(color, 0.24f, 0.22f, 0.11f)
                line(color, fine, 0.34f, 0.34f, 0.66f, 0.54f)
                line(color, fine, 0.66f, 0.54f, 0.90f, 0.44f)
                line(color, fine, 0.66f, 0.54f, 0.74f, 0.84f)
                line(color, fine, 0.42f, 0.38f, 0.30f, 0.66f)
            }
            "figure.walk.motion" -> {
                dot(color, 0.60f, 0.14f, 0.10f)
                line(color, fine, 0.58f, 0.26f, 0.52f, 0.54f)
                line(color, fine, 0.52f, 0.54f, 0.38f, 0.86f)
                line(color, fine, 0.52f, 0.54f, 0.72f, 0.86f)
                line(color, fine, 0.56f, 0.34f, 0.76f, 0.46f)
                line(color, fine, 0.06f, 0.28f, 0.26f, 0.28f)
                line(color, fine, 0.02f, 0.50f, 0.24f, 0.50f)
                line(color, fine, 0.08f, 0.72f, 0.26f, 0.72f)
            }
            "door.left.hand.open" -> {
                line(color, fine, 0.86f, 0.08f, 0.86f, 0.94f)
                poly(
                    color, fine,
                    0.14f to 0.06f, 0.66f to 0.20f, 0.66f to 0.94f, 0.14f to 0.94f, 0.14f to 0.06f,
                )
                dot(color, 0.56f, 0.56f, 0.06f)
            }
            "pills" -> {
                rotate(-40f, Offset(w * 0.36f, w * 0.36f)) {
                    roundRing(color, fine, 0.06f, 0.20f, 0.60f, 0.32f, 0.16f)
                    line(color, fine, 0.36f, 0.20f, 0.36f, 0.52f)
                }
                ring(color, fine, 0.70f, 0.70f, 0.26f)
                line(color, fine, 0.52f, 0.62f, 0.88f, 0.78f)
            }
            "fork.knife" -> {
                line(color, fine, 0.30f, 0.34f, 0.30f, 0.94f)
                line(color, fine, 0.16f, 0.06f, 0.16f, 0.30f)
                line(color, fine, 0.30f, 0.06f, 0.30f, 0.30f)
                line(color, fine, 0.44f, 0.06f, 0.44f, 0.30f)
                line(color, fine, 0.16f, 0.30f, 0.44f, 0.30f)
                poly(color, fine, 0.72f to 0.06f, 0.84f to 0.22f, 0.72f to 0.50f)
                line(color, fine, 0.72f, 0.50f, 0.72f, 0.94f)
            }
            "bed.double" -> {
                line(color, fine, 0.06f, 0.26f, 0.06f, 0.88f)
                line(color, fine, 0.06f, 0.60f, 0.94f, 0.60f)
                line(color, fine, 0.94f, 0.60f, 0.94f, 0.88f)
                line(color, fine, 0.06f, 0.88f, 0.94f, 0.88f)
                arc(color, fine, 0.30f, 0.60f, 0.14f, 180f, 180f)
                arc(color, fine, 0.62f, 0.60f, 0.14f, 180f, 180f)
            }
            "shower" -> {
                line(color, fine, 0.62f, 0.04f, 0.62f, 0.26f)
                arc(color, fine, 0.62f, 0.34f, 0.28f, 180f, 180f)
                line(color, fine, 0.34f, 0.34f, 0.90f, 0.34f)
                line(color, fine, 0.40f, 0.58f, 0.40f, 0.70f)
                line(color, fine, 0.62f, 0.64f, 0.62f, 0.78f)
                line(color, fine, 0.84f, 0.58f, 0.84f, 0.70f)
            }
            "tv" -> {
                roundRing(color, fine, 0.04f, 0.16f, 0.92f, 0.54f, 0.08f)
                line(color, fine, 0.5f, 0.70f, 0.5f, 0.88f)
                line(color, fine, 0.30f, 0.88f, 0.70f, 0.88f)
            }
            "drop" -> stroke(color, fine) {
                moveTo(0.5f * w, 0.04f * w)
                quadraticTo(0.94f * w, 0.54f * w, 0.5f * w, 0.94f * w)
                quadraticTo(0.06f * w, 0.54f * w, 0.5f * w, 0.04f * w)
                close()
            }
            "flame" -> stroke(color, fine) {
                moveTo(0.54f * w, 0.04f * w)
                quadraticTo(0.88f * w, 0.36f * w, 0.82f * w, 0.62f * w)
                quadraticTo(0.76f * w, 0.94f * w, 0.48f * w, 0.94f * w)
                quadraticTo(0.18f * w, 0.94f * w, 0.16f * w, 0.62f * w)
                quadraticTo(0.14f * w, 0.42f * w, 0.34f * w, 0.30f * w)
                quadraticTo(0.38f * w, 0.50f * w, 0.52f * w, 0.42f * w)
                quadraticTo(0.62f * w, 0.28f * w, 0.54f * w, 0.04f * w)
                close()
            }
            "exclamationmark.triangle" -> {
                poly(color, fine, 0.5f to 0.06f, 0.97f to 0.90f, 0.03f to 0.90f, 0.5f to 0.06f)
                line(color, fine, 0.5f, 0.36f, 0.5f, 0.62f)
                dot(color, 0.5f, 0.76f, 0.06f)
            }
            "heart" -> stroke(color, fine) {
                moveTo(0.5f * w, 0.90f * w)
                cubicTo(0.0f, 0.56f * w, 0.10f * w, 0.10f * w, 0.5f * w, 0.32f * w)
                cubicTo(0.90f * w, 0.10f * w, 1.0f * w, 0.56f * w, 0.5f * w, 0.90f * w)
                close()
            }
            "person" -> {
                ring(color, fine, 0.5f, 0.26f, 0.18f)
                arc(color, fine, 0.5f, 0.94f, 0.34f, 200f, 140f)
            }
            "cup.and.saucer" -> {
                poly(color, fine, 0.18f to 0.28f, 0.26f to 0.70f, 0.60f to 0.70f, 0.68f to 0.28f, 0.18f to 0.28f)
                arc(color, fine, 0.70f, 0.44f, 0.16f, 280f, 160f)
                line(color, fine, 0.08f, 0.84f, 0.92f, 0.84f)
            }
            "moon" -> {
                dot(color, 0.5f, 0.5f, 0.46f)
                dot(knockout, 0.76f, 0.30f, 0.42f)
            }
            "sun.max" -> {
                ring(color, fine, 0.5f, 0.5f, 0.22f)
                for (step in 0 until 8) {
                    val a = Math.toRadians(step * 45.0)
                    val cos = Math.cos(a).toFloat()
                    val sin = Math.sin(a).toFloat()
                    line(
                        color, fine,
                        0.5f + cos * 0.33f, 0.5f + sin * 0.33f,
                        0.5f + cos * 0.47f, 0.5f + sin * 0.47f,
                    )
                }
            }
            "clock" -> {
                ring(color, fine, 0.5f, 0.5f, 0.44f)
                line(color, fine, 0.5f, 0.5f, 0.5f, 0.26f)
                line(color, fine, 0.5f, 0.5f, 0.70f, 0.58f)
            }
            "bell" -> {
                stroke(color, fine) {
                    moveTo(0.16f * w, 0.76f * w)
                    quadraticTo(0.28f * w, 0.70f * w, 0.28f * w, 0.44f * w)
                    quadraticTo(0.28f * w, 0.14f * w, 0.50f * w, 0.14f * w)
                    quadraticTo(0.72f * w, 0.14f * w, 0.72f * w, 0.44f * w)
                    quadraticTo(0.72f * w, 0.70f * w, 0.84f * w, 0.76f * w)
                    close()
                }
                dot(color, 0.5f, 0.88f, 0.09f)
            }
            "car" -> {
                roundRing(color, fine, 0.04f, 0.42f, 0.92f, 0.26f, 0.08f)
                poly(color, fine, 0.22f to 0.42f, 0.32f to 0.20f, 0.68f to 0.20f, 0.80f to 0.42f)
                ring(color, fine, 0.26f, 0.74f, 0.11f)
                ring(color, fine, 0.74f, 0.74f, 0.11f)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Primitives. Every coordinate is a fraction of the glyph box.
// ---------------------------------------------------------------------------

private fun DrawScope.line(color: Color, w0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
    val w = size.width
    drawLine(color, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), w0, StrokeCap.Round)
}

/**
 * Round-capped segments through [points]. Drawn as separate lines rather than a
 * Path: with round caps, consecutive segments meeting at a shared point render
 * the same as a round join, and this keeps definitions readable as coordinates.
 */
private fun DrawScope.poly(color: Color, w0: Float, vararg points: Pair<Float, Float>) {
    for (i in 0 until points.size - 1) {
        line(color, w0, points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
    }
}

private fun DrawScope.dot(color: Color, cx: Float, cy: Float, r: Float) {
    val w = size.width
    drawCircle(color, r * w, Offset(cx * w, cy * w))
}

private fun DrawScope.ring(color: Color, w0: Float, cx: Float, cy: Float, r: Float) {
    val w = size.width
    drawCircle(color, r * w, Offset(cx * w, cy * w), style = Stroke(w0, cap = StrokeCap.Round))
}

private fun DrawScope.arc(
    color: Color,
    w0: Float,
    cx: Float,
    cy: Float,
    r: Float,
    startDeg: Float,
    sweepDeg: Float,
) {
    val w = size.width
    drawArc(
        color = color,
        startAngle = startDeg,
        sweepAngle = sweepDeg,
        useCenter = false,
        topLeft = Offset((cx - r) * w, (cy - r) * w),
        size = Size(2 * r * w, 2 * r * w),
        style = Stroke(w0, cap = StrokeCap.Round),
    )
}

private fun DrawScope.roundRing(
    color: Color,
    w0: Float,
    x: Float,
    y: Float,
    rectW: Float,
    rectH: Float,
    corner: Float,
) {
    val w = size.width
    drawRoundRect(
        color = color,
        topLeft = Offset(x * w, y * w),
        size = Size(rectW * w, rectH * w),
        cornerRadius = CornerRadius(corner * w, corner * w),
        style = Stroke(w0, join = StrokeJoin.Round),
    )
}

private fun DrawScope.roundFill(
    color: Color,
    x: Float,
    y: Float,
    rectW: Float,
    rectH: Float,
    corner: Float,
) {
    val w = size.width
    drawRoundRect(
        color = color,
        topLeft = Offset(x * w, y * w),
        size = Size(rectW * w, rectH * w),
        cornerRadius = CornerRadius(corner * w, corner * w),
    )
}

private fun DrawScope.stroke(color: Color, w0: Float, build: Path.() -> Unit) {
    drawPath(Path().apply(build), color, style = Stroke(w0, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.fill(color: Color, build: Path.() -> Unit) {
    drawPath(Path().apply(build), color)
}

/** The three arcs plus the dot shared by `wifi.slash`. */
private fun DrawScope.wifi(color: Color, w0: Float) {
    arc(color, w0, 0.5f, 0.86f, 0.54f, 200f, 140f)
    arc(color, w0, 0.5f, 0.86f, 0.36f, 200f, 140f)
    arc(color, w0, 0.5f, 0.86f, 0.18f, 200f, 140f)
    dot(color, 0.5f, 0.82f, 0.07f)
}

/** The cancel bar SF puts across a `.slash` symbol, corner to corner. */
private fun DrawScope.slash(color: Color, w0: Float) {
    line(color, w0, 0.12f, 0.12f, 0.88f, 0.88f)
}

/** Five round-capped bars, tallest in the middle. */
private fun DrawScope.waveformBars(color: Color, w0: Float) {
    val halves = listOf(0.16f, 0.30f, 0.42f, 0.26f, 0.14f)
    val xs = listOf(0.12f, 0.31f, 0.5f, 0.69f, 0.88f)
    for (i in xs.indices) {
        line(color, w0 * 1.4f, xs[i], 0.5f - halves[i], xs[i], 0.5f + halves[i])
    }
}

// ---------------------------------------------------------------------------
// Vector forms, for NemaraButton
// ---------------------------------------------------------------------------
//
// NemaraButton takes an ImageVector for its leading icon, so the two symbols
// that appear on buttons cannot use the Canvas path above. They are built as
// vectors instead of dropping the icon: `systemImage:` is set on both buttons in
// the Swift, and a silently missing glyph is exactly the kind of dropped detail
// this port is trying not to accumulate.

/** `plus`, on the list's "Add a sensor" button. */
internal val PlusVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "plus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(10.6f, 3f); lineTo(13.4f, 3f); lineTo(13.4f, 21f); lineTo(10.6f, 21f); close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(3f, 10.6f); lineTo(21f, 10.6f); lineTo(21f, 13.4f); lineTo(3f, 13.4f); close()
        }
    }.build()
}

/** `qrcode`, on the detail screen's "Show pairing code" button. */
internal val QrCodeVector: ImageVector by lazy {
    fun ImageVector.Builder.square(x: Float, y: Float, s: Float) {
        path(fill = SolidColor(Color.White)) {
            moveTo(x, y); lineTo(x + s, y); lineTo(x + s, y + s); lineTo(x, y + s); close()
        }
    }
    ImageVector.Builder(
        name = "qrcode",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Three finder squares, drawn as a ring plus a centre block.
        for ((fx, fy) in listOf(1f to 1f, 14f to 1f, 1f to 14f)) {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
                moveTo(fx, fy); lineTo(fx + 9f, fy); lineTo(fx + 9f, fy + 9f); lineTo(fx, fy + 9f); close()
                moveTo(fx + 2f, fy + 2f); lineTo(fx + 2f, fy + 7f)
                lineTo(fx + 7f, fy + 7f); lineTo(fx + 7f, fy + 2f); close()
            }
            square(fx + 3.5f, fy + 3.5f, 2f)
        }
        square(14f, 14f, 3f)
        square(19f, 14f, 3f)
        square(14f, 19f, 3f)
        square(19f, 19f, 3f)
    }.build()
}
