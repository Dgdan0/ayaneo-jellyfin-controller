package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingSeriesPreviewBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSeriesSelectionModelTest {
    private val books = listOf(
        ReadingSeriesPreviewBook(id = "OL1W", title = "Red Rising", position = 1, selected = true),
        ReadingSeriesPreviewBook(id = "OL2W", title = "Golden Son", position = 2, inLibrary = true, selected = false),
        ReadingSeriesPreviewBook(id = "OL3W", title = "Morning Star", position = 3, selected = true)
    )

    @Test
    fun `starts from server defaults and never selects owned books`() {
        val model = ReadingSeriesSelectionModel(books)
        assertEquals(listOf("OL1W", "OL3W"), model.selectedIds())
        model.focusBook(1)
        assertFalse(model.toggleFocused())
        assertEquals(listOf("OL1W", "OL3W"), model.selectedIds())
    }

    @Test
    fun `controller movement is clamped and confirm is separate from cards`() {
        val model = ReadingSeriesSelectionModel(books)
        model.moveHorizontal(-1)
        assertEquals(0, model.bookIndex)
        model.moveHorizontal(8)
        assertEquals(2, model.bookIndex)
        model.moveVertical(1)
        assertTrue(model.actionsFocused)
        model.moveHorizontal(1)
        assertEquals(ReadingSeriesSelectionModel.Action.CONFIRM, model.action)
        model.moveVertical(-1)
        assertFalse(model.actionsFocused)
        assertEquals(2, model.bookIndex)
    }

    @Test
    fun `select all targets only missing books and confirm returns exact ids`() {
        val model = ReadingSeriesSelectionModel(books)
        model.setAllMissing(false)
        assertTrue(model.selectedIds().isEmpty())
        model.setAllMissing(true)
        assertEquals(listOf("OL1W", "OL3W"), model.selectedIds())
    }
}
