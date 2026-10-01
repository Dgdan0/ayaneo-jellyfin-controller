package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingAuthorRef
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingBookFactsTest {
    private val pierce = listOf(ReadingAuthorRef("ra_1", "Pierce Brown"))

    @Test fun `a book says where it sits in its series, its year and pages`() {
        val lightBringer = ReadingWork(title = "Light Bringer", authors = listOf("Pierce Brown"), authorRefs = pierce,
            series = "Red Rising", seriesIndex = 6.0, year = 2023,
            editions = listOf(ReadingEdition(kind = "ebook", pageCount = 735)))
        assertEquals("Book 6 of Red Rising · 2023 · 735 pages · 49% read", ReadingBookFacts.line(lightBringer, "49% read"))
    }

    @Test fun `an audiobook says how long it is and who reads it`() {
        val well = ReadingWork(title = "Well of Ascension", authorRefs = listOf(ReadingAuthorRef("ra_2", "Brandon Sanderson")),
            series = "Mistborn", seriesIndex = 2.0,
            editions = listOf(ReadingEdition(kind = "audiobook", durationMs = 29L * 3_600_000 + 50 * 60_000, narrator = "Michael Kramer")))
        assertEquals("Book 2 of Mistborn · 29h 50m · read by Michael Kramer", ReadingBookFacts.line(well, null))
    }

    @Test fun `without author links the author is named in the line`() {
        val darkMatter = ReadingWork(title = "Dark Matter", authors = listOf("Blake Crouch"), year = 2016)
        assertEquals("Blake Crouch · 2016", ReadingBookFacts.line(darkMatter, null))
        assertNull(ReadingBookFacts.place(darkMatter))
        assertEquals("Saga", ReadingBookFacts.place(ReadingWork(series = "Saga")))
        assertEquals("Book 1.5 of Saga", ReadingBookFacts.place(ReadingWork(series = "Saga", seriesIndex = 1.5)))
    }
}
