package com.pocketds.hub.reader

/**
 * The `<par>`s of one media overlay, read in one pass over its text (#66). A word edition's overlays hold a `<par>` per
 * word: The Final Empire (Dramatized)'s 42 hold 193k of them, some 40 MB, and building the DOM of that took the emulator
 * 13 s on opening the book. This reads only what the narration needs, keeps no string it does not need, and refuses what
 * the DOM refused: a document that is not well formed (an end tag that does not match, a tag left open), and a DOCTYPE or
 * an entity declaration (the caller looks for those first, as it always has).
 *
 * What it reads is what [ReadAlongPackage] read from the DOM: every `<par>` in document order, the first `<text>` and
 * `<audio>` among its own children (their `src`, `clipBegin` and `clipEnd`), and, for a word, the `epub:textref` of the
 * nearest enclosing `<seq>` that names a place (a fragment), looking out through `<seq>`s only. Names are taken without
 * their prefix; the five predefined entities and character references are decoded in attribute values.
 */
internal object ReadAlongSmil {
    class Par(val textSrc: String, val audioSrc: String, val clipBegin: String, val clipEnd: String, val sentenceRef: String?)

    private const val OTHER = 0
    private const val PAR = 1
    private const val SEQ = 2
    private const val TEXT = 3
    private const val AUDIO = 4

    /** An element left open: where its name is in the text (to match its end tag), what it is, and what it carries. */
    private class Open(val nameFrom: Int, val nameTo: Int, val kind: Int, val sentenceRef: String?) {
        var text: String? = null
        var audioSrc: String? = null
        var clipBegin = ""
        var clipEnd = ""
        var audio = false
    }

    fun pars(xml: String): List<Par> {
        val out = ArrayList<Par>()
        val stack = ArrayList<Open>()
        val length = xml.length
        var at = 0
        while (true) {
            val lt = xml.indexOf('<', at)
            if (lt < 0) break
            if (lt + 1 >= length) malformed("a tag runs off the document")
            when (xml[lt + 1]) {
                '!' -> at = when {
                    xml.startsWith("<!--", lt) -> end(xml, "-->", lt)
                    xml.startsWith("<![CDATA[", lt) -> end(xml, "]]>", lt)
                    else -> malformed("a declaration in an overlay")
                }
                '?' -> at = end(xml, "?>", lt)
                '/' -> {
                    val gt = xml.indexOf('>', lt)
                    if (gt < 0) malformed("an end tag runs off the document")
                    var to = gt
                    while (to > lt + 2 && ws(xml[to - 1])) to--
                    val open = stack.removeLastOrNull() ?: malformed("an end tag closes nothing")
                    val (from, nameTo) = local(xml, lt + 2, to)
                    val (openFrom, openTo) = local(xml, open.nameFrom, open.nameTo)
                    if (nameTo - from != openTo - openFrom || !xml.regionMatches(from, xml, openFrom, nameTo - from)) malformed("an end tag closes another element")
                    if (open.kind == PAR) {
                        val text = open.text
                        if (text != null && open.audio) out += Par(text, open.audioSrc.orEmpty(), open.clipBegin, open.clipEnd, open.sentenceRef)
                    }
                    at = gt + 1
                }
                else -> at = start(xml, lt, stack)
            }
        }
        if (stack.isNotEmpty()) malformed("an element is never closed")
        return out
    }

