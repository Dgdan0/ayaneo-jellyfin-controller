package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HitRefreshTest {

    private data class Card(val id: String, val played: Boolean = false)

    @Test
    fun `only cards that changed are replaced, where they already sit`() {
        val current = listOf(Card("a"), Card("b"), Card("c"))
        val fresh = listOf(Card("b", played = true), Card("c"))
        assertEquals(listOf(IndexedValue(1, Card("b", played = true))), HitRefresh.changes(current, fresh, Card::id))
    }

    @Test
    fun `cards not on screen are not inserted`() {
        // A patch never changes the grid's shape; a new title waits for a reload.
        assertTrue(HitRefresh.changes(listOf(Card("a")), listOf(Card("z", true)), Card::id).isEmpty())
    }

    @Test
    fun `cards without an id are ignored`() {
        assertTrue(HitRefresh.changes(listOf(Card("")), listOf(Card("", true)), Card::id).isEmpty())
    }

    @Test
    fun `a position belongs to the last page that began at or before it`() {
        val starts = mapOf(1 to 0, 2 to 60, 3 to 120)
        assertEquals(1, HitRefresh.pageOf(0, starts))
        assertEquals(2, HitRefresh.pageOf(60, starts))
        assertEquals(3, HitRefresh.pageOf(150, starts))
        assertNull(HitRefresh.pageOf(5, emptyMap()))
    }
}
