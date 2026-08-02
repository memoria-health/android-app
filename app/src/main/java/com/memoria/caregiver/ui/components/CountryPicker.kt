package com.memoria.caregiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.CardAlt
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Terra
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.IllformedLocaleException
import java.util.Locale

// ---------------------------------------------------------------------------
// Countries — the picker's data source
// ---------------------------------------------------------------------------

/**
 * Full ISO 3166-1 country list for the onboarding/signup country picker.
 * Spec: Models/Countries.swift, PARITY.md §28.
 *
 * The wire value is the 2-letter alpha-2 code; the backend derives the
 * data-residency `region` (US/IN) from it. Names come from the platform locale
 * data so we localize for free and never hand-maintain 200+ labels. Mirrors the
 * web `lib/countries.ts`.
 *
 * **Lives here rather than in a `models` package only because this port task is
 * scoped to two files.** It is a model, not a component, and belongs beside the
 * other models when they land.
 */
object Countries {

    /** iOS: `Countries.Option`, `Identifiable` on `code`. */
    data class Option(val code: String, val name: String)

    /**
     * Every ISO country as `{ code, name }`, sorted by localized name.
     *
     * iOS caches this in a `static let`, evaluated once against `Locale.current`.
     * The Kotlin equivalent takes the locale as a parameter and memoizes the last
     * one: same "compute once" behavior, but it stays correct when Android
     * delivers a locale configuration change, and it makes the list Tier-1
     * testable against a fixed locale instead of the host default.
     *
     * The race between two threads building simultaneously is benign — both
     * produce the same list and the loser's copy is dropped.
     */
    @Volatile
    private var cached: Pair<Locale, List<Option>>? = null

    fun all(locale: Locale = Locale.getDefault()): List<Option> {
        cached?.let { (cachedLocale, options) -> if (cachedLocale == locale) return options }
        val built = build(locale)
        cached = locale to built
        return built
    }

    private fun build(locale: Locale): List<Option> {
        // Collator, not String.compareTo: iOS sorts with
        // localizedCaseInsensitiveCompare, which is locale-aware. SECONDARY
        // strength keeps accent differences but ignores case, which is what
        // "caseInsensitive" means there. A plain code-point sort would file
        // "Åland Islands" after "Zimbabwe".
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val byLocalizedName = Comparator<Option> { a, b -> collator.compare(a.name, b.name) }

        return Locale.getISOCountries()
            // Filter to 2-letter alpha codes so macro-regions (e.g. "419") drop
            // out. getISOCountries() already returns only alpha-2, so this is a
            // no-op today; it is carried over from iOS, where isoRegions does
            // include macro-regions, so the list cannot silently widen if the
            // platform source ever changes.
            //
            // Char.isLetter() is Unicode-aware and so is Swift's
            // Character.isLetter — this is one of the rare cases where the
            // Kotlin default is the exact match rather than the divergence.
            .filter { it.length == 2 && it.all { ch -> ch.isLetter() } }
            // mapNotNull, not map: iOS uses compactMap with a
            // `guard let name = localizedString(forRegionCode:)` that DROPS a
            // country with no localized name. On the JVM getDisplayCountry falls
            // back to the region code itself rather than returning null, so a
            // plain map would keep that country and label its row with a raw ISO
            // code like "XK" sitting among real country names.
            .mapNotNull { code ->
                val localized = localizedCountryName(code, locale)
                if (localized == null) null else Option(code = code, name = localized)
            }
            .sortedWith(byLocalizedName)
    }

    /**
     * Localized display name for a single code, falling back to the raw code.
     *
     * iOS: `Locale.current.localizedString(forRegionCode:) ?? code`. On the JVM
     * `getDisplayCountry` already returns the region code when it has no
     * localized name, so the explicit fallback here only covers codes that are
     * not even well-formed regions (`Locale.Builder.setRegion` throws on those) —
     * which is the same "garbage in, code out" contract as the Swift `?? code`.
     */
    fun name(code: String, locale: Locale = Locale.getDefault()): String =
        localizedCountryName(code, locale) ?: code
}

/**
 * The localized country name, or null when the platform has none.
 *
 * `getDisplayCountry` returns the region code itself when it cannot localize,
 * so "has a name" has to be detected by comparing against the input. That is
 * what makes the `mapNotNull` above equivalent to Swift's `compactMap` +
 * `guard let`, which drops such entries rather than showing a bare code.
 */
