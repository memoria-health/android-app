package com.memoria.caregiver.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The country list. Spec: Models/Countries.swift.
 *
 * Pure JVM (Tier 1) — `Locale` and `Collator` need no Android runtime, which is
 * the whole reason the list and the search filter were pulled out of the
 * composable. Nothing here asserts a country count: the ISO table grows with the
 * JDK, and a count assertion would fail on a toolchain bump for no real reason.
 */
class CountriesTest {

    private fun codes(locale: Locale) = Countries.all(locale).map { it.code }
    private fun index(code: String, locale: Locale) = codes(locale).indexOf(code)

    @Test fun `every entry is a two-letter alpha code with a name`() {
        Countries.all(Locale.US).forEach { opt ->
            assertTrue("bad code: ${opt.code}", opt.code.length == 2 && opt.code.all { it.isLetter() })
            assertTrue("empty name for ${opt.code}", opt.name.isNotEmpty())
        }
    }

    @Test fun `the list covers the two regions the backend maps to`() {
        val all = codes(Locale.US)
        assertTrue(all.contains("US"))
        assertTrue(all.contains("IN"))
    }

    @Test fun `codes are unique`() {
        val all = codes(Locale.US)
        assertEquals(all.size, all.toSet().size)
    }

    /**
     * Sorted by localized NAME, not by code. "South Africa" (ZA) precedes
     * "United Kingdom" (GB) by name and follows it by code, so this fails loudly
     * if the sort key is ever the wire value.
     */
    @Test fun `the list is sorted by name, not by code`() {
        assertTrue(index("ZA", Locale.US) < index("GB", Locale.US))
    }

    /**
     * The reason the sort uses a Collator rather than String.compareTo. 'A-ring'
     * is U+00C5, which is greater than 'Z', so a code-point sort files "Aland
     * Islands" after "Zimbabwe" — at the very bottom of a 250-row list, where a
     * caregiver in Mariehamn will never find it.
     */
    @Test fun `accented names collate with their base letter`() {
        val aland = index("AX", Locale.US)
        assertTrue("AX missing from the list", aland >= 0)
        assertTrue(aland < index("ZW", Locale.US))
        assertTrue(aland < index("BR", Locale.US))
    }

    @Test fun `names come from the requested locale`() {
        assertEquals("Germany", Countries.name("DE", Locale.US))
        assertNotEquals(Countries.name("DE", Locale.US), Countries.name("DE", Locale.FRENCH))
    }

    /** The whole list localizes, not just the single-code lookup. */
    @Test fun `the list localizes with the locale it is built for`() {
        val german = Countries.all(Locale.FRENCH).first { it.code == "DE" }.name
        assertEquals(Countries.name("DE", Locale.FRENCH), german)
        assertNotEquals("Germany", german)
    }

    /**
     * iOS: `localizedString(forRegionCode:) ?? code`. Anything the platform
     * cannot name comes back as the raw code rather than blank or a crash. Only
     * malformed codes are asserted here: a well-formed but unassigned code such
     * as "ZZ" is named "Unknown Region" by CLDR on both platforms, so it
     * exercises nothing, while these three are the inputs where the JVM throws
     * `IllformedLocaleException` and iOS returns nil.
     */
    @Test fun `an unnameable code falls back to itself`() {
        assertEquals("1A", Countries.name("1A", Locale.US))
        assertEquals("usa", Countries.name("usa", Locale.US))
        assertEquals("Q1", Countries.name("Q1", Locale.US))
    }

    /** An unset country renders as nothing, not as a placeholder. */
    @Test fun `an empty code yields an empty name`() {
        assertEquals("", Countries.name("", Locale.US))
    }

    /**
     * iOS builds the list once in a `static let`. The Kotlin memo has to deliver
     * the same "pay for it once" behavior, because the picker's sheet reads it on
     * every open and building it sorts ~250 strings through a Collator.
     */
    @Test fun `the list is built once per locale`() {
        assertSame(Countries.all(Locale.US), Countries.all(Locale.US))
    }

    /** ...but a locale change must not serve the previous locale's names. */
    @Test fun `switching locale rebuilds the list`() {
        val english = Countries.all(Locale.US).first { it.code == "DE" }.name
        val french = Countries.all(Locale.FRENCH).first { it.code == "DE" }.name
        val englishAgain = Countries.all(Locale.US).first { it.code == "DE" }.name
        assertNotEquals(english, french)
        assertEquals(english, englishAgain)
    }
}

