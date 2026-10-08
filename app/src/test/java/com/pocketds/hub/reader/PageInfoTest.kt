package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.library.ReadingBookFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kindle's corners over a book's page (#42): the words for each choice, the cycle, the clock, the ink. */
class PageInfoTest {
    private val left = TimeLeft(12 * 60_000L, 250 * 60_000L)

    /**
     * Sections of 10, 20 and 30 positions: 60 in the book, the way Readium counts it. With no [bookPages] from the hub the
     * corners count those; [span] is the part's start and end in the book (the second part, a sixth to a half).
     */
    private fun place(section: Int = 1, progression: Double = 0.5, bookPages: Int = 0,
                      span: Pair<Double, Double>? = 1.0 / 6 to 0.5,
                      timeLeft: TimeLeft? = left, fraction: Double? = 0.49) =
        PageInfo.place(bookPages, listOf(10, 20, 30), section, progression, span, fraction, timeLeft)

    @Test fun `a tap or a key moves through the five choices in Kindle's order and round again`() {
        val order = generateSequence(PageInfoCorner.PAGE_IN_BOOK) { PageInfo.next(it) }.take(6).toList()
        assertEquals(listOf(PageInfoCorner.PAGE_IN_BOOK, PageInfoCorner.PAGE_IN_CHAPTER, PageInfoCorner.TIME_IN_CHAPTER,
            PageInfoCorner.TIME_IN_BOOK, PageInfoCorner.NONE, PageInfoCorner.PAGE_IN_BOOK), order)
        // Every choice is reached, whichever one it starts from.
        PageInfoCorner.entries.forEach { start ->
            assertEquals(PageInfoCorner.entries.toSet(), generateSequence(start) { PageInfo.next(it) }.take(PageInfoCorner.entries.size).toSet())
        }
    }

    @Test fun `the settings list names every choice, None last`() {
        assertEquals(listOf("Page in book", "Page in chapter", "Time left in chapter", "Time left in book", "None"),
            PageInfoCorner.entries.map { it.choice })
    }

    @Test fun `the bottom left says each choice in its own words`() {
        // The book's own 735 pages, 49.45% through it, in the part a sixth to a half of the way: the book page's "page 363 of 735".
        val here = place(bookPages = 735, fraction = 0.4945)
        assertEquals("Page 363 of 735", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, here))
        assertEquals("Page 242 of 245 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, here))
        assertEquals("12 min left in chapter", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_CHAPTER, here))
        assertEquals("4h 10m left in book", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_BOOK, here))
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.NONE, here))
    }

    @Test fun `the page is the one the book's page and Resume name, from the same count and rounding`() {
        val work = ReadingWork(title = "Light Bringer", progress = ReadingProgress(percentage = 0.4945),
            editions = listOf(ReadingEdition(kind = "ebook", pageCount = 735), ReadingEdition(kind = "audiobook", pageCount = 9000)))
        val pages = ReadingBookFacts.pages(work)
        assertEquals(735, pages)
        assertEquals("49% · page 363 of 735", ReadingBookFacts.progress(work))
        assertEquals("Page 363 of 735", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(bookPages = pages, fraction = 0.4945)))
        // Wherever in the book it is, the corner and the line agree.
        for (fraction in listOf(0.0001, 0.01, 0.2, 0.333333, 0.5, 0.75, 0.9, 0.9999)) {
            val line = ReadingBookFacts.progress(work.copy(progress = ReadingProgress(percentage = fraction)))!!
            val page = PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(bookPages = pages, fraction = fraction))
            assertTrue("$page in $line", line.endsWith(page.replaceFirst("Page", "page")))
        }
    }

    @Test fun `the chapter's pages are its share of the book's, counted from its first`() {
        val pages = 600
        // A chapter from a tenth to a fifth of the way: 60 pages, pages 61 to 120.
        val span = 0.1 to 0.2
        fun chapter(fraction: Double) = PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, place(bookPages = pages, span = span, fraction = fraction))
        assertEquals("Page 1 of 60 in chapter", chapter(0.1))
        assertEquals("Page 3 of 60 in chapter", chapter(0.1 + 2.5 / 600))
        assertEquals("Page 60 of 60 in chapter", chapter(0.1999))
        // Past its end or before its start, it stays within the chapter.
        assertEquals("Page 60 of 60 in chapter", chapter(0.25))
        assertEquals("Page 1 of 60 in chapter", chapter(0.05))
        // The last chapter ends with the book.
        assertEquals("Page 60 of 60 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER,
            place(bookPages = pages, span = 0.9 to 1.0, fraction = 1.0)))
        // A chapter shorter than a page is one page.
        assertEquals("Page 1 of 1 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER,
            place(bookPages = pages, span = 0.5 to 0.5005, fraction = 0.5001)))
        // Where the chapter's place is not known, so is its page; the book's page is still said.
        val noSpan = place(bookPages = pages, span = null, fraction = 0.3)
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, noSpan))
        assertEquals("Page 180 of 600", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, noSpan))
    }

    @Test fun `a book that is one chapter says the same page for the book and for the chapter`() {
        for (fraction in listOf(0.0, 0.0004, 0.25, 0.3333, 0.5, 0.9, 0.9999, 1.0)) {
            val one = place(bookPages = 765, span = 0.0 to 1.0, fraction = fraction)
            assertEquals("Page ${one.bookPage} of 765", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, one))
            assertEquals("Page ${one.bookPage} of 765 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, one))
        }
    }

    @Test fun `a chapter's pages run on from the one before it`() {
        // Three chapters of a 300 page book: the first third, the next sixth, the rest.
        val spans = listOf(0.0 to 1.0 / 3, 1.0 / 3 to 0.5, 0.5 to 1.0)
        val counts = spans.map { span -> place(bookPages = 300, span = span, fraction = span.first + 0.0001).chapterPages }
        assertEquals(listOf(100, 50, 150), counts)
        // The chapter's last page is its share of the book: it never says a page the chapter does not have.
        spans.forEach { span ->
            val last = place(bookPages = 300, span = span, fraction = span.second - 0.0001)
            assertTrue("${last.chapterPage} of ${last.chapterPages}", last.chapterPage in (last.chapterPages - 1)..last.chapterPages)
        }
    }

    @Test fun `without a page count from the hub the pages are Readium's positions`() {
        val here = place()
        // Section 1 (positions 10 to 30), halfway: position 20, so the 21st of 60; the 11th of its 20.
        assertEquals("Page 21 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, here))
        assertEquals("Page 11 of 20 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, here))
        assertEquals("Page 1 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = 0, progression = 0.0)))
        // The end of the last part is the last page, not one past it.
        assertEquals("Page 60 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = 2, progression = 1.0)))
        assertEquals("Page 30 of 30 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, place(section = 2, progression = 1.0)))
        // A part's start is the page after the parts before it.
        assertEquals("Page 11 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = 1, progression = 0.0)))
        // A count the hub gave as nothing is no count.
        assertEquals(here, place(bookPages = 0))
        assertEquals(here, place(bookPages = -3))
    }

    @Test fun `a page of the book never runs past the book`() {
        assertEquals("Page 1 of 735", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(bookPages = 735, fraction = 0.0)))
        assertEquals("Page 735 of 735", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(bookPages = 735, fraction = 1.0)))
        assertEquals("Page 735 of 735", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(bookPages = 735, fraction = 1.4)))
        assertEquals("Page 1 of 735", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(bookPages = 735, fraction = -0.2)))
    }

    @Test fun `what is not known yet stays blank rather than saying something wrong`() {
        val unknown = PageInfo.place(0, emptyList(), -1, 0.0, null, null, null)
        PageInfoCorner.entries.forEach { assertEquals(it.name, "", PageInfo.bottomLeft(it, unknown)) }
        assertEquals("", PageInfo.bottomRight(PageInfoChoice(), unknown))
        // A part that is not in the book's list (the locator is not found among its parts).
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = -1)))
        // The hub counted pages but how far through is not known: not Readium's count of something else.
        val noFraction = place(bookPages = 735, fraction = null)
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, noFraction))
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, noFraction))
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(bookPages = 735, fraction = Double.NaN)))
        // The time is not known until the pace is: only its own choices go blank.
        val noTime = place(timeLeft = null)
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_CHAPTER, noTime))
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_BOOK, noTime))
        assertEquals("Page 21 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, noTime))
    }

    @Test fun `a part starts where it says and ends where the next begins`() {
        val starts = listOf(0.0, 0.1, null, 0.5)
        assertEquals(0.0 to 0.1, PageInfo.sectionSpan(starts, 0))
        assertEquals(0.1 to 1.0, PageInfo.sectionSpan(starts, 1))
        assertEquals(null, PageInfo.sectionSpan(starts, 2))
        assertEquals(0.5 to 1.0, PageInfo.sectionSpan(starts, 3))
        assertEquals(null, PageInfo.sectionSpan(starts, 4))
        assertEquals(null, PageInfo.sectionSpan(starts, -1))
        assertEquals(null, PageInfo.sectionSpan(emptyList(), 0))
    }

    /** Four parts of a 400 page book, a quarter each; Readium counts 10, 20, 30 and 40 positions in them. */
    private val sizes = listOf(10, 20, 30, 40)
    private val starts = listOf(0.0, 0.25, 0.5, 0.75)

    @Test fun `a Contents entry starts on the book's own page where the hub counted pages`() {
        fun page(section: Int, share: Double?) = PageInfo.entryPage(400, sizes, starts, section, share)
        // The same count and rounding as the corners, the book's page and Resume: ReadingBookFacts.page of how far through.
        assertEquals(1, page(0, 0.0))
        assertEquals(ReadingBookFacts.page(0.25, 400), page(1, 0.0))
        assertEquals(ReadingBookFacts.page(0.5, 400), page(2, 0.0))
        assertEquals(ReadingBookFacts.page(0.75, 400), page(3, 0.0))
        assertEquals(100, page(1, 0.0))
        assertEquals(300, page(3, 0.0))
        // A third of the way into the second part: a quarter plus a third of a quarter of the book.
        assertEquals(ReadingBookFacts.page(0.25 + 0.25 / 3, 400), page(1, 1.0 / 3))
    }

    @Test fun `without the hub's page count a Contents entry is the position Readium starts there, counted from 1`() {
        fun page(section: Int, share: Double?) = PageInfo.entryPage(0, sizes, starts, section, share)
        assertEquals(1, page(0, 0.0))
        assertEquals(11, page(1, 0.0))
        assertEquals(31, page(2, 0.0))
        assertEquals(61, page(3, 0.0))
        // Half way into the second part: its 20 positions, ten in, after the first part's ten.
        assertEquals(21, page(1, 0.5))
        // A count the hub gave as nothing is no count; a part with no start in the book still has its positions.
        assertEquals(11, PageInfo.entryPage(-3, sizes, starts, 1, 0.0))
        assertEquals(11, PageInfo.entryPage(0, sizes, listOf(0.0, null, 0.5, 0.75), 1, 0.0))
    }

    @Test fun `several entries in one file start on different pages by their anchors' shares`() {
        // The second part runs from page 100 to page 200 of 400: four anchors in it.
        val withCount = listOf(0.0, 0.25, 0.5, 0.75).map { PageInfo.entryPage(400, sizes, starts, 1, it) }
        assertEquals(listOf(100, 125, 150, 175), withCount)
        // By Readium's positions: its 20 positions after the first part's ten.
        val withPositions = listOf(0.0, 0.25, 0.5, 0.75).map { PageInfo.entryPage(0, sizes, starts, 1, it) }
        assertEquals(listOf(11, 16, 21, 26), withPositions)
        // A share outside the file stays in it.
        assertEquals(PageInfo.entryPage(400, sizes, starts, 1, 1.0), PageInfo.entryPage(400, sizes, starts, 1, 3.0))
        assertEquals(PageInfo.entryPage(400, sizes, starts, 1, 0.0), PageInfo.entryPage(400, sizes, starts, 1, -1.0))
    }

    @Test fun `a Contents entry whose file is not in the reading order, or whose place is not known, has no number`() {
        // The link names a file that is not one of the book's parts.
        assertEquals(null, PageInfo.entryPage(400, sizes, starts, -1, 0.0))
        assertEquals(null, PageInfo.entryPage(0, sizes, starts, -1, 0.0))
        assertEquals(null, PageInfo.entryPage(400, sizes, starts, 4, 0.0))
        // The anchor's share is still being worked out, or is not a number.
        assertEquals(null, PageInfo.entryPage(400, sizes, starts, 1, null))
        assertEquals(null, PageInfo.entryPage(400, sizes, starts, 1, Double.NaN))
        assertEquals(null, PageInfo.entryPage(0, sizes, starts, 1, Double.POSITIVE_INFINITY))
        // With the hub's pages, a part whose start in the book is not known has no page rather than Readium's count of something else.
        assertEquals(null, PageInfo.entryPage(400, sizes, listOf(0.0, null, 0.5, 0.75), 1, 0.0))
        // Before the book's positions are known.
        assertEquals(null, PageInfo.entryPage(0, emptyList(), emptyList(), 0, 0.0))
        assertEquals(null, PageInfo.entryPage(400, emptyList(), emptyList(), 0, 0.0))
    }

    @Test fun `a Contents entry's page is the page the corners show once the row is chosen`() {
        // Landing on an entry puts the reader at its share of its part: the corners' place there is the entry's number.
        for (bookPages in listOf(0, 400, 765)) {
            for (section in sizes.indices) {
                for (share in listOf(0.0, 0.1, 0.25, 0.3333, 0.5, 0.9, 0.9999)) {
                    val fraction = PageInfo.sectionSpan(starts, section)!!.let { PageInfo.within(it, share) }
                    val corner = PageInfo.place(bookPages, sizes, section, share, PageInfo.sectionSpan(starts, section), fraction, null)
                    assertEquals("pages $bookPages, part $section, share $share", corner.bookPage, PageInfo.entryPage(bookPages, sizes, starts, section, share))
                }
            }
        }
    }

    @Test fun `how far through the book a point in a part is`() {
        assertEquals(0.25 + 0.125, PageInfo.within(0.25 to 0.5, 0.5), 1e-12)
        assertEquals(0.25, PageInfo.within(0.25 to 0.5, 0.0), 0.0)
        assertEquals(1.0, PageInfo.within(0.75 to 1.0, 2.0), 0.0)
        assertEquals(0.0, PageInfo.within(0.0 to 0.1, -1.0), 0.0)
    }

    @Test fun `the bottom right is the menu's percentage, or nothing when it is off`() {
        assertEquals("49%", PageInfo.bottomRight(PageInfoChoice(), place(fraction = 0.49)))
        assertEquals("1%", PageInfo.bottomRight(PageInfoChoice(), place(fraction = 0.004)))
        assertEquals("99%", PageInfo.bottomRight(PageInfoChoice(), place(fraction = 0.9999)))
        assertEquals("", PageInfo.bottomRight(PageInfoChoice(percentage = false), place(fraction = 0.49)))
        assertEquals("", PageInfo.bottomRight(PageInfoChoice(), place(fraction = null)))
    }

    @Test fun `the clock follows the device's 12 or 24 hour setting`() {
        assertEquals("15:42", PageInfo.clock(15, 42, is24Hour = true))
        assertEquals("00:05", PageInfo.clock(0, 5, is24Hour = true))
        assertEquals("3:42 PM", PageInfo.clock(15, 42, is24Hour = false))
        assertEquals("12:05 AM", PageInfo.clock(0, 5, is24Hour = false))
        assertEquals("12:00 PM", PageInfo.clock(12, 0, is24Hour = false))
        assertEquals("11:59 PM", PageInfo.clock(23, 59, is24Hour = false))
        assertEquals("9:07 AM", PageInfo.clock(9, 7, is24Hour = false))
    }

    @Test fun `the clock is redrawn as the minute changes`() {
        // Thirty seconds into a minute: just past the next one.
        assertEquals(30_050L, PageInfo.millisToNextMinute(10 * 60_000L + 30_000L))
        // On the minute itself, a whole one.
        assertEquals(60_050L, PageInfo.millisToNextMinute(10 * 60_000L))
        assertTrue(PageInfo.millisToNextMinute(1_700_000_059_999L) in 51L..60_050L)
    }

    @Test fun `a strip is kept only where a corner is shown`() {
        val all = PageInfoChoice()
        assertTrue(all.topStrip && all.bottomStrip)
        // The title keeps the top clear when the clock is off, and the clock when the title is.
        assertTrue(all.copy(clock = false).topStrip)
        assertTrue(all.copy(title = false).topStrip)
        assertFalse(all.copy(clock = false, title = false).topStrip)
        assertTrue(all.copy(clock = false).bottomStrip)
        // Either bottom corner keeps the foot clear.
        assertTrue(all.copy(corner = PageInfoCorner.NONE).bottomStrip)
        assertTrue(all.copy(percentage = false).bottomStrip)
        assertFalse(all.copy(corner = PageInfoCorner.NONE, percentage = false).bottomStrip)
        val none = PageInfoChoice(clock = false, corner = PageInfoCorner.NONE, percentage = false, title = false)
        assertFalse(none.topStrip || none.bottomStrip)
    }

    @Test fun `all the corners are on until turned off`() {
        val defaults = PageInfoChoice()
        assertTrue(defaults.clock && defaults.percentage && defaults.title)
        assertEquals(PageInfoCorner.PAGE_IN_BOOK, defaults.corner)
    }

    @Test fun `a stored name finds its corner, and an unknown one the default`() {
        PageInfoCorner.entries.forEach { assertEquals(it, PageInfoCorner.named(it.name)) }
        assertEquals(PageInfoCorner.PAGE_IN_BOOK, PageInfoCorner.named("LOCATION"))
        assertEquals(PageInfoCorner.PAGE_IN_BOOK, PageInfoCorner.named(null))
    }

    @Test fun `the ink is the page's own ink at full strength`() {
        val ink = PageInfo.ink(0xFF5A4931.toInt())
        assertEquals(0x5A4931, ink and 0xFFFFFF)
        assertEquals(255, (ink ushr 24))
        // Whatever alpha the colour had, the corners' is the same.
        assertEquals(ink, PageInfo.ink(0x805A4931.toInt()))
    }

    @Test fun `the title is one line of capitals`() {
        assertEquals("LIGHT BRINGER", PageInfo.titleText("Light Bringer"))
        assertEquals("A GAME OF THRONES", PageInfo.titleText("  A Game\n of   Thrones "))
        assertEquals("", PageInfo.titleText("   "))
    }

    @Test fun `the corners are sized and strip-high for the Pocket`() {
        assertEquals(30, PageInfo.STRIP_DP)
        assertTrue(PageInfo.TEXT_SP in 12f..13f)
        assertTrue("the title is smaller", PageInfo.TITLE_SP < PageInfo.TEXT_SP)
    }
}
