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

    @Test fun `unmarking after leaving detail resets progress to beginning`() {
        val session = ReadingCompletionSession()
        val read = session.markRead(ReadingCompletionState(), book.id)
        session.leave()
        val reset = session.unmark(read, book.id)
        assertEquals(0.0, reset.project(book).progress!!.percentage, 0.0)
        assertNull(reset.project(book).continueAt)
        assertTrue(reset.shouldStartAtBeginning(book.id))
        assertNull(reset.ebookResume(book.id, ReadingResume(ReadingLocation(pageIndex = 8))).location)
        assertEquals(0, reset.pageResume(book.id, 8))
        assertEquals(reset, ReadingCompletionState.decode(reset.encode()))
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
