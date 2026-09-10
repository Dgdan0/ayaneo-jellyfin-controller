package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagedLoadStateTest {
    @Test
    fun `a page cannot be requested twice while loading`() {
        val state = PagedLoadState()
        assertEquals(1, state.initial())
        assertNull(state.initial())
        assertNull(state.next(59, 60))
    }

    @Test
    fun `reaching the prefetch window asks for the next page`() {
        val state = PagedLoadState(6)
        state.initial()
        state.complete(1, 3)
        assertNull(state.next(52, 60))
        assertEquals(2, state.next(54, 60))
    }

    @Test
    fun `failed page is retried without dropping loaded pages`() {
        val state = PagedLoadState()
        state.initial()
        state.complete(1, 3)
        assertEquals(2, state.next(59, 60))
        state.fail(2)
        assertEquals(2, state.retry())
        assertEquals(1, state.loadedPage)
    }

    @Test
    fun `the final page does not cause more requests`() {
        val state = PagedLoadState()
        state.initial()
        state.complete(1, 1)
        assertNull(state.next(59, 60))
        assertNull(state.retry())
    }

    @Test
    fun `an interrupted initial page is retried on return`() {
        val state = PagedLoadState()
        assertEquals(1, state.initial())
        state.cancelLoading()
        assertEquals(1, state.initial())
    }

    @Test
    fun `an interrupted later page keeps earlier content`() {
        val state = PagedLoadState()
        state.initial()
        state.complete(1, 3)
        assertEquals(2, state.next(59, 60))
        state.cancelLoading()
        assertEquals(2, state.retry())
        assertEquals(1, state.loadedPage)
    }
}