private fun localizedCountryName(code: String, locale: Locale): String? = try {
    val displayed = Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
    if (displayed.isBlank() || displayed == code) null else displayed
} catch (_: IllformedLocaleException) {
    null
}

/**
 * The sheet's search filter, extracted as pure logic so it is Tier-1 testable.
 * iOS: `CountryPickerSheet.results`.
 *
 * **Trimming is deliberately not `String.trim()`.** Swift trims
 * `CharacterSet.whitespaces`, which is Unicode category Zs plus TAB and
 * **excludes newlines**. Kotlin's `trim()` uses `Char.isWhitespace()`, which does
 * include `\n`, `\r` and the line/paragraph separators. A query of "\n" is
 * therefore non-empty on iOS (and matches nothing) but empty on a naive Kotlin
 * port (and returns the entire list) — opposite results from the same input.
 *
 * Matching is `contains(ignoreCase = true)`, the map of
 * `localizedCaseInsensitiveContains`: case-insensitive substring, **not**
 * diacritic-insensitive and not prefix-anchored.
 */
fun countrySearchResults(query: String, options: List<Countries.Option>): List<Countries.Option> {
    val q = query.trim { it.isSwiftWhitespace() }
    if (q.isEmpty()) return options
    return options.filter { it.name.contains(q, ignoreCase = true) }
}

/** Unicode Zs plus CHARACTER TABULATION — exactly Swift's `CharacterSet.whitespaces`. */
private fun Char.isSwiftWhitespace(): Boolean =
    this == '\t' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

// ---------------------------------------------------------------------------
// The picker
// ---------------------------------------------------------------------------

/**
 * Labeled country picker: a card row showing the current country that opens a
 * searchable sheet of every ISO country. Binds the ISO alpha-2 [code]. Used at
 * patient onboarding and account signup (data-residency `region` is derived from
 * the pick server-side). A searchable sheet — not a 250-item dropdown — is the
 * only non-janky way to pick from the full list.
 *
 * Spec: DesignSystem/Components/CountryPicker.swift.
 *
 * **No letter spacing anywhere in this component.** The Swift applies no
 * `.kerning()` or `.tracking()`, and per DECISIONS.md D-008 the type tokens carry
 * none, so leaving it unset is the parity-correct outcome rather than an
 * oversight.
 *
 * Built from `Modifier.clickable` rather than material3's `Button` for the same
 * reason as NemaraButton: Button imposes container color, elevation and
 * disabled-dimming that iOS does not have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPicker(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showing by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val shape = RoundedCornerShape(Radius.md)
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            // SwiftUI's Spacer() makes the HStack take the full proposed width;
            // a Compose Row wraps its content, so the greedy behavior has to be
            // asked for explicitly.
            .fillMaxWidth()
            .clip(shape)
            .background(Card)
            .border(1.dp, Line, shape)
            .clickable(interactionSource = interactionSource, indication = null) { showing = true }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Countries.name(code, locale),
            style = NemaraType.bodyLarge,
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        // SF Symbol "chevron.down" at system size 12, semibold.
        ChevronDownGlyph(size = 12.dp, color = Ink3)
    }

    if (showing) {
        val sheetState = rememberModalBottomSheetState(
            // An iOS `.sheet` over a NavigationStack presents at near-full height
            // immediately. The default half-expanded detent would open this one
            // showing four countries.
            skipPartiallyExpanded = true,
        )
        val scope = rememberCoroutineScope()

        // Animate the sheet out before tearing it down, so a selection does not
        // make the sheet vanish instantly the way `showing = false` alone would.
        fun close() {
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                if (!sheetState.isVisible) showing = false
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showing = false },
            sheetState = sheetState,
            // iOS page sheets show no grabber; the Cancel button is the
            // affordance, and swipe-down still dismisses.
            dragHandle = null,
            // The Swift never calls .scrollContentBackground(.hidden) here, so
            // the sheet keeps system chrome — a near-white list background, not
            // the app's Paper. `Card` is this palette's near-white surface and is
            // the honest equivalent.
            containerColor = Card,
        ) {
            CountryPickerSheet(
                code = code,
                locale = locale,
                onSelect = { picked ->
                    onCodeChange(picked)
                    close()
                },
                onCancel = { close() },
            )
        }
    }
}

/**
 * Searchable full-country list presented as a sheet. Tapping a row commits the
 * selection and dismisses.
 *
 * File-private, matching the Swift's `private struct` (Swift `private` at file
 * scope is file scope).
 *
 * The navigation bar, search field and row separators are all hand-built because
 * `NavigationStack` + `.searchable` + `.listStyle(.plain)` are system chrome on
 * iOS with no Compose counterpart that does not drag Material styling in with it.
 */
