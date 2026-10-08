package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.library.ReadingBookFacts.CoverMark
import com.pocketds.hub.screens.library.ReadingBookFacts.CoverShape
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a book's cover says of its formats (#54), wherever a book is shown on its own: the Books grid, Home's rows,
 * a series page's row, an author's page. Ebook only is the tall cover, audiobook only a square one, and a book that
 * is both is the tall cover with a small round mark: a book with sound once it is aligned for read along, else headphones.
 */
class ReadingCoverTest {
    private fun shape(kind: String, vararg formats: String) = ReadingBookFacts.coverShape(kind, formats.toList())
    private fun mark(kind: String, vararg formats: String) = ReadingBookFacts.formatMark(kind, formats.toList())

    @Test fun `an ebook is the tall cover, unmarked`() {
        assertEquals(CoverShape.TALL, shape("book", "ebook"))
        assertEquals(CoverMark.NONE, mark("book", "ebook"))
    }

    @Test fun `an audiobook is a square cover, unmarked`() {
        assertEquals(CoverShape.SQUARE, shape("audiobook", "audiobook"))
        assertEquals(CoverMark.NONE, mark("audiobook", "audiobook"))
        // What a book can be opened as decides, not what the hub called it.
        assertEquals(CoverShape.SQUARE, shape("book", "audiobook"))
    }

    @Test fun `an ebook with an audiobook is the tall cover with headphones until it is aligned`() {
        assertEquals(CoverShape.TALL, shape("book", "ebook", "audiobook"))
        assertEquals(CoverMark.HEADPHONES, mark("book", "ebook", "audiobook"))
        // However the hub orders them.
        assertEquals(CoverMark.HEADPHONES, mark("book", "audiobook", "ebook"))
    }

    @Test fun `once aligned for read along the mark is the book with sound`() {
        assertEquals(CoverShape.TALL, shape("book", "ebook", "audiobook", "readaloud"))
        assertEquals(CoverMark.READ_ALONG, mark("book", "ebook", "audiobook", "readaloud"))
        // A read-along edition is both an ebook and an audiobook, whatever else is listed with it.
        assertEquals(CoverShape.TALL, shape("book", "readaloud"))
        assertEquals(CoverMark.READ_ALONG, mark("book", "readaloud"))
        assertEquals(CoverMark.READ_ALONG, mark("book", "audiobook", "readaloud"))
    }

    @Test fun `a comic or manga keeps its tall cover and its kind pill, with no mark`() {
        listOf("comic", "manga").forEach { kind ->
            assertEquals(CoverShape.TALL, shape(kind, kind))
            assertEquals(CoverMark.NONE, mark(kind, kind))
            // Even with something else listed: the pill is what says what it is.
            assertEquals(CoverShape.TALL, shape(kind, "audiobook"))
            assertEquals(CoverMark.NONE, mark(kind, "ebook", "audiobook"))
        }
    }

    @Test fun `a hub that says nothing of formats leaves the kind to decide`() {
        assertEquals(CoverShape.SQUARE, shape("audiobook"))
        assertEquals(CoverMark.NONE, mark("audiobook"))
        assertEquals(CoverShape.TALL, shape("book"))
        assertEquals(CoverMark.NONE, mark("book"))
        assertEquals(CoverShape.TALL, shape("", "something-new"))
        assertEquals(CoverMark.NONE, mark("", "something-new"))
    }

    @Test fun `a work reads its formats from the hub's list, else from the editions it has`() {
        val both = ReadingWork(kind = "book", availability = listOf("ebook", "audiobook"))
        assertEquals(CoverMark.HEADPHONES, ReadingBookFacts.formatMark(both))
        assertEquals(CoverMark.READ_ALONG, ReadingBookFacts.formatMark(both.copy(availability = listOf("ebook", "audiobook", "readaloud"))))
        val onlyEditions = ReadingWork(kind = "book", editions = listOf(
            ReadingEdition(kind = "book", availability = "available"), ReadingEdition(kind = "audiobook", availability = "available")))
        assertEquals(CoverShape.TALL, ReadingBookFacts.coverShape(onlyEditions))
        assertEquals(CoverMark.HEADPHONES, ReadingBookFacts.formatMark(onlyEditions))
        assertEquals(CoverShape.SQUARE, ReadingBookFacts.coverShape(ReadingWork(kind = "audiobook", availability = listOf("audiobook"))))
        // An edition that is not here (still being aligned, missing) is not a format of the book.
        val aligning = ReadingWork(kind = "book", editions = listOf(ReadingEdition(kind = "book", availability = "available"),
            ReadingEdition(kind = "audiobook", availability = "available"), ReadingEdition(kind = "readaloud", availability = "queued")))
        assertEquals(CoverMark.HEADPHONES, ReadingBookFacts.formatMark(aligning))
    }

    @Test fun `a book in a series row is decided the same way`() {
        assertEquals(CoverShape.SQUARE, ReadingBookFacts.coverShape(ReadingSectionItem(kind = "audiobook", formats = listOf("audiobook"))))
        assertEquals(CoverMark.READ_ALONG,
            ReadingBookFacts.formatMark(ReadingSectionItem(kind = "book", formats = listOf("ebook", "audiobook", "readaloud"))))
        assertEquals(CoverMark.NONE, ReadingBookFacts.formatMark(ReadingSectionItem(kind = "book", formats = listOf("ebook"))))
    }

    @Test fun `a square cover sits lower in the tall one's place, so a row's captions line up`() {
        // 2:3 tall: a card 100dp across is 150dp tall; a square one 100dp tall sits at its foot, 50dp down.
        assertEquals(0f, ReadingBookFacts.coverLift(CoverShape.TALL, 100f), 0.001f)
        assertEquals(50f, ReadingBookFacts.coverLift(CoverShape.SQUARE, 100f), 0.001f)
        assertEquals(150f, ReadingBookFacts.tallHeight(100f), 0.001f)
    }
}
