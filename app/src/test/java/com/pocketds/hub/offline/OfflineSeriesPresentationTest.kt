package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.SeriesPlayTargetResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSeriesPresentationTest {
    @Test
    fun `offline header retains remembered series metadata when the hub is away`() {
        val remembered = LibraryItem(id = "drake", type = "series", title = "Drake & Josh", overview = "Saved")
        val manifest = LibraryItem(id = "drake", type = "series", title = "Drake and Josh")

        assertEquals(remembered, OfflineSeriesPresentation.detail(null, remembered, manifest))
        assertEquals(manifest, OfflineSeriesPresentation.detail(null, null, manifest))
        assertEquals(remembered, OfflineSeriesPresentation.detail(remembered, manifest, null))
    }

    @Test
    fun `server continue target is playable when that episode is downloaded`() {
        val one = episode("s2e11", 2, 11)
        val two = episode("s2e12", 2, 12)

        val view = OfflineSeriesPresentation.resolve(
            rows = listOf(one, two),
            progress = emptyMap(),
            serverTarget = SeriesPlayTargetResponse("drake", "resume", two.manifest.item)
        )

        val target = view.suggestion as OfflineSeriesSuggestion.Available
        assertEquals("s2e12", target.download.manifest.item.id)
        assertEquals("resume", target.kind)
        assertTrue(target.playableOffline)
        assertEquals(listOf(2), view.seasons.map { it.number })
    }

    @Test
    fun `server continue target explains when the episode is not downloaded`() {
        val local = episode("s1e1", 1, 1)
        val remote = item("s2e12", 2, 12)

        val view = OfflineSeriesPresentation.resolve(
            rows = listOf(local),
            progress = emptyMap(),
            serverTarget = SeriesPlayTargetResponse("drake", "next", remote)
        )

        val target = view.suggestion as OfflineSeriesSuggestion.NotDownloaded
        assertEquals("s2e12", target.item.id)
        assertEquals("next", target.kind)
        assertFalse(target.playableOffline)
        assertTrue(target.message.contains("not downloaded"))
        assertEquals(listOf(1), view.seasons.map { it.number })
    }

    @Test
    fun `local progress remains a usable fallback without a server target`() {
        val first = episode("s1e1", 1, 1)
        val second = episode("s1e2", 1, 2)
        val progress = mapOf("s1e1" to OfflineCatalogProgress(1_190_000, 1_200_000, 1))

        val view = OfflineSeriesPresentation.resolve(listOf(first, second), progress, serverTarget = null)

        val target = view.suggestion as OfflineSeriesSuggestion.Available
        assertEquals("s1e2", target.download.manifest.item.id)
        assertEquals("next", target.kind)
        assertTrue(target.playableOffline)
    }

    @Test
    fun `only downloaded seasons are exposed even when server target is elsewhere`() {
        val special = episode("special", 0, 1)
        val seasonTwo = episode("s2e1", 2, 1)
        val missing = item("s4e1", 4, 1)

        val view = OfflineSeriesPresentation.resolve(
            listOf(special, seasonTwo), emptyMap(), SeriesPlayTargetResponse("drake", "next", missing)
        )

        assertEquals(listOf(0, 2), view.seasons.map { it.number })
    }

    private fun episode(id: String, season: Int, episode: Int) = OfflineDownload(
        id = id,
        batchId = "batch",
        userId = "user",
        manifest = OfflineManifest(clientItemKey = id, item = item(id, season, episode)),
        state = OfflineState.COMPLETE,
        bytesDownloaded = 100,
        totalBytes = 100,
        localPath = "/offline/$id.mkv",
        error = "",
        attempts = 0,
        speedBytesPerSecond = 0,
        sortOrder = episode,
        updatedAt = 1
    )

    private fun item(id: String, season: Int, episode: Int) = LibraryItem(
        id = id,
        type = "episode",
        title = "Episode $episode",
        subtitle = "S${season}E${episode} · Episode $episode",
        seriesId = "drake",
        seriesTitle = "Drake & Josh",
        seasonId = "season-$season",
        seasonNumber = season,
        indexNumber = episode,
        runtimeSeconds = 1_200
    )
}