@Composable
private fun CountryPickerSheet(
    code: String,
    locale: Locale,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val options = remember(locale) { Countries.all(locale) }
    // Re-filtering 250 entries on every recomposition of the text field would be
    // wasteful; this recomputes only when the query actually changes.
    val results = remember(query, options) { countrySearchResults(query, options) }

    Column(Modifier.fillMaxSize()) {

        // ---- Inline navigation bar: centered "Country", Cancel leading ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Country",
                // No sans token is 17, so the inline nav title (SF Pro 17
                // semibold on iOS) takes the nearest: Inter 16 semibold.
                style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
            )
            Text(
                text = "Cancel",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCancel() },
                style = NemaraType.bodyLarge,
                // iOS: .tint(NemaraColor.ink) on the Cancel button.
                color = Ink,
            )
        }

        // ---- Search field ----
        val searchShape = RoundedCornerShape(Radius.md)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(searchShape)
                .background(CardAlt)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MagnifierGlyph(size = 15.dp, color = Ink4)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(text = "Search countries", style = NemaraType.bodyLarge, color = Ink4)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = NemaraType.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Terra),
                    singleLine = true,
                    // Only the return key is set. UISearchBar labels it "Search";
                    // capitalization and autocorrect are left at the platform
                    // default because the Swift specifies neither, and choosing
                    // them here would be a behavior this component's iOS
                    // original does not have.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- The list ----
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.code }) { opt ->
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(opt.code) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = opt.name, style = NemaraType.bodyLarge, color = Ink)
                        Spacer(Modifier.weight(1f))
                        if (opt.code == code) {
                            // SF Symbol "checkmark" at system size 14, semibold.
                            CheckmarkGlyph(size = 14.dp, color = Action)
                        }
                    }
                    // .listStyle(.plain) draws a hairline separator inset to the
                    // text edge. 0.5dp rather than 1dp because iOS's separator is
                    // a third of a point, and a full dp reads as a rule.
                    Box(
                        Modifier
                            .padding(start = 16.dp)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Line),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Glyphs
// ---------------------------------------------------------------------------
//
// The Swift names three SF Symbols: "chevron.down", "checkmark" and the
// magnifier that `.searchable` supplies. There is no material-icons dependency
// in this project's version catalog and the porting rules forbid adding one, so
// all three are drawn with Canvas.
//
// Each is laid out on a square box of the SF Symbol's point size, with round
// caps and joins to match SF Symbol terminals. Stroke weight is 0.145 x size,
// which is the semibold weight both call sites ask for; the drawn geometry is
// inset by half the stroke so round caps stay inside the box.

/** Stroke weight as a fraction of the glyph box, for SF Symbol `.semibold`. */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

@Composable
private fun ChevronDownGlyph(size: Dp, color: Color) {
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

@Composable
private fun CheckmarkGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        drawPolyline(
            points = listOf(
                Offset(w * 0.12f, w * 0.55f),
                Offset(w * 0.38f, w * 0.82f),
                Offset(w * 0.88f, w * 0.20f),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/** `magnifyingglass` — a circle with a trailing handle. */
@Composable
private fun MagnifierGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.12f
        drawCircle(
            color = color,
            radius = w * 0.28f,
            center = Offset(w * 0.42f, w * 0.42f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawPolyline(
            points = listOf(Offset(w * 0.63f, w * 0.63f), Offset(w * 0.88f, w * 0.88f)),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/**
 * Round-capped segments through [points]. Drawn as separate lines rather than a
 * Path: with round caps, consecutive segments meeting at a shared point render
 * the same as a round join, and this keeps the glyph definitions readable as
 * coordinate lists.
 */
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
