package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageCachePolicyTest {
    @Test
    fun `page cache names are stable url scoped and contain no coordinates`() {
        val first = ReaderPageCachePolicy.fileName("https://hub-a/v1/reading/works/rw_secret/publications/6/pages/2")
        val again = ReaderPageCachePolicy.fileName("https://hub-a/v1/reading/works/rw_secret/publications/6/pages/2")
        val otherHub = ReaderPageCachePolicy.fileName("https://hub-b/v1/reading/works/rw_secret/publications/6/pages/2")

        assertEquals(first, again)
        assertNotEquals(first, otherHub)
        assertTrue(first.matches(Regex("[0-9a-f]{64}\\.page")))
        assertTrue("rw_secret" !in first)
    }

    @Test
    fun `cache pruning removes oldest pages while protecting the page being opened`() {
        val entries = listOf(
            ReaderPageCacheEntry("old", 60, 1),
            ReaderPageCacheEntry("current", 60, 2),
            ReaderPageCacheEntry("new", 60, 3)
        )

        assertEquals(
            listOf("old"),
            ReaderPageCachePolicy.evictions(entries, maxBytes = 120, protectedName = "current")
        )
    }
}
