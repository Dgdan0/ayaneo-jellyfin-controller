package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PageGridTest {
    @Test fun `seven cells across the Pocket, never fewer than three`() {
        assertEquals(7, PageGrid.columns(853))
        assertEquals(3, PageGrid.columns(200))
        assertEquals(9, PageGrid.columns(3000))
    }

    @Test fun `left and right run on across rows, up and down keep the column`() {
        // 24 pages, 7 across: rows 0-6, 7-13, 14-20, 21-23.
        assertEquals(7, PageGrid.move(6, Direction.RIGHT, 7, 24))
        assertEquals(6, PageGrid.move(7, Direction.LEFT, 7, 24))
        assertEquals(10, PageGrid.move(3, Direction.DOWN, 7, 24))
        assertEquals(3, PageGrid.move(10, Direction.UP, 7, 24))
        // The edges hold.
        assertEquals(0, PageGrid.move(0, Direction.LEFT, 7, 24))
        assertEquals(23, PageGrid.move(23, Direction.RIGHT, 7, 24))
        assertEquals(4, PageGrid.move(4, Direction.UP, 7, 24))
        // Down from a column the short last row lacks lands on its last page.
        assertEquals(23, PageGrid.move(19, Direction.DOWN, 7, 24))
        // On the last row, down holds.
        assertEquals(22, PageGrid.move(22, Direction.DOWN, 7, 24))
        assertEquals(0, PageGrid.move(5, Direction.UP, 7, 0))
    }

    @Test fun `the triggers move a screenful of rows`() {
        assertEquals(17, PageGrid.page(3, 1, 7, 2, 40))
        assertEquals(3, PageGrid.page(17, -1, 7, 2, 40))
        assertEquals(39, PageGrid.page(30, 1, 7, 2, 40))
        assertEquals(5, PageGrid.page(12, -1, 7, 2, 40))
    }

    @Test fun `the grid names its own keys`() {
        assertEquals(listOf("Open page", "Close", "Earlier pages", "Later pages"), PageGrid.HINTS.map { it.label })
        assertEquals(PadAction.Activate, PageGrid.HINTS.first().action)
    }
}
