package com.pocketds.hub.reader

import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Highlights and notes (#62), on the Pocket, the iPad and the iPhone alike: kept by the hub per profile and per work.
 *
 * The four colours, in the order the colour row shows them. [id] is what the hub and the other apps keep.
 */
enum class HighlightColor(val id: String, val label: String) {
    YELLOW("yellow", "Yellow"), BLUE("blue", "Blue"), PINK("pink", "Pink"), GREEN("green", "Green");

    companion object {
        val DEFAULT = YELLOW
        fun of(id: String?): HighlightColor? = entries.firstOrNull { it.id == id }
        fun orDefault(id: String?): HighlightColor = of(id) ?: DEFAULT
    }
}

/**
 * The passage and the words either side of it, as the page's text says them. An anchor that survives a different edition of
 * the book (a different markup, another file split): the text is searched for, the context choosing between two places that
 * say the same words ([AnnotationFinder]).
 */
@Serializable
data class AnnotationQuote(val before: String = "", val highlight: String = "", val after: String = "")

/**
 * One highlight, with its note when it has one: the hub's JSON as it is. [document] is the file in [DocumentPath]'s one
 * spelling and [locator] Readium's own locator, a hint only for the app that wrote it. A [deleted] one is a tombstone, kept so a
 * device that was offline learns of it; it is never shown.
 */
@Serializable
data class ReadingAnnotation(
    val id: String,
    val color: String = HighlightColor.DEFAULT.id,
    val note: String = "",
    val document: String = "",
    val quote: AnnotationQuote = AnnotationQuote(),
    val locator: JsonObject? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    /** When the hub stored this version, on its own clock: what "changed since" counts. Never set by the app. */
    val syncedAt: Long = 0
) {
    val hasNote: Boolean get() = note.isNotBlank()
    val highlightColor: HighlightColor get() = HighlightColor.orDefault(color)
}

/** What the hub keeps of one highlight, in bytes (UTF-8): more is refused, so it is cut here, never sent to be refused for ever. */
object AnnotationLimits {
    const val NOTE_BYTES = 4000
    const val PASSAGE_BYTES = 2000

    /** The most of [text] that fits in [bytes], cut between characters. */
    fun clip(text: String, bytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= bytes) return text
        var used = 0
        var end = 0
        while (end < text.length) {
            val step = if (Character.isHighSurrogate(text[end]) && end + 1 < text.length) 2 else 1
            val size = text.substring(end, end + step).toByteArray(Charsets.UTF_8).size
            if (used + size > bytes) break
            used += size
            end += step
        }
        return text.substring(0, end)
    }
}

/** Making a quote from what Readium reports of a selection. */
object AnnotationQuotes {
    /** How much of the neighbouring text is kept either side: enough to choose between two places that read alike. */
    const val CONTEXT = 60

    /** A selection as a quote: the passage with its whitespace tidied, and a little of the words before and after it. */
    fun of(before: String?, highlight: String, after: String?): AnnotationQuote =
        AnnotationQuote(tail(before.orEmpty(), CONTEXT), AnnotationLimits.clip(highlight.replace(RUN, " ").trim(), AnnotationLimits.PASSAGE_BYTES), head(after.orEmpty(), CONTEXT))

    /** The last [max] characters of [text], starting at a word. */
    fun tail(text: String, max: Int): String {
        val tidy = text.replace(RUN, " ").trim()
        if (tidy.length <= max) return tidy
        val cut = tidy.substring(tidy.length - max)
        // The cut began inside a word when the character before it is a letter: drop that piece.
        val inside = tidy[tidy.length - max - 1].isLetterOrDigit() && cut.first().isLetterOrDigit()
        return if (inside) cut.substringAfter(' ', cut) else cut
    }

    /** The first [max] characters of [text], ending at a word. */
    fun head(text: String, max: Int): String {
        val tidy = text.replace(RUN, " ").trim()
        if (tidy.length <= max) return tidy
        val cut = tidy.substring(0, max)
        val inside = tidy[max].isLetterOrDigit() && cut.last().isLetterOrDigit()
        return if (inside) cut.substringBeforeLast(' ', cut) else cut
    }

    private val RUN = Regex("\\s+")
}

