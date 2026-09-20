package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentModeTest {
    @Test
    fun `unknown persisted values safely return to media`() {
        assertEquals(ContentMode.MEDIA, ContentMode.fromStored(null))
        assertEquals(ContentMode.MEDIA, ContentMode.fromStored(""))
        assertEquals(ContentMode.MEDIA, ContentMode.fromStored("future-mode"))
        assertEquals(ContentMode.BOOKS, ContentMode.fromStored("books"))
    }

    @Test
    fun `each mode keeps its own screen state`() {
        val memory = ContentModeMemory<String>()
        memory.remember(ContentMode.MEDIA, "media-focus")
        memory.remember(ContentMode.BOOKS, "book-focus")

        assertEquals("media-focus", memory.recall(ContentMode.MEDIA))
        assertEquals("book-focus", memory.recall(ContentMode.BOOKS))
        memory.clear(ContentMode.BOOKS)
        assertNull(memory.recall(ContentMode.BOOKS))
        assertEquals("media-focus", memory.recall(ContentMode.MEDIA))
    }
}
