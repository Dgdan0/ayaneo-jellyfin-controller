package com.pocketds.hub.reader

import kotlin.math.roundToLong

/**
 * The page and the voice move each other (#49). The web view reports only offsets, as a [PageProbe]; everything
 * else is here, plain Kotlin on primitives so a JVM test pins it: how far into a sentence a point of the page is,
 * the time that is, when the voice reaches the next page's first word, and what a page turned by hand does to
 * the narration.
 *
 * Sentence granularity is all the aligned books carry (Storyteller aligns sentences), so a page break inside a
 * sentence is a time inside that sentence: its begin plus the fraction of its letters before the break times its
 * length, moved on to the start of a word, because the voice does not begin between two letters.
 */

/**
 * The first or last character the page shows, in the narrated element holding it: its text, and where in the text. In a
 * word edition (#66) [word] is the first word of that sentence to begin at or after [offset] (the page's first word, or the
 * next page's), by the id the edition gives it; null where none does, or the sentence has no words of its own.
 */
data class PageEdge(val fragment: String, val text: String, val offset: Int, val word: String? = null)

/**
 * What the page shows of the narration. [first] is the first character on the page (its [PageEdge.offset] the index
 * of that character), [last] the last one (its offset the index after it); both are null where no narrated text is on
 * the page. [visible] is every narrated element with any part on the page.
 */
data class PageProbe(val href: String, val first: PageEdge?, val last: PageEdge?, val visible: List<String>) {
    /** Tells one page from the next: the same page probed again has the same key. */
    val key: String get() = "$href#${first?.fragment.orEmpty()}@${first?.offset ?: -1}"
}

/** A page in the narration's time: when the voice reaches its first word, and the next page's first word. */
data class PageSpan(val href: String, val start: ReadAlongPosition?, val end: ReadAlongPosition?, val visible: Set<String>) {
    val narrated: Boolean get() = visible.isNotEmpty()
}

object ReadAlongPageSync {
    /** What the voice says: letters and digits. Spaces and punctuation take no time of their own. */
    fun spoken(c: Char): Boolean = c.isLetterOrDigit()

