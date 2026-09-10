package com.pocketds.hub.state

import com.pocketds.hub.input.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowTest {

    /** The real app area: 1920x1026 px. */
    private val parentW = 1920
    private val parentH = 1026
    private val margin = 24

    @Test
    fun `starts bottom right, out of the way of the content`() {
        // The tab bar and the first row of posters are at the top, so a window
        // that opens there covers what you were just looking at.
        val window = FloatingWindow()
        assertEquals(Corner.BOTTOM_RIGHT, window.corner)
    }

    @Test
    fun `bounds keep a 16 by 9 shape`() {
        val bounds = FloatingWindow().bounds(parentW, parentH, margin)
        // Integer division, so allow a pixel either way.
        val expectedHeight = bounds.width * 9 / 16
        assertTrue(
            "got ${bounds.width}x${bounds.height}",
            Math.abs(bounds.height - expectedHeight) <= 1
        )
    }

    @Test
    fun `toolbar is outside the sixteen by nine video viewport`() {
        val chrome = 80
        val bounds = FloatingWindow().bounds(parentW, parentH, margin, chrome)
        val expectedVideoHeight = bounds.width * 9 / 16
        assertTrue(
            "got ${bounds.width}x${bounds.height} with $chrome chrome",
            Math.abs((bounds.height - chrome) - expectedVideoHeight) <= 1
        )
    }

    @Test
    fun `safe top and bottom keep floating window out of app chrome`() {
        val safeTop = 140
        val safeBottom = 100
        val window = FloatingWindow()
        window.nudge(com.pocketds.hub.input.Direction.UP)
        var bounds = window.bounds(parentW, parentH, margin, 80, safeTopPx = safeTop, safeBottomPx = safeBottom)
        assertTrue(bounds.top >= safeTop + margin)
        window.nudge(com.pocketds.hub.input.Direction.DOWN)
        bounds = window.bounds(parentW, parentH, margin, 80, safeTopPx = safeTop, safeBottomPx = safeBottom)
        assertTrue(bounds.bottom <= parentH - safeBottom - margin)
    }

    @Test
    fun `fullscreen ignores safe insets and preserves floating choice`() {
        val window = FloatingWindow()
        window.nudge(com.pocketds.hub.input.Direction.LEFT)
        window.cycleSize(1)
        val corner = window.corner
        val size = window.sizeStep
        window.toggleFullscreen()
        assertEquals(WindowBounds(0, 0, parentW, parentH), window.bounds(parentW, parentH, margin, 80, safeTopPx = 100))
        window.toggleFullscreen()
        assertEquals(corner, window.corner)
        assertEquals(size, window.sizeStep)
    }

    @Test
    fun `tiny safe area still returns positive contained geometry`() {
        val bounds = FloatingWindow().bounds(
            parentWidth = 240,
            parentHeight = 180,
            marginPx = 12,
            chromeHeightPx = 40,
            safeTopPx = 30,
            safeBottomPx = 30
        )
        assertTrue(bounds.width > 0)
        assertTrue(bounds.height > 0)
        assertTrue(bounds.left >= 0)
        assertTrue(bounds.top >= 30)
        assertTrue(bounds.right <= 240)
        assertTrue(bounds.bottom <= 150)
    }

    @Test
    fun `safe area smaller than two margins is still contained`() {
        val bounds = FloatingWindow().bounds(
            parentWidth = 80,
            parentHeight = 70,
            marginPx = 24,
            chromeHeightPx = 40,
            safeLeftPx = 30,
            safeTopPx = 20,
            safeRightPx = 30,
            safeBottomPx = 20
        )
        assertTrue(bounds.left >= 30)
        assertTrue(bounds.top >= 20)
        assertTrue(bounds.right <= 50)
        assertTrue(bounds.bottom <= 50)
    }

    @Test
    fun `bounds stay inside the parent at every size and corner`() {
        for (corner in Corner.entries) {
            val window = FloatingWindow()
            moveTo(window, corner)
            for (step in 0..2) {
                while (window.cycleSize(-1)) Unit
                repeat(step) { window.cycleSize(1) }
                val b = window.bounds(parentW, parentH, margin)
                assertTrue("$corner/$step left ${b.left}", b.left >= 0)
                assertTrue("$corner/$step top ${b.top}", b.top >= 0)
                assertTrue("$corner/$step right ${b.right} > $parentW", b.right <= parentW)
                assertTrue("$corner/$step bottom ${b.bottom} > $parentH", b.bottom <= parentH)
            }
        }
    }

    @Test
    fun `the largest size is limited by height, not width`() {
        // 55% of 1920 is 1056px wide, which at 16:9 is 594px tall -- taller
        // than the 1026px screen minus margins allows once you account for it
        // being a landscape handheld. The window must shrink to fit rather than
        // hang off the bottom.
        val window = FloatingWindow()
        while (window.cycleSize(1)) Unit
        val b = window.bounds(1920, 400, margin)
        assertTrue("height ${b.height}", b.height <= 400 - margin * 2)
        assertTrue("right ${b.right}", b.right <= 1920)
    }

    @Test
    fun `nudging walks the four corners`() {
        val window = FloatingWindow() // bottom right
        assertTrue(window.nudge(Direction.LEFT))
        assertEquals(Corner.BOTTOM_LEFT, window.corner)
        assertTrue(window.nudge(Direction.UP))
        assertEquals(Corner.TOP_LEFT, window.corner)
        assertTrue(window.nudge(Direction.RIGHT))
        assertEquals(Corner.TOP_RIGHT, window.corner)
        assertTrue(window.nudge(Direction.DOWN))
        assertEquals(Corner.BOTTOM_RIGHT, window.corner)
    }

    @Test
    fun `nudging at an edge does nothing and says so`() {
        // Not a wrap to the far side of the screen: the caller is told the press
        // was unused so it can mean something else.
        val window = FloatingWindow() // bottom right
        assertFalse(window.nudge(Direction.RIGHT))
        assertFalse(window.nudge(Direction.DOWN))
        assertEquals(Corner.BOTTOM_RIGHT, window.corner)
    }

    @Test
    fun `size cycles and clamps`() {
        val window = FloatingWindow()
        assertTrue(window.cycleSize(-1))
        assertFalse("already smallest", window.cycleSize(-1))
        assertTrue(window.cycleSize(1))
        assertTrue(window.cycleSize(1))
        assertFalse("already largest", window.cycleSize(1))
    }

    @Test
    fun `a bigger size step really is bigger`() {
        val small = FloatingWindow().also { while (it.cycleSize(-1)) Unit }
        val large = FloatingWindow().also { while (it.cycleSize(1)) Unit }
        assertTrue(
            large.bounds(parentW, parentH, margin).width >
                small.bounds(parentW, parentH, margin).width
        )
    }

    @Test
    fun `fullscreen fills the parent with no margin`() {
        // A video letterboxes itself, so a border around black is just a
        // smaller picture.
        val window = FloatingWindow()
        window.toggleFullscreen()
        val b = window.bounds(parentW, parentH, margin)
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(parentW, b.width)
        assertEquals(parentH, b.height)
    }

    @Test
    fun `fullscreen ignores move and resize`() {
        val window = FloatingWindow()
        window.toggleFullscreen()
        assertFalse(window.nudge(Direction.LEFT))
        assertFalse(window.cycleSize(1))
        assertFalse(window.snapTo(10, 10, parentW, parentH))
    }

    @Test
    fun `leaving fullscreen restores the corner it came from`() {
        val window = FloatingWindow()
        window.nudge(Direction.LEFT) // bottom left
        window.toggleFullscreen()
        window.toggleFullscreen()
        assertEquals(Corner.BOTTOM_LEFT, window.corner)
    }

    @Test
    fun `a drag snaps to the nearest corner`() {
        val window = FloatingWindow() // bottom right
        assertTrue(window.snapTo(100, 100, parentW, parentH))
        assertEquals(Corner.TOP_LEFT, window.corner)
        assertTrue(window.snapTo(parentW - 100, 100, parentW, parentH))
        assertEquals(Corner.TOP_RIGHT, window.corner)
        assertTrue(window.snapTo(100, parentH - 100, parentW, parentH))
        assertEquals(Corner.BOTTOM_LEFT, window.corner)
    }

    @Test
    fun `snapping to where it already is reports no change`() {
        val window = FloatingWindow() // bottom right
        assertFalse(window.snapTo(parentW - 50, parentH - 50, parentW, parentH))
    }

    @Test
    fun `a zero-sized parent does not produce nonsense`() {
        // Called once before the first layout pass, which is normal.
        val b = FloatingWindow().bounds(0, 0, margin)
        assertEquals(0, b.width)
        assertEquals(0, b.height)
    }

    @Test
    fun `size labels read plainly`() {
        val window = FloatingWindow()
        while (window.cycleSize(-1)) Unit
        assertEquals("Small", window.sizeLabel())
        window.cycleSize(1)
        assertEquals("Medium", window.sizeLabel())
        window.cycleSize(1)
        assertEquals("Large", window.sizeLabel())
        window.toggleFullscreen()
        assertEquals("Full", window.sizeLabel())
    }

    private fun moveTo(window: FloatingWindow, corner: Corner) {
        repeat(3) { window.nudge(Direction.UP); window.nudge(Direction.LEFT) }
        if (!corner.isTop) window.nudge(Direction.DOWN)
        if (!corner.isLeft) window.nudge(Direction.RIGHT)
        assertEquals(corner, window.corner)
    }
}
