package com.pocketds.hub.reader

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
    val percentage: Boolean = true
) {
    /** A strip at the top is kept for the clock, so it never sits on the text. */
    val topStrip: Boolean get() = clock

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
    /** The strip kept at the top and the foot of the page for the corners, in dp. */
    const val STRIP_DP = 26

    /** Quiet: the page's text colour at this much of its strength. */
    const val INK_ALPHA = 0.6f

    /** Kindle's order: where you are in the book, in the chapter, how long is left in each, then nothing. */
    fun next(corner: PageInfoCorner): PageInfoCorner {
        val all = PageInfoCorner.entries
        return all[(all.indexOf(corner) + 1) % all.size]
    }

    /**
     * [sectionSizes]: the positions in each part of the book (Readium's, the same the time left is
     * measured in); [section] and [progression]: the part on screen and how far through it.
     * [chapterPage] is the screen page counted within the part, [chapterPages] how many it has.
     */
    fun place(
        sectionSizes: List<Int>,
        section: Int,
        progression: Double,
        chapterPage: Int,
        chapterPages: Int,
        timeLeft: TimeLeft?,
        fraction: Double?
    ): PagePlace {
        val total = sectionSizes.sum()
        val position = if (total > 0 && section >= 0) TimeLeft.position(sectionSizes, section, progression) else null
        return PagePlace(
            bookPage = position?.let { (it.toInt() + 1).coerceIn(1, total) } ?: 0,
            bookPages = if (position != null) total else 0,
            chapterPage = if (chapterPages > 0) chapterPage.coerceIn(1, chapterPages) else 0,
            chapterPages = chapterPages.coerceAtLeast(0),
            timeLeft = timeLeft,
            fraction = fraction
        )
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

    /** [textColor] at [INK_ALPHA]: the page's own colour, quieter, whatever the theme. */
    fun ink(textColor: Int): Int = ((INK_ALPHA * 255).roundToInt() shl 24) or (textColor and 0x00FFFFFF)

    /**
     * The corners sit level with the text: Readium's gutter is 20 css pixels at the balanced margin and
     * grows and shrinks with it. Kept within a range that fits the screen's edge.
     */
    fun sideInsetDp(pageMargins: Float): Int = (20f * pageMargins).roundToInt().coerceIn(14, 40)
}
