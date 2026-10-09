package com.pocketds.hub.reader

/**
 * A word on the page the controller's cursor can stand on (#62). The web view reports them ([ReaderWordsScript]); [index] is the
 * word's number in its document, the same however the page is turned, and [paragraph] the block it belongs to. The rectangle is
 * in the web view's pixels, [visible] whether the word is on the page in front rather than in a paragraph that goes on past it.
 */
data class PageWord(
    val index: Int,
    val text: String,
    val paragraph: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val visible: Boolean
) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

/** Which sentence each word of a run of paragraphs is in, from the words alone. */
object SentenceSplitter {
    private val CLOSERS = "\"'”’)]»›".toSet()
    private val OPENERS = "\"'“‘([«‹¿¡".toSet()
    private val TERMINATORS = ".!?…".toSet()
    /** A full stop after one of these is not the end of a sentence. */
    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "prof", "sr", "jr", "vs", "etc", "mt", "gen", "col", "lt", "capt",
        "sgt", "cpt", "cmdr", "fig", "no", "vol", "ch", "approx", "e.g", "i.e", "a.m", "p.m", "u.s", "inc", "ltd", "co", "ft", "gov", "rev", "hon")

    /**
     * The sentence number of each word: a new sentence begins after a word that ends with `.`, `!`, `?` or `…` (a closing quote or
     * bracket may follow it) when the next word begins with a capital, a digit or an opening quote, and always at a new paragraph.
     * A full stop after an abbreviation or a single capital letter ("J. R. R.") ends nothing. [words] carry their punctuation, as the
     * page spells them.
     */
    fun split(words: List<String>, paragraphs: List<Int>): IntArray {
        require(words.size == paragraphs.size) { "one paragraph per word" }
        val numbers = IntArray(words.size)
        var sentence = 0
        for (i in words.indices) {
            numbers[i] = sentence
            val next = words.getOrNull(i + 1) ?: break
            if (paragraphs[i + 1] != paragraphs[i] || (ends(words[i]) && starts(next))) sentence++
        }
        return numbers
    }

    private fun ends(word: String): Boolean {
        var end = word.length
        while (end > 0 && word[end - 1] in CLOSERS) end--
        if (end == 0 || word[end - 1] !in TERMINATORS) return false
        if (word[end - 1] != '.') return true
        val stem = word.substring(0, end - 1).trimStart { it in OPENERS }
        if (stem.isEmpty()) return true
        if (stem.length == 1 && stem[0].isLetter() && stem[0].isUpperCase()) return false
        if (stem.lowercase() in ABBREVIATIONS) return false
        // "3.5", "U.S": a dot inside means an abbreviation or a number, whose last piece is short.
        if ('.' in stem && stem.substringAfterLast('.').length <= 1) return false
        return true
    }

    private fun starts(word: String): Boolean {
        val first = word.firstOrNull { it !in OPENERS && it != '-' && it != '—' } ?: return true
        return word.first() in OPENERS || first.isUpperCase() || first.isDigit() || !first.isLetter()
    }
}

/**
 * The controller's cursor on a page of words (#62), a state machine on primitives so a JVM test pins it. The D-pad moves it a word
 * at a time (left and right) or a line (up and down); Ⓐ starts a selection at the cursor and Ⓐ again finishes it; L1 grows the
 * selection to the sentence, R1 to the paragraph; Ⓑ cancels. At the edge of the page it asks for the next one ([Step.TurnPage]) and
 * is rebuilt on it ([restoring] puts the cursor and the start of the selection back by their word numbers, which survive the turn).
 *
 * The words are the visible page and the rest of every paragraph that goes on past it; the selection is kept by word numbers, so
 * it can begin on one page and end on the next.
 */
