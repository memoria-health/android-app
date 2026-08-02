package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// SF Symbols used by the patient Settings tab, drawn by hand.
// ---------------------------------------------------------------------------
//
// The Swift names eleven SF Symbols across `PatientSettingsView` and its four
// cards: person / person.fill, speaker.wave.2.fill / speaker.slash,
// waveform.badge.magnifyingglass, envelope.fill, camera.fill, pencil, trash,
// sparkles, chevron.right, checkmark.circle.fill, exclamationmark.triangle.fill
// and circle.dashed.
//
// There is no material-icons dependency in the version catalog and the porting
// rules forbid adding one, so they are drawn here — the same approach
// CountryPicker.kt and LoginScreen.kt already take for their glyphs, with the
// same 0.145-of-the-box stroke weight for SF Symbol `.semibold`.
//
// **These are approximations of Apple's shapes, not the shapes themselves**, and
// they are the most likely thing on this screen to need a nudge during the
// screenshot pass. Every coordinate below is a fraction of the glyph box, so
// they scale with the size the call site asks for.
//
// The `.fill` symbols (envelope, camera, checkmark.circle,
// exclamationmark.triangle) render their inner detail as negative space. Canvas
// has no cheap knockout, so those take a [knockout] color: the call site passes
// whatever is painted behind the glyph.

@Composable
internal fun PersonGlyph(size: Dp, color: Color, filled: Boolean) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val head = Offset(w * 0.5f, w * 0.27f)
        val headRadius = w * 0.185f
        // The shoulders are the top half of an oval that runs off the bottom of
        // the box, which is what gives SF's `person` its wide, shallow dome.
        val shoulders = Rect(
            offset = Offset(w * 0.13f, w * 0.55f),
            size = Size(w * 0.74f, w * 0.80f),
        )
        if (filled) {
            drawCircle(color = color, radius = headRadius, center = head)
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = shoulders.topLeft,
                size = shoulders.size,
            )
        } else {
            val stroke = w * 0.11f
            drawCircle(
                color = color,
                radius = headRadius,
                center = head,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = shoulders.topLeft,
                size = shoulders.size,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** `speaker.wave.2.fill` — filled cone plus two sound arcs. */
@Composable
internal fun SpeakerWaveGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawSpeakerCone(w, color, filled = true)
        val stroke = w * 0.09f
        for (radius in listOf(w * 0.30f, w * 0.44f)) {
            drawArc(
                color = color,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(w * 0.5f - radius, w * 0.5f - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** `speaker.slash` — outlined cone, no waves, struck through. */
@Composable
internal fun SpeakerSlashGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawSpeakerCone(w, color, filled = false)
        drawLine(
            color = color,
            start = Offset(w * 0.14f, w * 0.86f),
            end = Offset(w * 0.86f, w * 0.14f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
    }
}

/** The body shared by both speaker symbols: a small box with a flared cone. */
private fun DrawScope.drawSpeakerCone(w: Float, color: Color, filled: Boolean) {
    val cone = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.04f, w * 0.36f)
        lineTo(w * 0.20f, w * 0.36f)
        lineTo(w * 0.44f, w * 0.14f)
        lineTo(w * 0.44f, w * 0.86f)
        lineTo(w * 0.20f, w * 0.64f)
        lineTo(w * 0.04f, w * 0.64f)
        close()
    }
    drawPath(
        path = cone,
        color = color,
        style = if (filled) {
            androidx.compose.ui.graphics.drawscope.Fill
        } else {
            Stroke(width = w * 0.10f, join = StrokeJoin.Round)
        },
    )
}

/** `waveform.badge.magnifyingglass` — four bars with a magnifier badge. */
@Composable
internal fun WaveformMagnifierGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.10f
        // Half-heights, so the bars stay centred on the glyph's midline like the
        // SF symbol's.
        val bars = listOf(
            w * 0.08f to w * 0.18f,
            w * 0.26f to w * 0.36f,
            w * 0.44f to w * 0.24f,
            w * 0.62f to w * 0.30f,
        )
        for ((x, half) in bars) {
            drawLine(
                color = color,
                start = Offset(x, w * 0.44f - half),
                end = Offset(x, w * 0.44f + half),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        val badgeCenter = Offset(w * 0.74f, w * 0.74f)
        drawCircle(
            color = color,
            radius = w * 0.17f,
            center = badgeCenter,
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.86f, w * 0.86f),
            end = Offset(w * 0.97f, w * 0.97f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** `envelope.fill` — solid body with the flap knocked out. */
@Composable
internal fun EnvelopeFillGlyph(size: Dp, color: Color, knockout: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.04f, w * 0.20f),
            size = Size(w * 0.92f, w * 0.60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
        )
        drawPolyline(
            points = listOf(
                Offset(w * 0.10f, w * 0.28f),
                Offset(w * 0.50f, w * 0.56f),
                Offset(w * 0.90f, w * 0.28f),
            ),
            color = knockout,
            strokeWidth = w * 0.10f,
        )
    }
}

/** `camera.fill` — body, viewfinder bump, and a knocked-out lens. */
@Composable
internal fun CameraFillGlyph(size: Dp, color: Color, knockout: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.30f, w * 0.14f),
            size = Size(w * 0.26f, w * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.02f, w * 0.26f),
            size = Size(w * 0.96f, w * 0.60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f),
        )
        drawCircle(color = knockout, radius = w * 0.19f, center = Offset(w * 0.5f, w * 0.56f))
    }
}

// `sparkles` and `chevron.right` are NOT defined here. Both are already drawn by
// SettingsChrome.kt, which shares this package (the account-settings screens use
// the same two symbols), and a second `internal` copy would be a redeclaration.
// The feedback row calls those.

/** `checkmark.circle.fill` — solid disc with the tick knocked out. */
@Composable
internal fun CheckmarkCircleFillGlyph(size: Dp, color: Color, knockout: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(color = color, radius = w / 2f, center = Offset(w / 2f, w / 2f))
        drawPolyline(
            points = listOf(
                Offset(w * 0.28f, w * 0.52f),
                Offset(w * 0.44f, w * 0.68f),
                Offset(w * 0.73f, w * 0.34f),
            ),
            color = knockout,
            strokeWidth = w * 0.11f,
        )
    }
}

