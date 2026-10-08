package com.pocketds.hub.reader

import com.pocketds.hub.screens.library.ReadingBookFacts
import com.pocketds.hub.state.Fmt
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the bottom left of a book's page says (#42), Kindle's "Reading progress":
 * a tap on it moves to the next, and so does a key on the pad ([PageInfo.next]).
 */
enum class PageInfoCorner(val choice: String) {
    PAGE_IN_BOOK("Page in book"),
    PAGE_IN_CHAPTER("Page in chapter"),
    TIME_IN_CHAPTER("Time left in chapter"),
    TIME_IN_BOOK("Time left in book"),
    NONE("None");

    companion object {
        /** The corner stored under [name]; the default for a name this build does not know. */
        fun named(name: String?): PageInfoCorner = entries.firstOrNull { it.name == name } ?: PAGE_IN_BOOK
    }
}

/**
 * Which of Kindle's corners the page shows (#42), kept on the device for every
 * book ([com.pocketds.hub.settings.PageInfoSettings]). All three are on until
 * turned off: the clock at the top right, [corner] at the bottom left, the
 * percentage through the book at the bottom right.
 */
data class PageInfoChoice(
    val clock: Boolean = true,
    val corner: PageInfoCorner = PageInfoCorner.PAGE_IN_BOOK,
    val percentage: Boolean = true,
    /** The book's title, top centre in small capitals (#47), as Kindle has it. */
    val title: Boolean = true
) {
    /** A strip at the top is kept for the clock and the title, so they never sit on the text. */
    val topStrip: Boolean get() = clock || title

    /** A strip at the foot is kept for the bottom corners. */
    val bottomStrip: Boolean get() = corner != PageInfoCorner.NONE || percentage
}

/**
 * Where the reader is, in what the corners need. A zero or null is "not known
 * yet" (the book's pages are counted as it opens), and its corner stays blank
 * rather than saying something wrong.
 */
data class PagePlace(
    val bookPage: Int = 0,
    val bookPages: Int = 0,
    val chapterPage: Int = 0,
    val chapterPages: Int = 0,
    val timeLeft: TimeLeft? = null,
    val fraction: Double? = null
)

/**
 * The corners' words, order and look (#42): pure, so the labels, the cycle and
 * the clock are tested without a screen. Times come from [TimeLeft] (the one
 * pace, [ReadingPace]; or the narration's own, reading along) and the percentage
 * from [Fmt.readingPercentLabel], so the corners agree with the menu's line.
 */
object PageInfo {
    /** The strip kept at the top and the foot of the page for the corners, in dp (about 30 on the Pocket, #47). */
    const val STRIP_DP = 30

    /** The page's own ink at its full strength, as Kindle's corners are: they were hard to read at 60% (#47). */
    const val INK_ALPHA = 1f

    /** The corners' size, in sp. */
    const val TEXT_SP = 12.5f

    /** The title is a little smaller, in capitals. */
    const val TITLE_SP = 10.5f

    /** Kindle's order: where you are in the book, in the chapter, how long is left in each, then nothing. */
    fun next(corner: PageInfoCorner): PageInfoCorner {
        val all = PageInfoCorner.entries
        return all[(all.indexOf(corner) + 1) % all.size]
    }

