package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingContinue
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingWorkPresentationTest {
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
}
