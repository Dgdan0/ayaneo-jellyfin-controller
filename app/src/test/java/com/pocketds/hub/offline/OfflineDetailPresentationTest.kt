package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.ui.DetailSnapshot
import org.junit.Assert.*
import org.junit.Test

class OfflineDetailPresentationTest {
    private fun episode(id: String, number: Int) = OfflineDownload(
        id = id, batchId = "batch", userId = "dan",
        manifest = OfflineManifest(item = LibraryItem(id = id, type = "episode", title = "Episode $number",
            seriesId = "series", seriesTitle = "Example", seasonId = "s1", seasonNumber = 1,
            indexNumber = number, runtimeSeconds = 1200)),
        state = OfflineState.COMPLETE, bytesDownloaded = 10, totalBytes = 10, localPath = "/local/$id",
        error = "", attempts = 0, speedBytesPerSecond = 0, sortOrder = number, updatedAt = 1
    )
    private fun savedTarget(id: String, number: Int, recordedAt: Long = 10) = DetailSnapshot(
        item = LibraryItem(id = "series", type = "series", title = "Example"),
        target = SeriesPlayTargetResponse(kind = "next", item = episode(id, number).manifest.item),
        targetRecordedAt = recordedAt
    )

    @Test fun `missing known continuation never silently plays another download`() {
        val view = OfflineDetailPresentation.resolve(listOf(episode("e1", 1)), emptyMap(), savedTarget("e2", 2))
        assertNull(view.playable)
        assertEquals("e2", view.missing?.id)
    }

    @Test fun `a downloaded known next episode can be played`() {
        val view = OfflineDetailPresentation.resolve(listOf(episode("e1", 1), episode("e2", 2)), emptyMap(), savedTarget("e2", 2))
        assertEquals("e2", view.playable?.row?.id)
        assertNull(view.missing)
    }

    @Test fun `a newer local partial watch wins over a stale server suggestion`() {
        val view = OfflineDetailPresentation.resolve(listOf(episode("e1", 1)),
            mapOf("e1" to OfflineCatalogProgress(300_000, 1_200_000, 20)), savedTarget("e2", 2))
        assertEquals("e1", view.playable?.row?.id)
        assertEquals(300_000L, view.playable?.positionMillis)
        assertNull(view.missing)
    }

    @Test fun `without a saved server target the UI says the suggestion is local`() {
        val view = OfflineDetailPresentation.resolve(listOf(episode("e1", 1)), emptyMap(), null)
        assertTrue(view.localSuggestion)
        assertEquals("e1", view.playable?.row?.id)
    }

    @Test fun `finished local target does not resume because the saved target says next`() {
        val view = OfflineDetailPresentation.resolve(listOf(episode("e1", 1)),
            mapOf("e1" to OfflineCatalogProgress(1_200_000, 1_200_000, 20)), savedTarget("e1", 1))
        assertNull(view.playable)
        assertNull(view.missing)
    }

    @Test fun `displayed resume matches the local playback plan not a metadata-only snapshot`() {
        val downloaded = episode("e1", 1).let {
            it.copy(manifest = it.manifest.copy(item = it.manifest.item.copy(positionSeconds = 120)))
        }
        val snapshot = savedTarget("e1", 1).let {
            val target = requireNotNull(it.target)
            it.copy(target = target.copy(item = target.item.copy(positionSeconds = 600)))
        }
        val view = OfflineDetailPresentation.resolve(listOf(downloaded), emptyMap(), snapshot)
        assertEquals(120_000L, view.playable?.positionMillis)
        assertTrue(view.localSuggestion)
    }
}
