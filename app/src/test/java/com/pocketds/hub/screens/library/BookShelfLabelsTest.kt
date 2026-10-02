package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookShelfLabelsTest {
    @Test
    fun `a book in a series says its number, progress and unusual formats`() {
        assertEquals("#2 · Audio", SeriesBookLabels.subtitle("2", true, null, listOf("audiobook")))
        assertEquals("#1 · 40%", SeriesBookLabels.subtitle("1", true, ReadingProgress(percentage = 0.4), listOf("ebook")))
        assertEquals("#3 · Completed · Ebook + audio",
            SeriesBookLabels.subtitle("3", true, ReadingProgress(completed = true), listOf("ebook", "audiobook")))
        assertEquals("#2 · Missing", SeriesBookLabels.subtitle("2", false, null, emptyList()))
        // A plain ebook is the usual case and says nothing about its format.
        assertNull(SeriesBookLabels.formats(listOf("ebook")))
        assertEquals("Read along", SeriesBookLabels.formats(listOf("ebook", "readaloud")))
    }

    @Test
    fun `an author's shelf counts series and books`() {
        assertEquals("2 series · 6 books", AuthorLabels.shelf(2, 6, 2))
        assertEquals("2 books", AuthorLabels.shelf(0, 2, 2))
        assertEquals("1 series · 7 books", AuthorLabels.shelf(1, 7, 1))
        // An older hub sends only the item total.
        assertEquals("3 books", AuthorLabels.shelf(0, 0, 3))
    }

    @Test
    fun `an authors page says how far through their books you are`() {
        val started = com.pocketds.hub.model.ReadingProgress(percentage = .1)
        val done = com.pocketds.hub.model.ReadingProgress(percentage = 1.0, completed = true)
        assertEquals("2 in progress · 1 finished", AuthorLabels.reading(listOf(started, started, done, null)))
        assertEquals("All 2 finished", AuthorLabels.reading(listOf(done, done)))
        assertEquals(null, AuthorLabels.reading(listOf(null, com.pocketds.hub.model.ReadingProgress())))
    }
}
