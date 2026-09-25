package com.pocketds.hub.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReadingEntryPreferencesTest {
    @Test fun `mode choices are scoped to user connection and book`() {
        val first = ReadingEntryPreferences.key("connection-and-user-a", "book-one")
        assertEquals(first, ReadingEntryPreferences.key("connection-and-user-a", "book-one"))
        assertNotEquals(first, ReadingEntryPreferences.key("connection-and-user-b", "book-one"))
        assertNotEquals(first, ReadingEntryPreferences.key("connection-and-user-a", "book-two"))
    }
}
