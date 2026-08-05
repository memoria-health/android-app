package com.memoria.caregiver.ui.settings

/**
 * The `**bold**` spans in a policy string, as index ranges over the text with
 * the markers removed.
 *
 * iOS renders these with `AttributedString(markdown:)`, which is one call.
 * Compose has no markdown parser, so the one construct the policy actually uses
 * is parsed here — and only that one. A general markdown parser would be a much
 * larger thing to get wrong, and every other construct in the notice is plain
 * text.
 *
 * Kept as a pure function over strings rather than building an `AnnotatedString`
 * so it can be tested on the JVM with no Compose runtime, which is the same
 * split the rest of this port uses for anything with a rule in it.
 */
internal data class BoldRuns(
    /** The text with `**` markers stripped. */
    val text: String,
    /** Ranges over [text] that should render bold, in order, non-overlapping. */
    val bold: List<IntRange>,
)

private const val MARKER = "**"

/**
 * Split [source] into plain text plus the ranges that were wrapped in `**`.
 *
 * Empty spans (`****`) produce no range rather than an empty one, since an empty
 * bold range styles nothing and only complicates the caller.
 *
 * @param keepUnmatchedMarkers what to do with a `**` that has no partner, and
 *   the two callers genuinely want opposite things.
 *
 *   **True (the default) for the privacy notice**: the marker is rendered
 *   literally, because that text is a legal document and silently dropping
 *   characters from one because a typo confused a parser is worse than showing
 *   two asterisks.
 *
 *   **False for the Home briefing**: that text is written by a language model
 *   which is *instructed* to bold names, so a stray marker is a model slip
 *   rather than an authored character. iOS is explicit about it —
 *   "raw asterisks must never reach a caregiver's screen" — and strips.
 */
internal fun boldRuns(source: String, keepUnmatchedMarkers: Boolean = true): BoldRuns {
    if (!source.contains(MARKER)) return BoldRuns(source, emptyList())

    val text = StringBuilder(source.length)
    val bold = mutableListOf<IntRange>()
    var index = 0
    var openAt = -1

    while (index < source.length) {
        if (source.startsWith(MARKER, index)) {
            if (openAt < 0) {
                // Only treat this as an opener if there is a closer for it.
                // Otherwise it is literal, and falls through to be copied — or
                // dropped, when the caller does not want stray markers shown.
                if (source.indexOf(MARKER, index + MARKER.length) >= 0) {
                    openAt = text.length
                    index += MARKER.length
                    continue
                }
                if (!keepUnmatchedMarkers) {
                    index += MARKER.length
                    continue
                }
            } else {
                if (text.length > openAt) bold += openAt until text.length
                openAt = -1
                index += MARKER.length
                continue
            }
        }
        text.append(source[index])
        index++
    }

    // An opener whose closer turned out to be inside another span. Nothing to
    // style, and the marker was already consumed — restore it so no character
    // of the notice is lost, unless the caller asked for markers to be dropped.
    if (openAt >= 0 && keepUnmatchedMarkers) text.insert(openAt, MARKER)

    return BoldRuns(text.toString(), bold)
}
