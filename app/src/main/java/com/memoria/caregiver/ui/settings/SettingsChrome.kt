package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.PaperDim

/**
 * The pieces SwiftUI hands `AppSettingsView` for free and Compose does not: SF
 * Symbols, the `iconTile` helper, the hairline row separator, and a tap with no
 * ripple.
 *
 * ## The symbols are drawn, and they are approximations
 *
 * Seven SF Symbols are named across the two screens — `chevron.right`, `plus`,
 * `lock.shield.fill`, `envelope.fill`, `sparkles`,
 * `rectangle.portrait.and.arrow.right`, `trash.fill`. There is no icon
 * dependency in the version catalog, this task may not add one, and the app
 * ships no drawables, so each is drawn with `Canvas` — the same approach
 * `CountryPicker.kt` takes for `chevron.down` and `checkmark` and `LoginScreen.kt`
 * takes for `chevron.left`.
 *
 * `chevron.right` and `plus` are geometrically simple and are faithful. **The
 * five filled symbols in the account card are hand-drawn stand-ins**: the
 * silhouettes are recognizably the same objects at 14dp, but they are not
 * Apple's paths and will not survive a pixel diff. They want real vector assets
 * exported from the iOS build, which is a repo-level asset decision rather than
 * something this unit can make. Flagged, not hidden.
 *
 * Geometry below is written as fractions of the glyph box, so a symbol drawn at
 * 13dp and one drawn at 14dp share one description.
 */

// ---------------------------------------------------------------------------
// Tuning constants, carried from AppSettingsView.swift
// ---------------------------------------------------------------------------

/** `iconTile`: `.frame(width: 32, height: 32)`. */
internal val IconTileSize = 32.dp

/** `iconTile`: `RoundedRectangle(cornerRadius: 10, style: .continuous)`. */
internal val IconTileRadius = 10.dp

/** `iconTile`: `Image(systemName:).font(.system(size: 14, weight: .semibold))`. */
internal val IconTileGlyphSize = 14.dp

/** Every chevron on these screens: `.font(.system(size: 13, weight: .semibold))`. */
internal val RowChevronSize = 13.dp

/** `NemaraColor.line.frame(height: 1)` between card rows. Not a hairline — 1pt. */
internal val RowSeparatorHeight = 1.dp

/**
 * Stroke weight of a semibold SF Symbol glyph as a fraction of its box, matching
 * `CountryPicker.kt` and `LoginScreen.kt`.
 */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

// ---------------------------------------------------------------------------
// Shared chrome
// ---------------------------------------------------------------------------

/**
 * Tap with no ripple and no indication — SwiftUI's `.buttonStyle(.plain)`, which
 * every row and card on these two screens uses.
 *
 * Rows also carry `.contentShape(Rectangle())` on iOS so the transparent gap the
 * `Spacer` opens up is hit-testable. `Modifier.clickable` already covers the
 * node's whole box, so that comes for free here — but only if the modifier is
 * applied **before** the row's padding, which is why every call site orders it
 * that way.
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

/** `NemaraColor.line.frame(height: 1)` — the divider between rows inside a card. */
@Composable
internal fun RowSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(RowSeparatorHeight)
            .background(Line),
    )
}

/**
 * `iconTile(_:)` — a 32x32 rounded square of `paperDim` with a 14pt semibold
 * symbol in `ink2` centred on it.
 *
 * iOS uses `style: .continuous` (a squircle). `RoundedCornerShape` is a circular
 * corner, the same substitution `NemaraMark` makes and records in DIFFERENCES.md
 * D11.
 */
@Composable
internal fun IconTile(glyph: @Composable (Dp, Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(IconTileSize)
            .background(PaperDim, RoundedCornerShape(IconTileRadius)),
        contentAlignment = Alignment.Center,
    ) {
        glyph(IconTileGlyphSize, Ink2)
    }
}

// ---------------------------------------------------------------------------
// Symbols
// ---------------------------------------------------------------------------

/** `chevron.right`, semibold. The mirror of `LoginScreen`'s `chevron.left`. */
@Composable
internal fun ChevronRightGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        val inset = stroke / 2f
        drawPolyline(
            points = listOf(
                Offset(w * 0.32f, inset),
                Offset(w * 0.70f, w / 2f),
                Offset(w * 0.32f, w - inset),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/**
 * `lock.shield.fill` — a filled shield with a padlock knocked out of it.
 *
 * The knockout is painted in [IconTilePaper] rather than punched with a blend
 * mode, because the tile behind it is a flat colour and a second fill is both
 * cheaper and immune to the layer-compositing traps `NemaraCamFeedPlaceholder`
 * documents.
 */
@Composable
internal fun LockShieldGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val shield = Path().apply {
            moveTo(w * 0.50f, w * 0.03f)
            lineTo(w * 0.94f, w * 0.18f)
            lineTo(w * 0.94f, w * 0.50f)
            cubicTo(
                w * 0.94f, w * 0.74f,
                w * 0.75f, w * 0.91f,
                w * 0.50f, w * 0.99f,
            )
            cubicTo(
                w * 0.25f, w * 0.91f,
                w * 0.06f, w * 0.74f,
                w * 0.06f, w * 0.50f,
            )
            lineTo(w * 0.06f, w * 0.18f)
            close()
        }
        drawPath(shield, color)

        // Padlock shackle: a half-ring above the body.
        drawArc(
            color = IconTilePaper,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.35f, w * 0.30f),
            size = Size(w * 0.30f, w * 0.30f),
            style = Stroke(width = w * 0.09f),
        )
        // Padlock body.
        drawRoundRect(
            color = IconTilePaper,
            topLeft = Offset(w * 0.31f, w * 0.44f),
            size = Size(w * 0.38f, w * 0.26f),
            cornerRadius = CornerRadius(w * 0.05f),
        )
    }
}

