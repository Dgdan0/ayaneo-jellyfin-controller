package com.pocketds.hub.reader

import java.net.URLDecoder

/**
 * Where a Contents entry that points into a file (`chapter.xhtml#part2`) starts in it (#55), so several entries in
 * one file do not all show the page the file begins on ([PageInfo.entryPage] takes the share this finds).
 *
 * Pure: it reads a document's markup and nothing else. The reader reads each such file once when the book opens and
 * keeps the answers; Contents never waits for them.
 *
 * The share is the visible text before the element that carries the id, over all the visible text: the tags, the
 * head, styles and scripts are not words on the page, and a run of white space is one space, as a browser sets it.
 * It is an estimate (images, tables and headings take the room of more or fewer characters), and the numbers it
 * gives are good to a page or two over a chapter, which is what a printed contents list is.
 */
object AnchorShares {
    /** The anchor an href names: the part after `#`, percent-decoded; null for no fragment or an empty one. */
    fun fragmentOf(href: String): String? {
        val raw = href.substringAfter('#', "").takeIf { it.isNotEmpty() } ?: return null
        // A plus is a plus in a fragment, not a space.
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        return decoded.takeIf { it.isNotBlank() }
    }

    /**
     * How far into [markup] each of [anchors] starts, from 0 (the top) to just under 1. An anchor that is not found
     * has no entry, and so no page: it is not guessed at. Empty for a document with no visible text.
     */
    fun of(markup: String, anchors: Set<String>): Map<String, Double> {
        if (anchors.isEmpty() || markup.isEmpty()) return emptyMap()
        val offsets = HashMap<String, Int>()
        var visible = 0
        // A run of white space is one space, counted when a word follows it: none before the first word or after the last.
        var pending = false
        var i = 0
        val n = markup.length
        fun text(c: Char) {
            if (c.isWhitespace()) {
                if (visible > 0) pending = true
            } else {
                if (pending) { visible++; pending = false }
                visible++
            }
        }
        while (i < n) {
            val c = markup[i]
            if (c == '<') {
                if (markup.startsWith("<!--", i)) {
                    val end = markup.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                    continue
                }
                if (markup.startsWith("<![CDATA[", i)) {
                    val end = markup.indexOf("]]>", i + 9).let { if (it < 0) n else it }
                    for (k in i + 9 until end) text(markup[k])
                    i = (end + 3).coerceAtMost(n)
                    continue
                }
                val close = tagEnd(markup, i)
                val tag = markup.substring(i + 1, close.coerceAtMost(n))
                i = (close + 1).coerceAtMost(n)
                if (tag.isEmpty() || tag[0] == '/' || tag[0] == '!' || tag[0] == '?') continue
                val name = tag.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
                for (id in ids(tag)) if (id in anchors && id !in offsets) offsets[id] = visible + if (pending) 1 else 0
                // Not words on the page: the head, styles and scripts, up to their end tag (a self-closing one has none).
                if ((name == "head" || name == "style" || name == "script") && !tag.endsWith("/")) {
                    val end = markup.indexOf("</$name", i, ignoreCase = true)
                    if (end < 0) { i = n; continue }
                    i = (markup.indexOf('>', end).let { if (it < 0) n else it + 1 })
                }
                continue
            }
            if (c == '&') {
                // &amp; and &#8217; are one character each.
                val semi = markup.indexOf(';', i + 1)
                if (semi in i + 2..i + 10 && markup.subSequence(i + 1, semi).none { it.isWhitespace() || it == '<' || it == '&' }) {
                    text('x'); i = semi + 1; continue
                }
            }
            text(c)
            i++
        }
        if (visible <= 0) return emptyMap()
        // Just under 1: an anchor at the very end is still on the last page, not past it.
        return offsets.mapValues { (_, offset) -> (offset.toDouble() / visible).coerceIn(0.0, 0.999999) }
    }

    /** The index of the `>` that ends the tag opened at [open], past any `>` inside a quoted value; the end of the text if there is none. */
    private fun tagEnd(markup: String, open: Int): Int {
        var quote: Char? = null
        var k = open + 1
        while (k < markup.length) {
            val c = markup[k]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return k
            k++
        }
        return markup.length
    }

    private val idAttribute = Regex("""(?:^|\s)(?:xml:id|id|name)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /** The ids a tag carries: `id`, `xml:id` and the older `name` an anchor can have. */
    private fun ids(tag: String): List<String> =
        idAttribute.findAll(tag).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it.isNotEmpty() }.toList()
}
