package com.pocketds.hub.ui

/**
 * Lines that mix a Hebrew title with English facts.
 *
 * Joined plainly, "בלאגן ועד אילת - פרק 6 · 11 min" reads "11 · … פרק 6 min":
 * the bidi algorithm pulls the numbers next to the Hebrew into its run. Each
 * part wrapped in a first-strong isolate keeps its own direction, and the
 * line reads left to right part by part.
 */
object Bidi {
    private const val FIRST_STRONG_ISOLATE = '⁨'
    private const val POP_ISOLATE = '⁩'

    fun isolate(part: String): String = "$FIRST_STRONG_ISOLATE$part$POP_ISOLATE"

    fun join(parts: List<String>, separator: String): String = parts.joinToString(separator, transform = ::isolate)

    /** A line already joined with [separator], each part isolated. */
    fun isolateParts(line: String, separator: String): String = join(line.split(separator), separator)
}
