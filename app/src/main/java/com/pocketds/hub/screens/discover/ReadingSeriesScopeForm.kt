package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingSeriesPreview
import com.pocketds.hub.model.ReadingSeriesPreviewResponse
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.state.FormRow

/** Pure mapping for choosing between verified Open Library series scopes. */
object ReadingSeriesScopeForm {
    fun rows(response: ReadingSeriesPreviewResponse, selectedIndex: Int = 0): List<FormRow> {
        if (response.scopes.isEmpty()) return emptyList()
        val selected = selectedIndex.coerceIn(0, response.scopes.lastIndex)
        val selectedBookCount = response.scopes[selected].books.size
        return listOf(
            FormRow.Choice(
                id = "series_scope",
                label = "Collection",
                options = response.scopes.map { it.name },
                details = response.scopes.map { scope ->
                    buildString {
                        append(scope.books.size)
                        append(if (scope.books.size == 1) " book" else " books")
                        if (scope.author.isNotBlank()) {
                            append(" · ")
                            append(scope.author)
                        }
                    }
                },
                selected = selected
            ),
            FormRow.Action("review", "Review $selectedBookCount books")
        )
    }

    fun selected(
        response: ReadingSeriesPreviewResponse,
        model: FormModel
    ): ReadingSeriesPreview? = response.scopes.getOrNull(model.selectedIndex("series_scope"))
}
