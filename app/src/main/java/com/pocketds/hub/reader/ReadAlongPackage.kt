package com.pocketds.hub.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** EPUB 3 media overlays. Only in-package resources are accepted; source files are never modified. */
object ReadAlongPackage {
    private const val XML_LIMIT = 4 * 1024 * 1024
    private const val AUDIO_LIMIT = 2L * 1024 * 1024 * 1024
    /** A sentence edition's narration at most, the hub's cap for one too. */
    const val SENTENCE_LIMIT = 200_000
    /**
     * A word edition's (#66): a `<par>` per spoken word, 88k to 193k in the first three books, and a 50-hour book would pass
     * the sentence cap. The hub holds a word set to the same number.
     */
    const val WORD_LIMIT = 1_000_000

    /**
     * The narration's timeline. [requireAudio] false reads the edition without
     * its audio (#19: the hub's slim edition), whose narration streams from the
     * audiobook's tracks: its SMIL still names the audio files, which are not
     * in the archive.
     *
     * A word edition (#66) nests a `<seq epub:textref="chapter.xhtml#sentence">` round the `<par>`s of each sentence's
     * words: each word is a segment whose [ReadAlongSegment.sentenceFragment] is that sentence (any other `<par>`, the
     * sentence's own id). The words are put in the order they are read, their clips moved on where the aligner placed one
     * a moment before the word ahead of it (0.5% of The Final Empire's) or two voices overlap, so the highlight only ever
     * goes forward and nothing is heard twice; a stretch still ends where the narration goes back ([stretches]).
     */
    fun read(file: File, requireAudio: Boolean = true): ReadAlongTimeline = ZipFile(file).use { zip ->
        // Every path below is in [DocumentPath]'s one spelling, and the zip is asked for it by that spelling too (#61).
        val entries = entries(zip)
        val container = xml(zip, entries["META-INF/container.xml"])
        val opf = container.children("rootfile").firstOrNull()?.getAttribute("full-path") ?: error("No EPUB package")
        val packagePath = resolve("", opf).first
        val doc = xml(zip, entries[packagePath])
        val manifest = doc.children("item").associateBy { it.getAttribute("id") }
        val segments = mutableListOf<ReadAlongSegment>()
        var words = false
        for (ref in doc.children("itemref")) {
            val chapter = manifest[ref.getAttribute("idref")] ?: continue
            val overlayId = chapter.getAttribute("media-overlay").takeIf { it.isNotBlank() } ?: continue
            val overlay = manifest[overlayId] ?: error("Missing media overlay")
            val smilPath = resolve(packagePath, overlay.getAttribute("href")).first
            // One overlay names the same document and the same audio file in thousands of <par>s (a word edition's, #66):
            // each reference is resolved, decoded and normalised once.
            val places = References(smilPath)
            // The words of one sentence share their <seq>'s place (the same string): resolved once a sentence.
            var lastRef: String? = null
            var lastPlace: Pair<String, String>? = null
            for (par in ReadAlongSmil.pars(text(zip, entries[smilPath]))) {
                val (href, fragment) = places.place(par.textSrc)
                require(fragment.isNotBlank()) { "Narrated text needs a fragment" }
                val audioHref = places.place(par.audioSrc).first
                require(href in entries && (!requireAudio || audioHref in entries)) { "Missing narration resource" }
                val begin = clock(par.clipBegin.ifBlank { "0s" })
                val end = clock(par.clipEnd)
                // Word alignment can emit a boundary of no length for a word
                // it did not match, and an older aligner a clip that ends
                // before it begins (the hub mends those it serves now, but an
                // edition kept on the device from before still has them).
                // Neither has audio to highlight: that one sentence is skipped
                // and the rest of the edition plays. Only an edition made of
                // nothing else has no narration (below).
                if (end <= begin) continue
                // A word's sentence: the place its <seq> names, when that is in the word's own document.
                val ref = par.sentenceRef
                if (ref !== lastRef) { lastRef = ref; lastPlace = ref?.let(places::placeOrNull) }
                val sentence = lastPlace?.takeIf { it.first == href && it.second.isNotBlank() }?.second ?: fragment
                if (sentence != fragment) words = true
                segments += ReadAlongSegment(href, fragment, audioHref, begin, end, sentence)
                require(segments.size <= if (words) WORD_LIMIT else SENTENCE_LIMIT) { "Narration timeline is too large" }
            }
        }
        require(segments.isNotEmpty()) { "This edition has no aligned narration" }
        ReadAlongTimeline(stretches(segments))
    }

