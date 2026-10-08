package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingWork
import org.junit.Assert.*
import org.junit.Test

/** The words and the rule of Start over (#60): when a book offers it and what it asks first. */
class ReadingStartOverTest {
    @Test fun `it is offered to a book with a place or one that is finished, and to nothing else`() {
        assertTrue(ReadingStartOver.offered(hasPlace = true, finished = false))
        assertTrue(ReadingStartOver.offered(hasPlace = false, finished = true))
        assertFalse(ReadingStartOver.offered(hasPlace = false, finished = false))
    }

    @Test fun `a book has a place when the hub says it has been started, or this device kept one`() {
        assertFalse(ReadingStartOver.hasPlace(ReadingWork(id = "w"), kept = false))
        assertTrue(ReadingStartOver.hasPlace(ReadingWork(id = "w", progress = ReadingProgress(0.3)), kept = false))
        assertTrue(ReadingStartOver.hasPlace(ReadingWork(id = "w", progress = ReadingProgress(1.0, true)), kept = false))
        assertTrue("a place waiting to be sent counts", ReadingStartOver.hasPlace(ReadingWork(id = "w"), kept = true))
        assertFalse(ReadingStartOver.hasPlace(ReadingWork(id = "w", progress = ReadingProgress(0.0)), kept = false))
    }

    @Test fun `the question names the book and the formats it has, and what stays`() {
        val dune = ReadingWork(id = "w", title = "Dune", availability = listOf("ebook", "audiobook", "readaloud"))
        assertEquals("Start Dune over?", ReadingStartOver.confirmTitle(dune))
        assertEquals("Your place in the ebook, audiobook and read along is forgotten on every device. Your rating, notes and bookmarks stay.",
            ReadingStartOver.confirmDetail(dune))
        assertEquals("Your place in the ebook and audiobook is forgotten on every device. Your rating, notes and bookmarks stay.",
            ReadingStartOver.confirmDetail(dune.copy(availability = listOf("ebook", "audiobook"))))
        assertEquals("Your place in the audiobook is forgotten on every device. Your rating, notes and bookmarks stay.",
            ReadingStartOver.confirmDetail(dune.copy(availability = listOf("audiobook"))))
        assertEquals("Your place is forgotten on every device. Your rating, notes and bookmarks stay.",
            ReadingStartOver.confirmDetail(ReadingWork(id = "w", title = "Dune")))
        // A format only an edition says it has still counts.
        val byEdition = ReadingWork(id = "w", title = "Dune", editions = listOf(ReadingEdition(kind = "book", availability = "available"), ReadingEdition(kind = "audiobook", availability = "available")))
        assertTrue(ReadingStartOver.confirmDetail(byEdition).startsWith("Your place in the ebook and audiobook"))
    }

    @Test fun `a comic goes back to unread, issue by issue`() {
        val saga = ReadingWork(id = "w", title = "Saga", kind = "comic")
        assertEquals("Start Saga over?", ReadingStartOver.confirmTitle(saga))
        assertEquals("Your place is forgotten and every issue is unread again, on every device. Your lists stay.", ReadingStartOver.confirmDetail(saga))
        assertTrue(ReadingStartOver.isComic(saga.copy(kind = "manga")))
        assertFalse(ReadingStartOver.isComic(ReadingWork(id = "w", kind = "book")))
    }

    @Test fun `the harmless answer is the first, and says what it keeps`() {
        assertEquals("Keep my place", ReadingStartOver.KEEP)
        assertEquals("Start over", ReadingStartOver.ACTION)
    }

    @Test fun `what is said after it`() {
        assertEquals("Started over · back to not started", ReadingStartOver.done())
        assertEquals("Start over could not be done · the hub is busy", ReadingStartOver.failed("the hub is busy"))
    }
}
