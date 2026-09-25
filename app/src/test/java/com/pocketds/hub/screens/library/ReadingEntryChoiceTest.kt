package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingEntryChoiceTest {
    private val ebook = ReadingEdition(source = "storyteller", kind = "ebook", sourceItemId = "text", availability = "available")
    private val audioA = ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = "audio-a", availability = "available")
    private val audioB = ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = "audio-b", availability = "available")
    private val alignedA = ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = "audio-a", availability = "available")

    @Test fun `first entry chooses text when available and audio otherwise`() {
        assertEquals(ReadingEntryMode.READ, ReadingEntryChoice.choose(work(ebook, audioA), null)?.mode)
        assertEquals(ReadingEntryMode.LISTEN, ReadingEntryChoice.choose(work(audioA), null)?.mode)
        assertNull(ReadingEntryChoice.choose(work(), null))
    }

    @Test fun `last used mode and narration are restored only while available`() {
        val work = work(ebook, audioA, audioB, alignedA)
        val listen = ReadingEntryChoice.choose(work, ReadingEntryPreference(ReadingEntryMode.LISTEN, "audio-b"))!!
        assertEquals(ReadingEntryMode.LISTEN, listen.mode)
        assertEquals("audio-b", listen.audio?.sourceItemId)
        val along = ReadingEntryChoice.choose(work, ReadingEntryPreference(ReadingEntryMode.READ_ALONG, "audio-a"))!!
        assertEquals(ReadingEntryMode.READ_ALONG, along.mode)
        assertEquals("audio-a", along.aligned?.sourceItemId)
    }

    @Test fun `missing remembered narration falls back to another audio without borrowing its alignment`() {
        val available = work(ebook, audioB, alignedA)
        val selected = ReadingEntryChoice.choose(available, ReadingEntryPreference(ReadingEntryMode.READ_ALONG, "audio-a"))!!
        assertEquals(ReadingEntryMode.READ, selected.mode)
        assertNull(selected.aligned)

        val audioOnly = ReadingEntryChoice.choose(work(audioB), ReadingEntryPreference(ReadingEntryMode.READ_ALONG, "audio-a"))!!
        assertEquals(ReadingEntryMode.LISTEN, audioOnly.mode)
        assertEquals("audio-b", audioOnly.audio?.sourceItemId)
    }

    @Test fun `unavailable selected ebook falls back to audio`() {
        val missing = ebook.copy(availability = "missing")
        val selected = ReadingEntryChoice.choose(work(missing, audioA), ReadingEntryPreference(ReadingEntryMode.READ, ""))!!
        assertEquals(ReadingEntryMode.LISTEN, selected.mode)
    }

    @Test fun `missing edition does not hide a different readable edition`() {
        val missing = ebook.copy(sourceItemId = "old-text", availability = "missing")
        val available = ebook.copy(sourceItemId = "new-text")
        val selected = ReadingEntryChoice.choose(work(missing, available), null)!!
        assertEquals(ReadingEntryMode.READ, selected.mode)
        assertEquals("new-text", selected.text?.sourceItemId)
    }

    @Test fun `read along uses exactly the narration that owns the aligned edition`() {
        val work = work(ebook, audioA, audioB, alignedA)
        assertEquals(setOf(ReadingEntryMode.READ, ReadingEntryMode.LISTEN, ReadingEntryMode.READ_ALONG),
            ReadingEntryChoice.availableModes(work))
        val selected = ReadingEntryChoice.choose(work, ReadingEntryPreference(ReadingEntryMode.READ_ALONG, "audio-b"))!!
        assertEquals(ReadingEntryMode.READ, selected.mode)
        assertNull(selected.aligned)
    }

    private fun work(vararg editions: ReadingEdition) = ReadingWork(
        id = "book", entityType = "work", kind = "ebook", title = "Test book", editions = editions.toList())
}
