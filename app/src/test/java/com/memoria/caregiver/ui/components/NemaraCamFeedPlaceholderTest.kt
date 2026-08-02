package com.memoria.caregiver.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NemaraCamFeedPlaceholder's pure geometry and color math.
 * Spec: DesignSystem/Components/NemaraCamFeedPlaceholder.swift.
 *
 * The whole component is a drawing, so there is no state machine to test. What
 * there IS, and what a port loses silently, is two dozen tuning numbers and one
 * asymmetric clamp. Those are pulled out of the draw scope specifically so they
 * can be asserted here without a renderer.
 */
class CamFeedGradientTest {

    @Test fun `lit gradient has three stops with the documented saturation and brightness`() {
        val stops = camFeedGradientStops(30f)
        assertEquals(3, stops.size)
        assertEquals(0.32f, stops[0].saturation, 0f)
        assertEquals(0.72f, stops[0].brightness, 0f)
        assertEquals(0.36f, stops[1].saturation, 0f)
        assertEquals(0.56f, stops[1].brightness, 0f)
        assertEquals(0.30f, stops[2].saturation, 0f)
        assertEquals(0.34f, stops[2].brightness, 0f)
    }

    /** The +6 / -4 hue offsets are what make the gradient read as light falling across a room. */
    @Test fun `hue offsets are plus six and minus four degrees`() {
        val stops = camFeedGradientStops(30f)
        assertEquals(30f, stops[0].hueDegrees, 0f)
        assertEquals(36f, stops[1].hueDegrees, 0f)
        assertEquals(26f, stops[2].hueDegrees, 0f)
    }

    /**
     * Swift clamps ONLY the third stop: `max(0, (hue - 4) / 360)`. So a low hue
     * pulls the shadow stop down to 0 instead of wrapping it round to magenta,
     * while the first two stops are left to wrap. It looks like an oversight,
     * but it is observable and the port keeps it — this test is the record that
     * the asymmetry is deliberate rather than a transcription slip.
     */
    @Test fun `only the shadow stop is clamped at zero`() {
        val stops = camFeedGradientStops(2f)
        assertEquals(2f, stops[0].hueDegrees, 0f)
        assertEquals(8f, stops[1].hueDegrees, 0f)
        assertEquals(0f, stops[2].hueDegrees, 0f)
    }

    @Test fun `a negative hue wraps the first two stops and floors the third`() {
        val stops = camFeedGradientStops(-10f)
        assertEquals(350f, stops[0].hueDegrees, 0f)
        assertEquals(356f, stops[1].hueDegrees, 0f)
        assertEquals(0f, stops[2].hueDegrees, 0f)
    }

    /**
     * SwiftUI's Color(hue:) wraps a fraction past 1.0; Compose's Color.hsv
     * *throws* outside 0..360. Without an explicit wrap a hue of 354 would
     * render fine on iOS and crash the tile on Android.
     */
    @Test fun `a hue whose offset passes 360 wraps instead of going out of range`() {
        val stops = camFeedGradientStops(354f)
        assertEquals(354f, stops[0].hueDegrees, 0f)
        assertEquals(0f, stops[1].hueDegrees, 0f)
        assertEquals(350f, stops[2].hueDegrees, 0f)
    }

    /** Top-leading is the lit end, bottom-trailing the shadow end. */
    @Test fun `the gradient darkens from first stop to last`() {
        val stops = camFeedGradientStops(200f)
        assertEquals(true, stops[0].brightness > stops[1].brightness)
        assertEquals(true, stops[1].brightness > stops[2].brightness)
    }

    /**
     * Guards the units. SwiftUI takes hue as a 0...1 fraction, Compose takes
     * degrees, so handing `hue / 360` straight to Color.hsv compiles, stays in
     * range, and produces a near-grey instead of a warm room. The green channel
     * is the discriminator: at 30 degrees it is 0.6048, at 0.083 degrees it is
     * 0.4899. Red is 0.72 either way, so asserting red would not catch it.
     */
    @Test fun `hue is converted to degrees, not passed as a fraction`() {
        val first = camFeedGradientColors(30f).first()
        assertEquals(0.72f, first.red, 0.005f)
        assertEquals(0.6048f, first.green, 0.005f)
        assertEquals(0.4896f, first.blue, 0.005f)
    }

    @Test fun `every stop becomes a color`() {
        assertEquals(3, camFeedGradientColors(30f).size)
    }
}

/**
 * The four furniture blocks. Fractions of the tile's width and height, so they
 * hold at any aspect ratio — a transposed x/y or w/h here is invisible in review
 * and obvious on screen.
 */
