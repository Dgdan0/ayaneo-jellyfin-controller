package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class AnnotationBookTest {
    private fun note(id: String, color: String = "yellow", text: String = "", at: Long = 100, quote: String = "bird", synced: Long = 0) =
        ReadingAnnotation(id, color, text, "OEBPS/chapter-1.xhtml", AnnotationQuote("a ", quote, "."), null, at, at, false, synced)

    @Test fun aHighlightMadeHereIsShownAtOnceAndWaitsToBeSent() {
        val book = AnnotationBook()
        val kept = book.put(note("an_1", at = 0), now = 5_000)
        assertEquals(5_000L, kept.updatedAt)
        assertEquals(5_000L, kept.createdAt)
        assertEquals(listOf("an_1"), book.live.map { it.id })
        assertEquals(listOf("an_1"), book.pending().map { it.id })
    }

    @Test fun anEditIsStampedAfterWhatItEditsEvenWhenTheClockHasNotMovedOn() {
        val book = AnnotationBook(listOf(note("an_1", at = 9_000)))
        val edited = book.put(note("an_1", "blue", at = 0), now = 4_000)
        assertEquals(9_001L, edited.updatedAt)
        assertEquals(9_000L, edited.createdAt)
    }

    @Test fun aRemovedHighlightIsATombstoneWithNoNoteAndIsNotShown() {
        val book = AnnotationBook(listOf(note("an_1", text = "a thought", at = 100)))
        val tomb = book.remove("an_1", now = 500)!!
        assertTrue(tomb.deleted)
        assertEquals("", tomb.note)
        assertEquals("bird", tomb.quote.highlight)
        assertTrue(book.live.isEmpty())
        assertEquals(listOf("an_1"), book.pendingIds)
        assertNull(book.remove("an_unknown", 600))
    }

    @Test fun theHubsNewerVersionReplacesOursAndAnOlderOneDoesNot() {
        val book = AnnotationBook(listOf(note("an_1", "yellow", at = 100, synced = 1)))
        assertTrue(book.merge(listOf(note("an_1", "pink", at = 200, synced = 2))))
        assertEquals("pink", book["an_1"]!!.color)
        assertFalse(book.merge(listOf(note("an_1", "green", at = 150, synced = 3))))
        assertEquals("pink", book["an_1"]!!.color)
        assertEquals(3L, book.cursor)
    }

    @Test fun anEditWaitingToBeSentBeatsAnOlderVersionFromTheHubAndLosesToANewerOne() {
        val book = AnnotationBook(listOf(note("an_1", "yellow", at = 100)))
        book.put(note("an_1", "blue"), now = 300)
        book.merge(listOf(note("an_1", "pink", at = 200, synced = 5)))
        assertEquals("blue", book["an_1"]!!.color)
        assertEquals(listOf("an_1"), book.pendingIds)
        // Another device edited it later than we did: ours would lose at the hub, so it is dropped.
        book.merge(listOf(note("an_1", "green", at = 400, synced = 6)))
        assertEquals("green", book["an_1"]!!.color)
        assertTrue(book.pendingIds.isEmpty())
    }

    @Test fun theHubsVersionWinsATieSoTwoDevicesAgree() {
        val book = AnnotationBook(listOf(note("an_1", "yellow", at = 100)))
        book.put(note("an_1", "blue"), now = 200)
        book.merge(listOf(note("an_1", "pink", at = 200, synced = 4)))
        assertEquals("pink", book["an_1"]!!.color)
        assertTrue(book.pendingIds.isEmpty())
    }

    @Test fun aTombstoneFromTheHubRemovesWhatWeShowAndAnUndoNewerThanItBringsItBack() {
        val book = AnnotationBook(listOf(note("an_1", at = 100)))
        val tomb = note("an_1", at = 300, synced = 7).copy(deleted = true)
        assertTrue(book.merge(listOf(tomb)))
        assertTrue(book.live.isEmpty())
        // Not redrawn for a tombstone it already has.
        assertFalse(book.merge(listOf(tomb)))
        val back = book.put(note("an_1", "pink"), now = 250)
        assertFalse(back.deleted)
        assertEquals(301L, back.updatedAt)
        assertEquals(listOf("an_1"), book.live.map { it.id })
    }

    @Test fun whatIsOnlyANewStampOfTheSameThingDoesNotCallForARedraw() {
        val book = AnnotationBook(listOf(note("an_1", at = 100, synced = 1)))
        assertFalse(book.merge(listOf(note("an_1", at = 100, synced = 9))))
        assertEquals(9L, book.cursor)
    }

    @Test fun whenTheHubTookAnEditItIsNoLongerWaitingUnlessItWasEditedAgainMeanwhile() {
        val book = AnnotationBook()
        val first = book.put(note("an_1", "yellow"), now = 100)
        val stored = first.copy(syncedAt = 50)
        // Edited again while the first was on its way.
        val second = book.put(note("an_1", "blue"), now = 200)
        book.sent("an_1", first, stored)
        assertEquals(listOf("an_1"), book.pendingIds)
        assertEquals("blue", book["an_1"]!!.color)
        book.sent("an_1", second, second.copy(syncedAt = 60))
        assertTrue(book.pendingIds.isEmpty())
        assertEquals(60L, book.cursor)
    }

    @Test fun whenTheHubKeptSomethingNewerThatIsWhatWeKeep() {
        val book = AnnotationBook()
        val sent = book.put(note("an_1", "yellow"), now = 100)
        val held = note("an_1", "green", at = 900, synced = 70)
        book.sent("an_1", sent, held)
        assertEquals("green", book["an_1"]!!.color)
        assertTrue(book.pendingIds.isEmpty())
    }

    @Test fun anEditTheHubRefusesForeverStopsWaitingButStaysHere() {
        val book = AnnotationBook()
        book.put(note("an_1"), now = 100)
        book.refused("an_1")
        assertTrue(book.pendingIds.isEmpty())
        assertEquals(1, book.live.size)
    }

    @Test fun theOutboxIsSentOldestFirstSoADeleteAndItsUndoKeepTheirOrder() {
        val book = AnnotationBook()
        book.put(note("an_b"), now = 300)
        book.put(note("an_a"), now = 100)
        book.remove("an_a", now = 400)
        assertEquals(listOf("an_b", "an_a"), book.pending().map { it.id })
    }

    @Test fun aSnapshotComesBackAsTheSameBook() {
        val book = AnnotationBook(listOf(note("an_1", at = 100, synced = 5)), cursor = 5)
        book.put(note("an_2"), now = 200)
        val again = book.snapshot().book()
        assertEquals(book.all, again.all)
        assertEquals(book.pendingIds, again.pendingIds)
        assertEquals(5L, again.cursor)
    }

    @Test fun aWaitingIdWithNoHighlightBehindItIsNotKept() {
        val book = AnnotationBook(listOf(note("an_1")), pending = listOf("an_1", "an_gone"))
        assertEquals(listOf("an_1"), book.pendingIds)
    }
}
