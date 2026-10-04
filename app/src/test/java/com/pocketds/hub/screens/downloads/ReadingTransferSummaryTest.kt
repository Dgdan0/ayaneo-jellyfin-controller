package com.pocketds.hub.screens.downloads

import com.pocketds.hub.model.ReadingDownloadItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTransferSummaryTest {
    @Test fun `transfer stages do not call a downloaded book ready before import`() {
        assertEquals("Downloaded · adding to Library", ReadingTransferSummary.stageLabel("completed", false))
        assertEquals("Ready in Library", ReadingTransferSummary.stageLabel("imported", false))
        assertEquals(false, ReadingTransferSummary.showProgress("completed", false))
        assertEquals(true, ReadingTransferSummary.showProgress("downloading", false))
    }

    @Test fun `reading transfers group by the available content category without reordering a category`() {
        val items = listOf(
            ReadingDownloadItem(id = "comic", contentType = "comic"),
            ReadingDownloadItem(id = "book-a", contentType = "ebook"),
            ReadingDownloadItem(id = "manga", contentType = "manga"),
            ReadingDownloadItem(id = "book-b", contentType = "ebook")
        )
        assertEquals(listOf("book-a", "book-b", "comic", "manga"),
            ReadingTransferSummary.grouped(items).map { it.id })
        assertEquals("Books", ReadingTransferSummary.groupLabel("ebook"))
    }
    @Test fun `imported transfer never says it is waiting for the fetcher`() {
        assertEquals("Ready in Library", ReadingTransferSummary.fallback("imported", false))
        assertEquals("Adding to Library", ReadingTransferSummary.fallback("completed", false))
    }

    @Test fun `a transfer's chip says its state in a word or two`() {
        assertEquals("Failed", ReadingTransferSummary.chipLabel("downloading", true))
        assertEquals("In library", ReadingTransferSummary.chipLabel("imported", false))
        assertEquals("Importing", ReadingTransferSummary.chipLabel("completed", false))
        assertEquals("Downloading", ReadingTransferSummary.chipLabel("downloading", false))
        assertEquals("Retry ready", ReadingTransferSummary.chipLabel("retry_pending", false))
        assertEquals("Awaiting choice", ReadingTransferSummary.chipLabel("awaiting_choice", false))
    }

    @Test fun `failed and queued transfers have actionable summaries`() {
        assertEquals("Needs attention", ReadingTransferSummary.fallback("failed", true))
        assertEquals("Waiting for BookKeeprr", ReadingTransferSummary.fallback("queued", false))
    }
}
