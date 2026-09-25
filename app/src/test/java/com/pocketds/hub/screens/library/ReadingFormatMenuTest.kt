package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingFormatMenuTest {
    @Test fun `tracked queued unknown and incomplete downloads cannot be launched`() {
        for (state in listOf("", "unknown", "queued", "downloading", "downloaded", "processing", "tracked", "failed")) {
            val item = work(text.copy(availability = state), alice.copy(availability = state))
            assertTrue("$state must not be playable", ReadingFormatMenu.forWork(item).options.isEmpty())
        }
    }
    private val text = ReadingEdition(source = "storyteller", sourceItemId = "text", kind = "ebook", availability = "available")
    private val alice = ReadingEdition(source = "storyteller", sourceItemId = "alice", kind = "audiobook", narrator = "Alice", availability = "available")
    private val bob = alice.copy(sourceItemId = "bob", narrator = "Bob")
    private val aligned = ReadingEdition(source = "storyteller", sourceItemId = "alice", kind = "readaloud", availability = "available")

    @Test fun `formats are options and availability is informational`() {
        val work = work(text, alice, bob, aligned)
        val menu = ReadingFormatMenu.forWork(work)
        assertEquals(listOf(ReadingEntryMode.READ, ReadingEntryMode.LISTEN, ReadingEntryMode.LISTEN, ReadingEntryMode.READ_ALONG),
            menu.options.map { it.choice.mode })
        assertEquals(listOf("Alice", "Bob"), menu.options.filter { it.choice.mode == ReadingEntryMode.LISTEN }.map { it.narration })
        assertTrue(menu.availability.contains("Ebook ready"))
        assertTrue(menu.availability.contains("Audiobook ready"))
        assertTrue(menu.availability.contains("Read along ready"))
    }

    @Test fun `alignment in progress is not launchable`() {
        val work = work(text, alice, aligned.copy(availability = "processing"))
        val menu = ReadingFormatMenu.forWork(work)
        assertTrue(menu.availability.contains("Read along aligning"))
        assertFalse(menu.options.any { it.choice.mode == ReadingEntryMode.READ_ALONG })
    }

    @Test fun `previewing another narration does not alter remembered preference`() {
        val remembered = ReadingEntryPreference(ReadingEntryMode.LISTEN, "alice")
        val menu = ReadingFormatMenu.forWork(work(text, alice, bob), remembered)
        val preview = menu.options.first { it.choice.audio?.sourceItemId == "bob" }
        assertEquals("bob", preview.choice.audio?.sourceItemId)
        assertEquals("alice", remembered.audioSourceItemId)
        assertEquals("alice", menu.defaultChoice?.audio?.sourceItemId)
    }

    @Test fun `missing source falls back to playable text`() {
        val menu = ReadingFormatMenu.forWork(work(text, alice.copy(availability = "missing")),
            ReadingEntryPreference(ReadingEntryMode.LISTEN, "alice"))
        assertEquals(ReadingEntryMode.READ, menu.defaultChoice?.mode)
        assertEquals(1, menu.options.size)
    }

    @Test fun `comic and manga formats keep their own labels`() {
        val comic = ReadingFormatMenu.forWork(work(text.copy(kind = "comic")))
        assertEquals("Read comic", comic.options.single().label)
        assertEquals("Comic ready", comic.availability)
        val manga = ReadingFormatMenu.forWork(work(text.copy(kind = "manga")))
        assertEquals("Read manga", manga.options.single().label)
        assertEquals("Manga ready", manga.availability)
    }

    private fun work(vararg editions: ReadingEdition) = ReadingWork(id = "book", entityType = "work",
        title = "Test", editions = editions.toList())
}