    /**
     * Where the reader is, in the pages the book says it has. [bookPages] is the book's own page count from the
     * hub ([ReadingBookFacts.pages]), 0 when it has none, and the page is [ReadingBookFacts.page] of [fraction]
     * (how far through the book): the same count and rounding as "49% · page 363 of 735" on the book's page and
     * Resume, so every place agrees. The chapter's pages are its share of those: [span] is the chapter's start
     * and end in the book ([sectionSpan]), and "Page 3 of 18" counts from its first page.
     *
     * Without a page count it is Readium's positions, as the time left is measured in: [sectionSizes] (each
     * part's positions, in reading order), the part on screen ([section]) and how far through it ([progression]).
     */
    fun place(
        bookPages: Int,
        sectionSizes: List<Int>,
        section: Int,
        progression: Double,
        span: Pair<Double, Double>?,
        fraction: Double?,
        timeLeft: TimeLeft?
    ): PagePlace {
        if (bookPages > 0) {
            // How far through is not known: the pages stay blank, rather than say Readium's count of something else.
            if (fraction == null || !fraction.isFinite()) return PagePlace(timeLeft = timeLeft, fraction = fraction?.takeIf { it.isFinite() })
            val page = ReadingBookFacts.page(fraction, bookPages)
            // The chapter's share of the book's pages, counted from the page it starts on, so a book that is one
            // chapter says the same page in both.
            val chapter = span?.takeIf { it.second > it.first }?.let { (start, end) ->
                val count = Math.round((end - start) * bookPages).toInt().coerceAtLeast(1)
                (page - ReadingBookFacts.page(start, bookPages) + 1).coerceIn(1, count) to count
            }
            return PagePlace(page, bookPages, chapter?.first ?: 0, chapter?.second ?: 0, timeLeft, fraction)
        }
        val total = sectionSizes.sum()
        val position = if (total > 0 && section >= 0) TimeLeft.position(sectionSizes, section, progression) else null
        val size = sectionSizes.getOrNull(section)?.takeIf { it > 0 }
        return PagePlace(
            bookPage = position?.let { (it.toInt() + 1).coerceIn(1, total) } ?: 0,
            bookPages = if (position != null) total else 0,
            chapterPage = size?.let { (Math.floor(progression.coerceIn(0.0, 1.0) * it).toInt() + 1).coerceIn(1, it) } ?: 0,
            chapterPages = size ?: 0,
            timeLeft = timeLeft,
            fraction = fraction
        )
    }

    /**
     * Where a part of the book starts and ends in it, as how far through: [starts] holds each part's
     * `totalProgression` in reading order (null where Readium has none), and a part ends where the next begins
     * (the last at 1). Null for a part that is not there or whose start is not known. The menu's percentage and
     * the corners' chapter pages are both measured from this.
     */
    fun sectionSpan(starts: List<Double?>, section: Int): Pair<Double, Double>? {
        val start = starts.getOrNull(section) ?: return null
        return start to (starts.getOrNull(section + 1) ?: 1.0)
    }

    /** The bottom left's words for [corner]; blank when it is None or the place is not known. */
    fun bottomLeft(corner: PageInfoCorner, place: PagePlace): String = when (corner) {
        PageInfoCorner.PAGE_IN_BOOK -> if (place.bookPage > 0 && place.bookPages > 0) "Page ${place.bookPage} of ${place.bookPages}" else ""
        PageInfoCorner.PAGE_IN_CHAPTER -> if (place.chapterPage > 0 && place.chapterPages > 0) "Page ${place.chapterPage} of ${place.chapterPages} in chapter" else ""
        PageInfoCorner.TIME_IN_CHAPTER -> place.timeLeft?.chapterLabel().orEmpty()
        PageInfoCorner.TIME_IN_BOOK -> place.timeLeft?.bookLabel().orEmpty()
        PageInfoCorner.NONE -> ""
    }

    /** How far through the book, "49%"; blank when the percentage is off or not known. */
    fun bottomRight(choice: PageInfoChoice, place: PagePlace): String =
        if (choice.percentage) place.fraction?.let { Fmt.readingPercentLabel(it) }.orEmpty() else ""

    /** The time as the device shows it: "15:42", or "3:42 PM" on a 12-hour clock. */
    fun clock(hour: Int, minute: Int, is24Hour: Boolean): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        if (is24Hour) return String.format(Locale.US, "%02d:%02d", h, m)
        val shown = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", shown, m, if (h < 12) "AM" else "PM")
    }

    /** How long until the minute on the clock changes, a moment past it. */
    fun millisToNextMinute(nowMillis: Long): Long = 60_000L - Math.floorMod(nowMillis, 60_000L) + 50L

    /** The book's title as the top line says it: one line, in capitals, the spaces single (#47). */
    fun titleText(title: String): String = title.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)

    /** [textColor] at [INK_ALPHA]: the page's own ink, whatever the theme. */
    fun ink(textColor: Int): Int = ((INK_ALPHA * 255).roundToInt() shl 24) or (textColor and 0x00FFFFFF)
}
