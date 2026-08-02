package com.memoria.caregiver.ui.live

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The surface the composited frames land on.
 *
 * Two things worth a test, neither of them layout — the screenshot pass owns
 * layout:
 *
 * 1. **Nothing is painted before the first frame.** A tile that renders a stale
 *    or empty image is how an un-blurred frame reaches the screen.
 * 2. **No content description.** iOS gives the video view no accessibility
 *    label, and describing a live frame of someone's home is exactly the thing
 *    that must never be written down.
 */
// Robolectric's default LEGACY graphics returns a null Bitmap, so anything that
// rasterises dies with an NPE inside Compose. NATIVE runs real Skia.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class BlurOverlaySurfaceTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `before the first frame the tile paints nothing`() {
        compose.setContent {
            BlurOverlaySurface(image = null, modifier = Modifier.size(200.dp))
        }

        compose.onNodeWithTag(BLUR_OVERLAY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BLUR_OVERLAY_IMAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun `a composited frame is drawn, and carries no description of what is in it`() {
        compose.setContent {
            BlurOverlaySurface(image = ImageBitmap(64, 48), modifier = Modifier.size(200.dp))
        }

        compose.onNodeWithTag(BLUR_OVERLAY_IMAGE_TAG, useUnmergedTree = true)
            .assertExists()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }
}
