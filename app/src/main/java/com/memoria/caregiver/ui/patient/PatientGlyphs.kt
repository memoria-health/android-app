package com.memoria.caregiver.ui.patient

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The two SF Symbols the patient header uses, drawn as vectors.
 *
 * Same approach as `ui/home/HomeGlyphs.kt` and `ui/settings/SettingsGlyphs.kt`:
 * this project has no icon dependency, so every symbol iOS gets from SF Symbols
 * is drawn here. Both are FILLED symbols on iOS (`house.fill`,
 * `bubble.left.fill`), so these are filled paths rather than strokes.
 *
 * Built once as top-level `val`s. An `ImageVector` is immutable, and rebuilding
 * one inside a composable allocates on every recomposition and defeats
 * `VectorPainter`'s caching.
 */
private const val VIEWPORT = 24f

/** SF Symbol `house.fill`: a gabled roof over a square body. */
internal val HomeHouse: ImageVector = ImageVector.Builder(
    name = "house.fill",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(fill = SolidColor(Color.Black)) {
    // Roof: apex at top centre, eaves overhanging the body on both sides.
    moveTo(12f, 3f)
    lineTo(22f, 11.2f)
    lineTo(20.4f, 13.1f)
    lineTo(12f, 6.2f)
    lineTo(3.6f, 13.1f)
    lineTo(2f, 11.2f)
    close()
    // Body, tucked under the eaves so the two read as one shape.
    moveTo(12f, 8.4f)
    lineTo(19f, 14.1f)
    lineTo(19f, 21f)
    lineTo(5f, 21f)
    lineTo(5f, 14.1f)
    close()
}.build()

/**
 * SF Symbol `bubble.left.fill`: a rounded speech bubble whose tail points down
 * and to the left.
 */
internal val FeedbackBubble: ImageVector = ImageVector.Builder(
    name = "bubble.left.fill",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(fill = SolidColor(Color.Black)) {
    // Body: a rounded rectangle, corners drawn as quadratics so the radius
    // stays even at 11dp, which is the size the header renders it at.
    moveTo(6f, 3f)
    lineTo(18f, 3f)
    quadTo(21f, 3f, 21f, 6f)
    lineTo(21f, 13f)
    quadTo(21f, 16f, 18f, 16f)
    lineTo(9.5f, 16f)
    // Tail: down-left, ending in a point below the bubble's left edge.
    lineTo(4f, 21f)
    lineTo(5.2f, 16f)
    lineTo(6f, 16f)
    quadTo(3f, 16f, 3f, 13f)
    lineTo(3f, 6f)
    quadTo(3f, 3f, 6f, 3f)
    close()
}.build()
