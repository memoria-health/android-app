package com.memoria.caregiver.ui.theme

import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.remote.NemaraJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four category colours, and the one rule that makes them worth having.
 *
 * Written after the fact: the palette shipped in `38aea39` with no tests and
 * without compiling, so these pin the claims that change actually makes.
 */
class CategoryPaletteTest {

    // ---- Only safety shouts ----

    @Test
    fun `safety is the only category that escalates a patient card`() {
        // The whole point of adding two categories. A red card that fires for
        // agitation and for a fall teaches people to ignore it, and then it is
        // worth nothing on the day it matters.
        assertTrue(Detector.Category.SAFETY.escalates)
        assertFalse(Detector.Category.ROUTINE.escalates)
        assertFalse(Detector.Category.BEHAVIOUR.escalates)
        assertFalse(Detector.Category.CARE.escalates)
        assertFalse(Detector.Category.UNKNOWN.escalates)
    }

    @Test
    fun `a category from a newer server does not escalate`() {
        // Fail quiet, not loud. An unrecognised category showing up as a red
        // card would be the app inventing an emergency out of something it does
        // not understand.
        assertFalse(Detector.Category.UNKNOWN.escalates)
    }

    // ---- Four distinct colours ----

    @Test
    fun `each category has its own accent`() {
        val accents = listOf(
            Detector.Category.SAFETY,
            Detector.Category.ROUTINE,
            Detector.Category.BEHAVIOUR,
            Detector.Category.CARE,
        ).map { it.accent }
        assertEquals("two categories share an accent", accents.size, accents.toSet().size)
    }

    @Test
    fun `each category has its own soft background`() {
        val softs = listOf(
            Detector.Category.SAFETY,
            Detector.Category.ROUTINE,
            Detector.Category.BEHAVIOUR,
            Detector.Category.CARE,
        ).map { it.soft }
        assertEquals("two categories share a background", softs.size, softs.toSet().size)
    }

    @Test
    fun `the new colours are the ones iOS ships`() {
        // Verified against NemaraCaregiver/Resources/Assets.xcassets/Colors:
        // Plum 8A6E96, PlumSoft E8E0EC, Sage 6E9070, SageSoft DFEADF. A category
        // that looks different on two phones is a category the caregiver has to
        // learn twice.
        assertEquals(Plum, Detector.Category.BEHAVIOUR.accent)
        assertEquals(PlumSoft, Detector.Category.BEHAVIOUR.soft)
        assertEquals(Sage, Detector.Category.CARE.accent)
        assertEquals(SageSoft, Detector.Category.CARE.soft)
    }

    // ---- What a caregiver may pick ----

    @Test
    fun `UNKNOWN is never offered as a choice`() {
        // It exists only to absorb a value from a newer server. Offering it
        // would let someone file a detector under "we don't know".
        assertFalse(Detector.Category.UNKNOWN in SELECTABLE_CATEGORIES)
        assertEquals(4, SELECTABLE_CATEGORIES.size)
    }

    // ---- The claim that UNKNOWN protects decoding ----

    @Test
    fun `an unrecognised category from a newer server`() {
        // The comment on Category says UNKNOWN "absorbs a value from a newer
        // server. Without it the whole response fails to deserialize and the
        // screen blanks."
        //
        // That is only true if the decoder is told to coerce, and this asserts
        // whichever way the app actually behaves — see the message on failure.
        val json = """{"category":"companionship"}"""
        val result = runCatching {
            NemaraJson.json.decodeFromString(CategoryHolder.serializer(), json)
        }
        assertTrue(
            "An unknown category value did NOT fall back to UNKNOWN — it threw: " +
                "${result.exceptionOrNull()}. `category` is on both Detector and " +
                "DetectionEvent, and both load during bootstrap, which runs inside " +
                "sign-in. See FINDINGS.md F-44.",
            result.isSuccess,
        )
        assertEquals(Detector.Category.UNKNOWN, result.getOrThrow().category)
    }

    @kotlinx.serialization.Serializable
    private data class CategoryHolder(
        val category: Detector.Category = Detector.Category.UNKNOWN,
    )
}
