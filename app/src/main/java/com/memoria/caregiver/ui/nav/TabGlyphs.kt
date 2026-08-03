package com.memoria.caregiver.ui.nav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The tab-bar symbols, drawn as vectors.
 *
 * Same approach as `ui/home/HomeGlyphs.kt` and `ui/patient/PatientGlyphs.kt`:
 * this project has no icon dependency, so every SF Symbol iOS uses is drawn
 * here. Built once as top-level `val`s — an `ImageVector` is immutable, and
 * rebuilding one inside a composable allocates on every recomposition and
 * defeats `VectorPainter`'s caching.
 *
 * iOS's tab items are `Label(title, systemImage:)`, so each of these pairs with
 * a visible label rather than replacing it.
 */
private const val VIEWPORT = 24f
private const val STROKE = 1.9f

/** `house.fill` — the Home tab. Same shape as the patient header's, filled. */
internal val HomeTabGlyph: ImageVector = ImageVector.Builder(
    name = "house.fill",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(fill = SolidColor(Color.Black)) {
    moveTo(12f, 3f)
    lineTo(22f, 11.2f)
    lineTo(20.4f, 13.1f)
    lineTo(12f, 6.2f)
    lineTo(3.6f, 13.1f)
    lineTo(2f, 11.2f)
    close()
    moveTo(12f, 8.4f)
    lineTo(19f, 14.1f)
    lineTo(19f, 21f)
    lineTo(5f, 21f)
    lineTo(5f, 14.1f)
    close()
}.build()

/**
 * `gearshape` — Settings, and the patient Settings tab.
 *
 * Outlined rather than filled, matching iOS's un-suffixed `gearshape`. Eight
 * teeth as a stroked star-ish ring plus a bore, which reads correctly at the
 * 24dp a tab bar draws it at without needing true involute geometry.
 */
internal val SettingsTabGlyph: ImageVector = ImageVector.Builder(
    name = "gearshape",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(
    stroke = SolidColor(Color.Black),
    // 2.4 rather than the shared 1.9: beside iOS's `gearshape` the ring read
    // noticeably lighter than the filled symbols either side of it, so the bar
    // looked inconsistent (FINDINGS.md F-34).
    strokeLineWidth = 2.4f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
) {
    // Bore.
    moveTo(15f, 12f)
    arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
    arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
    close()
    // Four spokes on the axes and four on the diagonals: the cheapest reading
    // of a gear that still looks like one at 24dp.
    moveTo(12f, 2.6f); lineTo(12f, 6f)
    moveTo(12f, 18f); lineTo(12f, 21.4f)
    moveTo(2.6f, 12f); lineTo(6f, 12f)
    moveTo(18f, 12f); lineTo(21.4f, 12f)
    moveTo(5.4f, 5.4f); lineTo(7.8f, 7.8f)
    moveTo(16.2f, 16.2f); lineTo(18.6f, 18.6f)
    moveTo(18.6f, 5.4f); lineTo(16.2f, 7.8f)
    moveTo(7.8f, 16.2f); lineTo(5.4f, 18.6f)
    // Ring joining the spokes.
    moveTo(19f, 12f)
    arcToRelative(7f, 7f, 0f, true, true, -14f, 0f)
    arcToRelative(7f, 7f, 0f, true, true, 14f, 0f)
    close()
}.build()

/** `video.fill` — the patient Live tab. A rounded body with a lens wedge. */
internal val LiveTabGlyph: ImageVector = ImageVector.Builder(
    name = "video.fill",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(fill = SolidColor(Color.Black)) {
    moveTo(4f, 6f)
    lineTo(13f, 6f)
    quadTo(15f, 6f, 15f, 8f)
    lineTo(15f, 16f)
    quadTo(15f, 18f, 13f, 18f)
    lineTo(4f, 18f)
    quadTo(2f, 18f, 2f, 16f)
    lineTo(2f, 8f)
    quadTo(2f, 6f, 4f, 6f)
    close()
    // Lens.
    moveTo(17f, 10.5f)
    lineTo(22f, 7.5f)
    lineTo(22f, 16.5f)
    lineTo(17f, 13.5f)
    close()
}.build()

/** `waveform.path` — the patient Events tab. */
internal val EventsTabGlyph: ImageVector = ImageVector.Builder(
    name = "waveform.path",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
) {
    // Many short peaks, not one tall one. `waveform.path` reads as AUDIO because
    // of its repetition; a single spike reads as a chart. Same mistake as the
    // detector dot (FINDINGS.md F-34).
    moveTo(1.6f, 12f)
    lineTo(4f, 12f)
    lineTo(5.4f, 8.4f)
    lineTo(7f, 15.6f)
    lineTo(8.6f, 6.6f)
    lineTo(10.3f, 17.4f)
    lineTo(12f, 4.8f)
    lineTo(13.7f, 17.4f)
    lineTo(15.4f, 8.4f)
    lineTo(17f, 14.4f)
    lineTo(18.4f, 10.8f)
    lineTo(20f, 12f)
    lineTo(22.4f, 12f)
}.build()

/** `camera.fill` — the patient Sensors tab. */
internal val SensorsTabGlyph: ImageVector = ImageVector.Builder(
    name = "camera.fill",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(fill = SolidColor(Color.Black)) {
    moveTo(9.2f, 4f)
    lineTo(14.8f, 4f)
    lineTo(16.2f, 6.5f)
    lineTo(20f, 6.5f)
    quadTo(22f, 6.5f, 22f, 8.5f)
    lineTo(22f, 17.5f)
    quadTo(22f, 19.5f, 20f, 19.5f)
    lineTo(4f, 19.5f)
    quadTo(2f, 19.5f, 2f, 17.5f)
    lineTo(2f, 8.5f)
    quadTo(2f, 6.5f, 4f, 6.5f)
    lineTo(7.8f, 6.5f)
    close()
}.build()

/**
 * `sensor.tag.radiowaves.forward.fill` — the patient Detectors tab.
 *
 * A filled emitter dot with three arcs radiating right. An earlier version drew
 * the arcs alone, on the theory that the dot was detail — beside the original it
 * read as a bracket, not a sensor. FINDINGS.md F-34.
 */
internal val DetectorsTabGlyph: ImageVector = ImageVector.Builder(
    name = "sensor.tag.radiowaves.forward.fill",
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).path(fill = SolidColor(Color.Black)) {
    // The emitter. Without it the arcs read as a bracket rather than a sensor —
    // the dot is what makes the symbol legible at 24dp, and dropping it as
    // "simplification" was the mistake (FINDINGS.md F-34).
    moveTo(6.6f, 12f)
    arcToRelative(2.1f, 2.1f, 0f, true, true, -4.2f, 0f)
    arcToRelative(2.1f, 2.1f, 0f, true, true, 4.2f, 0f)
    close()
}.path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
) {
    // Three arcs radiating right, growing outward, as the SF Symbol does.
    moveTo(9f, 8.6f)
    quadTo(11.6f, 12f, 9f, 15.4f)
    moveTo(13f, 6.6f)
    quadTo(17f, 12f, 13f, 17.4f)
    moveTo(17f, 4.8f)
    quadTo(22.2f, 12f, 17f, 19.2f)
}.build()
