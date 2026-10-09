package com.pocketds.hub.reader

/**
 * A content document's text as the page shows it (#62), from its markup: what a highlight's passage is looked for in when the list
 * says whether the book still holds it, and what a sentence of the narration is found in. Not a parser: tags and the head are
 * dropped, a block's end is a space, and the entities a book uses are read; [AnnotationFinder] then compares in its own normal
 * form, so spacing here need not be exact.
 */
object DocumentText {
    private val HEAD = Regex("<head\\b.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HIDDEN = Regex("<(script|style|rt|rp)\\b.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK = Regex("</?(p|div|br|li|ul|ol|h[1-6]|blockquote|tr|td|th|section|article|aside|figure|figcaption|pre|dt|dd|hr|table)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ldquo" to "“",
        "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "shy" to "", "copy" to "©", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ouml" to "ö", "uuml" to "ü")

    fun plain(markup: String): String {
        val body = markup.replace(COMMENT, " ").replace(HEAD, " ").replace(HIDDEN, " ")
            .replace(BLOCK, " ").replace(TAG, "")
        return decode(body)
    }

    private fun decode(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name]
        } ?: match.value
    }

    private fun codePoint(value: Int): String? = if (value in 1..0x10FFFF) String(Character.toChars(value)) else null

    /**
     * A document's text twice, as it was written ([raw]: tags out and entities read, nothing else changed) and in the comparison form
     * ([normal]), and where the text of each element named in [ids] is in each: a sentence of the narration, found by the words it holds.
     */
    class Scan(val raw: String, val normal: String, val rawSpans: Map<String, IntRange>, val normalSpans: Map<String, IntRange>)

    private val HIDDEN_TAGS = setOf("script", "style", "head", "rt", "rp")
    private val VOID_TAGS = setOf("br", "hr", "img", "meta", "link", "input", "col", "area", "base", "wbr")
    private val BLOCK_TAGS = setOf("p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "tr", "td", "th", "section",
        "article", "aside", "figure", "figcaption", "pre", "dt", "dd", "hr", "table")
    private val ID = Regex("""\bid\s*=\s*("([^"]*)"|'([^']*)')""")

    private class Open(val name: String, val id: String?, val rawStart: Int, val normalStart: Int)

    fun scan(markup: String, ids: Set<String>): Scan {
        val raw = StringBuilder()
        val normal = AnnotationFinder.TextNormalizer()
        val rawSpans = HashMap<String, IntRange>()
        val normalSpans = HashMap<String, IntRange>()
        val open = ArrayList<Open>()
        fun text(chunk: String) {
            if (chunk.isEmpty()) return
            val read = decode(chunk)
            raw.append(read)
            normal.append(read)
        }
        fun boundary() { raw.append(' '); normal.append(" ") }
        var i = 0
        val n = markup.length
        while (i < n) {
            if (markup[i] != '<') {
                val next = markup.indexOf('<', i).let { if (it < 0) n else it }
                text(markup.substring(i, next))
                i = next
                continue
            }
            if (markup.startsWith("<!--", i)) { i = markup.indexOf("-->", i).let { if (it < 0) n else it + 3 }; continue }
            if (markup.startsWith("<?", i) || markup.startsWith("<!", i)) { i = markup.indexOf('>', i).let { if (it < 0) n else it + 1 }; continue }
            val close = tagEnd(markup, i)
            val tag = markup.substring(i + 1, close.coerceAtMost(n))
            i = (close + 1).coerceAtMost(n)
            val ending = tag.startsWith("/")
            val name = tag.removePrefix("/").trimStart().takeWhile { !it.isWhitespace() && it != '/' && it != '>' }.substringAfter(':').lowercase()
            val selfClosing = tag.endsWith("/") || name in VOID_TAGS
            if (name in HIDDEN_TAGS && !ending && !selfClosing) {
                // Everything to its end tag is out of the text.
                val end = Regex("</\\s*(?:[a-z]+:)?$name\\s*>", RegexOption.IGNORE_CASE).find(markup, i)
                i = end?.range?.last?.plus(1) ?: n
                continue
            }
            if (name in BLOCK_TAGS) boundary()
            if (ending) {
                val at = open.indexOfLast { it.name == name }
                if (at >= 0) {
                    while (open.size > at + 1) open.removeAt(open.lastIndex)
                    val closed = open.removeAt(at)
                    closed.id?.takeIf { it in ids }?.let {
                        rawSpans[it] = closed.rawStart until raw.length
                        normalSpans[it] = closed.normalStart until normal.length
                    }
                }
                continue
            }
            val id = ID.find(tag)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            if (selfClosing) {
                id?.takeIf { it in ids }?.let { rawSpans[it] = raw.length until raw.length; normalSpans[it] = normal.length until normal.length }
            } else open += Open(name, id, raw.length, normal.length)
        }
        return Scan(raw.toString(), normal.toString(), rawSpans, normalSpans)
    }

    /** Where a tag that begins at [from] ends: its `>`, past any quoted attribute that holds one. */
    private fun tagEnd(markup: String, from: Int): Int {
        var quote = '\u0000'
        var i = from + 1
        while (i < markup.length) {
            val c = markup[i]
            when {
                quote != '\u0000' -> if (c == quote) quote = '\u0000'
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return markup.length
    }
}
