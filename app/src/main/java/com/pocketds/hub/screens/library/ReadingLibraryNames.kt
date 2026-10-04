package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingLibrary
import java.util.concurrent.ConcurrentHashMap

/**
 * The reading libraries' names by id, as the hub last listed them: a comic
 * run's page says which library it is in ("Comic · My Marvelous Year") and its
 * work carries only the id. Remembered by whichever screen lists the libraries
 * (Books home, the Library); a page that finds none asks once itself.
 */
object ReadingLibraryNames {
    private val names = ConcurrentHashMap<String, String>()

    fun remember(libraries: List<ReadingLibrary>) {
        libraries.forEach { if (it.id.isNotBlank() && it.title.isNotBlank()) names[it.id] = it.title }
    }

    fun of(libraryId: String): String? = names[libraryId]
}
