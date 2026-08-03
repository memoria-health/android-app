package com.memoria.caregiver.ui.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp

/**
 * PROVISIONAL, AND IT SHOULD NOT LIVE HERE.
 *
 * iOS renders every icon in this feature with `Image(systemName:)` — SF Symbols,
 * which Android does not have. There is no icon dependency in the version
 * catalog and the porting rules forbid adding one, so the app's convention
 * (`CountryPicker.kt`, `LoginScreen.kt`) is to draw glyphs with [Canvas]. This
 * file follows that convention for the symbols the event screens name.
 *
 * **It is scoped to `ui.events` only because a porting unit may not write outside
 * its own package.** The same 20-symbol curated set is product surface for the
 * detector flows too (`AddDetectorView.swift` `iconOptions`, mirrored by the
 * backend's `_ALLOWED_ICONS`), and PARITY.md §17 requires Android to ship an
 * equivalent icon set. The right home is `ui/components`, shared by events,
 * detectors and sensors. Lifting it there is a move, not a rewrite.
 *
 * The drawings are geometric approximations, not traced SF Symbol outlines. They
 * carry the same meaning at the sizes used here (9-30dp) and no more.
 */

/**
 * Stroke weight as a fraction of the glyph box, matching the ladder
 * `LoginScreen.kt` established: 0.145 for semibold, 0.19 for black. Regular and
 * bold fill in the two steps those two screens did not need.
 */
internal enum class GlyphWeight(val strokeRatio: Float) {
    Regular(0.11f),
    Semibold(0.145f),
    Bold(0.165f),
    Black(0.19f),
}

/**
 * One SF Symbol, drawn.
 *
 * [size] is the box, mirroring the point size in `Image(systemName:).font(...)`.
 * An unrecognised [name] draws a neutral filled dot rather than nothing, so a
 * symbol the backend adds later degrades to a visible marker instead of a hole
 * in the layout.
 *
 * The glyph is drawn into a square of `min(width, height)`, so a caller that
 * hands this a non-square slot still gets an undistorted mark.
 */
@Composable
internal fun EventGlyph(
    name: String,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    weight: GlyphWeight = GlyphWeight.Semibold,
) {
    Canvas(modifier.size(size)) {
        drawGlyph(name = name, tint = tint, stroke = this.size.minDimension * weight.strokeRatio)
    }
}