/**
 * The sheet's search filter. Spec: `CountryPickerSheet.results` in
 * CountryPicker.swift.
 *
 * A fixed fixture rather than the real ISO list, so these assert the filter and
 * not the JDK's country table. Every non-ASCII character is written as an escape
 * so the intent survives an editor that normalizes whitespace.
 */
class CountrySearchResultsTest {

    /** "Åland Islands" — A-ring, the diacritic case. */
    private val options = listOf(
        Countries.Option("IN", "India"),
        Countries.Option("ID", "Indonesia"),
        Countries.Option("GB", "United Kingdom"),
        Countries.Option("US", "United States"),
        Countries.Option("AX", "Åland Islands"),
    )

    private fun names(query: String) = countrySearchResults(query, options).map { it.name }

    @Test fun `an empty query returns the list untouched`() {
        assertSame(options, countrySearchResults("", options))
    }

    @Test fun `a whitespace-only query returns everything`() {
        assertSame(options, countrySearchResults("   ", options))
        assertSame(options, countrySearchResults("\t", options))
        // U+00A0 NO-BREAK SPACE and U+2009 THIN SPACE are Unicode category Zs,
        // so Swift's CharacterSet.whitespaces trims them too.
        assertSame(options, countrySearchResults("\u00A0", options))
        assertSame(options, countrySearchResults("\u2009", options))
    }

    /**
     * **The divergence this test exists for.** Swift trims
     * `CharacterSet.whitespaces` — category Zs plus TAB, and NOT newlines.
     * Kotlin's `String.trim()` uses `Char.isWhitespace()`, which does include
     * `\n`, `\r` and U+000B VERTICAL TAB. A naive port therefore treats a
     * newline-only query as empty and shows all 250 countries, where iOS treats
     * it as a real query and shows none. Opposite results from the same
     * keystroke.
     */
    @Test fun `newlines are not trimmed, matching Swift's CharacterSet whitespaces`() {
        assertEquals(emptyList<String>(), names("\n"))
        assertEquals(emptyList<String>(), names("\r"))
        assertEquals(emptyList<String>(), names("\u000B"))
        assertEquals(emptyList<String>(), names(" India\n"))
    }

    @Test fun `surrounding spaces are trimmed off a real query`() {
        assertEquals(listOf("India"), names("  India  "))
        assertEquals(listOf("India"), names(" India\t"))
        assertEquals(listOf("India"), names("\u00A0India\u00A0"))
    }

    @Test fun `matching is case-insensitive`() {
        assertEquals(listOf("India"), names("india"))
        assertEquals(listOf("India"), names("INDIA"))
        assertEquals(listOf("India"), names("iNdIa"))
    }

    /**
     * Substring, not prefix — iOS uses `contains`, not `hasPrefix`.
     *
     * "kingdom" is the clean demonstration: it matches mid-string in "United
     * Kingdom" and a prefix search would return nothing.
     *
     * "nd" is kept because it makes the point sharply, but note it also matches
     * "Åland Islands" — twice, in "Åland" and in "Islands". That is correct
     * substring behavior and iOS returns it too. The original expectation here
     * omitted it, which is the mistake a reader is likely to repeat: it is easy
     * to scan the fixture for names that *start* with the query.
     */
    @Test fun `matching is a substring anywhere in the name`() {
        assertEquals(listOf("United Kingdom"), names("kingdom"))
        assertEquals(listOf("India", "Indonesia", "Åland Islands"), names("nd"))
    }

    @Test fun `a prefix shared by two countries returns both, in list order`() {
        assertEquals(listOf("United Kingdom", "United States"), names("United"))
    }

    /**
     * `localizedCaseInsensitiveContains` folds case but NOT diacritics — only
     * `.diacriticInsensitive` would do that, and iOS does not pass it. Typing
     * "aland" finds nothing on iOS, and must find nothing here.
     */
    @Test fun `matching is not diacritic-insensitive`() {
        assertEquals(emptyList<String>(), names("aland"))
        assertEquals(listOf("Åland Islands"), names("åland"))
    }

    @Test fun `a query that matches nothing returns nothing`() {
        assertEquals(emptyList<String>(), names("Atlantis"))
    }

    @Test fun `the filter does not mutate the source list`() {
        countrySearchResults("india", options)
        assertEquals(5, options.size)
    }
}
