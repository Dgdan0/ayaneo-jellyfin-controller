package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingContinue
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingWorkPresentationTest {
    @Test fun `Kavita comic Read opens a chapter rather than its series edition`() {
        val work = ReadingWork(
            kind = "comic",
            editions = listOf(ReadingEdition(source = "kavita", kind = "comic", sourceItemId = "91", availability = "available")),
            sections = listOf(ReadingSection(items = listOf(
                ReadingSectionItem(kind = "comic", sourceItemId = "901", availability = "")
            )))
        )
        assertEquals("901", ReadingWorkPresentation.primaryRead(work)?.sourceItemId)
    }

    @Test fun `individual book gets one resume target from its continued readable edition`() {
        val work = ReadingWork(
            id = "golden-son", kind = "ebook", entityType = "work", title = "Golden Son",
            progress = ReadingProgress(percentage = 0.47),
            editions = listOf(
                ReadingEdition(availability = "available", source = "storyteller", kind = "ebook", sourceItemId = "edition-a", format = "epub"),
                ReadingEdition(availability = "available", source = "storyteller", kind = "ebook", sourceItemId = "edition-b", format = "epub")
            ),
            continueAt = ReadingContinue(source = "storyteller", sourceItemId = "edition-b", percentage = 0.47)
        )
        val action = ReadingWorkPresentation.primaryRead(work)
        assertEquals("edition-b", action?.sourceItemId)
        assertEquals("storyteller", action?.source)
        assertEquals("Resume · 47%", action?.label)
    }

    @Test fun `book without progress starts read and completed book starts read again`() {
        val edition = ReadingEdition(availability = "available", source = "storyteller", kind = "ebook", sourceItemId = "epub-1")
        val work = ReadingWork(id = "book", kind = "ebook", editions = listOf(edition))
        assertEquals("Read book", ReadingWorkPresentation.primaryRead(work)?.label)
        assertEquals("Read again", ReadingWorkPresentation.primaryRead(work.copy(
            progress = ReadingProgress(percentage = 1.0, completed = true)
        ))?.label)
    }

    @Test fun `collections and audio only editions do not claim to have a readable primary action`() {
        val audio = ReadingEdition(availability = "available", source = "storyteller", kind = "audiobook", sourceItemId = "audio-1")
        assertEquals(null, ReadingWorkPresentation.primaryRead(ReadingWork(entityType = "collection", editions = listOf(audio))))
        assertEquals(null, ReadingWorkPresentation.primaryRead(ReadingWork(kind = "audiobook", editions = listOf(audio))))
    }

    @Test fun `audio continuation cannot replace an available ebook read target`() {
        val work = ReadingWork(
            kind = "ebook",
            progress = ReadingProgress(percentage = 0.35),
            editions = listOf(ReadingEdition(availability = "available", source = "storyteller", kind = "ebook", sourceItemId = "ebook-1")),
            sections = listOf(ReadingSection(items = listOf(
                ReadingSectionItem(workId = "book", kind = "audiobook", sourceItemId = "audio-1")
            ))),
            continueAt = ReadingContinue(source = "kavita", kind = "audiobook", sourceItemId = "audio-1")
        )
        val action = ReadingWorkPresentation.primaryRead(work)
        assertEquals("ebook-1", action?.sourceItemId)
        assertEquals("storyteller", action?.source)
    }

    @Test fun `book actions separate ebook audio and synchronized editions`() {
        val paired = ReadingWork(kind = "book", editions = listOf(
            ReadingEdition(source = "storyteller", kind = "ebook", sourceItemId = "42", availability = "available"),
            ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = "42", availability = "available"),
            ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = "42", availability = "available")
        ))
        assertEquals("42", ReadingWorkPresentation.primaryRead(paired)?.sourceItemId)
        assertEquals("42", ReadingWorkPresentation.primaryListen(paired)?.sourceItemId)
        assertEquals("42", ReadingWorkPresentation.readAlongEdition(paired)?.sourceItemId)
        assertEquals(1, ReadingWorkPresentation.audiobooks(paired).size)

        val unaligned = paired.copy(editions = paired.editions.dropLast(1))
        assertEquals(null, ReadingWorkPresentation.readAlongEdition(unaligned))
        val audioOnly = paired.copy(editions = listOf(paired.editions[1]))
        assertEquals(null, ReadingWorkPresentation.primaryRead(audioOnly))
        assertEquals("42", ReadingWorkPresentation.primaryListen(audioOnly)?.sourceItemId)
    }

    @Test fun `multiple audiobook records remain selectable by their source ids`() {
        val work = ReadingWork(editions = listOf(
            ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = "1", narrator = "Narrator A", availability = "available"),
            ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = "2", narrator = "Narrator B", availability = "available")
        ))
        assertEquals(listOf("1", "2"), ReadingWorkPresentation.audiobooks(work).map { it.sourceItemId })
    }

    @Test fun `only synchronized narrations appear in read along chooser`() {
        val work = ReadingWork(editions = listOf(
            ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = "1", availability = "available"),
            ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = "2", availability = "available"),
            ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = "1", availability = "available")
        ))
        assertEquals(listOf("1"), ReadingWorkPresentation.readAlongEditions(work).map { it.sourceItemId })
    }
    @Test
    fun `description starts collapsed expands and resets for a fresh entry`() {
        val firstEntry = ReadingWorkPresentation.initial("A long series description")
        assertFalse(firstEntry.descriptionExpanded)
        assertTrue(firstEntry.toggleDescription().descriptionExpanded)

        val reentered = ReadingWorkPresentation.initial("A long series description")
        assertFalse(reentered.descriptionExpanded)
    }

    @Test
    fun `continue cover resolves from the continued book before the collection fallback`() {
        val work = ReadingWork(
            artwork = "/series",
            sections = listOf(
                ReadingSection(items = listOf(
                    ReadingSectionItem(
                        workId = "rw_one", sourceItemId = "11", title = "Red Rising",
                        artwork = "/book-one"
                    )
                ))
            ),
            continueAt = ReadingContinue(
                workId = "rw_one", sourceItemId = "11", title = "Red Rising"
            )
        )

        assertEquals("/book-one", ReadingWorkPresentation.continueArtwork(work))
        assertEquals(
            "/explicit",
            ReadingWorkPresentation.continueArtwork(
                work.copy(continueAt = work.continueAt?.copy(artwork = "/explicit"))
            )
        )
    }

    @Test
    fun `missing books remain visible but cannot be opened`() {
        val available = ReadingSectionItem(workId = "rw_one", availability = "available")
        val missing = ReadingSectionItem(title = "Golden Son", availability = "missing")

        assertTrue(ReadingWorkPresentation.canOpen(available))
        assertFalse(ReadingWorkPresentation.canOpen(missing))
    }

    @Test
    fun `action focus restores the same publication and otherwise prefers continue reading`() {
        val readable = listOf("chapter-1", "chapter-2", "chapter-3")

        assertEquals(
            "chapter-2",
            ReadingWorkPresentation.preferredActionSource(
                continueSourceItemId = "chapter-1",
                readableSourceItemIds = readable,
                previouslyFocusedSourceItemId = "chapter-2"
            )
        )
        assertEquals(
            "chapter-1",
            ReadingWorkPresentation.preferredActionSource(
                continueSourceItemId = "chapter-1",
                readableSourceItemIds = readable,
                previouslyFocusedSourceItemId = "missing"
            )
        )
        assertEquals(
            "chapter-2",
            ReadingWorkPresentation.preferredActionSource(
                continueSourceItemId = "",
                readableSourceItemIds = listOf("chapter-2", "chapter-3"),
                previouslyFocusedSourceItemId = null
            )
        )
    }

    @Test fun `a comic run names the issue to continue rather than a percentage of every page`() {
        val issue51 = ReadingSectionItem(sourceItemId = "8959", title = "51", number = "51", kind = "comic", availability = "")
        val work = ReadingWork(
            id = "ff", kind = "comic",
            progress = ReadingProgress(percentage = 0.0002),
            editions = listOf(ReadingEdition(availability = "available", source = "kavita", kind = "comic", sourceItemId = "series-9")),
            sections = listOf(ReadingSection(items = listOf(issue51))),
            continueAt = ReadingContinue(source = "kavita", sourceItemId = "8959", number = "51", kind = "comic")
        )
        assertEquals("Continue · Issue 51", ReadingWorkPresentation.primaryRead(work)?.label)
        assertEquals("On issue 51 · 1% read", ReadingBookFacts.progress(work))
        assertEquals("Start · Issue 51", ReadingWorkPresentation.primaryRead(work.copy(progress = null, continueAt = null))?.label)
    }
}
