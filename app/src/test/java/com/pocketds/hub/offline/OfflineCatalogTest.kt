package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCatalogTest {
    @Test
    fun `titles group episodes by series and sort items and episodes`() {
        val values = listOf(
            episode("a2", "attack", "Attack on Titan", "season-1", 1, 2),
            movie("m1", "Zodiac"),
            episode("a1", "attack", "Attack on Titan", "season-1", 1, 1)
        )

        val result = OfflineCatalog.titles(values)

        assertEquals(listOf("Attack on Titan", "Zodiac"), result.map { it.title })
        assertTrue(result.first().isSeries)
        assertEquals(listOf(1, 2), result.first().rows.map { it.manifest.item.indexNumber })
        assertFalse(result.last().isSeries)
        assertEquals("m1", result.last().key)
    }

    @Test
    fun `seasons include only stored rows for the selected series`() {
        val values = listOf(
            episode("s1e4", "lanterns", "Lanterns", "season-1", 1, 4),
            episode("special", "lanterns", "Lanterns", "specials", 0, 1),
            episode("other", "other-series", "Other", "season-3", 3, 1)
        )

        val result = OfflineCatalog.seasons("lanterns", values)

        assertEquals(listOf(0, 1), result.map { it.number })
        assertEquals(listOf("special", "s1e4"), result.flatMap { it.rows }.map { it.id })
    }

    private fun episode(
        id: String,
        seriesId: String,
        seriesTitle: String,
        seasonId: String,
        season: Int,
        episode: Int
    ) = row(
        id,
        LibraryItem(
            id = id,
            type = "episode",
            title = "Episode $episode",
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            seasonId = seasonId,
            seasonNumber = season,
            indexNumber = episode
        )
    )

    private fun movie(id: String, title: String) = row(
        id,
        LibraryItem(id = id, type = "movie", title = title)
    )

    private fun row(id: String, item: LibraryItem) = OfflineDownload(
        id = id,
        batchId = "batch-$id",
        userId = "user",
        manifest = OfflineManifest(clientItemKey = id, item = item),
        state = OfflineState.COMPLETE,
        bytesDownloaded = 100,
        totalBytes = 100,
        localPath = "/offline/$id.mkv",
        error = "",
        attempts = 0,
        speedBytesPerSecond = 0,
        sortOrder = 0,
        updatedAt = 1
    )
}
