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

    @Test
    fun `progress names the page when the book's length is known`() {
        val book = ReadingWork(title = "Light Bringer", progress = com.pocketds.hub.model.ReadingProgress(percentage = 0.4945),
            editions = listOf(ReadingEdition(kind = "ebook", pageCount = 735)))
        assertEquals("49% · page 363 of 735", ReadingBookFacts.progress(book))
        assertEquals("49% read", ReadingBookFacts.progress(book.copy(editions = emptyList())))
        assertEquals("Finished", ReadingBookFacts.progress(book.copy(progress = com.pocketds.hub.model.ReadingProgress(completed = true))))
        assertNull(ReadingBookFacts.progress(book.copy(progress = null)))
    }

    @Test
    fun `a series page says which book and how many are finished`() {
        fun book(n: Int, pct: Double?, done: Boolean = false) = com.pocketds.hub.model.ReadingSectionItem(
            workId = "b$n", number = "$n", progress = pct?.let { com.pocketds.hub.model.ReadingProgress(percentage = it, completed = done) })
        val series = ReadingWork(entityType = "collection", bookCount = 6, sections = listOf(com.pocketds.hub.model.ReadingSection(
            items = listOf(book(1, 1.0, done = true), book(2, 0.3), book(3, null)))),
            continueAt = com.pocketds.hub.model.ReadingContinue(number = "2"))
        assertEquals("On #2 · 1 of 3 finished", ReadingBookFacts.seriesProgress(series))
        assertNull(ReadingBookFacts.seriesProgress(series.copy(continueAt = null,
            sections = listOf(com.pocketds.hub.model.ReadingSection(items = listOf(book(1, null)))))))
    }

    @Test
    fun `a comic says its kind on the cover and how far in under it`() {
        assertEquals("Comic", ReadingBookFacts.kindTag("comic"))
        assertEquals("Manga", ReadingBookFacts.kindTag("manga"))
        assertEquals(null, ReadingBookFacts.kindTag("book"))
        assertEquals("Not started", ReadingBookFacts.comicLine(null))
        // One page of a 4,437-page run is still "started".
        assertEquals("1% read", ReadingBookFacts.comicLine(com.pocketds.hub.model.ReadingProgress(percentage = 0.000225)))
        assertEquals("35% read", ReadingBookFacts.comicLine(com.pocketds.hub.model.ReadingProgress(percentage = 0.347)))
        assertEquals("Finished", ReadingBookFacts.comicLine(com.pocketds.hub.model.ReadingProgress(percentage = 1.0, completed = true)))
    }

    @Test
    fun `a comic run is counted in issues and each issue is named`() {
        val issue = com.pocketds.hub.model.ReadingSectionItem(sourceItemId = "8338", title = "7", number = "7", kind = "comic", pageCount = 36)
        val run = com.pocketds.hub.model.ReadingWork(id = "w", kind = "comic", year = 1961,
            sections = listOf(com.pocketds.hub.model.ReadingSection(items = listOf(issue, issue.copy(number = "8", title = "8")))))
        assertEquals(listOf("2 issues"), ReadingBookFacts.length(run))
        assertEquals("Issue 7", ReadingBookFacts.issueTitle(issue, "comic"))
        assertEquals("Chapter 7", ReadingBookFacts.issueTitle(issue, "manga"))
        assertEquals("Annual 1965", ReadingBookFacts.issueTitle(issue.copy(title = "Annual 1965"), "comic"))
        assertEquals("36 pages · Not started", ReadingBookFacts.issueLine(issue))
    }
}