/** `exclamationmark.triangle.fill` — solid triangle with the bang knocked out. */
@Composable
internal fun ExclamationTriangleFillGlyph(size: Dp, color: Color, knockout: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val triangle = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.50f, w * 0.08f)
            lineTo(w * 0.98f, w * 0.90f)
            lineTo(w * 0.02f, w * 0.90f)
            close()
        }
        drawPath(path = triangle, color = color)
        drawLine(
            color = knockout,
            start = Offset(w * 0.50f, w * 0.38f),
            end = Offset(w * 0.50f, w * 0.64f),
            strokeWidth = w * 0.10f,
            cap = StrokeCap.Round,
        )
        drawCircle(color = knockout, radius = w * 0.055f, center = Offset(w * 0.50f, w * 0.78f))
    }
}

/** `circle.dashed` — the neutral "nothing loaded yet" indicator. */
@Composable
internal fun CircleDashedGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.09f
        drawCircle(
            color = color,
            radius = w / 2f - stroke / 2f,
            center = Offset(w / 2f, w / 2f),
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(w * 0.10f, w * 0.09f),
                ),
            ),
        )
    }
}

/**
 * Round-capped segments through [points]. Same helper, same rationale, as
 * CountryPicker.kt: with round caps, consecutive segments meeting at a shared
 * point render the same as a round join, and it keeps the glyph definitions
 * readable as coordinate lists.
 */
private fun DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    for (i in 0 until points.size - 1) {
        drawLine(
            color = color,
            start = points[i],
            end = points[i + 1],
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

// ---------------------------------------------------------------------------
// The two glyphs that must be ImageVectors
// ---------------------------------------------------------------------------
//
// `NemaraButton` takes an `ImageVector`, not a composable, because that is the
// shape `Icon` needs — so `pencil` and `trash` cannot be Canvas drawings like
// the rest. They are built as 24x24 vector paths here. `Icon` recolors the whole
// painter with its tint, so the fill declared below never reaches the screen.

private fun settingsIcon(
    name: String,
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder)
}.build()

/** `pencil` — the Edit affordance on the profile card. */
internal val PencilIcon: ImageVector by lazy {
    settingsIcon("pencil") {
        moveTo(3f, 17.25f)
        verticalLineTo(21f)
        horizontalLineTo(6.75f)
        lineTo(17.81f, 9.94f)
        lineTo(14.06f, 6.19f)
        close()
        moveTo(20.71f, 7.04f)
        curveTo(21.1f, 6.65f, 21.1f, 6.02f, 20.71f, 5.63f)
        lineTo(18.37f, 3.29f)
        curveTo(17.98f, 2.9f, 17.35f, 2.9f, 16.96f, 3.29f)
        lineTo(15.13f, 5.12f)
        lineTo(18.88f, 8.87f)
        close()
    }
}

/** `trash` — the Remove photo affordance on the profile card. */
internal val TrashIcon: ImageVector by lazy {
    settingsIcon("trash") {
        moveTo(6f, 19f)
        curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
        horizontalLineTo(16f)
        curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
        verticalLineTo(7f)
        horizontalLineTo(6f)
        close()
        moveTo(19f, 4f)
        horizontalLineTo(15.5f)
        lineTo(14.5f, 3f)
        horizontalLineTo(9.5f)
        lineTo(8.5f, 4f)
        horizontalLineTo(5f)
        verticalLineTo(6f)
        horizontalLineTo(19f)
        close()
    }
}