class TextCursor private constructor(
    private val words: List<PageWord>,
    private val sentences: IntArray,
    private var at: Int,
    private var anchor: Int?
) {
    /** A cursor standing on word number [startIndex], else on the first word of the page. */
    constructor(words: List<PageWord>, startIndex: Int? = null) : this(
        words, SentenceSplitter.split(words.map { it.text }, words.map { it.paragraph }),
        words.indexOfFirst { it.index == startIndex }.takeIf { it >= 0 } ?: words.indexOfFirst { it.visible }.coerceAtLeast(0), null
    )

    sealed interface Step {
        /** The cursor moved (or the selection changed). */
        data object Moved : Step
        /** Nowhere to go: the first or last word of the run. */
        data object Stuck : Step
        /** The word is on another page: turn it, then [restoring] with [cursor]'s number. */
        data class TurnPage(val forward: Boolean, val cursor: Int) : Step
    }

    val isEmpty: Boolean get() = words.isEmpty()

    /** The word the cursor stands on. */
    val cursor: PageWord? get() = words.getOrNull(at)

    /** Whether a selection has been started. */
    val selecting: Boolean get() = anchor != null

    /** The words the selection covers (the cursor's own while none is started), by their number in the document. */
    val selectedIndexes: IntRange? get() {
        val here = cursor?.index ?: return null
        val from = anchor ?: here
        return minOf(from, here)..maxOf(from, here)
    }

    /** The words of the selection that are on this page, for drawing it. */
    fun selectedWords(): List<PageWord> = selectedIndexes?.let { range -> words.filter { it.index in range } }.orEmpty()

    /** The word numbers to carry across a page turn: the cursor and where the selection began. */
    fun carry(): Pair<Int, Int?> = (cursor?.index ?: 0) to anchor

    fun start() { if (!isEmpty && anchor == null) anchor = cursor?.index }

    fun cancelSelection() { anchor = null }

    /** Ⓐ finishes: the words from the start to the cursor; the cursor stays where it was. Null when nothing was started. */
    fun finish(): IntRange? {
        val range = selectedIndexes
        anchor = null
        return range
    }

    fun next(): Step = move(1)

    fun previous(): Step = move(-1)

    private fun move(delta: Int): Step {
        val target = at + delta
        if (target !in words.indices) return Step.Stuck
        if (!words[target].visible) return Step.TurnPage(delta > 0, words[target].index)
        at = target
        return Step.Moved
    }

    /** Up or down a line: the word on the next line whose middle is nearest the cursor's. */
    fun line(down: Boolean): Step {
        val here = cursor ?: return Step.Stuck
        val height = (here.bottom - here.top).coerceAtLeast(1f)
        val candidates = words.indices.filter { i ->
            val w = words[i]
            w.visible && if (down) w.centerY > here.centerY + height * 0.6f else w.centerY < here.centerY - height * 0.6f
        }
        if (candidates.isEmpty()) {
            // Past the last line of the page: the next page, at its first word (or back at the last word of the one before).
            val edge = (if (down) words.firstOrNull { !it.visible && it.index > here.index } else words.lastOrNull { !it.visible && it.index < here.index })
                ?: return Step.Stuck
            return Step.TurnPage(down, edge.index)
        }
        // The nearest line first, then the word on it nearest across.
        val nearest = candidates.minOf { Math.abs(words[it].centerY - here.centerY) }
        val onLine = candidates.filter { Math.abs(words[it].centerY - here.centerY) <= nearest + height * 0.5f }
        at = onLine.minBy { Math.abs(words[it].centerX - here.centerX) }
        return Step.Moved
    }

    /** L1: the selection becomes the sentence under the cursor; pressed again it takes the next sentence of the paragraph as well. */
    fun sentence(): Step {
        if (isEmpty) return Step.Stuck
        val sentence = sentences[at]
        val whole = indexesOf { sentences[it] == sentence }
        val anchorAt = words.indexOfFirst { it.index == anchor }
        // Already a run of whole sentences ending where this one does: the next sentence of the paragraph is added.
        val growing = anchor != null && anchorAt in 0..whole.first && at == whole.last &&
            (anchorAt == 0 || sentences[anchorAt - 1] != sentences[anchorAt])
        if (growing) {
            val next = indexesOf { sentences[it] == sentence + 1 && words[it].paragraph == words[whole.last].paragraph }
            if (next.isEmpty()) return Step.Stuck
            at = next.last
            return Step.Moved
        }
        anchor = words[whole.first].index
        at = whole.last
        return Step.Moved
    }

    /** R1: the selection becomes the paragraph under the cursor. */
    fun paragraph(): Step {
        if (isEmpty) return Step.Stuck
        val paragraph = words[at].paragraph
        val whole = indexesOf { words[it].paragraph == paragraph }
        anchor = words[whole.first].index
        at = whole.last
        return Step.Moved
    }

    private fun indexesOf(test: (Int) -> Boolean): IntRange {
        val found = words.indices.filter(test)
        return if (found.isEmpty()) IntRange.EMPTY else found.first()..found.last()
    }

    companion object {
        /** A cursor on a new page of words standing where [cursor] and [selectionStart] (word numbers) were. */
        fun restoring(words: List<PageWord>, cursor: Int, selectionStart: Int?): TextCursor =
            TextCursor(words, cursor).also { it.anchor = selectionStart }
    }
}
