package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingLibrary

/** Sort choices advertised by a concrete reader source. */
object ReadingSortFields {
    private val all = listOf(
        "title" to "Title",
        "series" to "Series",
        "author" to "Author",
        "added" to "Date added",
        "last_read" to "Last read"
    )

    fun forLibrary(library: ReadingLibrary): List<Pair<String, String>> {
        val advertised = library.capabilities
            .asSequence()
            .filter { it.startsWith("sort:") }
            .map { it.removePrefix("sort:") }
            .toSet()
        if (advertised.isEmpty()) return all.filter { it.first == "title" }
        return all.filter { it.first in advertised }
    }

    fun defaultAscending(field: String): Boolean = field != "added" && field != "last_read"
}
