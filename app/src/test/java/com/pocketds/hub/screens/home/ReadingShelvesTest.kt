package com.pocketds.hub.screens.home

import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYou
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingShelvesTest {
    @Test fun startingOrFinishingRemovesWantEntryPermanentlyButKeepsNamedLists() {
        for (progress in listOf(.001, .5, 1.0)) {
            val entry = ReadingListEntry("book", "Book")
            val before = ReadingListsState(wantToRead = listOf(entry), lists = listOf(ReadingList("list", "List", listOf(entry))))
            val after = ReadingListsState.decode(before.recordProgress("book", progress).encode())
            assertTrue(after.wantToRead.isEmpty())
            assertEquals(progress, after.lists.single().items.single().lastProgress!!, 0.0)
            assertTrue(after.recordProgress("book", 0.0).wantToRead.isEmpty())
        }
    }

    @Test fun unopenedBookStaysWantedAndUnknownProgressDoesNotRemoveIt() {
        val before = ReadingListsState(wantToRead = listOf(ReadingListEntry("book", "Book")))
        for (progress in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(listOf("book"), before.recordProgress("book", progress).wantToRead.map { it.workId })
        }
    }

    @Test fun existingWantedEntriesHideStartedBooksFromRemoteLocalAndCurrentShelf() {
        val state = ReadingListsState(wantToRead = listOf(
            ReadingListEntry("remote", "Remote"), ReadingListEntry("local", "Local", lastProgress = .2),
            ReadingListEntry("current", "Current"), ReadingListEntry("completed", "Completed"), ReadingListEntry("new", "New")
        ))
        val resolved = mapOf("remote" to work("remote", .1), "completed" to work("completed").copy(progress = ReadingProgress(0.0, true)))
        val row = ReadingShelves.rows(listOf(work("current", .3)), state, resolved).first { it.id == ReadingListsState.WANT_TO_READ }
        assertEquals(listOf("new"), row.items.map { it.id })
    }

    @Test fun cannotAddStartedOrCompletedBooksToWantToReadButCanAddToNamedList() {
        for (book in listOf(work("book", .2), work("book", 1.0))) {
            val entry = ReadingListEntry.from(book)
            assertTrue(ReadingListsState().add(ReadingListsState.WANT_TO_READ, entry).wantToRead.isEmpty())
            assertEquals(1, ReadingListsState().create("List", "list").add("list", entry).lists.single().items.size)
        }
    }

    @Test fun firstRecordedReadPromotesItsReadingListWithoutASecondUpdate() {
        val state = ReadingListsState(lists = listOf(
            ReadingList("first", "First", listOf(ReadingListEntry("new", "New")), updatedAt = 1),
            ReadingList("older", "Older", listOf(ReadingListEntry("old", "Old", lastProgress = .2, lastReadAt = 100)), updatedAt = 2)
        ))
        val updated = state.recordProgress("new", .1, at = 200)
        assertEquals(200, updated.lists.first().items.first().lastReadAt)
        assertEquals(listOf("first", "older"), ReadingShelves.rows(emptyList(), updated, emptyMap()).drop(1).map { it.id })
    }

    @Test fun undatedInitialObservationDoesNotPretendTheBookWasReadJustNow() {
        val state = ReadingListsState(lists = listOf(
            ReadingList("old", "Old", listOf(ReadingListEntry("book", "Book")), updatedAt = 1)
        ))
        val updated = state.recordProgress("book", .3, at = 0)
        assertEquals(0, updated.lists.single().items.single().lastReadAt)
    }

    private fun work(id: String, percent: Double = 0.0, updated: String = "") = ReadingWork(
        id = id, title = id, artwork = "/$id", progress = ReadingProgress(percent, percent >= 1.0, updatedAt = updated)
    )

    @Test fun currentReadsAreIndividualBooksNewestFirst() {
        val collection = ReadingWork(id = "series", entityType = "collection", title = "Series", sections = listOf(
            ReadingSection(items = listOf(
                ReadingSectionItem(workId = "book1", title = "First", progress = ReadingProgress(1.0, true)),
                ReadingSectionItem(workId = "book2", title = "Second", progress = ReadingProgress(.4, updatedAt = "2026-09-22T12:00:00Z"))
            ))
        ))
        val current = ReadingShelves.current(listOf(work("older", .3, "2026-09-21T12:00:00Z"), collection))
        assertEquals(listOf("book2", "older"), current.map { it.id })
        assertFalse(current.any { it.entityType == "collection" })
    }

    @Test fun listPreservesChosenOrderAndFocusesNextUnread() {
        val entries = (1..6).map { ReadingListEntry("book$it", "Book $it") }
        val list = ReadingList("list", "Comics", entries, updatedAt = 7)
        val resolved = (1..6).associate { i -> "book$i" to work("book$i", if (i <= 2) 1.0 else 0.0) }
        val row = ReadingShelves.listRow(list, resolved)
        assertEquals((1..6).map { "book$it" }, row.items.map { it.id })
        assertEquals(2, row.nextIndex)
        assertEquals(2, row.readCount)
        assertTrue(row.items.take(2).all { it.progress?.completed == true })
    }

    @Test fun listActivitySortsListsAndWantToReadHidesCompleted() {
        val old = ReadingList("old", "Old", listOf(ReadingListEntry("a", "A")))
        val recent = ReadingList("recent", "Recent", listOf(ReadingListEntry("b", "B")))
        val state = ReadingListsState(
            wantToRead = listOf(ReadingListEntry("a", "A"), ReadingListEntry("c", "C")),
            lists = listOf(old, recent)
        )
        val resolved = mapOf(
            "a" to work("a", 1.0, "2026-09-20T12:00:00Z"),
            "b" to work("b", .5, "2026-09-22T12:00:00Z"),
            "c" to work("c")
        )
        val rows = ReadingShelves.rows(emptyList(), state, resolved)
        assertEquals(listOf("want-to-read", "recent", "old"), rows.map { it.id })
        assertEquals(listOf("c"), rows.first().items.map { it.id })
    }

    @Test fun editsAreDurableAndDeduplicateWorkIds() {
        val empty = ReadingListsState()
        val first = empty.create("Sci-fi", "one", at = 10)
            .add("one", ReadingListEntry("red", "Red Rising"), at = 11)
            .add("one", ReadingListEntry("red", "Red Rising"), at = 12)
            .add("want-to-read", ReadingListEntry("mist", "Mistborn"), at = 13)
        val decoded = ReadingListsState.decode(first.encode())
        assertEquals(listOf("red"), decoded.lists.single().items.map { it.workId })
        assertEquals(listOf("mist"), decoded.wantToRead.map { it.workId })
        assertEquals(listOf("red"), decoded.move("one", "red", 1, 14).lists.single().items.map { it.workId })
        assertTrue(decoded.remove("one", "red", 15).lists.single().items.isEmpty())
    }

    @Test fun undatedKavitaReadsRetainServerLastReadOrder() {
        assertEquals(listOf("most-recent", "older"), ReadingShelves.current(listOf(
            work("most-recent", .3), work("older", .2)
        )).map { it.id })
    }

    @Test fun progressChangeOnThisDeviceMakesAListMostRecent() {
        val state = ReadingListsState(lists = listOf(
            ReadingList("older", "Older", listOf(ReadingListEntry("a", "A", lastProgress = .2, lastReadAt = 100)), updatedAt = 100),
            ReadingList("newer", "Newer", listOf(ReadingListEntry("b", "B", lastProgress = .2, lastReadAt = 200)), updatedAt = 200)
        ))
        val updated = state.recordProgress("a", .3, at = 300)
        assertEquals(listOf("older", "newer"), ReadingShelves.rows(emptyList(), updated, emptyMap()).drop(1).map { it.id })
        assertEquals(300, updated.lists.first().items.first().lastReadAt)
        assertEquals(updated, updated.recordProgress("a", .3, at = 400))
    }

    @Test fun savedCompletionRemainsVisibleWhenHubIsOffline() {
        val list = ReadingList("comics", "Comics", listOf(
            ReadingListEntry("read", "Read", lastProgress = 1.0),
            ReadingListEntry("next", "Next", lastProgress = .2)
        ))
        val row = ReadingShelves.listRow(list, emptyMap())
        assertEquals(1, row.readCount)
        assertEquals(1, row.nextIndex)
        assertTrue(row.items.first().progress?.completed == true)
    }

    @Test fun renamingAListDoesNotOutrankMoreRecentlyReadList() {
        val recentlyRead = ReadingList("recent", "Recent", listOf(ReadingListEntry("a", "A", lastReadAt = 300)), updatedAt = 10)
        val renamed = ReadingList("renamed", "Renamed", listOf(ReadingListEntry("b", "B", lastReadAt = 100)), updatedAt = 500)
        val rows = ReadingShelves.rows(emptyList(), ReadingListsState(lists = listOf(renamed, recentlyRead)), emptyMap())
        assertEquals(listOf("recent", "renamed"), rows.drop(1).map { it.id })
    }

    private fun series(id: String, vararg books: ReadingSectionItem) = ReadingWork(
        id = id, entityType = "collection", title = id, sections = listOf(ReadingSection(items = books.toList()))
    )

    private fun book(id: String, number: String, percent: Double = 0.0, done: Boolean = false, updated: String = "",
                     availability: String = "available") = ReadingSectionItem(
        workId = if (availability == "available") id else "", title = id, number = number, availability = availability,
        progress = if (percent > 0 || done) ReadingProgress(percent, done, updatedAt = updated) else null
    )

    @Test fun currentlyReadingShowsOneCardPerSeriesAndKeepsComicsApart() {
        val redRising = series("Red Rising",
            book("Red Rising", "1", .09, updated = "2026-09-21T16:00:00Z"),
            book("Golden Son", "2", .02, updated = "2026-09-22T02:00:00Z"),
            book("Light Bringer", "6", .49, updated = "2026-09-27T03:00:00Z"))
        val comic = work("Fantastic Four", .2, "2026-09-28T10:00:00Z").copy(kind = "comic")
        val rows = ReadingShelves.rows(listOf(redRising, comic, work("Dark Matter", .03, "2026-09-25T03:00:00Z")),
            ReadingListsState(), emptyMap())
        val reading = rows.first { it.id == ReadingShelves.CURRENTLY_READING }
        assertEquals(listOf("Light Bringer", "Dark Matter"), reading.items.map { it.id })
        assertEquals("Red Rising #6", reading.items.first().cardSubtitle)
        assertEquals(listOf("Fantastic Four"), rows.first { it.id == ReadingShelves.COMICS }.items.map { it.id })
        assertEquals("Comics", rows.first { it.id == ReadingShelves.COMICS }.title)
    }

    @Test fun nextInSeriesIsTheFirstUnreadBookAfterTheLastFinishedOne() {
        val finishedOne = series("Mistborn",
            book("Final Empire", "1", done = true, updated = "2026-09-20T10:00:00Z"),
            book("Well of Ascension", "2"),
            book("Hero of Ages", "3"))
        val stillReading = series("Red Rising", book("Red Rising", "1", done = true), book("Golden Son", "2", .4))
        val nothingFinished = series("Licanius", book("Shadow", "1"))
        val nextMissing = series("Stormlight", book("Way of Kings", "1", done = true), book("Words", "2", availability = "missing"),
            book("Oathbringer", "3"))
        val next = ReadingShelves.nextInSeries(listOf(finishedOne, stillReading, nothingFinished, nextMissing))
        assertEquals(listOf("Well of Ascension", "Oathbringer"), next.map { it.id })
        assertEquals("Mistborn #2", next.first().cardSubtitle)
    }

    @Test fun builtInRowsComeFirstAndRecentlyAddedLast() {
        val state = ReadingListsState(lists = listOf(ReadingList("mine", "Mine", listOf(ReadingListEntry("x", "X")))))
        val rows = ReadingShelves.rows(listOf(work("reading", .3)), state, emptyMap(),
            next = listOf(work("next")), recent = listOf(work("new")))
        assertEquals(listOf(ReadingShelves.CURRENTLY_READING, ReadingShelves.NEXT_IN_SERIES, ReadingListsState.WANT_TO_READ,
            "mine", ReadingShelves.RECENTLY_ADDED), rows.map { it.id })
        assertTrue(rows.filter { it.id in ReadingShelves.BUILT_IN }.none { it.id == "mine" })
    }

    @Test fun storytellerTimesWithASpaceAndNoZoneAreRead() {
        assertEquals(java.time.Instant.parse("2026-09-27T03:16:47Z").toEpochMilli(), ReadingShelves.timestamp("2026-09-27 03:16:47"))
        val redRising = series("Red Rising",
            book("Red Rising", "1", .09, updated = "2026-09-21 16:09:43"),
            book("Light Bringer", "6", .49, updated = "2026-09-27 03:16:47"))
        assertEquals("Light Bringer", ReadingShelves.rows(listOf(redRising), ReadingListsState(), emptyMap()).first().items.single().id)
    }

    @Test fun cardSubtitleNamesTheAuthorOutsideASeries() {
        assertEquals("Blake Crouch", ReadingWork(title = "Dark Matter", authors = listOf("Blake Crouch")).cardSubtitle)
        assertEquals("Saga #1.5", ReadingWork(title = "Novella", series = "Saga", seriesIndex = 1.5).cardSubtitle)
    }

    @Test fun newlyCreatedUnreadListFollowsListsWithReadingActivity() {
        val active = ReadingList("active", "Active", listOf(ReadingListEntry("a", "A", lastReadAt = 100)), updatedAt = 10)
        val empty = ReadingList("empty", "Empty", updatedAt = 500)
        val rows = ReadingShelves.rows(emptyList(), ReadingListsState(lists = listOf(empty, active)), emptyMap())
        assertEquals(listOf("active", "empty"), rows.drop(1).map { it.id })
    }

    @Test
    fun `your series fans the book you are on in front and says where you are`() {
        fun book(n: Int, pct: Double?, done: Boolean = false, at: String = "") = ReadingSectionItem(
            workId = "b$n", title = "Book $n", number = "$n", artwork = "/art/$n",
            progress = pct?.let { ReadingProgress(percentage = it, completed = done, updatedAt = at) })
        val redRising = ReadingWork(id = "rr", entityType = "collection", title = "Red Rising", bookCount = 6,
            sections = listOf(ReadingSection(items = listOf(book(1, 0.1, at = "2026-09-21 16:09:43"), book(2, 0.02), book(3, null),
                book(4, null), book(5, null), book(6, 0.49, at = "2026-09-27 03:16:47")))),
            continueAt = com.pocketds.hub.model.ReadingContinue(number = "6", artwork = "/art/6"))
        val mistborn = ReadingWork(id = "mb", entityType = "collection", title = "Mistborn", bookCount = 3,
            sections = listOf(ReadingSection(items = listOf(book(1, 0.02, at = "2026-09-21 13:48:57"), book(2, null), book(3, null)))))
        val unread = ReadingWork(id = "lt", entityType = "collection", title = "Licanius", bookCount = 1,
            sections = listOf(ReadingSection(items = listOf(book(1, null)))))
        val finished = ReadingWork(id = "f", entityType = "collection", title = "Done",
            sections = listOf(ReadingSection(items = listOf(book(1, 1.0, done = true)))))
        val shelf = ReadingShelves.yourSeries(listOf(mistborn, unread, redRising, finished))
        assertEquals(listOf("Red Rising", "Mistborn"), shelf.map { it.title })
        assertEquals("6 books · on #6", shelf[0].line)
        // The Series view's fan, three slots here: the last books, the one you are on lit at the right.
        assertEquals(listOf("/art/4", "/art/5", "/art/6"), shelf[0].plan.slots.map { it.book.cover })
        assertEquals(2, shelf[0].plan.slots.indexOfFirst { it.lit })
        assertEquals("/art/6", shelf[0].cover)
        // Book 1 is the one you are on in a series just begun: lit in the first slot, first in front on the left.
        assertEquals(listOf("/art/1", "/art/2", "/art/3"), shelf[1].plan.slots.map { it.book.cover })
        assertTrue(shelf[1].plan.slots[0].lit && shelf[1].plan.slots[0].front)
        assertEquals("3 books · on #1", shelf[1].line)
    }

    // ------------------------------------------------------------------ reading status (#63)

    @Test fun `a book put down is off Currently reading with its place kept, and Reading brings it back`() {
        val started = work("a", .4, "2026-09-21T12:00:00Z")
        assertEquals(listOf("a"), ReadingShelves.current(listOf(started)).map { it.id })
        assertTrue(ReadingShelves.current(listOf(started.copy(status = "not-reading"))).isEmpty())
        assertEquals(listOf("a"), ReadingShelves.current(listOf(started.copy(status = "reading"))).map { it.id })
        // The row that is made of it leaves too.
        val rows = ReadingShelves.rows(listOf(started.copy(status = "not-reading")), ReadingListsState(), emptyMap())
        assertTrue(rows.none { it.id == ReadingShelves.CURRENTLY_READING })
    }

    @Test fun `a book finished by hand or by import is not being read, though its place stops short of the end`() {
        assertTrue(ReadingShelves.current(listOf(work("a", .4).copy(status = "finished"))).isEmpty())
        assertTrue(ReadingShelves.current(listOf(work("a", .4).copy(you = ReadingYou(status = "read", finished = "2025-09")))).isEmpty())
    }

    @Test fun `a book chosen as Reading stays when its place is the end, which is reading it again`() {
        val again = work("a", 1.0).copy(status = "reading")
        assertEquals(listOf("a"), ReadingShelves.current(listOf(again)).map { it.id })
        assertTrue(ReadingShelves.current(listOf(work("a", 1.0))).isEmpty())
    }

    @Test fun `the books of a series carry their status into Currently reading`() {
        val collection = series("Red Rising",
            ReadingSectionItem(workId = "one", title = "One", progress = ReadingProgress(.3), status = "not-reading"),
            ReadingSectionItem(workId = "two", title = "Two", progress = ReadingProgress(.5), status = "reading"),
            ReadingSectionItem(workId = "three", title = "Three", progress = ReadingProgress(.2), status = "finished"))
        val current = ReadingShelves.current(listOf(collection))
        assertEquals(listOf("two"), current.map { it.id })
        assertEquals("reading", current.single().status)
    }

    @Test fun `a series is not being read when the only book begun was put down`() {
        fun part(id: String, n: String, percent: Double, status: String = "") =
            ReadingSectionItem(workId = id, title = id, number = n, artwork = "/art/$id", progress = if (percent > 0) ReadingProgress(percent) else null, status = status)
        val putDown = series("Mistborn", part("m1", "1", .3, "not-reading"), part("m2", "2", 0.0))
        assertTrue(ReadingShelves.yourSeries(listOf(putDown)).isEmpty())
        val reading = series("Mistborn", part("m1", "1", .3, "not-reading"), part("m2", "2", .1))
        assertEquals(listOf("Mistborn"), ReadingShelves.yourSeries(listOf(reading)).map { it.title })
        assertEquals("2", ReadingShelves.onNumber(reading))
        // Every book finished, however: an import that says read has no place to count.
        val allRead = series("Mistborn", part("m1", "1", 0.0, "finished"), part("m2", "2", 0.0, "finished"))
        assertTrue(ReadingShelves.yourSeries(listOf(allRead)).isEmpty())
        // One finished by status and the rest untouched: the series is begun.
        val begun = series("Mistborn", part("m1", "1", 0.0, "finished"), part("m2", "2", 0.0))
        assertEquals(listOf("Mistborn"), ReadingShelves.yourSeries(listOf(begun)).map { it.title })
    }

    @Test fun `next in series counts a finish by status and does not offer a book put down`() {
        fun part(id: String, n: String, status: String = "", percent: Double = 0.0) =
            ReadingSectionItem(workId = id, title = id, number = n, availability = "available", status = status,
                progress = if (percent > 0) ReadingProgress(percent) else null)
        val imported = series("Mistborn", part("a", "1", "finished"), part("b", "2"), part("c", "3"))
        assertEquals(listOf("b"), ReadingShelves.nextInSeries(listOf(imported)).map { it.id })
        val skip = series("Stormlight", part("a", "1", "finished"), part("b", "2", "not-reading", .2), part("c", "3"))
        assertEquals(listOf("c"), ReadingShelves.nextInSeries(listOf(skip)).map { it.id })
        // A book being read is the series' place; a book put down is not.
        val reading = series("Red Rising", part("a", "1", "finished"), part("b", "2", "reading", .4), part("c", "3"))
        assertTrue(ReadingShelves.nextInSeries(listOf(reading)).isEmpty())
    }

    @Test fun `Want to read leaves out a book the hub says is being read, finished or put down`() {
        val state = ReadingListsState(wantToRead = listOf("w", "r", "f", "n", "none").map { ReadingListEntry(it, it) })
        val resolved = mapOf(
            "w" to work("w").copy(status = "want"), "r" to work("r").copy(status = "reading"),
            "f" to work("f").copy(status = "finished"), "n" to work("n").copy(status = "not-reading"), "none" to work("none")
        )
        val row = ReadingShelves.rows(emptyList(), state, resolved).first { it.id == ReadingListsState.WANT_TO_READ }
        assertEquals(listOf("w", "none"), row.items.map { it.id })
    }

    @Test fun `a list counts finished books by their status too`() {
        val list = ReadingList("l", "L", listOf(ReadingListEntry("a", "A"), ReadingListEntry("b", "B"), ReadingListEntry("c", "C")))
        val resolved = mapOf("a" to work("a").copy(status = "finished"), "b" to work("b"), "c" to work("c"))
        val row = ReadingShelves.listRow(list, resolved)
        assertEquals(1, row.readCount)
        assertEquals(1, row.nextIndex)
    }
}