    fun spokenCount(text: String, from: Int = 0, to: Int = text.length): Int {
        var count = 0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(text.length)) if (spoken(text[i])) count++
        return count
    }

    /** A soft hyphen breaks a word for the eye and is not a gap in it. */
    private fun transparent(c: Char) = c == '\u00AD'

    private fun isWordStart(text: String, i: Int): Boolean {
        if (!spoken(text[i])) return false
        var j = i - 1
        while (j >= 0 && transparent(text[j])) j--
        if (j < 0) return true
        val before = text[j]
        // Inside a word, soft hyphen or none.
        if (spoken(before)) return false
        // "don't", "o'clock": a letter, an apostrophe, a letter is one word.
        if ((before == '\'' || before == '\u2019') && j >= 1 && text[j - 1].isLetter() && text[i].isLetter()) return false
        return true
    }

    /** The first word to begin at or after [offset] in [text]; null when only the tail of a word is left. */
    fun wordStart(text: String, offset: Int): Int? {
        for (i in offset.coerceAtLeast(0) until text.length) if (isWordStart(text, i)) return i
        return null
    }

    /**
     * Where in the sentence [segment] the first word to begin at or after [offset] of [text] is said: the
     * sentence's begin plus the share of its letters before that word times its length (the file's own
     * milliseconds). Null when no word begins there, as with the tail of the last word.
     */
    fun timeAt(segment: ReadAlongSegment, text: String, offset: Int): Long? = timeAt(segment.beginMs, segment.endMs, text, offset)

    /** [timeAt] for a sentence that runs from [beginMs] to [endMs]. */
    fun timeAt(beginMs: Long, endMs: Long, text: String, offset: Int): Long? {
        val start = wordStart(text, offset) ?: return null
        // A word begins at [start], so the text has letters and the total is not zero.
        val share = spokenCount(text, 0, start).toDouble() / spokenCount(text)
        return beginMs + (share * (endMs - beginMs)).roundToLong()
    }

    fun compare(a: ReadAlongPosition, b: ReadAlongPosition): Int =
        if (a.track != b.track) a.track.compareTo(b.track) else a.offsetMs.compareTo(b.offsetMs)

    /**
     * Where the voice is when it reaches [edge]'s word: in a word edition (#66), exactly that word's begin (or, for a word
     * the narration does not say, the next one it does in the sentence); in a sentence edition, the sentence's begin plus
     * the share of its letters before the edge ([timeAt]). Null where nothing of the sentence is said from there on.
     */
    private fun timeOf(timeline: ReadAlongTimeline, href: String, edge: PageEdge, sentence: ReadAlongTimeline.Sentence): ReadAlongPosition? {
        if (sentence.first.segment.isWord) {
            val word = edge.word ?: return null
            val at = timeline.wordAtOrAfter(href, sentence, word) ?: return null
            return timeline.positionAt(at.track, at.segment.beginMs)
        }
        val ms = timeAt(sentence.beginMs, sentence.endMs, edge.text, edge.offset) ?: return null
        return timeline.positionAt(sentence.first.track, ms)
    }

    /** When the voice reaches the first word of the page: from the probe's first character, else the next sentence. */
    fun startOf(timeline: ReadAlongTimeline, probe: PageProbe): ReadAlongPosition? {
        val edge = probe.first ?: return null
        val sentence = timeline.sentence(probe.href, edge.fragment) ?: return null
        timeOf(timeline, probe.href, edge, sentence)?.let { return it }
        return timeline.nextSentence(sentence.first)?.let { timeline.positionAt(it.track, it.segment.beginMs) }
    }

    /**
     * When the voice reaches the first word of the next page: from the probe's last character. A sentence that
     * ends on the page hands over to the next sentence; one that goes on is a time inside it. Null after the last
     * sentence of the book, where there is no page to turn to.
     */
    fun endOf(timeline: ReadAlongTimeline, probe: PageProbe): ReadAlongPosition? {
        val edge = probe.last ?: return null
        val sentence = timeline.sentence(probe.href, edge.fragment) ?: return null
        if (spokenCount(edge.text, edge.offset) > 0) timeOf(timeline, probe.href, edge, sentence)?.let { return it }
        return timeline.nextSentence(sentence.first)?.let { timeline.positionAt(it.track, it.segment.beginMs) }
    }

    /**
     * Where Play starts from a page that shows no narrated text (#61): the nearest narrated sentence. [order] is the book's
     * documents in reading order, [document] the page's, [progression] how far through that document the page is. A
     * document the narration reaches but whose page shows none of it (an image, a heading it does not read) goes to the
     * sentence the same share of the way through it; one it does not reach (a title page, the Prologue) to the first
     * sentence of the next narrated document, else the last of the one before. All in [DocumentPath]'s spelling. Null when
     * the page's document is not in the book, or nothing in the book is narrated.
     */
    fun nearest(timeline: ReadAlongTimeline, order: List<String>, document: String, progression: Double): ReadAlongPosition? {
        val here = order.indexOf(document)
        if (here < 0) return null
        val located = timeline.sentenceAt(document, progression)
            ?: order.drop(here + 1).firstNotNullOfOrNull { timeline.sentenceAt(it, 0.0) }
            ?: order.take(here).asReversed().firstNotNullOfOrNull { timeline.sentenceAt(it, 1.0) }
            ?: return null
        return timeline.positionAt(located.track, located.segment.beginMs)
    }

    fun span(timeline: ReadAlongTimeline, probe: PageProbe): PageSpan =
        PageSpan(probe.href, startOf(timeline, probe), endOf(timeline, probe), probe.visible.toSet())

    /** What the voice asks of the page. */
    sealed interface Step {
        data object Stay : Step
        /** The sentence being spoken goes on past the page's last word: the next page is next. */
        data object TurnPage : Step
        /** The voice is somewhere the page does not show. */
        data class GoTo(val segment: ReadAlongSegment) : Step
    }

    /**
     * The page follows the voice. The sentence being spoken is on the page and the voice has not reached the
     * next page's first word: stay. It has: turn the page. The sentence is not on the page at all (a jump, a
     * step, a sentence on the next page): go to it. Between two sentences there is nothing to follow.
     */
    fun follow(timeline: ReadAlongTimeline, position: ReadAlongPosition, span: PageSpan): Step {
        val active = timeline.active(position.track, position.offsetMs) ?: return Step.Stay
        // The page knows its sentences; a word is on the page with its sentence (#66).
        if (active.textHref != span.href || active.sentenceFragment !in span.visible) return Step.GoTo(active)
        val end = span.end ?: return Step.Stay
        return if (compare(position, end) >= 0) Step.TurnPage else Step.Stay
    }

    /** What a page turned by hand, while the voice plays, does to the narration. */
    sealed interface Manual {
        /** The sentence being spoken is still on the page: nothing restarts. */
        data object Keep : Manual
        /** The voice goes to the first word on the new page. */
        data class Jump(val to: ReadAlongPosition) : Manual
        /** No narrated text on the new page: the voice carries on. */
        data object Nothing : Manual
    }

    fun afterManualTurn(timeline: ReadAlongTimeline, position: ReadAlongPosition, span: PageSpan): Manual {
        val active = timeline.active(position.track, position.offsetMs)
        if (active != null && active.textHref == span.href && active.sentenceFragment in span.visible) return Manual.Keep
        val start = span.start ?: return Manual.Nothing
        return Manual.Jump(start)
    }

    /**
     * A page turned back by hand to the sentence still being spoken, when the voice is already past that page's
     * end: the page must not turn itself forward again at once. The next sentence brings the page on.
     */
    fun keptFor(span: PageSpan, position: ReadAlongPosition): PageSpan {
        val end = span.end ?: return span
        return if (compare(position, end) >= 0) span.copy(end = null) else span
    }
}
