package com.pocketds.hub.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkColorBookTest {

    private val red = ArtworkPalette(0xFFD62828.toInt(), 0xFF2C0807.toInt(), 0xFFDD2722.toInt(), 0xFFFFDBD6.toInt())
    private val blue = ArtworkPalette(0xFF1D4FA8.toInt(), 0xFF0C1331.toInt(), 0xFF556FDE.toInt(), 0xFFDAE4FF.toInt())

    @Test fun `asks once for what it does not know and not again while asking`() {
        val book = ArtworkColorBook()
        assertEquals(listOf("/a", "/b"), book.toAsk(listOf("/a", "/b", "/a", ""), 0))
        assertEquals(emptyList<String>(), book.toAsk(listOf("/a", "/b"), 0))
        assertEquals(listOf("/a"), book.answered(listOf("/a", "/b"), mapOf("/a" to red), listOf("/b"), 0))
        assertEquals(red, book.palette("/a"))
        assertTrue(book.isMissing("/b"))
        // Known and missing are never asked again.
        assertEquals(emptyList<String>(), book.toAsk(listOf("/a", "/b"), 99_999))
    }

    @Test fun `a request asks for at most sixty`() {
        val book = ArtworkColorBook()
        val sources = (1..75).map { "/p$it" }
        assertEquals(60, book.toAsk(sources, 0).size)
        assertEquals((61..75).map { "/p$it" }, book.toAsk(sources, 0))
    }

    @Test fun `pending is asked again after three then ten then thirty seconds, then given up`() {
        val book = ArtworkColorBook()
        var now = 0L
        fun pendingRound() {
            val asked = book.toAsk(listOf("/slow"), now)
            assertEquals(listOf("/slow"), asked)
            book.answered(asked, emptyMap(), emptyList(), now)
        }
        pendingRound()
        assertEquals(3_000L, book.nextDueAt())
        assertEquals(emptyList<String>(), book.toAsk(listOf("/slow"), 2_999))
        assertEquals(emptyList<String>(), book.due(2_999))
        now = 3_000; assertEquals(listOf("/slow"), book.due(now)); pendingRound()
        assertEquals(13_000L, book.nextDueAt())
        now = 13_000; pendingRound()
        assertEquals(43_000L, book.nextDueAt())
        now = 43_000; pendingRound()
        // Pending through every wait: it stops being asked this session.
        assertTrue(book.isMissing("/slow"))
        assertNull(book.nextDueAt())
        assertEquals(emptyList<String>(), book.toAsk(listOf("/slow"), 999_999))
    }

    @Test fun `a failed request waits like pending and an answer later clears the wait`() {
        val book = ArtworkColorBook()
        val asked = book.toAsk(listOf("/a"), 0)
        book.failed(asked, 0)
        assertEquals(3_000L, book.nextDueAt())
        val again = book.toAsk(listOf("/a"), 3_000)
        assertEquals(listOf("/a"), book.answered(again, mapOf("/a" to red), emptyList(), 3_000))
        assertNull(book.nextDueAt())
        assertFalse(book.isMissing("/a"))
    }

    @Test fun `the least recently used colours are dropped first`() {
        val book = ArtworkColorBook(capacity = 2)
        book.restore(listOf("/old" to red, "/kept" to blue))
        assertEquals(red, book.palette("/old")) // using it makes it recent
        book.answered(book.toAsk(listOf("/new"), 0), mapOf("/new" to blue), emptyList(), 0)
        assertNull(book.palette("/kept"))
        assertEquals(listOf("/old", "/new"), book.snapshot().map { it.first })
    }

    @Test fun `restoring keeps what was learnt since`() {
        val book = ArtworkColorBook()
        book.answered(book.toAsk(listOf("/a"), 0), mapOf("/a" to blue), emptyList(), 0)
        book.restore(listOf("/a" to red, "/b" to red, "" to red))
        assertEquals(blue, book.palette("/a"))
        assertEquals(red, book.palette("/b"))
        assertEquals(2, book.snapshot().size)
    }
}
