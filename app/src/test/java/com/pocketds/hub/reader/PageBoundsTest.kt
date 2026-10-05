package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Trimming a comic page's paper border on the hub's small thumbnail (#18, C5). */
class PageBoundsTest {
    private val random = Random(18)

    /**
     * A thumbnail [width] wide and [height] tall: paper of [paper] luma with
     * a little grain, and art inside the given margins (in pixels), busy
     * enough that no line of it passes for paper.
     */
    private fun page(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, paper: Int = 238): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x < left || x >= width - right || y < top || y >= height - bottom) (paper + random.nextInt(-6, 7)).coerceIn(0, 255)
            else if ((x / 3 + y / 5) % 2 == 0) 40 + random.nextInt(30) else 150 + random.nextInt(60)
        }

    @Test fun `a white border is found on every side, one pixel back from the art`() {
        val content = PageBounds.detect(page(96, 148, left = 5, top = 6, right = 4, bottom = 7), 96, 148)
        assertEquals(4 / 96.0, content.left, 1e-9)
        assertEquals(5 / 148.0, content.top, 1e-9)
        assertEquals(1 - 3 / 96.0, content.right, 1e-9)
        assertEquals(1 - 6 / 148.0, content.bottom, 1e-9)
        assertTrue(content.trimmed)
    }

    @Test fun `art to the edge is left whole`() {
        assertEquals(PageContent.WHOLE, PageBounds.detect(page(96, 148, 0, 0, 0, 0), 96, 148))
        // Bled on the left and right only: the top and bottom margins still go.
        val content = PageBounds.detect(page(96, 148, left = 0, top = 8, right = 0, bottom = 8), 96, 148)
        assertEquals(0.0, content.left, 1e-9)
        assertEquals(1.0, content.right, 1e-9)
        assertEquals(7 / 148.0, content.top, 1e-9)
    }

    @Test fun `a flat colour at an edge is art, not paper`() {
        // A red sky across the top and a mid-grey floor across the foot: no border to trim.
        val pixels = page(96, 148, left = 5, top = 0, right = 5, bottom = 0)
        for (y in 0 until 12) for (x in 0 until 96) pixels[y * 96 + x] = 96
        for (y in 140 until 148) for (x in 0 until 96) pixels[y * 96 + x] = 128
        val content = PageBounds.detect(pixels, 96, 148)
        assertEquals(0.0, content.top, 1e-9)
        assertEquals(1.0, content.bottom, 1e-9)
        // A pale sky is paper as far as the thumbnail can tell: trimmed, but never past the cap.
        val pale = page(96, 148, left = 0, top = 0, right = 0, bottom = 0)
        for (y in 0 until 30) for (x in 0 until 96) pale[y * 96 + x] = 232
        assertEquals(PageBounds.MAX_TRIM, PageBounds.detect(pale, 96, 148).top, 1e-9)
    }

    @Test fun `a black frame trims as paper does`() {
        val content = PageBounds.detect(page(96, 148, 6, 6, 6, 6, paper = 12), 96, 148)
        assertEquals(5 / 96.0, content.left, 1e-9)
        assertEquals(1 - 5 / 96.0, content.right, 1e-9)
    }

    @Test fun `never more than twelve percent of an axis, shared as found`() {
        // 15 and 10 pixels of 96 across: 26% found, 12% taken, three to two.
        val content = PageBounds.detect(page(96, 148, left = 15, top = 4, right = 10, bottom = 4), 96, 148)
        assertEquals(PageBounds.MAX_TRIM, content.left + (1 - content.right), 1e-9)
        assertEquals(14.0 / 9.0, content.left / (1 - content.right), 1e-9)
    }

    @Test fun `a page of only paper, a sliver of margin, or a tiny thumbnail is left whole`() {
        assertEquals(PageContent.WHOLE, PageBounds.detect(IntArray(96 * 148) { 240 }, 96, 148))
        assertEquals(PageContent.WHOLE, PageBounds.detect(page(96, 148, 1, 1, 1, 1), 96, 148))
        assertEquals(PageContent.WHOLE, PageBounds.detect(IntArray(16) { 240 }, 4, 4))
        assertEquals(PageContent.WHOLE, PageBounds.detect(IntArray(10), 96, 148))
    }

    @Test fun `a page number in the margin does not stop the trim`() {
        val pixels = page(96, 148, left = 6, top = 6, right = 6, bottom = 10)
        // Three dark pixels on the second row from the foot: a page number.
        for (x in 46..48) pixels[(148 - 2) * 96 + x] = 30
        val content = PageBounds.detect(pixels, 96, 148)
        assertEquals(1 - 9 / 148.0, content.bottom, 1e-9)
    }

    @Test fun `what trimming gains at the Pocket's screen`() {
        // A 1988 x 3056 scan with 5% of paper at each side and 4% top and bottom.
        val content = PageContent(0.05, 0.04, 0.95, 0.96)
        val width = PageBounds.gain(content, 1988, 3056, 1920, 1080, fitWidth = true)
        assertEquals(1 / 0.9, width, 1e-9)
        val whole = PageBounds.gain(content, 1988, 3056, 1920, 1080, fitWidth = false)
        assertEquals(1 / 0.92, whole, 1e-9)
        assertEquals(1.0, PageBounds.gain(PageContent.WHOLE, 1988, 3056, 1920, 1080, fitWidth = true), 1e-9)
        assertFalse(PageContent.WHOLE.trimmed)
    }

    @Test fun `a step down the content is a step down the page, for its map`() {
        val step = PageContent(0.1, 0.2, 0.9, 0.8).onPage(NormalizedViewport(0.0, 0.0, 1.0, 0.5))
        assertEquals(0.1, step.left, 1e-9)
        assertEquals(0.2, step.top, 1e-9)
        assertEquals(0.9, step.right, 1e-9)
        assertEquals(0.5, step.bottom, 1e-9)
    }

    @Test fun `luma weighs green most`() {
        assertEquals(255, PageBounds.luma(0xFFFFFFFF.toInt()))
        assertEquals(0, PageBounds.luma(0xFF000000.toInt()))
        assertEquals(149, PageBounds.luma(0xFF00FF00.toInt()))
    }
}
