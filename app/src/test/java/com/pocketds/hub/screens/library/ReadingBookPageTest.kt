package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingCommunity
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYou
import com.pocketds.hub.state.FormModel
import java.time.YearMonth
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A book's page under the owner's layout "1" (#39): the words of its facts, ratings and finish
 * date, the stars, the "When did you finish?" rows, what each tap writes, the formats that open it
 * and the Resume button. The page draws what these say.
 */
class ReadingBookPageTest {
    private val now = YearMonth.of(2026, 10)

    private fun edition(kind: String, state: String = "available", id: String = "1", pages: Int = 0, ms: Long = 0, narrator: String = "") =
        ReadingEdition(kind = kind, availability = state, source = "storyteller", sourceItemId = id, pageCount = pages, durationMs = ms, narrator = narrator)

    // ------------------------------------------------------------------ the facts line

    @Test fun `the facts are the year, the pages, the audiobook's length and what readers think`() {
        val work = ReadingWork(year = 2006, editions = listOf(edition("ebook", pages = 541), edition("audiobook", ms = 88_680_000, narrator = "Michael Kramer")),
            community = ReadingCommunity(4.45, 1_204_331, "hardcover"))
        assertEquals("2006 · 541 pages · 24h 38m · 4.5 from readers", ReadingBookPage.facts(work))
        // What is not known drops out, the narrator and the genres are not on this line.
        assertEquals("2006 · 541 pages · 24h 38m", ReadingBookPage.facts(work.copy(community = null)))
        assertEquals("", ReadingBookPage.facts(ReadingWork(genres = listOf("Fantasy"))))
        assertEquals("3.9 from readers", ReadingBookPage.facts(ReadingWork(community = ReadingCommunity(3.9, 0, "goodreads"))))
    }

    @Test fun `readers' rating is one decimal, rounded half up, and nothing while it is not known`() {
        assertEquals("4.5 from readers", ReadingBookPage.community(ReadingCommunity(4.45)))
        assertEquals("4.4 from readers", ReadingBookPage.community(ReadingCommunity(4.449)))
        assertEquals("4.0 from readers", ReadingBookPage.community(ReadingCommunity(4.0)))
        assertEquals("5.0 from readers", ReadingBookPage.community(ReadingCommunity(4.96)))
        assertNull(ReadingBookPage.community(ReadingCommunity(0.0, 12, "hardcover")))
        assertNull(ReadingBookPage.community(null))
    }

    @Test fun `the genres are one quiet line`() {
        assertEquals("Fantasy · Epic fantasy · Magic systems",
            ReadingBookPage.genres(ReadingWork(genres = listOf("Fantasy", " Epic fantasy ", "", "Magic systems"))))
        assertEquals("", ReadingBookPage.genres(ReadingWork()))
    }

    @Test fun `only a book of its own takes the layout, not a series or a comic`() {
        assertTrue(ReadingBookPage.isBook(ReadingWork(kind = "book")))
        assertTrue(ReadingBookPage.isBook(ReadingWork(kind = "audiobook")))
        assertFalse(ReadingBookPage.isBook(ReadingWork(entityType = "collection")))
        assertFalse(ReadingBookPage.isBook(ReadingWork(kind = "comic")))
        assertFalse(ReadingBookPage.isBook(ReadingWork(kind = "manga")))
    }

    // ------------------------------------------------------------------ under the cover: you

    @Test fun `the finish month and the times read, with a nudge to rate a finished book`() {
        assertEquals("Finished Sep 2025", ReadingBookPage.finished(ReadingYou(rating = 5, finished = "2025-09")))
        assertEquals("Finished Sep 2025 · rate it?", ReadingBookPage.finished(ReadingYou(finished = "2025-09")))
        assertEquals("Finished Sep 2025 · 2nd time", ReadingBookPage.finished(ReadingYou(rating = 4, finished = "2025-09", readCount = 2)))
        // A count alone is no finish and asks nothing.
        assertEquals("3rd time", ReadingBookPage.finished(ReadingYou(rating = 4, readCount = 3)))
        // The import's status says it was read; so does the page.
        assertEquals("Finished · rate it?", ReadingBookPage.finished(ReadingYou(status = "read", readCount = 1)))
        assertEquals("Finished · rate it?", ReadingBookPage.finished(null, finished = true))
        assertEquals("Finished", ReadingBookPage.finished(ReadingYou(rating = 3), finished = true))
        assertNull(ReadingBookPage.finished(null))
        assertNull(ReadingBookPage.finished(ReadingYou(rating = 3, status = "to-read", shelves = listOf("cosmere"))))
        assertNull(ReadingBookPage.finished(ReadingYou(status = "currently-reading")))
        // A first read is not "1st time".
        assertEquals("Finished Oct 2026", ReadingBookPage.finished(ReadingYou(rating = 2, finished = "2026-10", readCount = 1)))
    }

    @Test fun `months are written short, and ordinals as people say them`() {
        assertEquals("Sep 2025", ReadingBookPage.monthLabel("2025-09"))
        assertEquals("Oct 2026", ReadingBookPage.monthLabel("2026-10"))
        assertNull(ReadingBookPage.monthLabel(""))
        assertNull(ReadingBookPage.monthLabel("2025-13"))
        assertNull(ReadingBookPage.monthLabel("last year"))
        assertEquals(listOf("2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "101st", "111th"),
            listOf(2, 3, 4, 11, 12, 13, 21, 22, 101, 111).map(ReadingBookPage::ordinal))
    }

    @Test fun `the shelves are in the order the export gave them, and none is no line`() {
        assertEquals("cosmere · favorites", ReadingBookPage.shelves(ReadingYou(shelves = listOf("cosmere", "favorites"))))
        assertNull(ReadingBookPage.shelves(ReadingYou(rating = 5)))
        assertNull(ReadingBookPage.shelves(null))
    }

    // ------------------------------------------------------------------ the stars

    @Test fun `a star chosen rates, and the same star again takes the rating away`() {
        assertEquals(4, ReadingStars.after(0, 4))
        assertEquals(2, ReadingStars.after(4, 2))
        assertNull(ReadingStars.after(4, 4))
        assertEquals(5, ReadingStars.after(0, 9))
        assertEquals(1, ReadingStars.after(3, 0))
    }

    @Test fun `the cursor starts on the rating, else the middle, and stays among the five`() {
        assertEquals(4, ReadingStars.cursor(4))
        assertEquals(3, ReadingStars.cursor(0))
        assertEquals(3, ReadingStars.cursor(7))
        assertEquals(1, ReadingStars.step(1, -1))
        assertEquals(5, ReadingStars.step(5, 1))
        assertEquals(4, ReadingStars.step(3, 1))
    }

    @Test fun `a finger lands on a star by fifths of the row`() {
        assertEquals(1, ReadingStars.starAt(0f, 100f))
        assertEquals(1, ReadingStars.starAt(19.9f, 100f))
        assertEquals(2, ReadingStars.starAt(20f, 100f))
        assertEquals(5, ReadingStars.starAt(99f, 100f))
        assertEquals(5, ReadingStars.starAt(100f, 100f))
        assertEquals(1, ReadingStars.starAt(-5f, 100f))
        assertEquals(1, ReadingStars.starAt(50f, 0f))
        // What Ⓐ does under the cursor, for the hint bar.
        assertEquals("Rate 4 stars", ReadingStars.hint(0, 4))
        assertEquals("Rate 1 star", ReadingStars.hint(3, 1))
        assertEquals("Remove rating", ReadingStars.hint(4, 4))
        assertEquals("Your rating, 4 of 5 stars", ReadingStars.description(4))
        assertEquals("Rate this book", ReadingStars.description(0))
        assertEquals("Star 2 of 5, not rated", ReadingStars.description(0, cursor = 2))
        assertEquals("Star 2 of 5, your rating is 4", ReadingStars.description(4, cursor = 2))
    }

    // ------------------------------------------------------------------ "When did you finish?"

    @Test fun `the panel opens on this month, or on the month kept for the book`() {
        assertEquals(now, ReadingFinished.preset(null, now))
        assertEquals(YearMonth.of(2025, 9), ReadingFinished.preset(ReadingYou(finished = "2025-09"), now))
        // A month that has not come, or that is no month, is this one.
        assertEquals(now, ReadingFinished.preset(ReadingYou(finished = "2027-01"), now))
        assertEquals(now, ReadingFinished.preset(ReadingYou(finished = "soon"), now))
        assertEquals(now, ReadingFinished.preset(ReadingYou(finished = "1899-12"), now))
    }

    @Test fun `the rows are a month and a year, set to the preset, with Cancel and Mark finished under them`() {
        val rows = ReadingFinished.rows(now, now)
        assertEquals(listOf("month", "year", "cancel", "mark"), rows.map { it.id })
        val month = rows[0] as com.pocketds.hub.state.FormRow.Choice
        val year = rows[1] as com.pocketds.hub.state.FormRow.Choice
        assertEquals("October", month.value)
        assertEquals(12, month.options.size)
        assertEquals("2026", year.value)
        assertEquals("1976", year.options.first())
        assertEquals(now, ReadingFinished.chosen(rows, now))
        // A month long ago, kept for the book, is in the year row even beyond the usual fifty years.
        val old = ReadingFinished.rows(YearMonth.of(1962, 3), now)
        assertEquals("1962", (old[1] as com.pocketds.hub.state.FormRow.Choice).options.first())
        assertEquals(YearMonth.of(1962, 3), ReadingFinished.chosen(old, now))
        assertEquals(YearMonth.of(1962, 3), ReadingFinished.preset(ReadingYou(finished = "1962-03"), now))
    }

    @Test fun `left and right step a row, and a month that has not come is this month`() {
        val model = FormModel(ReadingFinished.rows(now, now))
        // Month: left is September, right twice is December, which the hub would refuse.
        model.adjust(-1)
        assertEquals(YearMonth.of(2026, 9), ReadingFinished.chosen(model.rows(), now))
        assertFalse(ReadingFinished.needsSettling(model.rows(), now))
        model.adjust(1); model.adjust(1)
        assertTrue(ReadingFinished.needsSettling(model.rows(), now))
        assertEquals(now, ReadingFinished.chosen(model.rows(), now))
        // Settled, the rows say the month that counts, and the cursor stays on the row.
        model.replace(ReadingFinished.rows(ReadingFinished.chosen(model.rows(), now), now))
        assertEquals("October", (model.current() as com.pocketds.hub.state.FormRow.Choice).value)
        assertEquals("month", model.current()?.id)
        // Year: down a year, then December is fine.
        model.move(1)
        model.adjust(-1)
        model.move(-1)
        repeat(2) { model.adjust(1) }
        assertFalse(ReadingFinished.needsSettling(model.rows(), now))
        assertEquals(YearMonth.of(2025, 12), ReadingFinished.chosen(model.rows(), now))
    }

    // ------------------------------------------------------------------ what each tap writes

    @Test fun `a rating is one number, and taking it away is null`() {
        assertEquals("""{"rating":4}""", ReadingYouEdits.rate(4).toJson())
        assertEquals("""{"rating":null}""", ReadingYouEdits.rate(null).toJson())
    }

    @Test fun `finishing a book never read sends the month alone, and the hub counts it once`() {
        assertEquals("""{"finished":"2026-10"}""", ReadingYouEdits.finish(null, now, finishedNow = false).toJson())
        assertEquals("""{"finished":"2026-10"}""", ReadingYouEdits.finish(ReadingYou(shelves = listOf("cosmere"), status = "to-read"), now, false).toJson())
    }

    @Test fun `finishing a book read before, and not finished now, is reading it again`() {
        val before = ReadingYou(finished = "2025-09", readCount = 1)
        assertEquals("""{"finished":"2026-10","readCount":2}""", ReadingYouEdits.finish(before, now, finishedNow = false).toJson())
        assertEquals("""{"finished":"2026-10","readCount":3}""", ReadingYouEdits.finish(ReadingYou(status = "read", readCount = 2), now, false).toJson())
        // The import said read and gave no count: it was read once.
        assertEquals("""{"finished":"2026-10","readCount":2}""", ReadingYouEdits.finish(ReadingYou(status = "read"), now, false).toJson())
        assertEquals("""{"finished":"2026-10","readCount":99}""", ReadingYouEdits.finish(ReadingYou(finished = "2020-01", readCount = 99), now, false).toJson())
    }

    @Test fun `a book still marked finished has its date put right, and its count stays`() {
        assertEquals("""{"finished":"2026-09"}""",
            ReadingYouEdits.finish(ReadingYou(finished = "2026-10", readCount = 2), YearMonth.of(2026, 9), finishedNow = true).toJson())
    }

    @Test fun `unread puts back what the page had before, else takes the finish away`() {
        val finished = ReadingYou(finished = "2026-10", readCount = 1)
        assertEquals("""{"finished":null,"readCount":null}""", ReadingYouEdits.unfinish(finished, null)!!.toJson())
        val again = ReadingYou(finished = "2026-10", readCount = 2)
        assertEquals("""{"finished":"2025-09","readCount":1}""",
            ReadingYouEdits.unfinish(again, ReadingYou(finished = "2025-09", readCount = 1))!!.toJson())
        // Nothing to put back where nothing was changed.
        assertNull(ReadingYouEdits.unfinish(null, null))
        assertNull(ReadingYouEdits.unfinish(ReadingYou(rating = 4), null))
        assertNull(ReadingYouEdits.unfinish(finished, finished))
    }

    // ------------------------------------------------------------------ the formats

    @Test fun `the formats are Ebook, Audiobook and Read along, each opening its own mode`() {
        val work = ReadingWork(editions = listOf(edition("ebook"), edition("audiobook"), edition("readaloud")))
        val chips = ReadingFormatChips.of(work, null)
        assertEquals(listOf("Ebook", "Audiobook", "Read along"), chips.map { it.label })
        assertEquals(listOf(ReadingEntryMode.READ, ReadingEntryMode.LISTEN, ReadingEntryMode.READ_ALONG), chips.map { it.choice?.mode })
        assertTrue(chips.all { it.ready })
    }

    @Test fun `a format the book lacks, or has not finished, is quiet and says why`() {
        val noEbook = ReadingFormatChips.of(ReadingWork(editions = listOf(edition("audiobook"))), null)
        assertEquals(listOf(false, true, false), noEbook.map { it.ready })
        assertNull(noEbook[0].choice)
        assertEquals("No ebook for this book yet", noEbook[0].note)
        val aligning = ReadingFormatChips.of(ReadingWork(editions = listOf(edition("ebook"), edition("audiobook"), edition("readaloud", "processing"))), null)
        assertEquals(FormatReadiness.PENDING, aligning[2].readiness)
        assertFalse(aligning[2].ready)
        assertEquals("Read along is still being aligned", aligning[2].note)
        assertEquals("No audiobook for this book yet", ReadingFormatChips.of(ReadingWork(editions = listOf(edition("ebook"))), null)[1].note)
    }

    @Test fun `the narration you last used is the one the audiobook opens`() {
        val work = ReadingWork(editions = listOf(edition("ebook"), edition("audiobook", id = "a1"), edition("audiobook", id = "a2")))
        val remembered = ReadingEntryPreference(ReadingEntryMode.LISTEN, "a2")
        assertEquals("a2", ReadingFormatChips.of(work, remembered)[1].choice?.audio?.sourceItemId)
        assertEquals("a1", ReadingFormatChips.of(work, null)[1].choice?.audio?.sourceItemId)
    }

    @Test fun `a series page and an empty book have no formats to open`() {
        assertTrue(ReadingFormatChips.of(ReadingWork(entityType = "collection"), null).isEmpty())
        assertTrue(ReadingFormatChips.of(ReadingWork(), null).none { it.ready })
    }

    // ------------------------------------------------------------------ Resume

    @Test fun `resume says where you are in the format it opens`() {
        val third = ReadingProgress(0.32)
        assertEquals("Resume · Chapter 14 · 32%", ReadingResumeLabel.of(ReadingEntryMode.READ, third, "Chapter 14"))
        assertEquals("Resume · 32%", ReadingResumeLabel.of(ReadingEntryMode.LISTEN, third, null))
        assertEquals("Resume · 32%", ReadingResumeLabel.of(ReadingEntryMode.READ_ALONG, third, " "))
        // Started is never 0%.
        assertEquals("Resume · 1%", ReadingResumeLabel.of(ReadingEntryMode.READ, ReadingProgress(0.004), null))
    }

    @Test fun `a book not started says what the format does, and a finished one offers it again`() {
        assertEquals("Read book", ReadingResumeLabel.of(ReadingEntryMode.READ, null, null))
        assertEquals("Listen", ReadingResumeLabel.of(ReadingEntryMode.LISTEN, ReadingProgress(0.0), null))
        assertEquals("Read along", ReadingResumeLabel.of(ReadingEntryMode.READ_ALONG, null, null))
        // A narration picked from the menu is named until the book is started, and never once there is a place.
        assertEquals("Listen · Bob", ReadingResumeLabel.of(ReadingEntryMode.LISTEN, null, null, narration = "Bob"))
        assertEquals("Read along · Bob", ReadingResumeLabel.of(ReadingEntryMode.READ_ALONG, null, null, narration = "Bob"))
        assertEquals("Read book", ReadingResumeLabel.of(ReadingEntryMode.READ, null, null, narration = "Bob"))
        assertEquals("Resume · 32%", ReadingResumeLabel.of(ReadingEntryMode.LISTEN, ReadingProgress(0.32), null, narration = "Bob"))
        val done = ReadingProgress(1.0, completed = true)
        assertEquals("Read again", ReadingResumeLabel.of(ReadingEntryMode.READ, done, "Chapter 40"))
        assertEquals("Listen again", ReadingResumeLabel.of(ReadingEntryMode.LISTEN, done, null))
        assertEquals("Read along again", ReadingResumeLabel.of(ReadingEntryMode.READ_ALONG, done, null))
    }

    @Test fun `a chapter is the locator's title, never a file's name`() {
        assertEquals("Chapter 14", ReadingResumeLabel.chapter(buildJsonObject { put("title", "Chapter 14"); put("href", "text/part0014.html") }))
        assertEquals("Prologue", ReadingResumeLabel.chapter(buildJsonObject { put("title", "  Prologue ") }))
        assertNull(ReadingResumeLabel.chapter(buildJsonObject { put("href", "text/part0014.html") }))
        assertNull(ReadingResumeLabel.chapter(buildJsonObject { put("title", "text/part0014.html") }))
        assertNull(ReadingResumeLabel.chapter(buildJsonObject { put("title", "part0014.xhtml") }))
        assertNull(ReadingResumeLabel.chapter(buildJsonObject { put("title", "   ") }))
        assertNull(ReadingResumeLabel.chapter(buildJsonObject { put("title", "x".repeat(61)) }))
        assertNull(ReadingResumeLabel.chapter(null))
    }

    // ------------------------------------------------------------------ the ⋯ menu

    @Test fun `the menu offers Finished, Want to read, a list and the offline copy, in that order`() {
        val entries = ReadingMoreMenu.entries(null, finished = false, wanted = false)
        assertEquals(listOf("finished", "want", "lists", "offline-remove", "server-remove"), entries.map { it.id })
        assertEquals(listOf("Finished", "Want to read", "Add to a list", "Remove offline copy", "Delete from server…"), entries.map { it.label })
        assertTrue(entries.last().danger)
        assertEquals("Say when you finished it", entries[0].detail)
    }

    @Test fun `a book with several narrations offers the choice, after Finished`() {
        val entries = ReadingMoreMenu.entries(null, finished = false, wanted = false, narrations = true)
        assertEquals(listOf("finished", "narration", "want", "lists", "offline-remove", "server-remove"), entries.map { it.id })
        assertEquals("Choose narration", entries[1].label)
    }

    @Test fun `a finished book can be marked unread, and a wanted one taken off the list`() {
        val entries = ReadingMoreMenu.entries(ReadingYou(finished = "2025-09"), finished = true, wanted = true)
        assertEquals(listOf("finished", "unread", "want", "lists", "offline-remove", "server-remove"), entries.map { it.id })
        assertEquals("Finished Sep 2025 · change the date", entries[0].detail)
        assertEquals("Mark unread", entries[1].label)
        assertEquals("Remove from Want to read", entries[2].label)
        assertNotNull(entries.firstOrNull { it.id == "lists" })
    }
}
