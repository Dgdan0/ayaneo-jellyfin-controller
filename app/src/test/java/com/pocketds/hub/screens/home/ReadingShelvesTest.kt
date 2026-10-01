package com.pocketds.hub.screens.home

import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
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
        assertEquals("Book 6 · Red Rising", reading.items.first().cardSubtitle)
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
        assertEquals("Book 2 · Mistborn", next.first().cardSubtitle)
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
        assertEquals("Book 1.5 · Saga", ReadingWork(title = "Novella", series = "Saga", seriesIndex = 1.5).cardSubtitle)
    }

    @Test fun newlyCreatedUnreadListFollowsListsWithReadingActivity() {
        val active = ReadingList("active", "Active", listOf(ReadingListEntry("a", "A", lastReadAt = 100)), updatedAt = 10)
        val empty = ReadingList("empty", "Empty", updatedAt = 500)
        val rows = ReadingShelves.rows(emptyList(), ReadingListsState(lists = listOf(empty, active)), emptyMap())
        assertEquals(listOf("active", "empty"), rows.drop(1).map { it.id })
    }
}
