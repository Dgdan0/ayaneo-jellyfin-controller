package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.settings.SortPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSortFieldsTest {
    @Test fun `one sort sheet labels recent activity direction honestly`() {
        assertEquals("Most recently read first", SortPreference("last_read", false).directionLabel())
        assertEquals("Least recently read first", SortPreference("last_read", true).directionLabel())
        assertEquals("A to Z", SortPreference("title", true).directionLabel())
        assertEquals("Z to A", SortPreference("title", false).directionLabel())
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
        assertEquals(false, SortPreference.forField("last_read").ascending)
        assertEquals(false, SortPreference.forField("added").ascending)
        assertEquals(true, SortPreference.forField("series").ascending)
        assertEquals(true, SortPreference.forField("author").ascending)
    }
}
