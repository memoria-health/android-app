package com.memoria.caregiver.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Line

/**
 * The SF Symbols the onboarding and enrollment screens name, drawn with Canvas.
 *
 * Same approach as `CountryPicker.kt` and `SettingsGlyphs.kt`: there is no
 * material-icons dependency in the version catalog and the porting rules forbid
 * adding one, so each symbol is a simplified line mark laid out on a square box
 * of the symbol's point size, with round caps to match SF Symbol terminals.
 *
 * Stroke weight is a fraction of the box: 0.145 for semibold, matching the two
 * files above, and 0.19 for bold.
 */

private const val SEMIBOLD_STROKE_RATIO = 0.145f
private const val BOLD_STROKE_RATIO = 0.19f

/** `xmark` — the sheet's close button, 18pt semibold. */
@Composable
internal fun XmarkGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        val inset = w * 0.18f
        drawLine(
            color = color,
            start = Offset(inset, inset),
            end = Offset(w - inset, w - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w - inset, inset),
            end = Offset(inset, w - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** `chevron.down` — the gender menu's affordance, 12pt semibold. */
@Composable
internal fun ChevronDownGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        val inset = stroke / 2f
        drawPolyline(
            points = listOf(
                Offset(inset, w * 0.30f),
                Offset(w / 2f, w * 0.70f),
                Offset(w - inset, w * 0.30f),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/**
 * `calendar` — the date-of-birth field's trailing mark, 13pt semibold. An
 * outlined page with a filled header band, which is what the symbol reads as at
 * this size; the day dots below it do not survive 13dp.
 */
@Composable
internal fun CalendarGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.11f
        val top = w * 0.14f
        val bottom = w * 0.94f
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, top),
            size = Size(w - stroke, bottom - top),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(stroke / 2f, top + w * 0.24f),
            end = Offset(w - stroke / 2f, top + w * 0.24f),
            strokeWidth = stroke,
        )
        // The two binder tabs above the page.
        for (x in listOf(w * 0.30f, w * 0.70f)) {
            drawLine(
                color = color,
                start = Offset(x, w * 0.02f),
                end = Offset(x, top + w * 0.06f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * `faceid` — the consent screen's mark, 28pt semibold. Four corner brackets
 * around two eyes and a mouth, which is the symbol's construction.
 */
@Composable
internal fun FaceIdGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.09f
        val near = w * 0.10f
        val far = w * 0.90f
        val arm = w * 0.22f

        // One corner bracket: an arm along y, the corner, then an arm along x.
        // dx/dy are +1 or -1, pointing back toward the middle of the box.
        fun bracket(cornerX: Float, cornerY: Float, dx: Float, dy: Float) {
            drawPolyline(
                points = listOf(
                    Offset(cornerX, cornerY + arm * dy),
                    Offset(cornerX, cornerY),
                    Offset(cornerX + arm * dx, cornerY),
                ),
                color = color,
                strokeWidth = stroke,
            )
        }
        bracket(near, near, 1f, 1f)
        bracket(far, near, -1f, 1f)
        bracket(near, far, 1f, -1f)
        bracket(far, far, -1f, -1f)

        // Eyes.
        for (x in listOf(w * 0.34f, w * 0.66f)) {
            drawLine(
                color = color,
                start = Offset(x, w * 0.34f),
                end = Offset(x, w * 0.46f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        // Nose.
        drawPolyline(
            points = listOf(
                Offset(w * 0.50f, w * 0.36f),
                Offset(w * 0.50f, w * 0.56f),
                Offset(w * 0.42f, w * 0.56f),
            ),
            color = color,
            strokeWidth = stroke,
        )
        // Mouth.
        drawPolyline(
            points = listOf(
                Offset(w * 0.34f, w * 0.66f),
                Offset(w * 0.42f, w * 0.72f),
                Offset(w * 0.58f, w * 0.72f),
                Offset(w * 0.66f, w * 0.66f),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/** `camera.fill` — the no-camera placeholder's mark, 30pt. */
@Composable
internal fun CameraFillGlyph(size: Dp, color: Color, knockout: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.30f, w * 0.14f),
            size = Size(w * 0.26f, w * 0.16f),
            cornerRadius = CornerRadius(w * 0.05f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.02f, w * 0.26f),
            size = Size(w * 0.96f, w * 0.60f),
            cornerRadius = CornerRadius(w * 0.14f),
        )
        drawCircle(color = knockout, radius = w * 0.19f, center = Offset(w * 0.5f, w * 0.56f))
    }
}

/** `checkmark` — the enrollment-complete tick, drawn bold. */
@Composable
internal fun CheckmarkGlyph(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawPolyline(
            points = listOf(
                Offset(w * 0.12f, w * 0.55f),
                Offset(w * 0.38f, w * 0.82f),
                Offset(w * 0.88f, w * 0.20f),
            ),
            color = color,
            strokeWidth = w * BOLD_STROKE_RATIO,
        )
    }
}

/**
 * Round-capped segments through [points]. Drawn as separate lines rather than a
 * Path: with round caps, consecutive segments meeting at a shared point render
 * the same as a round join. See CountryPicker.kt.
 */
private fun DrawScope.drawPolyline(points: List<Offset>, color: Color, strokeWidth: Float) {
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

/**
 * Tap with no ripple and no indication — SwiftUI's `.buttonStyle(.plain)`, which
 * every tappable label in this flow uses. One copy per package, as in
 * `LoginScreen.kt` and `DetectorGlyphs.kt`.
 */
@Composable
internal fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        // Every one of these is a SwiftUI `Button` on iOS, and a button tells a
        // screen reader that it is a button. Without a role TalkBack reads the
        // label as ordinary text: the caregiver hears the words and is never
        // told they can be tapped. Set inside the helper rather than at each
        // call site so a new caller cannot be added without it. Controls that
        // are a CHOICE rather than an action use `plainSelectable` or
        // `plainToggleable`, which carry the role their state needs.
        role = Role.Button,
        onClick = onClick,
    )
}

/**
 * The 1px rule under a header. iOS:
 * `.overlay(NemaraColor.line.frame(height: 1), alignment: .bottom)`.
 *
 * Drawn rather than added as a `Row`, so it cannot take part in layout and shift
 * the header's height.
 */
internal fun Modifier.bottomHairline(): Modifier = drawBehind {
    val thickness = 1.dp.toPx()
    drawRect(
        color = Line,
        topLeft = Offset(0f, size.height - thickness),
        size = Size(size.width, thickness),
    )
}

/** The same rule above a footer. iOS: the same overlay at `.top`. */
internal fun Modifier.topHairline(): Modifier = drawBehind {
    val thickness = 1.dp.toPx()
    drawRect(color = Line, size = Size(size.width, thickness))
}
