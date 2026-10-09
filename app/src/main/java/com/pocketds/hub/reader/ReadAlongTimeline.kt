package com.pocketds.hub.reader

/**
 * One narrated sentence, or one word of it (#66). [textHref] and [audioHref] are paths inside the package in
 * [DocumentPath]'s one spelling (decoded, NFC, no fragment, #61): that is what every `href` this timeline is asked about
 * must be too, so a page's href from Readium goes through [DocumentPath.of] first.
 *
 * A word edition (our wordsync packs, which the hub serves with `granularity=word`) narrates a `<par>` per word inside a
 * `<seq epub:textref="…#sentence">` per sentence: [fragment] is the word's id (`<sid>-wN`) and [sentenceFragment] the
 * sentence's. A sentence edition (Storyteller's own, and a sentence a word edition could not wrap) has the two the same.
 * Places are saved, stepped and probed by the sentence; only the highlight and the seeks are by the word.
 */
data class ReadAlongSegment(
    val textHref: String, val fragment: String, val audioHref: String, val beginMs: Long, val endMs: Long,
    val sentenceFragment: String = fragment
) {
    /** A word of a sentence, not a sentence of its own. */
    val isWord: Boolean get() = fragment != sentenceFragment
}
data class ReadAlongPosition(val track: Int, val offsetMs: Long)
data class ReadAlongTrack(val audioHref: String, val segments: List<ReadAlongSegment>) {
    val startMs get() = segments.first().beginMs
    val durationMs get() = segments.last().endMs - startMs
}

/**
 * The narration's stretches, each a run of segments in one audio file whose clips run forward: what the player plays,
 * one media item a stretch. [ReadAlongPackage.read] makes them so (a word edition's words are put in order within each
 * sentence), and every lookup here relies on it.
 */