class CamFeedFurnitureTest {

    private val floor get() = CamFeedFurniture[0]
    private val couch get() = CamFeedFurniture[1]
    private val chest get() = CamFeedFurniture[2]
    private val window get() = CamFeedFurniture[3]

    @Test fun `there are four pieces, painted floor first and window last`() {
        assertEquals(4, CamFeedFurniture.size)
        assertEquals(Color(0xFF3A2A1C), floor.color)
        assertEquals(Color(0xFF5A3F2A), couch.color)
        assertEquals(Color(0xFF6B4A30), chest.color)
    }

    /** The floor spans the full width and occupies exactly the bottom 32%. */
    @Test fun `the floor is flush with the bottom and both sides`() {
        assertEquals(0f, floor.x, 0f)
        assertEquals(1f, floor.width, 0f)
        assertEquals(0.68f, floor.y, 0f)
        assertEquals(1f, floor.y + floor.height, 0.0001f)
    }

    @Test fun `couch and chest sit on the floor line`() {
        assertEquals(0.12f, couch.x, 0f)
        assertEquals(0.40f, couch.y, 0f)
        assertEquals(0.31f, couch.width, 0f)
        assertEquals(0.30f, couch.height, 0f)

        assertEquals(0.56f, chest.x, 0f)
        assertEquals(0.30f, chest.y, 0f)
        assertEquals(0.28f, chest.width, 0f)
        assertEquals(0.40f, chest.height, 0f)

        // Both bottom out at 0.70 while the floor starts at 0.68, so each
        // overlaps the floor slightly. That overlap is what makes them read as
        // standing on it rather than floating above it, and it is also why the
        // silhouette needs group alpha rather than per-shape alpha.
        assertEquals(0.70f, couch.y + couch.height, 0.0001f)
        assertEquals(0.70f, chest.y + chest.height, 0.0001f)
    }

    /** The window is flush with the top and the right edge. */
    @Test fun `the window is flush with the top-right corner`() {
        assertEquals(0f, window.y, 0f)
        assertEquals(0.42f, window.height, 0f)
        assertEquals(0.75f, window.x, 0f)
        assertEquals(1f, window.x + window.width, 0.0001f)
    }

    /** Only the two soft-furniture pieces are rounded; floor and window are plain rectangles. */
    @Test fun `couch and chest are rounded at 3dp, floor and window are square`() {
        assertEquals(3.dp, couch.cornerRadius)
        assertEquals(3.dp, chest.cornerRadius)
        assertEquals(0.dp, floor.cornerRadius)
        assertEquals(0.dp, window.cornerRadius)
    }

    /** The window is the only translucent piece — daylight through glass, at 60%. */
    @Test fun `the window is translucent and the rest are opaque`() {
        assertEquals(0.6f, window.color.alpha, 0.005f)
        listOf(floor, couch, chest).forEach { assertEquals(1f, it.color.alpha, 0f) }
    }
}

/** Opacities, radii and durations that carry no other test. */
class CamFeedTuningTest {

    @Test fun `layer opacities match the swift`() {
        assertEquals(0.4f, CamFeedFurnitureOpacity, 0f)
        assertEquals(0.3f, CamFeedLoadingScrimOpacity, 0f)
        assertEquals(0.92f, CamFeedSpinnerStrokeOpacity, 0f)
        assertEquals(0.9f, CamFeedSpinnerLabelOpacity, 0f)
    }

    @Test fun `the unlit glow is a warm pool at 60 percent over ink`() {
        assertEquals(0.6f, CamFeedUnlitGlow.alpha, 0.005f)
        assertEquals(Color(0xFF3C3228), CamFeedUnlitGlow.copy(alpha = 1f))
        assertEquals(220.dp, CamFeedUnlitGlowRadius)
    }

    /**
     * `Circle().trim(from: 0, to: 0.72)` is a 259.2 degree arc. Compose has no
     * trim, so the fraction has to survive as a sweep angle; a spinner drawn as
     * a full circle looks plausible in isolation and is wrong.
     */
    @Test fun `the spinner arc is 72 percent of a circle`() {
        assertEquals(0.72f, CamFeedSpinnerTrimFraction, 0f)
        assertEquals(259.2f, CamFeedSpinnerSweepDegrees, 0.01f)
    }

    @Test fun `the spinner turns once every 900ms`() {
        assertEquals(900, CamFeedSpinnerDurationMillis)
    }

    @Test fun `the spinner is 26dp with a 3dp stroke`() {
        assertEquals(26.dp, CamFeedSpinnerDiameter)
        assertEquals(3.dp, CamFeedSpinnerStrokeWidth)
    }
}
