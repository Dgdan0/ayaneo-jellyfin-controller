package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderStoragePolicyTest {
    @Test
    fun `source fallback prefers a verified download then cache then remote`() {
        val remote = ReaderSource("remote", ReaderSourceKind.REMOTE, 0, valid = true, complete = true)
        val cached = ReaderSource("cache", ReaderSourceKind.CACHE, 50, valid = true, complete = true)
        val pinned = ReaderSource("download", ReaderSourceKind.DOWNLOAD, 100, valid = true, complete = true)

        assertEquals(pinned, ReaderSourceSelector.select(listOf(remote, cached, pinned)))
        assertEquals(cached, ReaderSourceSelector.select(listOf(remote, cached)))
        assertEquals(remote, ReaderSourceSelector.select(listOf(remote)))
    }

    @Test
    fun `incomplete or invalid local files never beat a remote source`() {
        val remote = ReaderSource("remote", ReaderSourceKind.REMOTE, 0, valid = true, complete = true)
        val broken = ReaderSource("broken", ReaderSourceKind.DOWNLOAD, 100, valid = false, complete = true)
        val partial = ReaderSource("partial", ReaderSourceKind.CACHE, 20, valid = true, complete = false)

        assertEquals(remote, ReaderSourceSelector.select(listOf(broken, partial, remote)))
        assertNull(ReaderSourceSelector.select(listOf(broken, partial)))
    }

    @Test
    fun `cache eviction removes oldest temporary entries and never downloads or active files`() {
        val entries = listOf(
            ReaderCacheEntry("old", 40, 10, ReaderSourceKind.CACHE),
            ReaderCacheEntry("active", 40, 20, ReaderSourceKind.CACHE, active = true),
            ReaderCacheEntry("new", 40, 30, ReaderSourceKind.CACHE),
            ReaderCacheEntry("download", 500, 0, ReaderSourceKind.DOWNLOAD)
        )

        assertEquals(
            listOf("old"),
            ReaderCachePolicy.evictions(entries, temporaryBudgetBytes = 80)
        )
    }

    @Test
    fun `outbox keeps the latest event per user work and edition in chronological order`() {
        val values = listOf(
            event("one", "epub", 10, 0.1),
            event("two", "epub", 30, 0.3),
            event("one", "epub", 40, 0.4),
            event("one", "audio", 20, 0.2)
        )

        val compact = ReaderProgressOutbox.compact(values)

        assertEquals(listOf(20L, 30L, 40L), compact.map { it.updatedAtMillis })
        assertEquals(listOf("audio", "epub", "epub"), compact.map { it.editionFingerprint })
    }

    private fun event(work: String, edition: String, at: Long, progress: Double) =
        ReaderProgressEvent(
            userId = "user",
            workId = work,
            editionFingerprint = edition,
            locator = ReaderLocator(work, "chapter", progress),
            baseRevision = 1,
            updatedAtMillis = at
        )
}
