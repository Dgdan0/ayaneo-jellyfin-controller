package com.pocketds.hub.reader

/**
 * A content document's text as the page shows it (#62), from its markup: what a highlight's passage is looked for in when the list
 * says whether the book still holds it. Not a parser: tags and the head are dropped, a block's end is a space, and the entities a
 * book uses are read; [AnnotationFinder] then compares in its own normal form, so spacing here need not be exact.
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
        return ENTITY.replace(body) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[name]
            } ?: match.value
        }
    }

    private fun codePoint(value: Int): String? = if (value in 1..0x10FFFF) String(Character.toChars(value)) else null
}