data class ReadAlongTimeline(val tracks: List<ReadAlongTrack>) {
    /**
     * The segment being read at a moment: the last one begun, while it lasts. Between two words of one sentence the word
     * before is still the one being read (#66): the trail and the word stay lit through the pause, and the next word takes
     * over when it begins. Between two sentences (and before the first) nothing is.
     */
    fun active(track: Int, offsetMs: Long): ReadAlongSegment? {
        val value = tracks.getOrNull(track) ?: return null
        val absolute = value.startMs + offsetMs
        // Word-level overlays can contain hundreds of thousands of segments.
        // Playback asks for the active segment on every position tick.
        var low = 0
        var high = value.segments.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (value.segments[middle].beginMs <= absolute) low = middle + 1
            else high = middle - 1
        }
        val begun = value.segments.getOrNull(high) ?: return null
        if (absolute < begun.endMs) return begun
        if (!begun.isWord) return null
        val next = value.segments.getOrNull(high + 1) ?: return null
        return begun.takeIf { next.textHref == it.textHref && next.sentenceFragment == it.sentenceFragment }
    }

    /**
     * Where [fragment] of [href] is narrated: a word or a sentence by its own id, else a sentence of a word edition by the
     * sentence's id (a saved place, which is always a sentence), at its first word.
     */
    fun find(href: String, fragment: String): ReadAlongPosition? {
        val located = locate(href, fragment) ?: return null
        return ReadAlongPosition(located.track, located.segment.beginMs - tracks[located.track].startMs)
    }

    /** A segment with its place in the narration: which file, which one in it. */
    data class Located(val track: Int, val index: Int, val segment: ReadAlongSegment)

    /**
     * Where [fragment] of [href] is narrated (the first segment naming it), for the page's maths (#49): a word or a sentence
     * by its own id, else the first word of the sentence of that id.
     */
    fun locate(href: String, fragment: String): Located? =
        index.exact[href]?.get(fragment) ?: index.sentences[href]?.get(fragment)?.first

    /** The segment after [located], on into the next file at the end of one; null after the last. */
    fun after(located: Located): Located? {
        val value = tracks.getOrNull(located.track) ?: return null
        value.segments.getOrNull(located.index + 1)?.let { return Located(located.track, located.index + 1, it) }
        val next = tracks.getOrNull(located.track + 1)?.takeIf { it.segments.isNotEmpty() } ?: return null
        return Located(located.track + 1, 0, next.segments.first())
    }

    /** The first segment of the sentence after the one [located] is in; null after the last. For a sentence edition, [after]. */
    fun nextSentence(located: Located): Located? {
        var at: Located = located
        while (true) {
            val next = after(at) ?: return null
            if (next.segment.textHref != located.segment.textHref || next.segment.sentenceFragment != located.segment.sentenceFragment) return next
            at = next
        }
    }

    /**
     * A sentence's stretch of the narration: from its first word's begin to its last word's end, in the file of its first
     * word. For a sentence edition, the sentence's own clip.
     */
    data class Sentence(val first: Located, val endMs: Long) {
        val beginMs: Long get() = first.segment.beginMs
    }

    /** The sentence of that id in [href]: a sentence of its own, or a sentence of words. */
    fun sentence(href: String, sentenceFragment: String): Sentence? = index.sentences[href]?.get(sentenceFragment)

    /**
     * The first word of [sentence] the narration says at [word] or after it (#66): [word] itself, else (a word the
     * dramatization cut, which has no clip) the next one by the number its id ends in (`<sid>-wN`). Null when none is.
     */
    fun wordAtOrAfter(href: String, sentence: Sentence, word: String): Located? {
        val id = sentence.first.segment.sentenceFragment
        index.exact[href]?.get(word)?.takeIf { it.segment.sentenceFragment == id }?.let { return it }
        val wanted = wordNumber(word) ?: return null
        var at: Located? = sentence.first
        while (at != null && at.segment.textHref == href && at.segment.sentenceFragment == id) {
            if ((wordNumber(at.segment.fragment) ?: -1) >= wanted) return at
            at = after(at)
        }
        return null
    }

    /** The N of a word's id, `<sentence>-wN`. */
    private fun wordNumber(fragment: String): Int? =
        fragment.substringAfterLast("-w", "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    /** Where in its file [absoluteMs] of [track] is, as the player counts it. */
    fun positionAt(track: Int, absoluteMs: Long): ReadAlongPosition =
        ReadAlongPosition(track, absoluteMs - (tracks.getOrNull(track)?.startMs ?: 0L))

    /** Every sentence of [href] the narration names, in the order it reads them: what a page is probed for (#49). */
    fun fragments(href: String): List<String> = index.sentences[href]?.keys?.toList().orEmpty()

    /** A word edition: some segment is a word of its sentence. */
    val wordLevel: Boolean get() = index.wordLevel

    /**
     * L1 and R1 read along (#16, A5): where the sentence [delta] away from
     * [position] begins, across tracks. Back from more than [RESTART_MS] into
     * a sentence goes to its own start first, as a player's Previous does.
     * Null past either end. Always by the sentence, in a word edition too.
     */
    fun step(position: ReadAlongPosition, delta: Int): ReadAlongPosition? {
        val all = index.order
        if (all.isEmpty() || delta == 0) return null
        val track = tracks.getOrNull(position.track) ?: return null
        val absolute = track.startMs + position.offsetMs
        // The sentence playing, or the last one begun before a gap.
        var low = 0
        var high = all.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = all[middle].first
            if (start.track < position.track || (start.track == position.track && start.segment.beginMs <= absolute)) low = middle + 1
            else high = middle - 1
        }
        val here = high
        val target = when {
            here < 0 -> if (delta > 0) delta - 1 else return null
            delta < 0 && all[here].first.track == position.track && absolute - all[here].beginMs > RESTART_MS -> here + delta + 1
            else -> here + delta
        }
        val start = all.getOrNull(target)?.first ?: return null
        return ReadAlongPosition(start.track, start.segment.beginMs - tracks[start.track].startMs)
    }

    /** Some sentence of [href]'s text is narrated: the page can be followed. */
    fun narrates(href: String): Boolean = index.sentences[href]?.isNotEmpty() == true

    /**
     * The start of a sentence of [href]'s narration, [share] of the way through it in the order it is read (0 the first,
     * 1 the last); null where [href] is not narrated. Where Play goes from a page that shows none of the narration (#61).
     */
    fun sentenceAt(href: String, share: Double): Located? {
        val all = index.sentences[href]?.values?.toList()?.takeIf { it.isNotEmpty() } ?: return null
        val at = if (share.isFinite()) (share.coerceIn(0.0, 1.0) * all.size).toInt() else 0
        return all[at.coerceIn(0, all.lastIndex)].first
    }

    /**
     * The lookups from a place in the text, made the first time one is asked for: never by [active], which playback asks
     * ten times a second, and once for a book of a hundred thousand words rather than a scan of it per question.
     */
    private val index: Index by lazy { Index.of(tracks) }

    private class Index(
        val exact: Map<String, Map<String, Located>>,
        val sentences: Map<String, LinkedHashMap<String, Sentence>>,
        val order: List<Sentence>,
        val wordLevel: Boolean
    ) {
        companion object {
            fun of(tracks: List<ReadAlongTrack>): Index {
                class Span(val first: Located) { var endMs = first.segment.endMs }
                val exact = HashMap<String, HashMap<String, Located>>()
                val spans = HashMap<String, LinkedHashMap<String, Span>>()
                val order = ArrayList<Span>()
                var words = false
                tracks.forEachIndexed { track, value ->
                    value.segments.forEachIndexed { index, segment ->
                        val located = Located(track, index, segment)
                        exact.getOrPut(segment.textHref) { HashMap() }.putIfAbsent(segment.fragment, located)
                        if (segment.isWord) words = true
                        val inDocument = spans.getOrPut(segment.textHref) { LinkedHashMap() }
                        val known = inDocument[segment.sentenceFragment]
                        if (known == null) Span(located).also { inDocument[segment.sentenceFragment] = it; order += it }
                        // The later words of a sentence that has begun, in its file: its end moves on.
                        else if (known.first.track == track) known.endMs = maxOf(known.endMs, segment.endMs)
                    }
                }
                val made = HashMap<Span, Sentence>(order.size * 2)
                order.forEach { made[it] = Sentence(it.first, it.endMs) }
                val sentences = HashMap<String, LinkedHashMap<String, Sentence>>(spans.size * 2)
                spans.forEach { (href, inDocument) ->
                    sentences[href] = LinkedHashMap<String, Sentence>(inDocument.size * 2).apply { inDocument.forEach { (id, span) -> put(id, made.getValue(span)) } }
                }
                return Index(exact, sentences, order.map(made::getValue), words)
            }
        }
    }

    companion object {
        /** Further into a sentence than this, back goes to its start rather than the sentence before. */
        const val RESTART_MS = 1_500L
    }
}

/**
 * What a read-along page says about the narration (#16, A5, #49): while it plays
 * the page and the voice move each other, so the page is always following, except
 * where this part of the book has no narration to follow.
 */
object ReadAlongFollow {
    fun label(narrated: Boolean): String = if (narrated) "Following" else "Alignment unavailable"
}
