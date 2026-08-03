package com.memoria.caregiver.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads the app's own source and fails if any analytics label could carry a
 * person's data.
 *
 * ## Why a source scan rather than an assertion about behaviour
 *
 * [UiEvent] has no field a caller can fill freely — [UiEventSchemaTest] pins
 * that. But `name` is a `String`, and it is written by whoever writes the call
 * site: `logButton` falls back to a button's own title when no explicit track
 * label is given. So a button titled `"Remove ${'$'}{patient.name}"` would put a
 * dementia patient's name into a row in `ui_events`, and no runtime test would
 * ever see it, because no test renders that hypothetical button.
 *
 * The property that actually protects us is structural: **every analytics label
 * in this app is a fixed string, written by hand, containing no interpolation.**
 * That is checkable, and this checks it. A Kotlin string template — a `${'$'}`
 * inside a label — is exactly the syntax that turns a constant into runtime data,
 * and there is no legitimate use for one in an event label.
 *
 * Scope covered:
 * - the four `log*` calls, wherever they appear;
 * - every `NemaraButton` title and track label, because the title is what
 *   `logButton` sends when there is no track label;
 * - every `NemaraToggle` track label.
 */
class AnalyticsLabelsAreFixedStringsTest {

    @Test
    fun `the source tree is actually being read`() {
        // Without this, a wrong working directory would scan zero files and this
        // whole class would pass by finding nothing — the shape of failure
        // HARNESS.md 4d exists to stop.
        assertTrue("expected to find main sources under $mainSources", sourceFiles.size > 50)
        assertTrue(
            "expected to find the analytics call sites",
            sourceFiles.any { it.name == "NemaraButton.kt" },
        )
    }

    @Test
    fun `no log call interpolates its label`() {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles) {
            val text = file.readText()
            for (call in listOf("logButton(", "logToggle(", "logScreen(", "logLifecycle(")) {
                for (argument in argumentsOfCallsTo(call, text)) {
                    if (INTERPOLATION in argument) {
                        offenders += "${file.name}: $call$argument"
                    }
                }
            }
        }
        assertEquals(
            "an analytics label must be a fixed string — see the class comment",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no button or toggle label interpolates`() {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles) {
            val text = file.readText()
            for (component in listOf("NemaraButton(", "NemaraToggle(")) {
                for (argumentList in argumentsOfCallsTo(component, text)) {
                    for (name in listOf("title", "track")) {
                        val value = namedArgument(name, argumentList) ?: continue
                        if (INTERPOLATION in value) {
                            offenders += "${file.name}: $component$name = $value"
                        }
                    }
                }
            }
        }
        assertEquals(
            "a button's title is what logButton sends when there is no track " +
                "label, so an interpolated title is an interpolated event name",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every button and toggle names itself with a literal or a plain title`() {
        // The companion guarantee to the test above: a label built somewhere
        // else and passed in as a variable would slip past an interpolation
        // check. Every call site must therefore name itself with either a
        // string literal `track`, or a string literal `title`.
        val offenders = mutableListOf<String>()
        for (file in sourceFiles) {
            val text = file.readText()
            for (component in listOf("NemaraButton(", "NemaraToggle(")) {
                for (argumentList in argumentsOfCallsTo(component, text)) {
                    val track = namedArgument("track", argumentList)
                    val title = namedArgument("title", argumentList)
                    val labelled = listOfNotNull(track, title).any { it.isStringLiteralExpression() }
                    if (!labelled) {
                        offenders += "${file.name}: $component${argumentList.take(80)}"
                    }
                }
            }
        }
        assertEquals(
            "every button and toggle must carry a hand-written label",
            emptyList<String>(),
            offenders,
        )
    }

    // ------------------------------------------------------------------

    /**
     * True when the expression is written out by hand rather than assembled at
     * runtime: a string literal, or a conditional choosing between literals,
     * which is how a button whose copy changes with state is written
     * (`if (isDeleting) "Removing…" else "Remove this patient"`).
     *
     * A conditional's *condition* is allowed to mention state — that only picks
     * which of two hand-written labels is used. What is not allowed is a label
     * that is itself a value, such as `title = patient.displayName`.
     */
    private fun String.isStringLiteralExpression(): Boolean {
        if (!contains('"')) return false
        // Drop `if (…)` conditions, then the literals themselves. Whatever is
        // left must be punctuation and the word `else`.
        var remainder = this
        while (true) {
            val condition = Regex("\\bif\\s*\\(").find(remainder) ?: break
            val close = matchingParenthesis(remainder, condition.range.last)
            if (close < 0) break
            remainder = remainder.removeRange(condition.range.first..close)
        }
        remainder = remainder.replace(Regex("\"[^\"]*\""), "")
            .replace("else", " ")
        return !Regex("[A-Za-z_][A-Za-z0-9_]*").containsMatchIn(remainder)
    }

    /**
     * The text between the parentheses of each call to [call], found by matching
     * brackets rather than by regex, so a nested call or a lambda argument does
     * not truncate the result.
     */
    private fun argumentsOfCallsTo(call: String, text: String): List<String> {
        val results = mutableListOf<String>()
        var from = text.indexOf(call)
        while (from >= 0) {
            // Skip the component's own declaration — `fun NemaraButton(` — which
            // matches the same text but is a parameter list, not a call site.
            val isDeclaration = text.substring(maxOf(0, from - 4), from) == "fun "
            val open = from + call.length - 1
            val close = matchingParenthesis(text, open)
            if (!isDeclaration && close > open) results += text.substring(open + 1, close)
            from = text.indexOf(call, from + call.length)
        }
        return results
    }

    private fun matchingParenthesis(text: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            index += 1
        }
        return -1
    }

    /** The value of `name = …` in an argument list, up to the next top-level comma. */
    private fun namedArgument(name: String, argumentList: String): String? {
        val marker = Regex("(^|[,\\s])$name\\s*=\\s*").find(argumentList) ?: return null
        var index = marker.range.last + 1
        var depth = 0
        val value = StringBuilder()
        var inString = false
        while (index < argumentList.length) {
            val character = argumentList[index]
            if (character == '"' && argumentList.getOrNull(index - 1) != '\\') inString = !inString
            if (!inString) {
                when (character) {
                    '(', '{', '[' -> depth += 1
                    ')', '}', ']' -> depth -= 1
                    ',' -> if (depth == 0) break
                    '\n' -> if (depth == 0 && value.isBalancedArgument()) break
                }
            }
            value.append(character)
            index += 1
        }
        return value.toString().trim().ifEmpty { null }
    }

    private fun StringBuilder.isBalancedArgument(): Boolean =
        count { it == '"' } % 2 == 0

    private companion object {

        /** A Kotlin string template. The one thing a label must never contain. */
        const val INTERPOLATION = '$'

        val mainSources: File by lazy {
            val candidates = listOf(
                "src/main/java/com/memoria/caregiver",
                "app/src/main/java/com/memoria/caregiver",
                "../app/src/main/java/com/memoria/caregiver",
            )
            candidates.map { File(it).absoluteFile }.firstOrNull { it.isDirectory }
                ?: error(
                    "could not locate the main source tree from " +
                        File("").absolutePath,
                )
        }

        val sourceFiles: List<File> by lazy {
            mainSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }
}