    /** References from one overlay, resolved as [resolve] does, each document's once. */
    private class References(private val base: String) {
        private val documents = HashMap<String, String?>()
        fun place(reference: String): Pair<String, String> = placeOrNull(reference) ?: throw IllegalArgumentException("External narration resource")
        fun placeOrNull(reference: String): Pair<String, String>? {
            val hash = reference.indexOf('#')
            val path = if (hash < 0) reference else reference.substring(0, hash)
            // A reference that is only a fragment is a place in the overlay itself, which is resolved as it is.
            if (path.isEmpty()) return DocumentPath.resolve(base, reference)
            val document = documents.getOrPut(path) { DocumentPath.resolve(base, path)?.first } ?: return null
            val fragment = if (hash < 0) "" else reference.substring(hash + 1).let { if ('%' in it) DocumentPath.decode(it) else it }
            return document to fragment
        }
    }

    /**
     * How far a word edition's sentence may begin before the one ahead of it has ended and still be heard in the same
     * stretch (#66). Voices overlap in a dramatization, and the aligner's word ends run on a little: in The Final Empire
     * (Dramatized) 227 of the word edition's 258 such places overlap by a second or less, the largest by 5.8 s, while a
     * chapter told out of order goes back an hour. A new stretch is a new media item, which plays the overlap twice.
     */
    const val WORD_OVERLAP_MS = 10_000L

    /**
     * The segments, in the order the text reads them, as stretches the player can play one after another: a new stretch
     * where the audio file changes or a sentence begins before the one ahead of it has ended (the narration goes back), in
     * a word edition only when it begins more than [WORD_OVERLAP_MS] before. A stretch with words in it is then made to
     * run forward: each segment begins and ends no earlier than the one ahead of it, so the highlight only goes on and
     * nothing is heard twice. A sentence edition's stretches are what they always were.
     */
    internal fun stretches(segments: List<ReadAlongSegment>): List<ReadAlongTrack> {
        val tracks = mutableListOf<ReadAlongTrack>()
        var group = mutableListOf<ReadAlongSegment>()
        var groupEnd = Long.MIN_VALUE
        var words = false
        fun close() {
            if (group.isEmpty()) return
            tracks += ReadAlongTrack(group.first().audioHref, if (words) forward(group) else group.toList())
            group = mutableListOf(); groupEnd = Long.MIN_VALUE; words = false
        }
        var at = 0
        while (at < segments.size) {
            // One sentence's run of segments in one file.
            val first = segments[at]
            var until = at + 1
            while (until < segments.size && segments[until].let {
                    it.audioHref == first.audioHref && it.textHref == first.textHref && it.sentenceFragment == first.sentenceFragment
                }) until++
            val run = segments.subList(at, until)
            val runBegin = run.minOf { it.beginMs }
            val leeway = if (run.any { it.isWord }) WORD_OVERLAP_MS else 0L
            if (group.isNotEmpty() && (group.last().audioHref != first.audioHref || runBegin < groupEnd - leeway)) close()
            group += run
            groupEnd = maxOf(groupEnd, run.maxOf { it.endMs })
            words = words || leeway > 0
            at = until
        }
        close()
        return tracks
    }

    /** Segments as they are read, each beginning and ending no earlier than the one before. */
    private fun forward(words: List<ReadAlongSegment>): List<ReadAlongSegment> {
        var begin = Long.MIN_VALUE
        var end = Long.MIN_VALUE
        return words.map { word ->
            begin = maxOf(begin, word.beginMs)
            end = maxOf(end, word.endMs)
            if (begin == word.beginMs && end == word.endMs) word else word.copy(beginMs = begin, endMs = end)
        }
    }

