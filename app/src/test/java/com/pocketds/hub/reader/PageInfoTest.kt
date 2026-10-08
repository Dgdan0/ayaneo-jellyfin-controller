package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kindle's corners over a book's page (#42): the words for each choice, the cycle, the clock, the ink. */
class PageInfoTest {
    private val left = TimeLeft(12 * 60_000L, 250 * 60_000L)

    /** Sections of 10, 20 and 30 positions: 60 in the book. */
    private fun place(section: Int = 1, progression: Double = 0.5, chapterPage: Int = 4, chapterPages: Int = 12,
                      timeLeft: TimeLeft? = left, fraction: Double? = 0.49) =
        PageInfo.place(listOf(10, 20, 30), section, progression, chapterPage, chapterPages, timeLeft, fraction)

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
        val here = place()
        // Section 1 (positions 10 to 30), halfway: position 20, so the 21st of 60.
        assertEquals("Page 21 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, here))
        assertEquals("Page 4 of 12 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, here))
        assertEquals("12 min left in chapter", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_CHAPTER, here))
        assertEquals("4h 10m left in book", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_BOOK, here))
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.NONE, here))
    }

    @Test fun `a page of the book never runs past the book`() {
        assertEquals("Page 1 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = 0, progression = 0.0)))
        // The end of the last part is the last page, not one past it.
        assertEquals("Page 60 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = 2, progression = 1.0)))
        // A part's start is the page after the parts before it.
        assertEquals("Page 11 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = 1, progression = 0.0)))
        assertEquals("Page 12 of 12 in chapter", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, place(chapterPage = 99)))
    }

    @Test fun `what is not known yet stays blank rather than saying something wrong`() {
        val unknown = PageInfo.place(emptyList(), -1, 0.0, 0, 0, null, null)
        PageInfoCorner.entries.forEach { assertEquals(it.name, "", PageInfo.bottomLeft(it, unknown)) }
        assertEquals("", PageInfo.bottomRight(PageInfoChoice(), unknown))
        // A part that is not in the book's list (the locator is not found among its parts).
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, place(section = -1)))
        // The time is not known until the pace is: only its own choices go blank.
        val noTime = place(timeLeft = null)
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_CHAPTER, noTime))
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.TIME_IN_BOOK, noTime))
        assertEquals("Page 21 of 60", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_BOOK, noTime))
        // A part with no pages counted yet.
        assertEquals("", PageInfo.bottomLeft(PageInfoCorner.PAGE_IN_CHAPTER, place(chapterPage = 0, chapterPages = 0)))
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
        assertFalse(all.copy(clock = false).topStrip)
        assertTrue(all.copy(clock = false).bottomStrip)
        // Either bottom corner keeps the foot clear.
        assertTrue(all.copy(corner = PageInfoCorner.NONE).bottomStrip)
        assertTrue(all.copy(percentage = false).bottomStrip)
        assertFalse(all.copy(corner = PageInfoCorner.NONE, percentage = false).bottomStrip)
        val none = PageInfoChoice(clock = false, corner = PageInfoCorner.NONE, percentage = false)
        assertFalse(none.topStrip || none.bottomStrip)
    }

    @Test fun `all three corners are on until turned off`() {
        val defaults = PageInfoChoice()
        assertTrue(defaults.clock && defaults.percentage)
        assertEquals(PageInfoCorner.PAGE_IN_BOOK, defaults.corner)
    }

    @Test fun `a stored name finds its corner, and an unknown one the default`() {
        PageInfoCorner.entries.forEach { assertEquals(it, PageInfoCorner.named(it.name)) }
        assertEquals(PageInfoCorner.PAGE_IN_BOOK, PageInfoCorner.named("LOCATION"))
        assertEquals(PageInfoCorner.PAGE_IN_BOOK, PageInfoCorner.named(null))
    }

    @Test fun `the ink is the page's text colour, quieter`() {
        val ink = PageInfo.ink(0xFF3E3526.toInt())
        assertEquals(0x3E3526, ink and 0xFFFFFF)
        assertEquals(153, (ink ushr 24))
        // Whatever alpha the colour had, the corners' is the same.
        assertEquals(ink, PageInfo.ink(0x803E3526.toInt()))
    }

    @Test fun `the corners sit level with the text and stay on the screen`() {
        assertEquals(20, PageInfo.sideInsetDp(1f))
        assertEquals(14, PageInfo.sideInsetDp(0.5f))
        assertEquals(34, PageInfo.sideInsetDp(1.7f))
        assertEquals(40, PageInfo.sideInsetDp(2f))
    }
}
