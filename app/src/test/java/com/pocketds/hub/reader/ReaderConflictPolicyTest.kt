package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderConflictPolicyTest {
    @Test
    fun `a local continuation based on the server revision applies normally`() {
        val server = point("c1", 0.20, revision = 7, base = 6, updated = 100)
        val local = point("c3", 0.60, revision = 8, base = 7, updated = 200)

        assertEquals(
            ReaderProgressResolution.Use(ReaderProgressSide.LOCAL),
            ReaderConflictPolicy.resolve(local, server)
        )
    }

    @Test
    fun `a server continuation based on the local revision applies normally`() {
        val local = point("c1", 0.20, revision = 7, base = 6, updated = 100)
        val server = point("c3", 0.60, revision = 8, base = 7, updated = 200)

        assertEquals(
            ReaderProgressResolution.Use(ReaderProgressSide.SERVER),
            ReaderConflictPolicy.resolve(local, server)
        )
    }

    @Test
    fun `close positions in one chapter keep the later event`() {
        val local = point("c2", 0.40, revision = 5, base = 3, updated = 300)
        val server = point("c2", 0.42, revision = 6, base = 4, updated = 200)

        assertEquals(
            ReaderProgressResolution.Use(ReaderProgressSide.LOCAL),
            ReaderConflictPolicy.resolve(local, server)
        )
    }

    @Test
    fun `divergent chapters require a visible choice`() {
        val local = point("c2", 0.40, revision = 5, base = 3, updated = 300)
        val server = point("c8", 0.75, revision = 6, base = 4, updated = 400)

        assertEquals(ReaderProgressResolution.Prompt, ReaderConflictPolicy.resolve(local, server))
    }

    @Test
    fun `stale completion never overwrites newer active progress`() {
        val local = point("end", 1.0, revision = 4, base = 2, updated = 100, complete = true)
        val server = point("c7", 0.80, revision = 8, base = 7, updated = 400)

        assertEquals(ReaderProgressResolution.Prompt, ReaderConflictPolicy.resolve(local, server))
    }

    private fun point(
        chapter: String,
        progression: Double,
        revision: Long,
        base: Long,
        updated: Long,
        complete: Boolean = false
    ) = ReaderProgressPoint(
        locator = ReaderLocator("book", chapter, progression, chapter, complete),
        revision = revision,
        baseRevision = base,
        updatedAtMillis = updated,
        deviceName = if (updated % 2L == 0L) "Pocket DS" else "Server"
    )
}
