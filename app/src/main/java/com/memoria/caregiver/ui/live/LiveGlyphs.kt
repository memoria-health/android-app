package com.memoria.caregiver.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.unit.Dp

/**
 * The SF Symbols the Live tab's own chrome names, drawn.
 *
 * PROVISIONAL LOCATION, for the reason `ui/events/EventGlyphs.kt` already
 * records: iOS renders every icon with `Image(systemName:)`, Android has no SF
 * Symbols, there is no icon dependency in the version catalog and the porting
 * rules forbid adding one, so the app's convention is to draw glyphs with a
 * [Canvas]. A porting unit may not write outside its own package, so this file
 * covers only the symbols this screen needs and nothing else.
 *
 * **The room pictograms are deliberately NOT duplicated here.** The sensor list
 * inside the cameras card renders `camera.iconName`, which is the same curated
 * set `ui/cameras/SensorSymbols.kt` already draws, so this screen imports
 * `SensorSymbol` for those rows rather than making a third copy of twenty
 * drawings. When that set is lifted into `ui/components` — which both that file
 * and `EventGlyphs.kt` say is the right home — this file's chrome should go with
 * it and the import here disappears.
 *
 * The drawings are geometric approximations, not traced SF Symbol outlines. They
 * carry the same meaning at the sizes used here (8-30dp) and no more.
 */

/**
 * One SF Symbol, drawn at [size] in [tint].
 *
 * An unrecognised [name] draws a neutral filled dot rather than nothing, so a
 * symbol added later degrades to a visible marker instead of a hole in the
 * layout. Same choice as `EventGlyph`.
 */
@Composable
internal fun LiveGlyph(
    name: String,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    strokeRatio: Float = LiveGlyphSemiboldStroke,
) {
    Canvas(modifier.size(size)) {
        drawLiveGlyph(name = name, tint = tint, stroke = this.size.minDimension * strokeRatio)
    }
}

/** Stroke weight for SF `.semibold`, matching the ladder the other glyph files use. */
internal const val LiveGlyphSemiboldStroke: Float = 0.145f

/** SF `.bold` — the two pill glyphs and the call icons ask for it. */
internal const val LiveGlyphBoldStroke: Float = 0.165f

/**
 * Pictograms carry more internal detail than a chevron, so at small sizes they
 * are drawn a step lighter or they fill in solid. Same value `SensorSymbols.kt`
 * settled on.
 */