/**
 * Finding a quote in a document's text (#62): the same search Readium's decoration makes to draw it, here for the list of
 * highlights, which says "can't find this passage" for one that the book's text no longer holds (another edition, a corrected
 * typo) rather than dropping it. Plain Kotlin on strings, so a JVM test pins it.
 *
 * Both sides are compared in one normal form ([normalize]): Unicode NFC, a run of whitespace as one space, curly quotes and
 * dashes as their plain forms, no soft hyphens, case ignored. That is a comparison form only; nothing is written in it.
 */
object AnnotationFinder {
    /** Where the quote is: [start] to [end] in the normalised text, [share] of the way through it, and how many places say it. */
    data class Found(val start: Int, val end: Int, val length: Int, val places: Int) {
        val share: Double get() = if (length <= 0) 0.0 else start.toDouble() / length
    }

    fun normalize(text: String): String = TextNormalizer().apply { append(text) }.toString()

    /**
     * Streams text into the comparison form chunk by chunk, so a document's text can be built as its nodes are read and the place of
     * each element in it noted ([length] is where the next character goes). A chunk's leading space is held until a character follows.
     */
    class TextNormalizer {
        private val out = StringBuilder()
        private var space = false

        val length: Int get() = out.length

        fun append(text: String) {
            val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
            for (c in composed) {
                when {
                    c == '\u00AD' || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' -> Unit
                    c.isWhitespace() || c == '\u00A0' -> space = out.isNotEmpty()
                    c == '\u2026' -> { if (space) out.append(' '); space = false; out.append("...") }
                    else -> {
                        if (space) out.append(' ')
                        space = false
                        out.append(Character.toLowerCase(when (c) {
                            '\u2018', '\u2019', '\u201B', '\u02BC' -> '\''
                            '\u201C', '\u201D', '\u201F' -> '"'
                            '\u2013', '\u2014', '\u2212' -> '-'
                            else -> c
                        }))
                    }
                }
            }
        }

        override fun toString(): String = out.toString()
    }

    /**
     * Where [quote] is in [text], or null when it is not there. The passage itself must be present; when it is said in more
     * than one place the one whose neighbours agree best with the quote's wins, and between equals the one nearest [hint]
     * (how far through the document it was, 0 to 1) else the first.
     */
    fun find(text: String, quote: AnnotationQuote, hint: Double? = null): Found? = findIn(normalize(text), quote, hint)

    /** [find] in a document's text that is already in the comparison form ([normalize]). */
    fun findIn(document: String, quote: AnnotationQuote, hint: Double? = null): Found? {
        val passage = normalize(quote.highlight).trim()
        if (passage.isEmpty() || document.isEmpty()) return null
        val before = normalize(quote.before)
        val after = normalize(quote.after)
        var best: Found? = null
        var bestScore = -1
        var bestDistance = Double.MAX_VALUE
        var places = 0
        var from = 0
        while (true) {
            val at = document.indexOf(passage, from)
            if (at < 0) break
            places++
            val end = at + passage.length
            val score = agreement(document, at, end, before, after)
            val distance = if (hint == null) at.toDouble() else Math.abs(at.toDouble() / document.length - hint)
            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                best = Found(at, end, document.length, 0)
                bestScore = score
                bestDistance = distance
            }
            from = at + 1
        }
        return best?.copy(places = places)
    }

    /**
     * How many characters of the words just before and just after the passage the quote's context repeats. The context is
     * kept trimmed, so the one space between it and the passage is stepped over rather than compared.
     */
    private fun agreement(document: String, start: Int, end: Int, before: String, after: String): Int {
        val trimmedBefore = before.trimEnd()
        val trimmedAfter = after.trimStart()
        var score = 0
        val from = if (start > 0 && document[start - 1] == ' ') start - 1 else start
        var i = 0
        while (i < trimmedBefore.length && from - 1 - i >= 0 && document[from - 1 - i] == trimmedBefore[trimmedBefore.length - 1 - i]) { score++; i++ }
        val to = if (end < document.length && document[end] == ' ') end + 1 else end
        i = 0
        while (i < trimmedAfter.length && to + i < document.length && document[to + i] == trimmedAfter[i]) { score++; i++ }
        return score
    }
}
