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

    @Test fun `an audiobook while you listen says what it is, its place, its author and its reader`() {
        val well = ReadingWork(title = "Well of Ascension", authorRefs = listOf(ReadingAuthorRef("ra_2", "Brandon Sanderson")),
            series = "Mistborn", seriesIndex = 2.0)
        assertEquals("Audiobook · Book 2 · Mistborn", ReadingBookFacts.listeningEyebrow(well))
        assertEquals("Brandon Sanderson · read by Michael Kramer", ReadingBookFacts.listeningLine(well, " Michael Kramer "))
        // Before the book's details arrive, and outside a series.
        assertEquals("Audiobook", ReadingBookFacts.listeningEyebrow(null))
        assertEquals("Audiobook", ReadingBookFacts.listeningEyebrow(ReadingWork(title = "Dark Matter")))
        assertEquals("read by Jon Lindstrom", ReadingBookFacts.listeningLine(null, "Jon Lindstrom"))
        assertEquals("Blake Crouch", ReadingBookFacts.listeningLine(ReadingWork(authors = listOf("Blake Crouch")), ""))
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
        // Rounded down, as every reading percent is (Fmt.readingPercent).
        assertEquals("34% read", ReadingBookFacts.comicLine(com.pocketds.hub.model.ReadingProgress(percentage = 0.347)))
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

    @Test
    fun `a page's eyebrow says what it is and where it belongs`() {
        val lightBringer = ReadingWork(title = "Light Bringer", series = "Red Rising", seriesIndex = 6.0)
        assertEquals("Book 6 · Red Rising", ReadingBookFacts.eyebrow(lightBringer))
        assertEquals("Red Rising", ReadingBookFacts.eyebrow(lightBringer.copy(seriesIndex = 0.0)))
        assertEquals("Series · Pierce Brown", ReadingBookFacts.eyebrow(ReadingWork(entityType = "collection", title = "Red Rising", authors = listOf("Pierce Brown"))))
        assertEquals("Series", ReadingBookFacts.eyebrow(ReadingWork(entityType = "collection")))
        assertEquals("Comic · My Marvelous Year", ReadingBookFacts.eyebrow(ReadingWork(kind = "comic"), "My Marvelous Year"))
        assertEquals("Manga", ReadingBookFacts.eyebrow(ReadingWork(kind = "manga")))
        assertEquals("Book", ReadingBookFacts.eyebrow(ReadingWork(title = "Dark Matter")))
        assertEquals("Audiobook", ReadingBookFacts.eyebrow(ReadingWork(kind = "audiobook")))
    }

    @Test
    fun `a book also being read says whose or which it is and how far in`() {
        val darkMatter = ReadingWork(title = "Dark Matter", authors = listOf("Blake Crouch"),
            progress = com.pocketds.hub.model.ReadingProgress(percentage = 0.0325))
        assertEquals("Blake Crouch · 3%", ReadingBookFacts.miniLine(darkMatter))
        // Opened is never 0%.
        assertEquals("Mistborn #1 · 1%", ReadingBookFacts.miniLine(ReadingWork(series = "Mistborn", seriesIndex = 1.0,
            progress = com.pocketds.hub.model.ReadingProgress(percentage = 0.004))))
        assertEquals("Blake Crouch · Finished", ReadingBookFacts.miniLine(darkMatter.copy(
            progress = com.pocketds.hub.model.ReadingProgress(percentage = 1.0, completed = true))))
        assertEquals("Blake Crouch", ReadingBookFacts.miniLine(darkMatter.copy(progress = null)))
    }

    @Test
    fun `a series' continue card says which book, how far and which page`() {
        val point = com.pocketds.hub.model.ReadingContinue(title = "Light Bringer", number = "6", percentage = 0.4945)
        assertEquals("Book 6 · 49% · page 363 of 735", ReadingBookFacts.continueLine(point, "book", 735))
        assertEquals("Book 6 · 49%", ReadingBookFacts.continueLine(point, "book", 0))
        assertEquals("Issue 51 · 1%", ReadingBookFacts.continueLine(point.copy(number = "51", percentage = 0.002), "comic", 0))
        assertEquals("Book 1", ReadingBookFacts.continueLine(point.copy(number = "1", percentage = 0.0), "book", 300))
    }

    @Test
    fun `formats come in one order whatever order the hub sends`() {
        assertEquals(listOf("ebook", "audiobook", "readaloud"),
            ReadingBookFacts.formats(ReadingWork(availability = listOf("readaloud", "ebook", "audiobook"))))
        assertEquals(listOf("audiobook"), ReadingBookFacts.formats(ReadingWork(availability = listOf("audiobook"))))
        // A book's own page carries editions rather than the list.
        assertEquals(listOf("ebook", "audiobook"), ReadingBookFacts.formats(ReadingWork(editions = listOf(
            ReadingEdition(kind = "book", availability = "available"),
            ReadingEdition(kind = "audiobook", availability = "available"),
            ReadingEdition(kind = "readaloud", availability = "missing")))))
    }
}
