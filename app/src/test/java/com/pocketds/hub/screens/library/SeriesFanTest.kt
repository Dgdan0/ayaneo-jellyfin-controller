package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingSeriesBook
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.library.SeriesFan.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Series view as fans (#54): which five of a series' books stand in the fan, which is lit, how each leans and
 * darkens, what the caption and the bar under it say, and where a tap goes. The owner's rule, decided from mockups.
 */
class SeriesFanTest {
    private fun book(n: Int, state: String = "", owned: Boolean = true, kind: String = "book") =
        ReadingSeriesBook(number = n.toString(), title = "Book $n", cover = "/c/$n", kind = kind, owned = owned, state = state)

    /** [count] books, [on] the one you are on (1-based, 0 for none), the ones before it read. */
    private fun series(count: Int, on: Int = 0, finished: Boolean = false, missing: Set<Int> = emptySet(), audio: Set<Int> = emptySet()): ReadingWork =
        ReadingWork(id = "s", entityType = "collection", title = "Series", seriesBooks = (1..count).map { n ->
            book(n, when { finished && n !in missing -> "read"; n == on -> "on"; on > 0 && n < on && n !in missing -> "read"; else -> "" },
                owned = n !in missing, kind = if (n in audio) "audiobook" else "book")
        })

    private fun plan(series: ReadingWork) = checkNotNull(SeriesFan.plan(series))
    private fun numbers(plan: SeriesFan.Plan) = plan.slots.map { it.book.number }.joinToString(" ")

    @Test fun `the book you are on takes the middle slot when it has two books each side`() {
        val plan = plan(series(10, on = 5))
        assertEquals("3 4 5 6 7", numbers(plan))
        assertEquals(2, plan.slots.indexOfFirst { it.lit })
        assertEquals(1, plan.slots.count { it.lit })
    }

    @Test fun `near the start the fan keeps its shape and your book is lit in its own slot at that slot's angle`() {
        val first = plan(series(10, on = 1))
        assertEquals("1 2 3 4 5", numbers(first))
        assertEquals(0, first.slots.indexOfFirst { it.lit })
        val second = plan(series(10, on = 2))
        assertEquals("1 2 3 4 5", numbers(second))
        assertEquals(1, second.slots.indexOfFirst { it.lit })
        // Its angle is its slot's, the same as when nothing is lit.
        assertEquals(plan(series(10)).slots[1].angleDeg, second.slots[1].angleDeg, 0.001f)
    }

    @Test fun `near the end it shows the last five`() {
        val last = plan(series(10, on = 10))
        assertEquals("6 7 8 9 10", numbers(last))
        assertEquals(4, last.slots.indexOfFirst { it.lit })
        val before = plan(series(10, on = 9))
        assertEquals("6 7 8 9 10", numbers(before))
        assertEquals(3, before.slots.indexOfFirst { it.lit })
        // Three from the end has two after it: the middle again.
        assertEquals("5 6 7 8 9", numbers(plan(series(10, on = 7))))
        assertEquals(2, plan(series(10, on = 7)).slots.indexOfFirst { it.lit })
    }

    @Test fun `a series shorter than five has that many slots, the angles still symmetrical`() {
        listOf(1, 2, 3, 4).forEach { count ->
            val plan = plan(series(count, on = 1))
            assertEquals(count, plan.slots.size)
            assertEquals(0f, plan.slots.sumOf { it.angleDeg.toDouble() }.toFloat(), 0.001f)
            assertEquals(0f, plan.slots.sumOf { it.offset.toDouble() }.toFloat(), 0.001f)
        }
        assertEquals("1 2 3", numbers(plan(series(3, on = 3))))
        assertEquals(2, plan(series(3, on = 3)).slots.indexOfFirst { it.lit })
    }

    @Test fun `five slots lean symmetrically about the middle and spread by their place`() {
        val slots = plan(series(8, on = 4)).slots
        assertEquals(listOf(-16f, -8f, 0f, 8f, 16f), slots.map { it.angleDeg })
        assertEquals(listOf(-2f, -1f, 0f, 1f, 2f), slots.map { it.offset })
    }

    @Test fun `the lit book is on top and the others are layered and darkened by their distance from it`() {
        val slots = plan(series(10, on = 5)).slots
        val lit = slots[2]
        assertTrue(lit.lit && lit.front)
        assertEquals(0f, lit.shade, 0f)
        assertEquals(slots.maxOf { it.layer }, lit.layer)
        // The nearer to it, the higher and the lighter, either side.
        assertTrue(slots[1].layer > slots[0].layer && slots[3].layer > slots[4].layer)
        assertTrue(slots[1].shade > 0f && slots[0].shade > slots[1].shade && slots[4].shade > slots[3].shade)
        assertEquals(slots[1].shade, slots[3].shade, 0f)
        assertTrue(slots.all { it.shade <= SeriesFan.MAX_SHADE })
        // One layer for each slot: none shares.
        assertEquals(5, slots.map { it.layer }.toSet().size)
        // Lit in a slot that is not the middle: distance is from that slot.
        val near = plan(series(10, on = 2)).slots
        assertEquals(0f, near[1].shade, 0f)
        assertTrue(near[4].shade > near[2].shade)
    }

    @Test fun `a series not started lights nothing and shows books one to five with a grey bar`() {
        val plan = plan(series(8))
        assertEquals("1 2 3 4 5", numbers(plan))
        assertTrue(plan.slots.none { it.lit })
        assertFalse(plan.started)
        assertFalse(plan.finished)
        // The first book is in front, and the others are layered from it.
        assertTrue(plan.slots[0].front)
        assertEquals(plan.slots.maxOf { it.layer }, plan.slots[0].layer)
        assertEquals("8 books", plan.caption)
        assertEquals(List(8) { Part.TO_READ }, (plan.bar as SeriesFan.Bar.Segments).parts)
    }

    @Test fun `a finished series lights nothing, the bar is all gold and the front book is the one with the tick`() {
        val plan = plan(series(8, finished = true))
        assertTrue(plan.finished)
        assertTrue(plan.slots.none { it.lit })
        assertEquals("1 2 3 4 5", numbers(plan))
        assertEquals(List(8) { Part.READ }, (plan.bar as SeriesFan.Bar.Segments).parts)
        assertEquals("8 books · finished", plan.caption)
        assertTrue(plan.slots[0].front)
    }

    @Test fun `the caption says how many books and which you are on`() {
        assertEquals("6 books · on #1", plan(series(6, on = 1)).caption)
        assertEquals("10 books · on #7", plan(series(10, on = 7)).caption)
        assertEquals("1 book", plan(series(1)).caption)
        assertEquals("1 book · on #1", plan(series(1, on = 1)).caption)
        // A novella you are on is named by its own number.
        val novella = ReadingWork(entityType = "collection", seriesBooks = listOf(book(1, "read"), ReadingSeriesBook(number = "1.5", title = "Novella", state = "on"), book(2)))
        assertEquals("3 books · on #1.5", plan(novella).caption)
        // A book with no place: no number to say.
        val unnumbered = ReadingWork(entityType = "collection", seriesBooks = listOf(ReadingSeriesBook(title = "A", state = "on"), ReadingSeriesBook(title = "B")))
        assertEquals("2 books", plan(unnumbered).caption)
    }

    @Test fun `the bar has a part for each book, gold read, white on, grey to read and outlined when you do not have it`() {
        val plan = plan(series(6, on = 3, missing = setOf(5, 6)))
        assertEquals(listOf(Part.READ, Part.READ, Part.ON, Part.TO_READ, Part.MISSING, Part.MISSING), (plan.bar as SeriesFan.Bar.Segments).parts)
        // Missing books count in the caption and in the bar.
        assertEquals("6 books · on #3", plan.caption)
    }

    @Test fun `past twenty five books the bar is one line with a white mark`() {
        val long = plan(series(26, on = 13))
        val bar = long.bar as SeriesFan.Bar.Continuous
        assertEquals(12f / 26f, bar.readFraction, 0.001f)
        assertEquals(12.5f / 26f, bar.mark!!, 0.001f)
        assertTrue(plan(series(25, on = 13)).bar is SeriesFan.Bar.Segments)
        assertNull((plan(series(30)).bar as SeriesFan.Bar.Continuous).mark)
        assertEquals(1f, (plan(series(30, finished = true)).bar as SeriesFan.Bar.Continuous).readFraction, 0f)
    }

    @Test fun `books you do not have are in the fan, dimmed`() {
        val plan = plan(series(6, on = 1, missing = setOf(2, 3, 4)))
        assertEquals("1 2 3 4 5", numbers(plan))
        assertEquals(listOf(false, true, true, true, false), plan.slots.map { it.dimmed })
    }

    @Test fun `every slot is tall and an audiobook only book is square at the slot's bottom`() {
        val plan = plan(series(6, on = 1, audio = setOf(2)))
        assertEquals(listOf(false, true, false, false, false), plan.slots.map { it.square })
    }

    @Test fun `a tap opens the series at the lit book, or the request page of a front book you do not have`() {
        assertEquals(SeriesFan.Target.OpenSeries("5"), plan(series(10, on = 5)).target)
        // Nothing lit: the series, from its start.
        assertEquals(SeriesFan.Target.OpenSeries(null), plan(series(10)).target)
        assertEquals(SeriesFan.Target.OpenSeries(null), plan(series(10, finished = true)).target)
        // The front book is one you do not have: its request page.
        val request = plan(series(10, missing = setOf(1))).target as SeriesFan.Target.Request
        assertEquals("Book 1", request.book.title)
    }

    @Test fun `a series that carries no books has no fan`() {
        assertNull(SeriesFan.plan(ReadingWork(entityType = "collection")))
        assertFalse(SeriesFan.hasFan(ReadingWork(entityType = "collection")))
        assertTrue(SeriesFan.hasFan(series(2)))
        assertFalse(SeriesFan.hasFan(series(2).copy(entityType = "work")))
    }

    @Test fun `the fan opens a little with focus, as far as its card has room for and no further`() {
        val plan = plan(series(8))
        assertEquals(1f, SeriesFan.opening(false, plan, 56f, 1000f), 0f)
        // With all the room in the world it opens as far as it ever does.
        assertEquals(SeriesFan.MAX_OPENING, SeriesFan.opening(true, plan, 56f, 1000f), 0.001f)
        // The Pocket's card: 195dp wide, and a small margin round it.
        val room = 195f / 2 + SeriesFan.SPREAD_MARGIN_DP
        val opened = SeriesFan.opening(true, plan, 56f, room)
        assertTrue("it opens, but not fully: $opened", opened > 1f && opened < SeriesFan.MAX_OPENING)
        assertTrue(SeriesFan.reachDp(plan, 56f, opened) <= room)
        // A hundredth more would not fit.
        assertTrue(SeriesFan.reachDp(plan, 56f, opened + 0.01f) > room)
        // A fan already wider than its room does not open at all, and never closes below its rest.
        assertEquals(1f, SeriesFan.opening(true, plan, 56f, 90f), 0f)
    }

    @Test fun `how far a fan reaches is measured at the corners of its leaning covers`() {
        // The outer slot of five 56dp covers: two steps out, and the top corner leaning 16 degrees about its foot.
        assertEquals(97.1f, SeriesFan.reachDp(plan(series(8)), 56f, 1f), 0.1f)
        // Opening it reaches further.
        assertTrue(SeriesFan.reachDp(plan(series(8)), 56f, 1.1f) > 97.1f)
        // The lit book is bigger, and at an outer slot it reaches further than the same book unlit.
        assertEquals(102.1f, SeriesFan.reachDp(plan(series(8, on = 1)), 56f, 1f), 0.1f)
        // A square audiobook is not as tall, so its corner leans less far.
        assertEquals(89.4f, SeriesFan.reachDp(plan(series(8, audio = setOf(1, 5))), 56f, 1f), 0.1f)
        // A lit book in the middle does not reach past the outer ones.
        assertEquals(97.1f, SeriesFan.reachDp(plan(series(10, on = 5)), 56f, 1f), 0.1f)
        // A series of two is as wide as its two covers.
        assertTrue(SeriesFan.reachDp(plan(series(2)), 56f, 1f) < 60f)
    }

    @Test fun `the box has room over the lit book for it leaning at the end of a series`() {
        // A 56dp cover, its lit book 1.1 times that, at the outer slot (16 degrees, opened to 19.5): about 5dp higher than upright.
        assertEquals(5f, SeriesFan.leanRiseDp(56f), 0.3f)
        assertTrue(SeriesFan.leanRiseDp(100f) > SeriesFan.leanRiseDp(56f))
    }

    @Test fun `a fan of one known cover stands upright in the middle of its box`() {
        // The Pocket's 64dp cover in its 130 x 108dp box: 33dp in from each side, 6dp from the top and from the foot.
        val (left, top) = SeriesFan.centred(130f, 108f, 64f)
        assertEquals(33f, left, 0f)
        assertEquals(6f, top, 0f)
        assertEquals(33f, 130f - 64f - left, 0f)
    }

    @Test fun `the room a fan needs follows its covers and its slots`() {
        // Five slots 0.42 of a cover apart: a cover and four steps across.
        assertEquals(76 + 4 * (76 * SeriesFan.STEP_FRACTION), SeriesFan.widthDp(76f, 5), 0.01f)
        assertEquals(76f, SeriesFan.widthDp(76f, 1), 0.01f)
        assertEquals(76 * 1.5f, SeriesFan.coverHeightDp(76f, square = false), 0.001f)
        assertEquals(76f, SeriesFan.coverHeightDp(76f, square = true), 0.001f)
    }
}