/** `envelope.fill` — a filled body with the flap drawn back out of it. */
@Composable
internal fun EnvelopeGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.03f, w * 0.19f),
            size = Size(w * 0.94f, w * 0.62f),
            cornerRadius = CornerRadius(w * 0.11f),
        )
        drawPolyline(
            points = listOf(
                Offset(w * 0.12f, w * 0.30f),
                Offset(w * 0.50f, w * 0.57f),
                Offset(w * 0.88f, w * 0.30f),
            ),
            color = IconTilePaper,
            strokeWidth = w * 0.10f,
        )
    }
}

/** `sparkles` — one large four-point star and two small ones. */
@Composable
internal fun SparklesGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawPath(fourPointStar(w * 0.40f, w * 0.52f, w * 0.44f), color)
        drawPath(fourPointStar(w * 0.82f, w * 0.18f, w * 0.18f), color)
        drawPath(fourPointStar(w * 0.84f, w * 0.80f, w * 0.14f), color)
    }
}

/**
 * `rectangle.portrait.and.arrow.right` — a portrait frame open on its right side
 * with an arrow leaving through the gap.
 *
 * Stroked rather than filled: the SF Symbol without a `.fill` suffix is an
 * outline, and this is the one row where iOS names the outline variant.
 */
@Composable
internal fun SignOutGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.11f
        // Three sides of the frame; the right edge is left open for the arrow.
        drawPolyline(
            points = listOf(
                Offset(w * 0.46f, w * 0.09f),
                Offset(w * 0.10f, w * 0.09f),
                Offset(w * 0.10f, w * 0.91f),
                Offset(w * 0.46f, w * 0.91f),
            ),
            color = color,
            strokeWidth = stroke,
        )
        // Shaft.
        drawPolyline(
            points = listOf(Offset(w * 0.34f, w * 0.50f), Offset(w * 0.94f, w * 0.50f)),
            color = color,
            strokeWidth = stroke,
        )
        // Head.
        drawPolyline(
            points = listOf(
                Offset(w * 0.74f, w * 0.30f),
                Offset(w * 0.94f, w * 0.50f),
                Offset(w * 0.74f, w * 0.70f),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/** `trash.fill` — filled can, lid and handle, with two ribs knocked back out. */
@Composable
internal fun TrashGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        // Handle: a filled block with its centre removed, so it reads as a strap.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, w * 0.02f),
            size = Size(w * 0.32f, w * 0.18f),
            cornerRadius = CornerRadius(w * 0.05f),
        )
        drawRect(
            color = IconTilePaper,
            topLeft = Offset(w * 0.42f, w * 0.10f),
            size = Size(w * 0.16f, w * 0.12f),
        )
        // Lid.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, w * 0.18f),
            size = Size(w * 0.88f, w * 0.13f),
            cornerRadius = CornerRadius(w * 0.06f),
        )
        // Can, tapering toward the base.
        val can = Path().apply {
            moveTo(w * 0.17f, w * 0.35f)
            lineTo(w * 0.83f, w * 0.35f)
            lineTo(w * 0.74f, w * 0.93f)
            cubicTo(
                w * 0.73f, w * 0.98f,
                w * 0.70f, w * 0.99f,
                w * 0.65f, w * 0.99f,
            )
            lineTo(w * 0.35f, w * 0.99f)
            cubicTo(
                w * 0.30f, w * 0.99f,
                w * 0.27f, w * 0.98f,
                w * 0.26f, w * 0.93f,
            )
            close()
        }
        drawPath(can, color)
        // Two ribs.
        listOf(0.40f, 0.60f).forEach { x ->
            drawPolyline(
                points = listOf(Offset(w * x, w * 0.46f), Offset(w * x, w * 0.85f)),
                color = IconTilePaper,
                strokeWidth = w * 0.07f,
            )
        }
    }
}

/**
 * `plus`, as an [ImageVector] because `NemaraButton` takes one — it is the
 * `systemImage: "plus"` on "Add another patient".
 *
 * Drawn as two round-capped strokes rather than two bars, matching the rounded
 * terminals SF Symbols use. `Icon` tints the whole rendering, so the black here
 * is never seen.
 */
internal val PlusIcon: ImageVector = ImageVector.Builder(
    name = "plus",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.6f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(12f, 4.2f)
        lineTo(12f, 19.8f)
        moveTo(4.2f, 12f)
        lineTo(19.8f, 12f)
    }
}.build()

/**
 * The colour the filled symbols knock their detail back out to.
 *
 * It is the icon tile's own background, so the "cut out" parts of a symbol read
 * as holes. Every filled symbol here is drawn inside an [IconTile]; if one is
 * ever placed on a different surface, this is the constant that has to become a
 * parameter.
 */
private val IconTilePaper: Color = PaperDim

/**
 * A four-point star: four arms with concave shoulders, the shape SF Symbols uses
 * for `sparkles`.
 *
 * Each shoulder is a quadratic pulled toward the centre by [CONCAVITY], which is
 * what makes the arms look drawn rather than like a rotated square.
 */
private fun fourPointStar(cx: Float, cy: Float, r: Float): Path {
    val inner = r * CONCAVITY
    return Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx + inner, cy - inner, cx + r, cy)
        quadraticTo(cx + inner, cy + inner, cx, cy + r)
        quadraticTo(cx - inner, cy + inner, cx - r, cy)
        quadraticTo(cx - inner, cy - inner, cx, cy - r)
        close()
    }
}

private const val CONCAVITY = 0.24f

/** Round-capped segments through [points]. See `CountryPicker.kt` for the rationale. */
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
