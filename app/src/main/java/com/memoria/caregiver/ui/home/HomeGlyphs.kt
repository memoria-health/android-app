package com.memoria.caregiver.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * The two SF Symbols the roster names: `plus` (RosterView.swift:23 at 20pt
 * semibold, EmptyRosterView.swift:36 inside a NemaraButton, and
 * EmptyRosterView.swift:70 at 30pt semibold) and `chevron.right`
 * (PatientRow.swift:79 at 14pt semibold).
 *
 * There is no material-icons dependency in the version catalog and the porting
 * rules forbid adding one, so both are drawn by hand — the same constraint
 * CountryPicker.kt and LoginScreen.kt hit.
 *
 * **Those two files draw with `Canvas`; this one builds `ImageVector`s, and the
 * difference is forced rather than a matter of taste.** `NemaraButton` accepts
 * an `ImageVector` and nothing else, so the plus on "Add a patient" has to
 * exist as a vector. Given that, drawing a *second* plus with `Canvas` for the
 * header button would leave one glyph with two geometries that could drift
 * apart. One definition per glyph, rendered through `Icon` everywhere, is the
 * cheaper invariant — and `Icon` tints with a `SrcIn` color filter, so a
 * stroked path recolors exactly like a filled one.
 *
 * Geometry is expressed in a 24-unit viewport and scaled by the `Icon` size at
 * the call site, so the point sizes above map to dp box sizes: 20 / 18 / 30 for
 * the three plus instances (18 is what NemaraButton derives for `Lg`, its
 * 16sp label plus 2) and 14 for the chevron.
 */

/** The 24-unit design viewport both glyphs are drawn in. */
private const val VIEWPORT = 24f

/**
 * Stroke weight as a fraction of the glyph box, for SF Symbol `.semibold`.
 * The same 0.145 CountryPicker.kt and LoginScreen.kt use, so the roster's
 * chevron reads at the same weight as the login screen's.
 */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

private const val STROKE = VIEWPORT * SEMIBOLD_STROKE_RATIO

/**
 * Half the stroke, kept clear at every end so a round cap stays inside the
 * viewport instead of being clipped by it. Same trick as the Canvas glyphs,
 * which inset by `stroke / 2f`.
 */
private const val CAP_INSET = STROKE / 2f

/**
 * SF Symbol `plus`: two round-capped strokes crossing at the centre.
 *
 * Built once as a top-level `val` rather than per call, because an `ImageVector`
 * is immutable and rebuilding it inside a composable would allocate a new one on
 * every recomposition and defeat `VectorPainter`'s caching.
 */
internal val PlusGlyph: ImageVector = ImageVector.Builder(
    name = "plus",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
) {
    moveTo(VIEWPORT / 2f, CAP_INSET)
    lineTo(VIEWPORT / 2f, VIEWPORT - CAP_INSET)
    moveTo(CAP_INSET, VIEWPORT / 2f)
    lineTo(VIEWPORT - CAP_INSET, VIEWPORT / 2f)
}.build()

/**
 * SF Symbol `chevron.right`: LoginScreen.kt's `chevron.left` mirrored on the
 * vertical axis, so the two point at each other with identical proportions.
 */
internal val ChevronRightGlyph: ImageVector = ImageVector.Builder(
    name = "chevron.right",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
    // One polyline, so the corner is a JOIN rather than two capped ends.
    // LoginScreen.kt draws its chevron as separate round-capped segments, which
    // rounds the corner as a side effect; a mitered join here would give the
    // same glyph a sharp point at 14dp and read as a different weight.
    strokeLineJoin = StrokeJoin.Round,
) {
    moveTo(VIEWPORT * 0.32f, CAP_INSET)
    lineTo(VIEWPORT * 0.70f, VIEWPORT / 2f)
    lineTo(VIEWPORT * 0.32f, VIEWPORT - CAP_INSET)
}.build()
