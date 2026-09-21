package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedImageStateTest {
    private val portrait = { index: Int -> PageDimension(index, 1200, 1800) }

    @Test
    fun `cover is isolated and ltr pairs read left to right`() {
        val spreads = SpreadPlanner.plan((0..4).map(portrait), PageDirection.LTR)

        assertEquals(listOf(0), spreads[0].readingOrder)
        assertEquals(listOf(1, 2), spreads[1].readingOrder)
        assertEquals(1, spreads[1].leftPage)
        assertEquals(2, spreads[1].rightPage)
        assertEquals(listOf(3, 4), spreads[2].readingOrder)
    }

    @Test
    fun `manga pairs have right to left reading order without swapping source indices`() {
        val spreads = SpreadPlanner.plan((0..4).map(portrait), PageDirection.RTL)

        assertEquals(2, spreads[1].leftPage)
        assertEquals(1, spreads[1].rightPage)
        assertEquals(listOf(1, 2), spreads[1].readingOrder)
    }

    @Test
    fun `wide pages stay alone and do not steal a neighbour`() {
        val pages = listOf(
            portrait(0),
            portrait(1),
            PageDimension(2, 2400, 1400, isWide = true),
            portrait(3),
            portrait(4)
        )

        val spreads = SpreadPlanner.plan(pages, PageDirection.LTR)

        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), spreads.map { it.readingOrder })
    }

    @Test
    fun `viewport thirds overlap and reverse horizontally for manga`() {
        val ltr = ViewportStepPlanner.steps(ViewportAxis.HORIZONTAL, PageDirection.LTR)
        val rtl = ViewportStepPlanner.steps(ViewportAxis.HORIZONTAL, PageDirection.RTL)

        assertEquals(3, ltr.size)
        assertEquals(ltr.reversed(), rtl)
        assertEquals(0.0, ltr.first().left, 0.0001)
        assertEquals(1.0, ltr.last().right, 0.0001)
        assertTrue(ltr[0].right > ltr[1].left)
    }

    @Test
    fun `vertical thirds run from top to bottom`() {
        val steps = ViewportStepPlanner.steps(ViewportAxis.VERTICAL, PageDirection.RTL)

        assertEquals(3, steps.size)
        assertEquals(0.0, steps.first().top, 0.0001)
        assertEquals(1.0, steps.last().bottom, 0.0001)
        assertTrue(steps[0].bottom > steps[1].top)
    }

    @Test
    fun `paged navigation consumes viewport steps before changing page`() {
        val state = PagedImageState(pageCount = 3, startPage = 1, viewportSteps = 3)

        assertTrue(state.advance())
        assertEquals(1, state.pageIndex)
        assertEquals(1, state.viewportIndex)
        assertTrue(state.advance())
        assertEquals(2, state.viewportIndex)
        assertTrue(state.advance())
        assertEquals(2, state.pageIndex)
        assertEquals(0, state.viewportIndex)
        assertTrue(state.advance())
        assertTrue(state.advance())
        assertFalse(state.advance())
        assertTrue(state.retreat())
        assertEquals(2, state.pageIndex)
        assertEquals(1, state.viewportIndex)
        assertTrue(state.retreat())
        assertTrue(state.retreat())
        assertEquals(1, state.pageIndex)
        assertEquals(2, state.viewportIndex)
    }

    @Test
    fun `page progress is zero based and reaches completion on final page`() {
        val state = PagedImageState(pageCount = 5, startPage = 2)

        assertEquals(0.5, state.progression, 0.0001)
        state.seek(4)
        assertEquals(1.0, state.progression, 0.0001)
        assertTrue(state.completed)
        state.seek(-50)
        assertEquals(0, state.pageIndex)
    }

    @Test
    fun `reader title does not repeat identical series and publication names`() {
        assertEquals("Daredevil 000.5", ReaderTitleFormatter.format("Daredevil 000.5", "Daredevil 000.5", "Fallback"))
        assertEquals("Daredevil · 000.5", ReaderTitleFormatter.format("Daredevil", "000.5", "Fallback"))
        assertEquals("Fallback", ReaderTitleFormatter.format("", "", "Fallback"))
    }

    @Test
    fun `reader controls initially focus the forward page action`() {
        assertEquals(6, ReaderControlFocusPolicy.initialIndex(controlCount = 7))
        assertEquals(null, ReaderControlFocusPolicy.initialIndex(controlCount = 0))
    }
}
