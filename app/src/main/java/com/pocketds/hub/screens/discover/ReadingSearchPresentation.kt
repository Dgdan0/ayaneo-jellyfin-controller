package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingItem

data class ReadingSearchPresentation(
    val close: List<ReadingItem> = emptyList(),
    val broader: List<ReadingItem> = emptyList(),
    val showBroader: Boolean = false
) {
    val hasBroaderResults: Boolean get() = broader.isNotEmpty()
    val canToggle: Boolean get() = close.isNotEmpty() && broader.isNotEmpty()

    fun visibleResults(): List<ReadingItem> = if (showBroader) close + broader else close
    fun toggleBroader(): ReadingSearchPresentation = if (canToggle) copy(showBroader = !showBroader) else this

    fun summary(): String = when {
        showBroader && close.isEmpty() -> "${broader.size} broader results · no close matches"
        showBroader -> "${close.size + broader.size} results · ${broader.size} broader"
        close.isEmpty() && broader.isEmpty() -> "No results"
        close.isEmpty() -> "No close matches · ${broader.size} broader results"
        broader.isEmpty() -> "${close.size} close matches"
        else -> "${close.size} close matches · ${broader.size} broader results"
    }

    companion object {
        fun forResults(close: List<ReadingItem>, broader: List<ReadingItem>) =
            ReadingSearchPresentation(close, broader, showBroader = close.isEmpty() && broader.isNotEmpty())
    }
}
