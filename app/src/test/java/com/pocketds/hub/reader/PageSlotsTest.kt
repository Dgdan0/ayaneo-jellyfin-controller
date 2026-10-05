package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSlotsTest {
    private fun key(page: Int, publication: String = "issue-51") = PageKey(publication, page)

    @Test fun `the page, the next the way you read, then the one before`() {
        assertEquals(listOf(4, 5, 3), PageSlots.wanted(4, 20))
        assertEquals(listOf(4, 3, 5), PageSlots.wanted(4, 20, forward = false))
        // At either end there is only one neighbour.
        assertEquals(listOf(0, 1), PageSlots.wanted(0, 20))
        assertEquals(listOf(19, 18), PageSlots.wanted(19, 20))
        assertEquals(listOf(0), PageSlots.wanted(0, 1))
        assertEquals(listOf(4, 5), PageSlots.wanted(4, 20, count = 2))
    }

    @Test fun `a turn keeps what is decoded and loads only the new neighbour`() {
        // On page 4, holding 4, 5 and 3; turn to 5: 4 stays as the page before, 5 is already there.
        val held = listOf(key(4), key(5), key(3))
        val next = PageSlots.assign(held, PageSlots.wanted(5, 20).map { key(it) })
        assertEquals(listOf(key(4), key(5), key(6)), next)
    }

    @Test fun `turning back finds the page before still decoded`() {
        val held = listOf(key(4), key(5), key(6))
        val next = PageSlots.assign(held, PageSlots.wanted(4, 20, forward = false).map { key(it) })
        assertEquals(listOf(key(4), key(5), key(3)), next)
    }

    @Test fun `a jump gives every surface but the wanted ones away`() {
        val held = listOf(key(4), key(5), key(3))
        assertEquals(listOf(key(12), key(13), key(11)), PageSlots.assign(held, PageSlots.wanted(12, 20).map { key(it) }))
    }

    @Test fun `another issue's pages are never taken for this one's`() {
        val held = listOf(key(2, "issue-52"), null, key(1))
        assertEquals(listOf(key(0), null, key(1)), PageSlots.assign(held, listOf(key(0), key(1))))
    }

    @Test fun `a page held twice is kept once`() {
        val held = listOf(key(4), key(4), null)
        assertEquals(listOf(key(4), key(5), key(3)), PageSlots.assign(held, listOf(key(4), key(5), key(3))))
    }
}