internal const val LiveGlyphPictogramStroke: Float = 0.095f

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DrawScope.drawLiveGlyph(name: String, tint: Color, stroke: Float) {
    val s = size.minDimension
    val pen = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun at(x: Float, y: Float) = Offset(x * s, y * s)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, at(x1, y1), at(x2, y2), stroke, StrokeCap.Round)

    fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * s, at(cx, cy))

    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * s, at(cx, cy), style = pen)

    fun box(x: Float, y: Float, w: Float, h: Float, corner: Float, fill: Boolean = false) =
        drawRoundRect(
            color = tint,
            topLeft = at(x, y),
            size = Size(w * s, h * s),
            cornerRadius = CornerRadius(corner * s),
            style = if (fill) Fill else pen,
        )

    fun shape(fill: Boolean = false, build: Path.(scale: Float) -> Unit) {
        drawPath(Path().apply { build(s) }, tint, style = if (fill) Fill else pen)
    }

    /** The slash SF lays over a "…slash" symbol, knocked out then re-drawn. */
    fun slash() {
        drawLine(
            Color.Transparent, at(0.08f, 0.06f), at(0.92f, 0.94f),
            stroke * 2.4f, StrokeCap.Round, blendMode = BlendMode.Clear,
        )
    }

    fun Path.almond(scale: Float) {
        moveTo(0.05f * scale, 0.50f * scale)
        quadraticTo(0.50f * scale, 0.14f * scale, 0.95f * scale, 0.50f * scale)
        quadraticTo(0.50f * scale, 0.86f * scale, 0.05f * scale, 0.50f * scale)
        close()
    }

    /** The speaker cone both volume symbols share. */
    fun Path.speakerCone(scale: Float) {
        moveTo(0.04f * scale, 0.36f * scale)
        lineTo(0.18f * scale, 0.36f * scale)
        lineTo(0.38f * scale, 0.14f * scale)
        lineTo(0.38f * scale, 0.86f * scale)
        lineTo(0.18f * scale, 0.64f * scale)
        lineTo(0.04f * scale, 0.64f * scale)
        close()
    }

    /** The rounded video-camera body plus its lens wedge. */
    fun videoBody(fill: Boolean) {
        box(0.04f, 0.28f, 0.58f, 0.44f, 0.10f, fill = fill)
        shape(fill = fill) {
            moveTo(0.68f * it, 0.42f * it)
            lineTo(0.94f * it, 0.26f * it)
            lineTo(0.94f * it, 0.74f * it)
            lineTo(0.68f * it, 0.58f * it)
            close()
        }
    }

    /** The three arcs of the antenna symbol, drawn on the given side. */
    fun antennaArcs(sign: Float) {
        for ((index, radius) in listOf(0.16f, 0.30f, 0.44f).withIndex()) {
            val sweep = 96f - index * 6f
            drawArc(
                color = tint,
                startAngle = if (sign > 0f) -sweep / 2f else 180f - sweep / 2f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = at(0.50f - radius, 0.50f - radius),
                size = Size(2f * radius * s, 2f * radius * s),
                style = pen,
            )
        }
    }

    when (name) {
        // ---- Privacy ----------------------------------------------------
        "lock.shield.fill" -> layered {
            shape(fill = true) {
                moveTo(0.50f * it, 0.04f * it)
                lineTo(0.94f * it, 0.20f * it)
                cubicTo(
                    0.94f * it, 0.62f * it, 0.78f * it, 0.88f * it,
                    0.50f * it, 0.98f * it,
                )
                cubicTo(
                    0.22f * it, 0.88f * it, 0.06f * it, 0.62f * it,
                    0.06f * it, 0.20f * it,
                )
                close()
            }
            // The padlock is a hole in the filled shield, as SF renders it.
            drawRoundRect(
                color = Color.Transparent,
                topLeft = at(0.32f, 0.46f),
                size = Size(0.36f * s, 0.28f * s),
                cornerRadius = CornerRadius(0.06f * s),
                blendMode = BlendMode.Clear,
            )
            drawArc(
                color = Color.Transparent,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = at(0.38f, 0.30f),
                size = Size(0.24f * s, 0.24f * s),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                blendMode = BlendMode.Clear,
            )
        }
        "eye.slash.fill" -> {
            layered {
                shape(fill = true) { almond(it) }
                slash()
            }
            line(0.08f, 0.06f, 0.92f, 0.94f)
        }

        // ---- Per-tile audio ---------------------------------------------
        "speaker.wave.2.fill" -> {
            shape(fill = true) { speakerCone(it) }
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = at(0.30f, 0.28f),
                size = Size(0.44f * s, 0.44f * s),
                style = pen,
            )
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = at(0.30f, 0.14f),
                size = Size(0.72f * s, 0.72f * s),
                style = pen,
            )
        }
        "speaker.slash.fill" -> {
            layered {
                shape(fill = true) { speakerCone(it) }
                slash()
            }
            line(0.08f, 0.06f, 0.92f, 0.94f)
        }

        // ---- Tile controls ----------------------------------------------
        "arrow.up.left.and.arrow.down.right" -> {
            line(0.08f, 0.08f, 0.40f, 0.40f)
            line(0.08f, 0.08f, 0.08f, 0.34f)
            line(0.08f, 0.08f, 0.34f, 0.08f)
            line(0.92f, 0.92f, 0.60f, 0.60f)
            line(0.92f, 0.92f, 0.92f, 0.66f)
            line(0.92f, 0.92f, 0.66f, 0.92f)
        }
        "xmark" -> {
            line(0.16f, 0.16f, 0.84f, 0.84f)
            line(0.84f, 0.16f, 0.16f, 0.84f)
        }
        "record.circle" -> {
            ring(0.50f, 0.50f, 0.42f)
            dot(0.50f, 0.50f, 0.20f)
        }
        "record.circle.fill" -> layered {
            dot(0.50f, 0.50f, 0.46f)
            drawCircle(Color.Transparent, 0.30f * s, at(0.50f, 0.50f), blendMode = BlendMode.Clear)
            drawCircle(tint, 0.20f * s, at(0.50f, 0.50f))
        }

        // ---- Pills --------------------------------------------------------
        "pause.fill" -> {
            box(0.18f, 0.10f, 0.22f, 0.80f, 0.05f, fill = true)
            box(0.60f, 0.10f, 0.22f, 0.80f, 0.05f, fill = true)
        }
        "pause.circle" -> {
            ring(0.50f, 0.50f, 0.42f)
            line(0.40f, 0.32f, 0.40f, 0.68f)
            line(0.60f, 0.32f, 0.60f, 0.68f)
        }

        // ---- Call ---------------------------------------------------------
        "phone.fill" -> shape(fill = true) {
            moveTo(0.10f * it, 0.16f * it)
            quadraticTo(0.26f * it, 0.02f * it, 0.36f * it, 0.16f * it)
            lineTo(0.46f * it, 0.32f * it)
            quadraticTo(0.48f * it, 0.40f * it, 0.38f * it, 0.46f * it)
            quadraticTo(0.44f * it, 0.62f * it, 0.56f * it, 0.68f * it)
            quadraticTo(0.62f * it, 0.58f * it, 0.70f * it, 0.60f * it)
            lineTo(0.86f * it, 0.70f * it)
            quadraticTo(0.99f * it, 0.79f * it, 0.86f * it, 0.94f * it)
            quadraticTo(0.72f * it, 1.04f * it, 0.44f * it, 0.80f * it)
            quadraticTo(0.14f * it, 0.52f * it, 0.10f * it, 0.16f * it)
            close()
        }
        "phone.down.fill" -> {
            // The same handset, rotated a half turn and sitting on a baseline,
            // which is how SF distinguishes hang-up from dial.
            shape(fill = true) {
                moveTo(0.06f * it, 0.62f * it)
                quadraticTo(0.50f * it, 0.24f * it, 0.94f * it, 0.62f * it)
                quadraticTo(0.98f * it, 0.72f * it, 0.86f * it, 0.74f * it)
                lineTo(0.72f * it, 0.70f * it)
                quadraticTo(0.66f * it, 0.66f * it, 0.68f * it, 0.58f * it)
                quadraticTo(0.50f * it, 0.48f * it, 0.32f * it, 0.58f * it)
                quadraticTo(0.34f * it, 0.66f * it, 0.28f * it, 0.70f * it)
                lineTo(0.14f * it, 0.74f * it)
                quadraticTo(0.02f * it, 0.72f * it, 0.06f * it, 0.62f * it)
                close()
            }
        }

        // ---- Bulk monitoring lever -----------------------------------------
        "video.fill" -> videoBody(fill = true)
        "video.slash" -> {
            layered {
                videoBody(fill = false)
                slash()
            }
            line(0.08f, 0.06f, 0.92f, 0.94f)
        }

        // ---- Cards and the sensors header -----------------------------------
        "wifi" -> {
            for ((index, radius) in listOf(0.20f, 0.34f, 0.48f).withIndex()) {
                val sweep = 92f - index * 6f
                drawArc(
                    color = tint,
                    startAngle = 180f + (90f - sweep / 2f),
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = at(0.50f - radius, 0.78f - radius),
                    size = Size(2f * radius * s, 2f * radius * s),
                    style = pen,
                )
            }
            dot(0.50f, 0.80f, 0.08f)
        }
        "antenna.radiowaves.left.and.right.slash" -> {
            layered {
                dot(0.50f, 0.50f, 0.10f)
                antennaArcs(sign = 1f)
                antennaArcs(sign = -1f)
                slash()
            }
            line(0.08f, 0.06f, 0.92f, 0.94f)
        }
        "iphone.slash" -> {
            layered {
                box(0.26f, 0.04f, 0.48f, 0.92f, 0.10f)
                line(0.44f, 0.14f, 0.56f, 0.14f)
                slash()
            }
            line(0.08f, 0.06f, 0.92f, 0.94f)
        }

        // Unknown symbol: a neutral marker, never an empty box.
        else -> dot(0.50f, 0.50f, 0.18f)
    }
}

/**
 * Run [block] against an offscreen layer, so a `BlendMode.Clear` inside it cuts
 * the glyph rather than punching through to whatever is behind the composable.
 * Every "…slash" and every filled symbol with interior detail needs it, because
 * SF renders that detail as a hole.
 */
private inline fun DrawScope.layered(block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    block()
    canvas.restore()
}
