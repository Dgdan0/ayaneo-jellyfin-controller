package com.pocketds.hub.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusGuardTest {

    /** An 82dp card at 2.25 density is ~185px wide, ~330px tall. */
    private fun card(x: Int, y: Int = 300) = FocusRect(x, y, x + 185, y + 330)

    @Test
    fun `right accepts the next card along`() {
        assertTrue(FocusGuard.accepts(Direction.RIGHT, card(0), card(195)))
    }

    @Test
    fun `right refuses a wrap back to the start of the same row`() {
        // The bug this exists for. Pressing right on the last loaded card in a
        // row made FocusFinder return the leftmost card, so the selection
        // teleported to the beginning -- while the next page was still loading.
        val last = card(1500)
        val first = card(0)
        assertFalse(FocusGuard.accepts(Direction.RIGHT, last, first))
    }

    @Test
    fun `left refuses a wrap to the end of the same row`() {
        assertFalse(FocusGuard.accepts(Direction.LEFT, card(0), card(1500)))
    }

    @Test
    fun `left accepts the previous card`() {
        assertTrue(FocusGuard.accepts(Direction.LEFT, card(195), card(0)))
    }

    @Test
    fun `right off the end of a row stays put by default`() {
        // A row of posters is navigated up and down between rows, so a sideways
        // press that runs out of items should do nothing.
        val endOfRow = card(1500, y = 300)
        val nextRow = card(0, y = 700)
        assertFalse(FocusGuard.accepts(Direction.RIGHT, endOfRow, nextRow))
    }

    @Test
    fun `right never escapes upward to the tab bar`() {
        // Measured on the device: pressing right past the last loaded poster
        // found the tab bar *above* the row and the selection left the content
        // entirely. FocusFinder searches the whole window, not just rightwards.
        val card = card(1500, y = 300)
        val tab = FocusRect(300, 60, 500, 150)
        assertFalse(FocusGuard.accepts(Direction.RIGHT, card, tab))
        assertFalse(FocusGuard.accepts(Direction.RIGHT, card, tab, HorizontalMode.GRID))
    }

    @Test
    fun `a grid wraps right onto the next line`() {
        // Reading order continues below, and stopping dead at the right-hand
        // edge of a grid would be wrong.
        val endOfLine = card(1700, y = 300)
        val startOfNextLine = card(0, y = 700)
        assertTrue(
            FocusGuard.accepts(Direction.RIGHT, endOfLine, startOfNextLine, HorizontalMode.GRID)
        )
    }

    @Test
    fun `a grid wraps left onto the previous line`() {
        val startOfLine = card(0, y = 700)
        val endOfPreviousLine = card(1700, y = 300)
        assertTrue(
            FocusGuard.accepts(Direction.LEFT, startOfLine, endOfPreviousLine, HorizontalMode.GRID)
        )
    }

    @Test
    fun `a grid still refuses a wrap within the same line`() {
        assertFalse(
            FocusGuard.accepts(Direction.RIGHT, card(1700), card(0), HorizontalMode.GRID)
        )
    }

    @Test
    fun `moving along a tab bar is same-band and allowed`() {
        val discover = FocusRect(20, 60, 220, 150)
        val library = FocusRect(240, 60, 400, 150)
        assertTrue(FocusGuard.accepts(Direction.RIGHT, discover, library))
        assertTrue(FocusGuard.accepts(Direction.LEFT, library, discover))
    }

    @Test
    fun `up and down are never second-guessed`() {
        // Rows are stacked, so vertical search either finds something in the
        // right direction or nothing. Interfering would break the seams
        // between the tab bar, the content and the hint bar.
        val from = card(600, y = 300)
        assertTrue(FocusGuard.accepts(Direction.UP, from, card(0, y = 700)))
        assertTrue(FocusGuard.accepts(Direction.DOWN, from, card(0, y = 0)))
    }

    @Test
    fun `slack tolerates a slightly misaligned neighbour`() {
        // A focused card is scaled up 8%, so its rect is a few pixels wider
        // than its neighbours' and can start marginally to the left of the one
        // that is genuinely next. That must still count as moving right.
        val focusedAndScaled = FocusRect(1000, 300, 1200, 640)
        val genuinelyNext = FocusRect(995, 305, 1180, 635)
        assertTrue(FocusGuard.accepts(Direction.RIGHT, focusedAndScaled, genuinelyNext))
    }

    @Test
    fun `slack does not let a real wrap through`() {
        // Half a card of tolerance, not half a screen.
        val from = card(1500)
        assertFalse(FocusGuard.accepts(Direction.RIGHT, from, card(1200)))
    }

    @Test
    fun `vertical overlap decides what counts as the same row`() {
        assertTrue(FocusGuard.overlapsVertically(card(0, y = 300), card(900, y = 300)))
        assertTrue(FocusGuard.overlapsVertically(card(0, y = 300), card(900, y = 500)))
        assertFalse(FocusGuard.overlapsVertically(card(0, y = 300), card(900, y = 700)))
    }

    @Test
    fun `touching but not overlapping rows are separate`() {
        // A row ending exactly where the next begins shares no vertical extent.
        val a = FocusRect(0, 100, 185, 400)
        val b = FocusRect(0, 400, 185, 700)
        assertFalse(FocusGuard.overlapsVertically(a, b))
    }

    @Test
    fun `a rect reports its own centre and size`() {
        val rect = FocusRect(10, 20, 110, 220)
        org.junit.Assert.assertEquals(60, rect.centerX)
        org.junit.Assert.assertEquals(120, rect.centerY)
        org.junit.Assert.assertEquals(100, rect.width)
        org.junit.Assert.assertEquals(200, rect.height)
    }
}
