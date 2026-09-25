package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSortFieldsTest {
    @Test fun `one sort sheet labels recent activity direction honestly`() {
        assertEquals("Most recently read first", ReadingSortFields.directionLabel("last_read", ascending = false))
        assertEquals("Least recently read first", ReadingSortFields.directionLabel("last_read", ascending = true))
        assertEquals("A to Z", ReadingSortFields.directionLabel("title", ascending = true))
        assertEquals("Z to A", ReadingSortFields.directionLabel("title", ascending = false))
    }
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