// ---------------------------------------------------------------------------
// The drawings
// ---------------------------------------------------------------------------

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DrawScope.drawGlyph(name: String, tint: Color, stroke: Float) {
    val s = size.minDimension
    val pen = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun at(x: Float, y: Float) = Offset(x * s, y * s)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, at(x1, y1), at(x2, y2), stroke, StrokeCap.Round)

    fun poly(vararg xy: Float) {
        var i = 0
        while (i + 3 < xy.size) {
            line(xy[i], xy[i + 1], xy[i + 2], xy[i + 3])
            i += 2
        }
    }

    fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * s, at(cx, cy))

    fun ring(cx: Float, cy: Float, r: Float) =
        drawCircle(tint, r * s, at(cx, cy), style = pen)

    fun box(x: Float, y: Float, w: Float, h: Float, corner: Float, fill: Boolean = false) =
        drawRoundRect(
            color = tint,
            topLeft = at(x, y),
            size = Size(w * s, h * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * s),
            style = if (fill) Fill else pen,
        )

    fun shape(fill: Boolean = false, build: Path.(scale: Float) -> Unit) {
        val path = Path().apply { build(s) }
        drawPath(path, tint, style = if (fill) Fill else pen)
    }

    // The almond that "eye" and "eye.slash.fill" share.
    fun Path.almond(scale: Float) {
        moveTo(0.05f * scale, 0.50f * scale)
        quadraticTo(0.50f * scale, 0.14f * scale, 0.95f * scale, 0.50f * scale)
        quadraticTo(0.50f * scale, 0.86f * scale, 0.05f * scale, 0.50f * scale)
        close()
    }

    fun Path.triangle(scale: Float) {
        moveTo(0.50f * scale, 0.09f * scale)
        lineTo(0.98f * scale, 0.88f * scale)
        lineTo(0.02f * scale, 0.88f * scale)
        close()
    }

    when (name) {
        // ---- the 20 curated detector symbols (backend _ALLOWED_ICONS) ----
        "eye" -> {
            shape { almond(it) }
            dot(0.50f, 0.50f, 0.13f)
        }
        "eye.slash.fill" -> {
            // SF knocks the slash out of the filled eye. Reproduced with a
            // layer so the Clear blend cuts the glyph and not the background.
            layered {
                shape(fill = true) { almond(it) }
                drawLine(
                    Color.Transparent, at(0.10f, 0.08f), at(0.90f, 0.92f),
                    stroke * 2.4f, StrokeCap.Round, blendMode = BlendMode.Clear,
                )
            }
            line(0.10f, 0.08f, 0.90f, 0.92f)
        }
        "figure.fall" -> {
            dot(0.74f, 0.20f, 0.11f)
            poly(0.66f, 0.36f, 0.34f, 0.54f)
            poly(0.66f, 0.36f, 0.84f, 0.54f)
            poly(0.34f, 0.54f, 0.14f, 0.44f)
            poly(0.34f, 0.54f, 0.40f, 0.86f)
            poly(0.34f, 0.54f, 0.10f, 0.76f)
        }
        "figure.walk.motion" -> {
            dot(0.56f, 0.14f, 0.10f)
            poly(0.56f, 0.28f, 0.48f, 0.54f)
            poly(0.48f, 0.54f, 0.66f, 0.88f)
            poly(0.48f, 0.54f, 0.32f, 0.86f)
            poly(0.54f, 0.36f, 0.72f, 0.46f)
            line(0.04f, 0.30f, 0.20f, 0.30f)
            line(0.00f, 0.52f, 0.16f, 0.52f)
            line(0.06f, 0.74f, 0.20f, 0.74f)
        }
        "door.left.hand.open" -> {
            line(0.12f, 0.06f, 0.12f, 0.94f)
            line(0.06f, 0.94f, 0.94f, 0.94f)
            shape {
                moveTo(0.34f * it, 0.18f * it)
                lineTo(0.88f * it, 0.06f * it)
                lineTo(0.88f * it, 0.86f * it)
                lineTo(0.34f * it, 0.94f * it)
                close()
            }
            dot(0.44f, 0.54f, 0.055f)
        }
        "pills" -> {
            rotate(-40f, at(0.36f, 0.34f)) {
                drawRoundRect(
                    color = tint,
                    topLeft = at(0.04f, 0.20f),
                    size = Size(0.64f * s, 0.28f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.14f * s),
                    style = pen,
                )
                drawLine(tint, at(0.36f, 0.20f), at(0.36f, 0.48f), stroke, StrokeCap.Round)
            }
            ring(0.72f, 0.72f, 0.24f)
            line(0.55f, 0.72f, 0.89f, 0.72f)
        }
        "fork.knife" -> {
            line(0.14f, 0.06f, 0.14f, 0.30f)
            line(0.28f, 0.06f, 0.28f, 0.30f)
            line(0.42f, 0.06f, 0.42f, 0.30f)
            line(0.14f, 0.30f, 0.42f, 0.30f)
            line(0.28f, 0.30f, 0.28f, 0.94f)
            shape {
                moveTo(0.74f * it, 0.52f * it)
                quadraticTo(0.94f * it, 0.34f * it, 0.78f * it, 0.06f * it)
                quadraticTo(0.70f * it, 0.28f * it, 0.74f * it, 0.52f * it)
                close()
            }
            line(0.76f, 0.52f, 0.76f, 0.94f)
        }
        "bed.double" -> {
            line(0.06f, 0.24f, 0.06f, 0.88f)
            line(0.06f, 0.88f, 0.96f, 0.88f)
            box(0.06f, 0.50f, 0.90f, 0.24f, 0.08f)
            box(0.16f, 0.34f, 0.30f, 0.16f, 0.06f)
            box(0.56f, 0.34f, 0.30f, 0.16f, 0.06f)
        }
        "shower" -> {
            line(0.50f, 0.04f, 0.50f, 0.30f)
            box(0.20f, 0.30f, 0.60f, 0.14f, 0.07f)
            line(0.28f, 0.56f, 0.24f, 0.72f)
            line(0.50f, 0.58f, 0.50f, 0.76f)
            line(0.72f, 0.56f, 0.76f, 0.72f)
        }
        "tv" -> {
            box(0.04f, 0.16f, 0.92f, 0.58f, 0.10f)
            line(0.30f, 0.90f, 0.70f, 0.90f)
            line(0.42f, 0.74f, 0.38f, 0.90f)
            line(0.58f, 0.74f, 0.62f, 0.90f)
        }
        "drop" -> shape {
            moveTo(0.50f * it, 0.04f * it)
            quadraticTo(0.92f * it, 0.50f * it, 0.78f * it, 0.72f * it)
            quadraticTo(0.50f * it, 1.02f * it, 0.22f * it, 0.72f * it)
            quadraticTo(0.08f * it, 0.50f * it, 0.50f * it, 0.04f * it)
            close()
        }
        "flame" -> shape {
            moveTo(0.50f * it, 0.03f * it)
            quadraticTo(0.86f * it, 0.36f * it, 0.80f * it, 0.62f * it)
            quadraticTo(0.76f * it, 0.96f * it, 0.50f * it, 0.96f * it)
            quadraticTo(0.24f * it, 0.96f * it, 0.20f * it, 0.62f * it)
            quadraticTo(0.16f * it, 0.38f * it, 0.44f * it, 0.30f * it)
            quadraticTo(0.54f * it, 0.20f * it, 0.50f * it, 0.03f * it)
            close()
        }
        "exclamationmark.triangle" -> {
            shape { triangle(it) }
            line(0.50f, 0.40f, 0.50f, 0.62f)
            dot(0.50f, 0.75f, 0.055f)
        }
        "exclamationmark.triangle.fill" -> layered {
            shape(fill = true) { triangle(it) }
            drawLine(
                Color.Transparent, at(0.50f, 0.40f), at(0.50f, 0.62f),
                stroke, StrokeCap.Round, blendMode = BlendMode.Clear,
            )
            drawCircle(
                Color.Transparent, 0.055f * s, at(0.50f, 0.75f),
                blendMode = BlendMode.Clear,
            )
        }
        "heart" -> shape {
            moveTo(0.50f * it, 0.90f * it)
            cubicTo(
                0.06f * it, 0.60f * it, 0.02f * it, 0.32f * it,
                0.22f * it, 0.18f * it,
            )
            cubicTo(
                0.36f * it, 0.07f * it, 0.46f * it, 0.16f * it,
                0.50f * it, 0.28f * it,
            )
            cubicTo(
                0.54f * it, 0.16f * it, 0.64f * it, 0.07f * it,
                0.78f * it, 0.18f * it,
            )
            cubicTo(
                0.98f * it, 0.32f * it, 0.94f * it, 0.60f * it,
                0.50f * it, 0.90f * it,
            )
            close()
        }
        "person" -> {
            ring(0.50f, 0.26f, 0.19f)
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = at(0.10f, 0.54f),
                size = Size(0.80f * s, 0.68f * s),
                style = pen,
            )
        }
        "cup.and.saucer" -> {
            shape {
                moveTo(0.18f * it, 0.28f * it)
                lineTo(0.24f * it, 0.62f * it)
                quadraticTo(0.46f * it, 0.78f * it, 0.68f * it, 0.62f * it)
                lineTo(0.74f * it, 0.28f * it)
                close()
            }
            drawArc(
                color = tint,
                startAngle = -70f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = at(0.66f, 0.30f),
                size = Size(0.28f * s, 0.28f * s),
                style = pen,
            )
            line(0.04f, 0.86f, 0.96f, 0.86f)
        }
        "moon" -> shape {
            moveTo(0.68f * it, 0.06f * it)
            cubicTo(
                0.28f * it, 0.16f * it, 0.20f * it, 0.52f * it,
                0.44f * it, 0.76f * it,
            )
            cubicTo(
                0.58f * it, 0.90f * it, 0.76f * it, 0.94f * it,
                0.90f * it, 0.88f * it,
            )
            cubicTo(
                0.56f * it, 0.80f * it, 0.42f * it, 0.48f * it,
                0.68f * it, 0.06f * it,
            )
            close()
        }
        "sun.max" -> {
            ring(0.50f, 0.50f, 0.22f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val cos = kotlin.math.cos(a).toFloat()
                val sin = kotlin.math.sin(a).toFloat()
                line(
                    0.50f + 0.32f * cos, 0.50f + 0.32f * sin,
                    0.50f + 0.46f * cos, 0.50f + 0.46f * sin,
                )
            }
        }
        "clock" -> {
            ring(0.50f, 0.50f, 0.42f)
            line(0.50f, 0.50f, 0.50f, 0.26f)
            line(0.50f, 0.50f, 0.68f, 0.58f)
        }
        "bell" -> {
            dot(0.50f, 0.08f, 0.06f)
            shape {
                moveTo(0.12f * it, 0.74f * it)
                cubicTo(
                    0.28f * it, 0.66f * it, 0.24f * it, 0.44f * it,
                    0.30f * it, 0.32f * it,
                )
                quadraticTo(0.38f * it, 0.14f * it, 0.50f * it, 0.14f * it)
                quadraticTo(0.62f * it, 0.14f * it, 0.70f * it, 0.32f * it)
                cubicTo(
                    0.76f * it, 0.44f * it, 0.72f * it, 0.66f * it,
                    0.88f * it, 0.74f * it,
                )
                close()
            }
            drawArc(
                color = tint,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = at(0.38f, 0.76f),
                size = Size(0.24f * s, 0.18f * s),
                style = pen,
            )
        }
        "car" -> {
            box(0.02f, 0.44f, 0.96f, 0.28f, 0.10f)
            poly(0.20f, 0.44f, 0.30f, 0.22f, 0.70f, 0.22f, 0.80f, 0.44f)
            ring(0.26f, 0.76f, 0.10f)
            ring(0.74f, 0.76f, 0.10f)
        }

        // ---- symbols used by the event screens themselves ----
        "tray" -> {
            box(0.03f, 0.30f, 0.94f, 0.44f, 0.10f)
            poly(
                0.03f, 0.56f, 0.28f, 0.56f, 0.36f, 0.68f,
                0.64f, 0.68f, 0.72f, 0.56f, 0.97f, 0.56f,
            )
        }
        "checkmark.circle" -> {
            ring(0.50f, 0.50f, 0.44f)
            poly(0.28f, 0.52f, 0.44f, 0.68f, 0.72f, 0.34f)
        }
        "film" -> {
            box(0.03f, 0.16f, 0.94f, 0.68f, 0.08f)
            line(0.24f, 0.16f, 0.24f, 0.84f)
            line(0.76f, 0.16f, 0.76f, 0.84f)
            for (y in listOf(0.28f, 0.46f, 0.64f)) {
                box(0.09f, y, 0.09f, 0.09f, 0.02f, fill = true)
                box(0.82f, y, 0.09f, 0.09f, 0.02f, fill = true)
            }
        }
        "sparkles" -> {
            fun star(cx: Float, cy: Float, r: Float) = shape(fill = true) {
                moveTo(cx * it, (cy - r) * it)
                quadraticTo(cx * it, cy * it, (cx + r) * it, cy * it)
                quadraticTo(cx * it, cy * it, cx * it, (cy + r) * it)
                quadraticTo(cx * it, cy * it, (cx - r) * it, cy * it)
                quadraticTo(cx * it, cy * it, cx * it, (cy - r) * it)
                close()
            }
            star(0.40f, 0.40f, 0.38f)
            star(0.80f, 0.80f, 0.20f)
        }
        "chevron.backward", "chevron.left" -> poly(0.68f, 0.08f, 0.30f, 0.50f, 0.68f, 0.92f)
        "chevron.right" -> poly(0.32f, 0.08f, 0.70f, 0.50f, 0.32f, 0.92f)
        "checkmark" -> poly(0.12f, 0.55f, 0.38f, 0.82f, 0.88f, 0.20f)
        // Three dots on the centre line. SF sizes them off the stroke weight, so
        // they thicken with the symbol's weight rather than staying fixed.
        "ellipsis" -> for (cx in listOf(0.18f, 0.50f, 0.82f)) {
            drawCircle(tint, stroke * 0.8f, at(cx, 0.50f))
        }
        "xmark" -> {
            line(0.16f, 0.16f, 0.84f, 0.84f)
            line(0.84f, 0.16f, 0.16f, 0.84f)
        }

        // Unknown symbol: a neutral marker, never an empty box.
        else -> dot(0.50f, 0.50f, 0.18f)
    }
}

/**
 * Run [block] against an offscreen layer, so a `BlendMode.Clear` inside it cuts
 * the glyph rather than punching through to whatever is behind the composable.
 */
private inline fun DrawScope.layered(block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    block()
    canvas.restore()
}
