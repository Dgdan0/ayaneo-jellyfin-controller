package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingSeriesPreview
import com.pocketds.hub.model.ReadingSeriesPreviewBook
import com.pocketds.hub.model.ReadingSeriesPreviewResponse
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.state.FormRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSeriesScopeFormTest {
    private val response = ReadingSeriesPreviewResponse(
        key = "reading:red-rising",
        scopes = listOf(
            ReadingSeriesPreview(
                seriesId = "subject:saga",
                name = "Red Rising Saga",
                author = "Pierce Brown",
                books = (1..6).map { ReadingSeriesPreviewBook(id = "OL${it}W") }
            ),
            ReadingSeriesPreview(
                seriesId = "OL100L",
                name = "Red Rising Trilogy",
                author = "Pierce Brown",
                books = (1..3).map { ReadingSeriesPreviewBook(id = "TR${it}W") }
            )
        )
    )

    @Test
    fun `scope chooser labels every verified collection with its size`() {
        val rows = ReadingSeriesScopeForm.rows(response)
        val scope = rows.first() as FormRow.Choice

        assertEquals(listOf("Red Rising Saga", "Red Rising Trilogy"), scope.options)
        assertEquals(listOf("6 books · Pierce Brown", "3 books · Pierce Brown"), scope.details)
        assertEquals("Review 6 books", (rows.last() as FormRow.Action).label)
    }

    @Test
    fun `selected scope follows the form choice and updates review count`() {
        val model = FormModel(ReadingSeriesScopeForm.rows(response))
        model.adjust(1)

        assertEquals("OL100L", ReadingSeriesScopeForm.selected(response, model)?.seriesId)
        assertEquals("Review 3 books", ReadingSeriesScopeForm.rows(response, 1).last().label)
    }
}
