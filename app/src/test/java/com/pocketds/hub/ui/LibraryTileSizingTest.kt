package com.pocketds.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTileSizingTest {
    @Test fun legacyHubArtworkKeepsExplicitFolderBanner() {
        assertEquals("banner", LibraryArtworkStyle.media("", "marvel-folder", "/v1/img/jellyfin/marvel-folder/Primary?tag=custom"))
        assertEquals("poster", LibraryArtworkStyle.media("", "marvel-folder", "/v1/img/jellyfin/movie-1/Primary?tag=poster"))
        assertEquals("banner", LibraryArtworkStyle.media("banner", "folder", "/v1/img/jellyfin/movie-1/Primary"))
    }

    @Test fun dailyArtworkRefreshesOnNextVisitAfterDateChanges() {
        assertTrue(LibraryArtworkRefresh.needed("2026-09-22", "2026-09-23"))
        assertTrue(LibraryArtworkRefresh.needed("", "2026-09-23"))
        assertEquals(false, LibraryArtworkRefresh.needed("2026-09-23", "2026-09-23"))
    }

    @Test fun wideCardsKeepSixteenByNineArtworkWithFocusClearance() {
        for (width in listOf(180, 220, 248, 300)) {
            val height = LibraryTileSizing.heightForWidth(width)
            assertTrue("$width × $height", kotlin.math.abs(width.toDouble() / height - 16.0 / 9.0) < .02)
        }
        assertEquals(3, LibraryTileSizing.columnsFor(780))
        assertEquals(2, LibraryTileSizing.columnsFor(480))
        assertEquals(1, LibraryTileSizing.columnsFor(300))
        assertTrue(LibraryTileSizing.cardWidthFor(780) >= 220)
        assertTrue(LibraryTileSizing.cardWidthFor(480) >= 180)
        assertTrue(LibraryTileSizing.cardWidthFor(300) >= 220)
    }
}
