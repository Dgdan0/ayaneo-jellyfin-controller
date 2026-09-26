package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.OfflineSource
import com.pocketds.hub.model.OfflineSubtitle
import com.pocketds.hub.model.PlaybackTrack
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class OfflineSubtitleSyncTest {
    private val original = OfflineManifest(
        item = LibraryItem(id = "movie-id"),
        source = OfflineSource(id = "source-id", sizeBytes = 1_000)
    )

    @Test fun selectedSubtitleCanJoinTheSameDownloadedVideo() {
        val fresh = original.copy(subtitles = listOf(OfflineSubtitle(
            track = PlaybackTrack(index = 3, language = "heb", external = true)
        )))
        validateSubtitleRefresh(original, fresh, 1_000, "he")
    }

    @Test fun changedVideoCannotAttachItsSubtitleToTheOldDownload() {
        val fresh = original.copy(source = original.source.copy(sizeBytes = 2_000))
        assertFailure("online video differs") { validateSubtitleRefresh(original, fresh, 1_000, "") }
    }

    @Test fun missingSelectedTrackRemainsPendingUntilJellyfinIndexesIt() {
        val fresh = original.copy(subtitles = listOf(OfflineSubtitle(
            track = PlaybackTrack(index = 2, language = "eng", external = true)
        )))
        assertFailure("Waiting for Jellyfin") { validateSubtitleRefresh(original, fresh, 1_000, "he") }
    }

    @Test fun allAlreadyOnlineLanguagesMustBeIndexedBeforeTheOfflineCopyIsCurrent() {
        val hebrewOnly=original.copy(subtitles=listOf(OfflineSubtitle(
            track=PlaybackTrack(index=3,language="heb",external=true)
        )))
        assertFailure("Waiting for Jellyfin") {
            validateSubtitleRefresh(original,hebrewOnly,1_000,"English,Hebrew")
        }
        val both=hebrewOnly.copy(subtitles=hebrewOnly.subtitles+OfflineSubtitle(
            track=PlaybackTrack(index=4,language="eng",external=true)
        ))
        validateSubtitleRefresh(original,both,1_000,"English,Hebrew")
    }

    private fun assertFailure(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected a refused subtitle refresh")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains(message))
        }
    }
}
