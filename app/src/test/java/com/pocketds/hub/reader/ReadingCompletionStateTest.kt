package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingContinue
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.home.ReadingShelves
import org.junit.Assert.*
import org.junit.Test

class ReadingCompletionStateTest {
    private val book = ReadingWork(id = "red-rising", title = "Red Rising",
        progress = ReadingProgress(.5, false),
        continueAt = ReadingContinue(workId = "red-rising", sourceItemId = "12", percentage = .5))

    @Test fun `read removes continuation and completes detail and library card`() {
        val state = ReadingCompletionState().markRead(book.id)
        val projected = state.project(book)
        assertTrue(projected.progress!!.completed)
        assertEquals(1.0, projected.progress!!.percentage, 0.0)
        assertNull(projected.continueAt)
        assertTrue(ReadingShelves.current(listOf(projected)).isEmpty())
    }

    @Test fun `unmarking before leaving detail restores exact prior progress`() {
        val session = ReadingCompletionSession()
        val original = ReadingCompletionState()
        val read = session.markRead(original, book.id)
        assertEquals(book, session.unmark(read, book.id).project(book))
    }

    @Test fun `undo restores earlier manual state and timestamp without changing other books`() {
        val original = ReadingCompletionState().reset(book.id, at = 123L).markRead("other", at = 22L)
        val session = ReadingCompletionSession()
        assertEquals(original, session.unmark(session.markRead(original, book.id), book.id))
    }

    @Test fun `unmarking after leaving detail takes the finish away and leaves the place where it was (#60)`() {
        val session = ReadingCompletionSession()
        val read = session.markRead(ReadingCompletionState(), book.id)
        session.leave()
        val unmarked = session.unmark(read, book.id)
        // Not a reset: the book is as far through as the hub says, and a reader opens where it was.
        assertEquals(book, unmarked.project(book))
        assertFalse(unmarked.isRead(book.id))
        assertFalse(unmarked.shouldStartAtBeginning(book.id))
        val place = ReadingResume(ReadingLocation(pageIndex = 8))
        assertEquals(place, unmarked.ebookResume(book.id, place))
        assertEquals(8, unmarked.pageResume(book.id, 8))
        assertEquals(unmarked, ReadingCompletionState.decode(unmarked.encode()))
    }

    @Test fun `a reset written by an earlier build still starts at the beginning until a reader saves a place`() {
        val old = ReadingCompletionState().reset(book.id, at = 5L)
        assertTrue(old.shouldStartAtBeginning(book.id))
        assertEquals(0, old.pageResume(book.id, 8))
        assertEquals(old, ReadingCompletionState.decode(old.encode()))
    }

    @Test fun `collection marks only targeted book and never other children`() {
        val collection = ReadingWork(id = "series", entityType = "collection",
            sections = listOf(ReadingSection(items = listOf(
                ReadingSectionItem(workId = book.id, sourceItemId = "12", title = book.title, progress = book.progress),
                ReadingSectionItem(workId = "next", sourceItemId = "13", title = "Next")))))
        val projected = ReadingCompletionState().markRead(book.id).project(collection)
        assertTrue(projected.sections[0].items[0].progress!!.completed)
        assertNull(projected.sections[0].items[1].progress)
    }
}
