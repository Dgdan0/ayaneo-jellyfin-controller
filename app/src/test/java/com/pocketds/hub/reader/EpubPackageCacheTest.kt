package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EpubPackageCacheTest {
    @Test
    fun `failed replacement keeps last complete epub and removes partial file`() {
        val root = createTempDir(prefix = "epub-cache-")
        try {
            val cache = EpubPackageCache(root)
            val original = cache.install("rw_book", "12") { it.writeText("complete-edition") }

            runCatching {
                cache.install("rw_book", "12") {
                    it.writeText("partial")
                    error("network dropped")
                }
            }

            assertEquals("complete-edition", original.readText())
            assertTrue(cache.completeFile("rw_book", "12").isFile)
            assertFalse(File(root, cache.temporaryName("rw_book", "12")).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zero byte download is never promoted to a readable package`() {
        val root = createTempDir(prefix = "epub-cache-")
        try {
            val cache = EpubPackageCache(root)
            val result = runCatching { cache.install("rw_book", "12") { } }

            assertTrue(result.isFailure)
            assertFalse(cache.isComplete("rw_book", "12"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withCache(block: (EpubPackageCache, File) -> Unit) {
        val root = createTempDir(prefix = "epub-cache-")
        try { block(EpubPackageCache(root), root) } finally { root.deleteRecursively() }
    }

    /** What the transfer leaves before a promote. */
    private fun EpubPackageCache.stage(text: String) = temporaryFile("rw_book", "12").writeText(text)

    @Test
    fun `a promoted copy keeps the tag the hub sent beside it`() = withCache { cache, _ ->
        cache.stage("edition-a")
        cache.promote("rw_book", "12", "\"tag-a\"")

        assertEquals(CopyState.Tagged("\"tag-a\""), cache.copyState("rw_book", "12"))
        assertTrue(cache.etagFile("rw_book", "12").isFile)
        assertEquals("edition-a", cache.completeFile("rw_book", "12").readText())
    }

    @Test
    fun `a promote replaces the old tag with the new one`() = withCache { cache, _ ->
        cache.stage("edition-a"); cache.promote("rw_book", "12", "\"tag-a\"")
        cache.stage("edition-b"); cache.promote("rw_book", "12", "\"tag-b\"")

        assertEquals(CopyState.Tagged("\"tag-b\""), cache.copyState("rw_book", "12"))
        assertEquals("edition-b", cache.completeFile("rw_book", "12").readText())
    }

    @Test
    fun `a copy promoted without a tag is not the same as one never recorded`() = withCache { cache, _ ->
        cache.stage("edition-a")
        cache.promote("rw_book", "12", "")
        assertEquals(CopyState.Unverifiable, cache.copyState("rw_book", "12"))

        // A copy from before the tag was kept has no file at all.
        cache.etagFile("rw_book", "12").delete()
        assertEquals(CopyState.Unrecorded, cache.copyState("rw_book", "12"))
    }

    @Test
    fun `an unreadable or damaged tag file asks about the copy afresh`() = withCache { cache, _ ->
        cache.stage("edition-a"); cache.promote("rw_book", "12", "\"tag-a\"")
        for (damaged in listOf("W/\"tag-a\"", "\"tag-a", "tag-a", "\"tag\n-a\"")) {
            cache.etagFile("rw_book", "12").writeText(damaged)
            assertEquals(damaged, CopyState.Unrecorded, cache.copyState("rw_book", "12"))
        }
    }

    @Test
    fun `no complete copy is missing whatever tag is left behind`() = withCache { cache, _ ->
        cache.stage("edition-a"); cache.promote("rw_book", "12", "\"tag-a\"")
        cache.completeFile("rw_book", "12").delete()
        assertEquals(CopyState.Missing, cache.copyState("rw_book", "12"))
    }

    @Test
    fun `remove takes the copy, its tag and a partial transfer together`() = withCache { cache, root ->
        cache.stage("edition-a"); cache.promote("rw_book", "12", "\"tag-a\"")
        cache.stage("partial")
        File(cache.temporaryFile("rw_book", "12").path + ".meta").writeText("url=x")
        val other = cache.install("rw_book", "13") { it.writeText("another source") }

        cache.remove("rw_book", "12")

        assertEquals(CopyState.Missing, cache.copyState("rw_book", "12"))
        assertEquals(listOf(other.name, cache.etagFile("rw_book", "13").name).sorted(), root.list()!!.sorted())
    }

    @Test
    fun `clearing a partial transfer leaves the copy and its tag`() = withCache { cache, _ ->
        cache.stage("edition-a"); cache.promote("rw_book", "12", "\"tag-a\"")
        cache.stage("partial")

        cache.clearDownload("rw_book", "12")

        assertFalse(cache.temporaryFile("rw_book", "12").exists())
        assertEquals(CopyState.Tagged("\"tag-a\""), cache.copyState("rw_book", "12"))
    }

    @Test
    fun `a move that fails keeps the old tag`() = withCache { cache, _ ->
        // A directory where the copy goes makes the move fail; the old tag must not be lost with it.
        val target = cache.completeFile("rw_book", "12")
        target.mkdir(); File(target, "x").writeText("x")
        cache.etagFile("rw_book", "12").writeText("\"tag-a\"")
        cache.stage("edition-b")

        assertTrue(runCatching { cache.promote("rw_book", "12", "\"tag-b\"") }.isFailure)

        assertEquals("\"tag-a\"", cache.etagFile("rw_book", "12").readText())
    }

    @Test
    fun `pruning removes least recently used inactive packages first`() {
        val entries = listOf(
            EpubCacheEntry("active", 80, lastAccessMillis = 1, active = true),
            EpubCacheEntry("old", 40, lastAccessMillis = 2),
            EpubCacheEntry("new", 50, lastAccessMillis = 3)
        )

        assertEquals(listOf("old", "new"), EpubPackageCachePolicy.evict(entries, budgetBytes = 80))
    }
}
