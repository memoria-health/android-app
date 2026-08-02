package com.memoria.caregiver.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.memoria.caregiver.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * res/values/colors.xml duplicates a small number of tokens that the PLATFORM
 * needs before Compose starts — the cold-start window background, in particular.
 * Kotlin is the source of truth, so the XML is a hand-maintained copy, and its
 * own comment admits the two "must stay in sync by hand".
 *
 * A hand-sync with no test is a drift waiting to happen. The failure is quiet
 * and looks like a rendering glitch: the palette is retuned in Color.kt, the XML
 * is not, and every cold start flashes the old cream for a frame before Compose
 * paints the new one.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformColorSyncTest {

    // Robolectric's own accessor, so this test does not pull in androidx.test.core
    // for a single call.
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `platform paper matches the Paper token`() {
        assertEquals(Paper.toArgb(), context.getColor(R.color.paper))
    }
}
