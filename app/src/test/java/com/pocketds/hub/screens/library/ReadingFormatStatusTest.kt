package com.pocketds.hub.screens.library

import com.pocketds.hub.model.*
import org.junit.Assert.*
import org.junit.Test

class ReadingFormatStatusTest {
    private fun edition(kind: String, state: String = "available", id: String = "1") =
        ReadingEdition(kind=kind, availability=state, source="storyteller", sourceItemId=id)
    @Test fun `only verified synchronized pairs are highlighted`() {
        fun states(vararg editions: ReadingEdition) = ReadingFormatStatus.forWork(ReadingWork(editions=editions.toList()))
        assertEquals(listOf(FormatReadiness.READY, FormatReadiness.READY, FormatReadiness.MISSING),
            states(edition("ebook"),edition("audiobook")).map { it.readiness })
        assertEquals(FormatReadiness.READY, states(edition("ebook"),edition("audiobook"),edition("readaloud")).last().readiness)
        assertEquals(FormatReadiness.UNKNOWN, states(edition("readaloud")).last().readiness)
        assertEquals(FormatReadiness.PENDING, states(edition("readaloud", "processing")).last().readiness)
    }
    @Test fun `lookup failures stay unknown and collections have no format row`() {
        assertTrue(ReadingFormatStatus.forWork(ReadingWork(entityType="collection")).isEmpty())
        assertTrue(ReadingFormatStatus.unknown().all { it.readiness == FormatReadiness.UNKNOWN })
        assertEquals(3, ReadingFormatStatus.forWork(ReadingWork(editions=listOf(edition("audiobook"),edition("audiobook",id="2")))).size)
    }
}
