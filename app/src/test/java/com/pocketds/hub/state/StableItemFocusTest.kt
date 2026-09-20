package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Test

class StableItemFocusTest {
    @Test
    fun `returning from details ignores stray focus and restores the opened item`() {
        val focus = StableItemFocus()
        val ids = listOf("first", "second", "opened", "fourth")

        focus.pin(2, "opened")
        focus.remember(0, "first")

        assertEquals(2, focus.resolve(ids))
        focus.confirmRestored(2, "opened")
        focus.remember(3, "fourth")
        assertEquals(3, focus.resolve(ids))
    }

    @Test
    fun `a missing opened item falls back to the first available item`() {
        val focus = StableItemFocus()
        focus.pin(4, "deleted")

        assertEquals(0, focus.resolve(listOf("first", "second")))
        focus.remember(1, "second")
        assertEquals(1, focus.resolve(listOf("first", "second")))
    }

    @Test
    fun `reload resolves the remembered item by stable id after reordering`() {
        val focus = StableItemFocus()
        focus.remember(1, "book-b")

        assertEquals(2, focus.resolve(listOf("book-c", "book-a", "book-b")))
        assertEquals(-1, focus.resolve(emptyList()))
    }
}
