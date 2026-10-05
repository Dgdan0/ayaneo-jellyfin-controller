package com.pocketds.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryArtworkRefreshTest {
    @Test fun dailyArtworkRefreshesOnNextVisitAfterDateChanges() {
        assertTrue(LibraryArtworkRefresh.needed("2026-09-22", "2026-09-23"))
        assertTrue(LibraryArtworkRefresh.needed("", "2026-09-23"))
        assertEquals(false, LibraryArtworkRefresh.needed("2026-09-23", "2026-09-23"))
    }
}
