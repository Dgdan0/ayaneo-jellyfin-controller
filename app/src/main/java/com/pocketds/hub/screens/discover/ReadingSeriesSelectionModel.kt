package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingSeriesPreviewBook

/** Pure controller state for the exact-book series chooser. */
class ReadingSeriesSelectionModel(books: List<ReadingSeriesPreviewBook>) {
    enum class Action { SELECT_ALL, CONFIRM }

    val books: List<ReadingSeriesPreviewBook> = books.toList()
    private val selected = books.map { it.selected && !it.inLibrary }.toMutableList()

    var bookIndex: Int = 0
        private set
    var actionsFocused: Boolean = false
        private set
    var action: Action = Action.SELECT_ALL
        private set

    fun moveHorizontal(delta: Int) {
        if (actionsFocused) {
            action = if (delta < 0) Action.SELECT_ALL else Action.CONFIRM
        } else if (books.isNotEmpty()) {
            bookIndex = (bookIndex + delta).coerceIn(0, books.lastIndex)
        }
    }

    fun moveVertical(delta: Int) {
        if (delta > 0) actionsFocused = true
        if (delta < 0) actionsFocused = false
    }

    fun focusBook(index: Int) {
        if (index in books.indices) {
            bookIndex = index
            actionsFocused = false
        }
    }

    fun focusAction(value: Action) {
        action = value
        actionsFocused = true
    }

    fun toggleFocused(): Boolean {
        val book = books.getOrNull(bookIndex) ?: return false
        if (book.inLibrary) return false
        selected[bookIndex] = !selected[bookIndex]
        return true
    }

    fun isSelected(index: Int): Boolean = selected.getOrElse(index) { false }

    fun selectedIds(): List<String> = books.indices
        .filter { selected[it] }
        .map { books[it].id }

    fun setAllMissing(value: Boolean) {
        books.indices.forEach { index -> selected[index] = value && !books[index].inLibrary }
    }

    fun toggleAllMissing() {
        val missing = books.indices.filter { !books[it].inLibrary }
        val next = missing.any { !selected[it] }
        missing.forEach { selected[it] = next }
    }
}
