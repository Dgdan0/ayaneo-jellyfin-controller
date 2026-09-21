package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSortFieldsTest {
    @Test
    fun `storyteller exposes series author and last read sorts`() {
        val library = ReadingLibrary(
            id = "storyteller:books",
            source = "storyteller",
            capabilities = listOf("sort:title", "sort:series", "sort:author", "sort:added", "sort:last_read")
        )

        assertEquals(
            listOf("title", "series", "author", "added", "last_read"),
            ReadingSortFields.forLibrary(library).map { it.first }
        )
    }

    @Test
    fun `kavita omits author when the source cannot honor it`() {
        val library = ReadingLibrary(
            id = "kavita:2",
            source = "kavita",
            capabilities = listOf("sort:title", "sort:series", "sort:added", "sort:last_read")
        )

        assertEquals(
            listOf("title", "series", "added", "last_read"),
            ReadingSortFields.forLibrary(library).map { it.first }
        )
    }

    @Test
    fun `recent activity sorts default to newest first`() {
        assertEquals(false, ReadingSortFields.defaultAscending("last_read"))
        assertEquals(false, ReadingSortFields.defaultAscending("added"))
        assertEquals(true, ReadingSortFields.defaultAscending("series"))
        assertEquals(true, ReadingSortFields.defaultAscending("author"))
    }
}
