package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPositionStateTest {
    private val start = ReaderLocator("book", "chapter-1", 0.10, "Page 10")
    private val preview = ReaderLocator("book", "chapter-4", 0.72, "Page 72")

    @Test
    fun `preview never commits progress until confirmed`() {
        val state = ReaderPositionState(start)

        state.beginPreview(preview)

        assertEquals(preview, state.visible)
        assertEquals(start, state.saved)
        assertNull(state.pendingProgress)

        assertEquals(preview, state.commitPreview())
        assertEquals(preview, state.saved)
        assertEquals(preview, state.pendingProgress)
    }

    @Test
    fun `cancel preview returns to the saved position`() {
        val state = ReaderPositionState(start)
        state.beginPreview(preview)

        state.cancelPreview()

        assertEquals(start, state.visible)
        assertEquals(start, state.saved)
        assertNull(state.pendingProgress)
    }

    @Test
    fun `late engine positions from an old publication are ignored`() {
        val state = ReaderPositionState(start)
        val oldGeneration = state.generation
        val replacement = ReaderLocator("other", "opening", 0.0, "Opening")
        val currentGeneration = state.replacePublication(replacement)

        assertFalse(state.acceptSettled(oldGeneration, preview))
        assertEquals(replacement, state.visible)
        assertTrue(
            state.acceptSettled(
                currentGeneration,
                ReaderLocator("other", "opening", 0.05, "Page 2")
            )
        )
        assertEquals(0.05, state.saved.progression, 0.0001)
    }
}
