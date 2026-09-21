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