    fun extractAudio(file: File, timeline: ReadAlongTimeline, directory: File, checkCancelled: () -> Unit = {}): List<File> {
        directory.mkdirs()
        return ZipFile(file).use { zip ->
            val extracted = mutableMapOf<String, File>()
            val entries = entries(zip)
            timeline.tracks.map { track -> extracted.getOrPut(track.audioHref) {
                checkCancelled()
                val entry = entries[track.audioHref] ?: error("Missing audio")
                require(entry.size in 1..AUDIO_LIMIT) { "Invalid audio size" }
                val target = File(directory, ReadingCheckpointKey.digest(track.audioHref + ":" + entry.crc) + ".audio")
                if (target.length() != entry.size) {
                    require(directory.usableSpace > entry.size + (32L shl 20)) { "Not enough space for narration" }
                    val partial = File(directory, target.name + ".part")
                    try {
                        zip.getInputStream(entry).use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var total = 0L
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= entry.size && total <= AUDIO_LIMIT)
                                output.write(buffer, 0, count)
                            }
                            require(total == entry.size)
                        } }
                        require(partial.renameTo(target)) { "Could not save narration" }
                    } finally { partial.delete() }
                }
                target
            } }
        }
    }

    internal fun clock(raw: String): Long {
        exactSeconds(raw)?.let { return it }
        val text = raw.trim().removePrefix("npt=")
        val seconds = when {
            ':' in text -> {
                val parts = text.split(':').map(String::toDouble)
                require(parts.size in 2..3 && parts.all { it >= 0 && it.isFinite() })
                parts.fold(0.0) { value, part -> value * 60 + part }
            }
            text.endsWith("ms") -> text.dropLast(2).toDouble() / 1000
            text.endsWith("min") -> text.dropLast(3).toDouble() * 60
            text.endsWith("h") -> text.dropLast(1).toDouble() * 3600
            else -> text.removeSuffix("s").toDouble()
        }
        require(seconds.isFinite() && seconds >= 0 && seconds < 365.0 * 24 * 3600)
        // Rounded, as the hub reads the same clock (alignment.go): "540.080s" is 540080 ms, never 540079.
        return Math.round(seconds * 1000)
    }

    /**
     * "540.080s", "12s": seconds with at most three decimals, which is how Storyteller and our packs write every clip, read
     * as whole milliseconds without a Double (two of them a word, 400k for a book, #66). Null for anything else.
     */
    private fun exactSeconds(raw: String): Long? {
        val n = raw.length
        if (n < 2 || raw[n - 1] != 's' || !raw[0].isDigit()) return null
        var i = 0
        var seconds = 0L
        while (i < n - 1 && raw[i] in '0'..'9') { seconds = seconds * 10 + (raw[i] - '0'); i++; if (seconds >= 365L * 24 * 3600) return null }
        var millis = 0L
        var digits = 0
        if (i < n - 1) {
            if (raw[i] != '.') return null
            i++
            while (i < n - 1) { if (raw[i] !in '0'..'9' || digits == 3) return null; millis = millis * 10 + (raw[i] - '0'); digits++; i++ }
            if (digits == 0) return null
        }
        repeat(3 - digits) { millis *= 10 }
        return seconds * 1000 + millis
    }

    /**
     * What a reference in the package points at: the path in [DocumentPath]'s one spelling and the fragment
     * ([DocumentPath.resolve]), for any legal path (a space, a bracket or a non-ASCII letter in a file name included, which
     * `java.net.URI` refused, #61); only a resource inside the package.
     */
    private fun resolve(base: String, relative: String): Pair<String, String> =
        DocumentPath.resolve(base, relative) ?: throw IllegalArgumentException("External narration resource")

    /**
     * The archive's entries by [DocumentPath]'s spelling, so a name written as one character in the book and as two in the zip
     * (or the reverse) is still found. The first of two entries that spell alike wins.
     */
    private fun entries(zip: ZipFile): Map<String, ZipEntry> {
        val found = LinkedHashMap<String, ZipEntry>()
        for (entry in zip.entries()) found.putIfAbsent(DocumentPath.name(entry.name), entry)
        return found
    }

    /** A document of the package as text, within the cap, with no DOCTYPE or entity declaration in it. */
    private fun text(zip: ZipFile, entry: ZipEntry?): String {
        entry ?: error("Missing EPUB document")
        require(entry.size in 1..XML_LIMIT.toLong())
        val bytes = zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= XML_LIMIT)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = bytes.toString(Charsets.UTF_8)
        // Only where a declaration can begin: a case-blind search of the whole text cost seconds for a word edition (#66).
        var at = text.indexOf("<!")
        while (at >= 0) {
            require(!text.regionMatches(at + 2, "DOCTYPE", 0, 7, ignoreCase = true) && !text.regionMatches(at + 2, "ENTITY", 0, 6, ignoreCase = true)) {
                "External XML entities are forbidden"
            }
            at = text.indexOf("<!", at + 2)
        }
        return text
    }

    /** The container or the package, as a DOM: small documents, read whole. The overlays are read by [ReadAlongSmil]. */
    private fun xml(zip: ZipFile, entry: ZipEntry?): Element {
        val text = text(zip, entry)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val parser = factory.newDocumentBuilder()
        parser.setEntityResolver { _, _ -> error("External XML is forbidden") }
        return parser.parse(InputSource(text.byteInputStream())).documentElement
    }
    /**
     * The elements named [name] below this one, in document order, by one walk of the tree. Not `getElementsByTagNameNS`,
     * whose live list Android's DOM walks again from the top for each item it is asked for: a word edition's overlay holds
     * thousands of `<par>`s (#66), and a book as large as The Final Empire took 20 s to open on the emulator that way.
     */
    private fun Element.children(name: String): List<Element> {
        val found = ArrayList<Element>()
        var node: org.w3c.dom.Node? = firstChild
        while (node != null) {
            if (node is Element && (node.localName ?: node.tagName.substringAfter(':')) == name) found += node
            // Depth first: down, else on, else up and on.
            node = node.firstChild ?: run {
                var up: org.w3c.dom.Node? = node
                while (up != null && up !== this && up.nextSibling == null) up = up.parentNode
                if (up == null || up === this) null else up.nextSibling
            }
        }
        return found
    }
}
