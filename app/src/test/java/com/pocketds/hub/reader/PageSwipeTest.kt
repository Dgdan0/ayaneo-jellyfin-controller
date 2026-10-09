package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a finger that began where Readium's page is not did (#64): a tap in the margin, a swipe that turns the page, or neither. */
class PageSwipeTest {
    private val width = 1920f
    private val slop = 24f
    private val longPress = 500L

    private fun outcome(dx: Float, dy: Float = 0f, ms: Long = 90) = PageSwipe.classify(dx, dy, ms, width, slop, longPress)

    @Test fun `a finger that stays where it is, quickly, is a tap`() {
        assertEquals(PageSwipe.Outcome.Tap, outcome(0f))
        assertEquals(PageSwipe.Outcome.Tap, outcome(slop, slop, 120))
        assertEquals(PageSwipe.Outcome.Tap, outcome(-10f, 8f, 499))
        // Held there, it is a long press, not a tap and not a turn.
        assertEquals(PageSwipe.Outcome.Nothing, outcome(0f, 0f, 500))
        assertEquals(PageSwipe.Outcome.Nothing, outcome(5f, 5f, 900))
    }

    @Test fun `a finger that goes across the page is a swipe the way it went`() {
        assertEquals(PageSwipe.Outcome.Swipe(leftwards = true), outcome(-700f))
        assertEquals(PageSwipe.Outcome.Swipe(leftwards = false), outcome(700f))
        // Any speed: a slow drag across is a turn too, as is a quick short flick that passes the least a swipe is.
        assertEquals(PageSwipe.Outcome.Swipe(true), outcome(-700f, 0f, 800))
        assertEquals(PageSwipe.Outcome.Swipe(true), outcome(-160f, 0f, 60))
        // A little drift up or down is still a sideways swipe.
        assertEquals(PageSwipe.Outcome.Swipe(true), outcome(-600f, 200f))
    }

    @Test fun `a swipe goes a fraction of the width, and never less than twice the slop`() {
        // 8% of 1920 is 153.6 px.
        assertEquals(PageSwipe.Outcome.Nothing, outcome(-150f))
        assertEquals(PageSwipe.Outcome.Swipe(true), outcome(-154f))
        // On a narrow reader the touch slop sets the least: twice 24 is 48, over 8% of 400.
        assertEquals(PageSwipe.Outcome.Nothing, PageSwipe.classify(-45f, 0f, 90, 400f, slop, longPress))
        assertEquals(PageSwipe.Outcome.Swipe(true), PageSwipe.classify(-50f, 0f, 90, 400f, slop, longPress))
    }

    @Test fun `a drag that is more up or down than across is no turn`() {
        assertEquals(PageSwipe.Outcome.Nothing, outcome(-300f, 400f))
        assertEquals(PageSwipe.Outcome.Nothing, outcome(300f, -300f))
        assertEquals(PageSwipe.Outcome.Nothing, outcome(0f, 600f))
        // Exactly one and a half times as far across as along counts as across.
        assertEquals(PageSwipe.Outcome.Swipe(true), outcome(-450f, 300f))
        assertEquals(PageSwipe.Outcome.Nothing, outcome(-449f, 300f))
    }

    @Test fun `a flick is a quick, mostly sideways swipe, the kind Readium's pager turns by itself`() {
        val density = 2.25f
        // 768 px (about 340 dp) in 90 ms: quick and far.
        assertTrue(PageSwipe.isFlick(-768f, 0f, 90, density))
        assertTrue(PageSwipe.isFlick(768f, 40f, 90, density))
        // A short flick: 80 px, a little over 32 dp, in 60 ms.
        assertTrue(PageSwipe.isFlick(-80f, 0f, 60, density))
        // Not far enough: under 32 dp (72 px).
        assertFalse(PageSwipe.isFlick(-60f, 0f, 30, density))
        // Not quick enough: 300 dp a second is 675 px a second, so 300 px in 600 ms (500 px/s) is a drag.
        assertFalse(PageSwipe.isFlick(-300f, 0f, 600, density))
        assertTrue(PageSwipe.isFlick(-300f, 0f, 400, density))
        // More up or down than across is a scroll.
        assertFalse(PageSwipe.isFlick(-300f, 300f, 90, density))
        // A touch with no time is not a divide by zero.
        assertTrue(PageSwipe.isFlick(-300f, 0f, 0, density))
    }

    @Test fun `leftwards turns on in a book read from the left and back in one read from the right`() {
        assertTrue(PageSwipe.forward(leftwards = true, rightToLeft = false))
        assertFalse(PageSwipe.forward(leftwards = false, rightToLeft = false))
        assertFalse(PageSwipe.forward(leftwards = true, rightToLeft = true))
        assertTrue(PageSwipe.forward(leftwards = false, rightToLeft = true))
    }
}
