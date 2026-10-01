package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowPagingTest {

    @Test
    fun `a row asks for the page after the one it arrived with, once`() {
        val paging = RowPaging(prefetchAhead = 6)
        // Arrived with page 1 of 5 and 20 cards; the user is 15 cards in.
        assertEquals(2, paging.next("trending", 1, 5, lastVisible = 15, itemCount = 20))
        // A sweep fires this on every step while page 2 is in flight.
        assertNull(paging.next("trending", 1, 5, lastVisible = 16, itemCount = 20))
        paging.complete("trending", 2, 5)
        assertEquals(3, paging.next("trending", 2, 5, lastVisible = 35, itemCount = 40))
    }

    @Test
    fun `rows page independently`() {
        val paging = RowPaging(prefetchAhead = 6)
        assertEquals(2, paging.next("films", 1, 5, 15, 20))
        assertEquals(2, paging.next("series", 1, 5, 15, 20))
    }

    @Test
    fun `not near the end, or on the last page, asks for nothing`() {
        val paging = RowPaging(prefetchAhead = 6)
        assertNull(paging.next("films", 1, 5, lastVisible = 3, itemCount = 20))
        assertNull(paging.next("last", 1, 1, lastVisible = 19, itemCount = 20))
    }

    @Test
    fun `a failed page is retried by the next scroll`() {
        val paging = RowPaging(prefetchAhead = 6)
        assertEquals(2, paging.next("films", 1, 5, 15, 20))
        paging.fail("films", 2)
        assertEquals(2, paging.next("films", 1, 5, 16, 20))
    }

    @Test
    fun `clearing lets rows page again after their requests were cancelled`() {
        val paging = RowPaging(prefetchAhead = 6)
        assertEquals(2, paging.next("films", 1, 5, 15, 20))
        paging.clear()
        assertEquals(2, paging.next("films", 1, 5, 15, 20))
    }

    @Test
    fun `seeding never moves a pager backwards or past a request in flight`() {
        val state = PagedLoadState(6)
        state.seed(2, 5)
        assertEquals(2, state.loadedPage)
        state.seed(1, 5)
        assertEquals(2, state.loadedPage)
        assertEquals(3, state.next(15, 20))
        state.seed(3, 5)
        assertEquals(2, state.loadedPage)
    }
}
