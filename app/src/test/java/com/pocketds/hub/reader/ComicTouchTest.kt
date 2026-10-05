package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** A finger on a comic's page does what the keys do (#18, C7). */
class ComicTouchTest {
    @Test fun `the outer thirds read on and back, the middle shows the controls`() {
        assertEquals(ComicTouch.Tap.BACK, ComicTouch.tap(100f, 1920, rtl = false, controlsVisible = false))
        assertEquals(ComicTouch.Tap.CONTROLS, ComicTouch.tap(960f, 1920, rtl = false, controlsVisible = false))
        assertEquals(ComicTouch.Tap.FORWARD, ComicTouch.tap(1800f, 1920, rtl = false, controlsVisible = false))
        // Exactly on a line between thirds is the middle's.
        assertEquals(ComicTouch.Tap.CONTROLS, ComicTouch.tap(640f, 1920, rtl = false, controlsVisible = false))
    }

    @Test fun `right to left the sides swap`() {
        assertEquals(ComicTouch.Tap.FORWARD, ComicTouch.tap(100f, 1920, rtl = true, controlsVisible = false))
        assertEquals(ComicTouch.Tap.BACK, ComicTouch.tap(1800f, 1920, rtl = true, controlsVisible = false))
    }

    @Test fun `with the controls showing any tap on the page hides them`() {
        assertEquals(ComicTouch.Tap.CONTROLS, ComicTouch.tap(100f, 1920, rtl = false, controlsVisible = true))
        assertEquals(ComicTouch.Tap.CONTROLS, ComicTouch.tap(1800f, 1920, rtl = false, controlsVisible = true))
    }

    @Test fun `a swipe across turns the page, the way you read`() {
        assertEquals(1, ComicTouch.swipe(-600f, 40f, 1920, rtl = false, zoomed = false, pinched = false))
        assertEquals(-1, ComicTouch.swipe(600f, 40f, 1920, rtl = false, zoomed = false, pinched = false))
        assertEquals(-1, ComicTouch.swipe(-600f, 40f, 1920, rtl = true, zoomed = false, pinched = false))
        assertEquals(1, ComicTouch.swipe(600f, 40f, 1920, rtl = true, zoomed = false, pinched = false))
    }

    @Test fun `a zoomed page, a pinch, a short or a mostly vertical swipe turn nothing`() {
        assertEquals(0, ComicTouch.swipe(-600f, 40f, 1920, rtl = false, zoomed = true, pinched = false))
        assertEquals(0, ComicTouch.swipe(-600f, 40f, 1920, rtl = false, zoomed = false, pinched = true))
        assertEquals(0, ComicTouch.swipe(-150f, 0f, 1920, rtl = false, zoomed = false, pinched = false))
        assertEquals(0, ComicTouch.swipe(-600f, 500f, 1920, rtl = false, zoomed = false, pinched = false))
        assertEquals(0, ComicTouch.swipe(-600f, 0f, 0, rtl = false, zoomed = false, pinched = false))
    }
}
