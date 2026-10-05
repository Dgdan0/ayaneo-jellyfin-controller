package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reading a scrolling book with the D-pad and the right stick (#18, E1). */
class BookScrollTest {
    @Test fun `a gentle push still moves, a pixel at a time as the fractions add up`() {
        val scroll = BookScroll(edgePx = 300f)
        // 0.4 of a pixel a frame used to round down to nothing, every frame.
        val moves = (1..10).map { scroll.glide(0.4f, canDown = true, canUp = true) }
        val moved = moves.sumOf { (it as? BookScroll.Move.By)?.px ?: 0 }
        assertEquals(4, moved)
        assertEquals(BookScroll.Move.Stay, moves.first())
    }

    @Test fun `up carries its own fractions`() {
        val scroll = BookScroll(edgePx = 300f)
        val moved = (1..5).sumOf { (scroll.glide(-0.5f, canDown = true, canUp = true) as? BookScroll.Move.By)?.px ?: 0 }
        assertEquals(-2, moved)
    }

    @Test fun `the stick tips into the next part only after pushing on past the end`() {
        val scroll = BookScroll(edgePx = 100f)
        assertEquals(BookScroll.Move.Stay, scroll.glide(40f, canDown = false, canUp = true))
        assertEquals(BookScroll.Move.Stay, scroll.glide(40f, canDown = false, canUp = true))
        assertEquals(BookScroll.Move.NextPart, scroll.glide(40f, canDown = false, canUp = true))
        // Then it starts again from nothing.
        assertEquals(BookScroll.Move.Stay, scroll.glide(40f, canDown = false, canUp = true))
    }

    @Test fun `a push that turns back, or scrolls again, starts the edge over`() {
        val scroll = BookScroll(edgePx = 100f)
        scroll.glide(80f, canDown = false, canUp = true)
        assertEquals(BookScroll.Move.Stay, scroll.glide(-30f, canDown = false, canUp = false))
        assertEquals(BookScroll.Move.Stay, scroll.glide(-60f, canDown = false, canUp = false))
        assertEquals(BookScroll.Move.PreviousPart, scroll.glide(-20f, canDown = false, canUp = false))
        scroll.glide(80f, canDown = false, canUp = true)
        assertEquals(BookScroll.Move.By(5), scroll.glide(5f, canDown = true, canUp = true))
        assertEquals(BookScroll.Move.Stay, scroll.glide(80f, canDown = false, canUp = true))
    }

    @Test fun `the D-pad scrolls at once and goes on to the next part at the end`() {
        val scroll = BookScroll(edgePx = 100f)
        assertEquals(BookScroll.Move.By(342), scroll.step(342, canDown = true, canUp = true))
        assertEquals(BookScroll.Move.NextPart, scroll.step(342, canDown = false, canUp = true))
        assertEquals(BookScroll.Move.PreviousPart, scroll.step(-342, canDown = true, canUp = false))
        assertEquals(BookScroll.Move.Stay, scroll.step(0, canDown = true, canUp = true))
    }
}