    /** A start tag at [lt]: its name, the attributes the narration needs, whether it closes itself. Answers where it ends. */
    private fun start(xml: String, lt: Int, stack: ArrayList<Open>): Int {
        val length = xml.length
        var i = lt + 1
        while (i < length && !ws(xml[i]) && xml[i] != '>' && xml[i] != '/') i++
        val nameFrom = lt + 1
        val nameTo = i
        if (nameTo == nameFrom) malformed("a tag with no name")
        val (localFrom, localTo) = local(xml, nameFrom, nameTo)
        val kind = when {
            is_(xml, localFrom, localTo, "par") -> PAR
            is_(xml, localFrom, localTo, "seq") -> SEQ
            is_(xml, localFrom, localTo, "text") -> TEXT
            is_(xml, localFrom, localTo, "audio") -> AUDIO
            else -> OTHER
        }
        var src: String? = null
        var clipBegin = ""
        var clipEnd = ""
        var textref: String? = null
        var closed = false
        while (true) {
            while (i < length && ws(xml[i])) i++
            if (i >= length) malformed("a tag runs off the document")
            if (xml[i] == '>') { i++; break }
            if (xml[i] == '/') {
                if (i + 1 < length && xml[i + 1] == '>') { closed = true; i += 2; break }
                malformed("a stray / in a tag")
            }
            val attributeFrom = i
            while (i < length && xml[i] != '=' && !ws(xml[i]) && xml[i] != '>' && xml[i] != '/') i++
            val attributeTo = i
            while (i < length && ws(xml[i])) i++
            if (i >= length || xml[i] != '=') malformed("an attribute with no value")
            i++
            while (i < length && ws(xml[i])) i++
            if (i >= length || (xml[i] != '"' && xml[i] != '\'')) malformed("an attribute value with no quotes")
            val quote = xml[i]
            val close = xml.indexOf(quote, i + 1)
            if (close < 0) malformed("an attribute value runs off the document")
            // Only what the narration reads is kept; every value is still looked at for an entity it could not decode.
            when {
                (kind == TEXT || kind == AUDIO) && is_(xml, attributeFrom, attributeTo, "src") -> { if (src == null) src = decode(xml, i + 1, close) }
                kind == AUDIO && is_(xml, attributeFrom, attributeTo, "clipBegin") -> clipBegin = decode(xml, i + 1, close)
                kind == AUDIO && is_(xml, attributeFrom, attributeTo, "clipEnd") -> clipEnd = decode(xml, i + 1, close)
                kind == SEQ && textref == null && isTextref(xml, attributeFrom, attributeTo) -> textref = decode(xml, i + 1, close)
                else -> check(xml, i + 1, close)
            }
            i = close + 1
        }
        val parent = stack.lastOrNull()
        // A <text> or <audio> that is a child of a <par>: its first of each.
        if (parent != null && parent.kind == PAR) {
            if (kind == TEXT && parent.text == null) parent.text = src.orEmpty()
            if (kind == AUDIO && !parent.audio) { parent.audio = true; parent.audioSrc = src; parent.clipBegin = clipBegin; parent.clipEnd = clipEnd }
        }
        if (!closed) {
            val sentenceRef = when (kind) {
                SEQ -> textref?.takeIf { it.substringAfter('#', "").isNotBlank() } ?: parent?.takeIf { it.kind == SEQ }?.sentenceRef
                // A <par> is a word of the sentence its <seq> names; nothing else carries one on.
                PAR -> parent?.takeIf { it.kind == SEQ }?.sentenceRef
                else -> null
            }
            stack += Open(nameFrom, nameTo, kind, sentenceRef)
        }
        return i
    }

    private fun is_(xml: String, from: Int, to: Int, name: String) = to - from == name.length && xml.regionMatches(from, name, 0, name.length)

    /** XML's white space, which is these four and no other (Unicode's test is a table, and this runs for every character). */
    private fun ws(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r'

    /** `epub:textref`, by any prefix bound to it. */
    private fun isTextref(xml: String, from: Int, to: Int): Boolean {
        val colon = xml.indexOf(':', from).takeIf { it in from until to } ?: return false
        return is_(xml, colon + 1, to, "textref")
    }

    /** A name's local part, after its prefix. */
    private fun local(xml: String, from: Int, to: Int): Pair<Int, Int> {
        var i = from
        while (i < to && xml[i] != ':') i++
        return if (i < to) (i + 1) to to else from to to
    }

    private fun end(xml: String, marker: String, from: Int): Int {
        val found = xml.indexOf(marker, from)
        if (found < 0) malformed("a comment or instruction runs off the document")
        return found + marker.length
    }

    /** A value the narration does not need: refused only for an entity the DOM would have refused. */
    private fun check(xml: String, from: Int, to: Int) {
        var i = from
        while (i < to) { if (xml[i] == '&') { decode(xml, from, to); return }; i++ }
    }

    /** An attribute's value with the predefined entities and character references decoded. */
    private fun decode(xml: String, from: Int, to: Int): String {
        // Looked for in the value only: indexOf would search on to the end of the document for every value without one.
        var amp = from
        while (amp < to && xml[amp] != '&') amp++
        if (amp >= to) return xml.substring(from, to)
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = xml[i]
            if (c != '&') { out.append(c); i++; continue }
            var semi = i
            while (semi < to && xml[semi] != ';') semi++
            if (semi >= to) malformed("an entity with no end")
            val entity = xml.substring(i + 1, semi)
            when {
                entity == "amp" -> out.append('&')
                entity == "lt" -> out.append('<')
                entity == "gt" -> out.append('>')
                entity == "quot" -> out.append('"')
                entity == "apos" -> out.append('\'')
                entity.startsWith("#x") || entity.startsWith("#X") -> out.appendCodePoint(entity.substring(2).toIntOrNull(16) ?: malformed("a character reference"))
                entity.startsWith("#") -> out.appendCodePoint(entity.substring(1).toIntOrNull() ?: malformed("a character reference"))
                else -> malformed("an entity the document does not declare")
            }
            i = semi + 1
        }
        return out.toString()
    }

    private fun malformed(what: String): Nothing = throw IllegalArgumentException("The overlay is not well-formed XML: $what")
}
