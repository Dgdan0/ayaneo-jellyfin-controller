package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioManifest

/** A sentence of the book, as another edition's text can find it: the file and the quote ([AnnotationQuote]). */
data class SentenceAnchor(val document: String, val quote: AnnotationQuote)

/**
 * The read-along edition's text and its narration put together (#62), so a place in one is a place in the other whatever the markup of
 * the edition a person reads: the sentence of the narration that holds a passage of the ebook (by the words, as a highlight is found),
 * and the words of a sentence of the narration, for the ebook to find and mark ("Heard to here"). The markup of a document comes from
 * [markup], which answers with it for a path in [DocumentPath]'s spelling, or null.
 */
class AlignedBook(private val timeline: ReadAlongTimeline, private val markup: (document: String) -> String?) {
    private val scans = HashMap<String, DocumentText.Scan?>()

    private fun scan(document: String): DocumentText.Scan? = scans.getOrPut(document) {
        runCatching { markup(document)?.let { DocumentText.scan(it, timeline.fragments(document).toSet()) } }.getOrNull()
    }

    /**
     * The narrated sentence that holds the start of [quote] in [document]: the smallest narrated element that contains it, else the one
     * that follows it, else the last before it. Null when the document is not narrated or does not hold the passage.
     */
    fun sentenceOf(document: String, quote: AnnotationQuote, hint: Double? = null): ReadAlongSegment? {
        val scan = scan(document) ?: return null
        val found = AnnotationFinder.findIn(scan.normal, quote, hint) ?: return null
        val spans = scan.normalSpans
        val holding = spans.entries.filter { found.start in it.value }.minByOrNull { it.value.last - it.value.first }
        val chosen = holding?.key
            ?: spans.entries.filter { it.value.first >= found.start }.minByOrNull { it.value.first }?.key
            ?: spans.entries.filter { it.value.last < found.start }.maxByOrNull { it.value.last }?.key
            ?: return null
        return timeline.locate(document, chosen)?.segment
    }

    /** The words of a narrated sentence as the page of the same book reads them, with a little of what comes either side. */
    fun anchorOf(segment: ReadAlongSegment): SentenceAnchor? {
        val scan = scan(segment.textHref) ?: return null
        // A word of a word edition (#66) is heard as part of its sentence: the anchor is the sentence's words.
        val span = scan.rawSpans[segment.sentenceFragment] ?: return null
        val sentence = scan.raw.substring(span.first, (span.last + 1).coerceAtMost(scan.raw.length)).replace(WHITESPACE, " ").trim()
        if (sentence.isEmpty()) return null
        val quote = AnnotationQuotes.of(scan.raw.substring(0, span.first), sentence, scan.raw.substring((span.last + 1).coerceAtMost(scan.raw.length)))
        return SentenceAnchor(segment.textHref, quote)
    }

    private companion object { val WHITESPACE = Regex("\\s+") }
}

/** Where the narration of a read-along edition is heard in the audiobook's tracks, and back (#62): one owner for the mapping. */
object AlignedPlaces {
    /** The track of the manifest and the moment in it where [segment] is heard, as the place the hub keeps; null where its audio file is not mapped. */
    fun audioPlace(manifest: ReadingAudioManifest, segment: ReadAlongSegment): AudioPlace? {
        val file = manifest.alignment?.audio.orEmpty().firstOrNull { DocumentPath.name(it.href) == DocumentPath.name(segment.audioHref) } ?: return null
        return AudioPlace.canonical(manifest.tracks, file.track, file.startMs.coerceAtLeast(0) + segment.beginMs)
    }

    /** The sentence heard at [offsetMs] of the manifest's track [track] (its index), or the last one before it; null where no file is mapped there. */
    fun segmentAt(manifest: ReadingAudioManifest, timeline: ReadAlongTimeline, track: Int, offsetMs: Long): ReadAlongSegment? {
        val file = manifest.alignment?.audio.orEmpty().filter { it.track == track && it.startMs <= offsetMs }.maxByOrNull { it.startMs } ?: return null
        val inFile = offsetMs - file.startMs.coerceAtLeast(0)
        val sentences = timeline.tracks.filter { DocumentPath.name(it.audioHref) == DocumentPath.name(file.href) }.flatMap { it.segments }
        return sentences.lastOrNull { it.beginMs <= inFile && inFile < it.endMs } ?: sentences.lastOrNull { it.endMs <= inFile } ?: sentences.firstOrNull()
    }

    /** Where the read-along player stands for [segment]: its stretch of the timeline and the moment in it. */
    fun position(timeline: ReadAlongTimeline, segment: ReadAlongSegment): ReadAlongPosition? =
        timeline.locate(segment.textHref, segment.fragment)?.let { timeline.positionAt(it.track, segment.beginMs) }
}

/** The markup of the documents of an EPUB on disk, by [DocumentPath]'s spelling of their names: what [AlignedBook] reads. */
class EpubMarkup(private val file: java.io.File) {
    private val names: Map<String, String> by lazy {
        java.util.zip.ZipFile(file).use { zip -> zip.entries().asSequence().associate { DocumentPath.name(it.name) to it.name } }
    }

    fun read(document: String): String? = runCatching {
        val entry = names[document] ?: return null
        java.util.zip.ZipFile(file).use { zip ->
            val found = zip.getEntry(entry) ?: return null
            if (found.size > LIMIT) return null
            zip.getInputStream(found).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.getOrNull()

    private companion object { const val LIMIT = 16L * 1024 * 1024 }
}
