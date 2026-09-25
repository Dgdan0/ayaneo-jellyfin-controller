package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingDownloadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAcquisitionStateTest {
    @Test fun `manual request without a grab asks the user to choose a release`() {
        val state = ReadingAcquisitionState.from(42, emptyList(), manualSelection = true)
        assertEquals(ReadingAcquisitionState.Stage.AWAITING_CHOICE, state.stage)
        assertEquals("Choose release", state.nextAction)
    }
    @Test fun `an accepted request without a transfer is still searching`() {
        val state = ReadingAcquisitionState.from(11, emptyList())
        assertEquals(ReadingAcquisitionState.Stage.SEARCHING, state.stage)
        assertFalse(state.terminal)
        assertTrue(state.message.contains("No transfer"))
        assertEquals("Open Transfers", state.nextAction)
    }

    @Test fun `completed download stays in import phase until library confirms it`() {
        val state = ReadingAcquisitionState.from(11, listOf(
            ReadingDownloadItem(seriesId = 11, status = "completed", title = "Recursion", progressPercent = 100)
        ))
        assertEquals(ReadingAcquisitionState.Stage.IMPORTING, state.stage)
        assertFalse(state.terminal)
        assertFalse(state.message.contains("Ready"))
    }

    @Test fun `only the requested BookKeeprr series determines its status`() {
        val rows = listOf(
            ReadingDownloadItem(seriesId = 10, status = "failed", title = "Other book"),
            ReadingDownloadItem(seriesId = 11, status = "downloading", progressPercent = 42, title = "Recursion")
        )
        val state = ReadingAcquisitionState.from(11, rows)
        assertEquals(ReadingAcquisitionState.Stage.DOWNLOADING, state.stage)
        assertEquals("Downloading · 42%", state.message)
    }

    @Test fun `an imported transfer supersedes the old searching confirmation`() {
        val state = ReadingAcquisitionState.from(11, listOf(
            ReadingDownloadItem(seriesId = 11, status = "imported", title = "Recursion")
        ))
        assertEquals(ReadingAcquisitionState.Stage.IMPORTED, state.stage)
        assertTrue(state.terminal)
        assertTrue(state.message.contains("Library"))
    }

    @Test fun `failed transfer points to retry instead of claiming it is searching`() {
        val state = ReadingAcquisitionState.from(11, listOf(
            ReadingDownloadItem(seriesId = 11, status = "failed", title = "Recursion", failed = true)
        ))
        assertEquals(ReadingAcquisitionState.Stage.FAILED, state.stage)
        assertTrue(state.terminal)
        assertTrue(state.message.contains("Transfers"))
    }

    @Test fun `an earlier request is recovered only from a unique exact title and type`() {
        val rows = listOf(
            ReadingDownloadItem(seriesId = 11, title = "Recursion", releaseTitle = "Recursion by Blake Crouch EPUB", contentType = "ebook", status = "imported"),
            ReadingDownloadItem(seriesId = 12, title = "Recursive Book", contentType = "ebook", status = "imported")
        )
        assertEquals(11, ReadingAcquisitionState.findSeriesId("Recursion", "Blake Crouch", "ebook", rows))
        assertEquals(0, ReadingAcquisitionState.findSeriesId("Recursion", "Tony Ballantyne", "ebook", rows))
        assertEquals(0, ReadingAcquisitionState.findSeriesId("Recursion", "", "ebook", rows))
        assertEquals(0, ReadingAcquisitionState.findSeriesId("Recursion", "Blake Crouch", "audiobook", rows))
        assertEquals(0, ReadingAcquisitionState.findSeriesId("Recursion", "Blake Crouch", "ebook", rows +
            ReadingDownloadItem(seriesId = 13, title = "Recursion", releaseTitle = "Recursion Blake Crouch EPUB", contentType = "ebook")))
    }
}
