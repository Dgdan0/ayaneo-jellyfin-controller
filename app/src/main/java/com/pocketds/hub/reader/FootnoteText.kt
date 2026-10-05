package com.pocketds.hub.reader

/**
 * A footnote's words for its card (#18, E5), from the HTML Readium hands over
 * when a note reference is tapped: the note's element, already cleaned to a
 * safe subset (Jsoup's relaxed list, which keeps links but drops `epub:type`
 * and `role`). Paragraphs and line breaks stay; the link back to the text,
 * an arrow such as ↩, goes, since the card is closed rather than followed.
 *
 * Pure: a string in, a string out.
 */
object FootnoteText {
    private val BACKLINK = Regex("""<a\b[^>]*>\s*(?:↩︎?|↑|⤴|⏎|\^|&#8617;|&#x21a9;|&#8593;|&#x2191;)\s*</a>""", RegexOption.IGNORE_CASE)
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("""</(p|div|li|blockquote|h[1-6]|aside|section|tr)\s*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”")
    private val LONE_ARROW = Regex("""[ \t]*[↩⤴]︎?[ \t]*(?=\n|$)""")
    private val SPACES = Regex("""[ \t ]+""")
    private val GAPS = Regex("""\n{3,}""")

    fun plain(html: String): String {
        var text = BACKLINK.replace(html, "")
        text = BREAK.replace(text, "\n")
        text = BLOCK_END.replace(text, "\n\n")
        text = TAG.replace(text, "")
        // One pass, so "&amp;lt;" reads "&lt;" rather than "<".
        text = ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::character)
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::character)
                else -> NAMED[name]
            } ?: match.value
        }
        // An arrow written as a character rather than a link, left at the end of a line.
        text = LONE_ARROW.replace(text, "")
        return text.lines().joinToString("\n") { SPACES.replace(it, " ").trim() }
            .replace(GAPS, "\n\n")
            .trim()
    }

    private fun character(code: Int): String? = if (code in 1..0x10FFFF) String(Character.toChars(code)) else null
}
