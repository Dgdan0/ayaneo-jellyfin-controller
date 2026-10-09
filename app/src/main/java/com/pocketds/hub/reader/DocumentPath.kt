package com.pocketds.hub.reader

import java.io.ByteArrayOutputStream
import java.text.Normalizer

/**
 * The one spelling of a content document's path (#61), so the narration's file and the page's file can be compared.
 *
 * A book's files arrive in several spellings: the narration's SMIL writes `Brandon Sanderson - [Mistborn 01] - Part_010.htm`
 * (raw spaces and brackets), the zip holds it that way, and Readium hands the page's href back percent-encoded
 * (`Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20Part_010.htm`), maybe with a fragment, while a book made on a
 * Mac may have the same letter as one character or two (é, or e and a combining accent). Compared as they came, none of
 * these match, and a book whose file names have a space found no narration on any page. The comparison form is
 * **decoded, in Unicode NFC, with no fragment and no leading slash**: [of] makes it from an encoded spelling (what
 * Readium and a locator say), [name] from a path already decoded (a zip entry, what [resolve] returns), and
 * [encode] goes back to the form Readium resolves, for a locator that has to be handed to it.
 *
 * Plain Kotlin on strings (no `Uri`, no `URI`), so a JVM test pins it, and nothing in it throws: a legal EPUB path is
 * any string a zip entry can be named, which `java.net.URI` is not (it refuses `[`, `]` and a space).
 */
object DocumentPath {
    /** The document an encoded [href] names (a locator's, Readium's `Url`, a link): decoded, NFC, no fragment, no leading slash. */
    fun of(href: String): String = name(decode(href.substringBefore('#')))

    /** A path that is already decoded (a zip entry's name, a resolved path) in the same form: NFC, no leading slash. */
    fun name(path: String): String = Normalizer.normalize(path.trimStart('/'), Normalizer.Form.NFC)

    /**
     * Percent-decoded as UTF-8, leniently: a `%` that is not followed by two hex digits is a `%`, and a run of escapes
     * that is not UTF-8 is left as it was written, so no spelling is lost to a replacement character.
     */
    fun decode(encoded: String): String {
        if ('%' !in encoded) return encoded
        val out = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            if (encoded[i] != '%' || escape(encoded, i) < 0) { out.append(encoded[i]); i++; continue }
            val start = i
            val bytes = ByteArrayOutputStream()
            while (i < encoded.length && encoded[i] == '%' && escape(encoded, i) >= 0) { bytes.write(escape(encoded, i)); i += 3 }
            val raw = bytes.toByteArray()
            var at = 0
            while (at < raw.size) {
                val lead = raw[at].toInt() and 0xFF
                val length = when { lead < 0x80 -> 1; lead in 0xC2..0xDF -> 2; lead in 0xE0..0xEF -> 3; lead in 0xF0..0xF4 -> 4; else -> 0 }
                val whole = length > 0 && at + length <= raw.size && (1 until length).all { (raw[at + it].toInt() and 0xC0) == 0x80 }
                val piece = if (whole) String(raw, at, length, Charsets.UTF_8) else ""
                // Only a clean character is taken: bytes that are not UTF-8 would come back as U+FFFD, which is not what was written.
                if (whole && '�' !in piece) { out.append(piece); at += length }
                else { out.append(encoded, start + at * 3, start + at * 3 + 3); at++ }
            }
        }
        return out.toString()
    }

    /** The byte `%XY` at [at] is, or -1 where it is not an escape. */
    private fun escape(text: String, at: Int): Int {
        if (at + 2 > text.lastIndex) return -1
        val high = Character.digit(text[at + 1], 16)
        val low = Character.digit(text[at + 2], 16)
        return if (high < 0 || low < 0) -1 else high * 16 + low
    }

    /**
     * A decoded [path] as Readium spells an href it was given in the clear: the same escapes as `Url.fromDecodedPath`
     * (Android's `Uri.encode`, leaving letters, digits and `_-!.~'()*` and the path's own `$&+,/:=@`), UTF-8 and upper case.
     * What Readium resolves is the manifest's own spelling, which the reader keeps; this is for a spelling nothing has.
     */
    fun encode(path: String): String {
        val out = StringBuilder(path.length + 16)
        for (byte in path.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_-!.~'()*" || c in "$&+,/:=@") out.append(c)
            else out.append('%').append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[byte.toInt() and 0xF])
        }
        return out.toString()
    }

    /**
     * A reference as an EPUB document writes it ([reference]: a SMIL's `src`, the container's `full-path`, an OPF `href`,
     * encoded or not) resolved against the decoded path of the document holding it ([base], "" for the package's root):
     * the target's path in the comparison form, and its fragment decoded (the id as the page spells it). Null where the
     * reference is not a path inside the package: blank, a scheme, an absolute path, a query, a backslash, a `..`
     * out of the package, or a directory.
     *
     * Resolved here and not with `java.net.URI`, which refuses a path with a space or a bracket in it: a file called
     * `Author - [Series 01] - Title_split_010.htm` is a legal name and a legal entry of the zip.
     */
    fun resolve(base: String, reference: String): Pair<String, String>? {
        if (reference.isBlank() || '\\' in reference || '?' in reference.substringBefore('#') || reference.startsWith("/")) return null
        if (SCHEME.containsMatchIn(reference)) return null
        val fragment = decode(reference.substringAfter('#', ""))
        val path = decode(reference.substringBefore('#'))
        if ('\\' in path || path.endsWith("/")) return null
        val segments = ArrayList<String>()
        if (!path.isEmpty()) base.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty).forEach(segments::add)
        for (part in (if (path.isEmpty()) base else path).split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(part)
            }
        }
        if (segments.isEmpty()) return null
        return name(segments.joinToString("/")) to fragment
    }

    /** "mailto:", "https:" and the like: a reference that begins with a scheme is not a path in the package. */
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
}
