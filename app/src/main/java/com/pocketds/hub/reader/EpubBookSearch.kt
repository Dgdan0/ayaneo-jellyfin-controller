package com.pocketds.hub.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import kotlin.coroutines.coroutineContext

/** Uses Readium's text locators, so a result opens the passage rather than just its chapter. */
@OptIn(ExperimentalReadiumApi::class)
object EpubBookSearch {
    suspend fun find(book: Publication, query: String, limit: Int = 100): List<Locator> = withContext(Dispatchers.Default) {
        require(query.isNotBlank() && query.length <= 200)
        withTimeout(30_000) {
            val iterator = book.search(query.trim()) ?: error("Search is not available for this edition")
            try {
                val results = mutableListOf<Locator>()
                while (results.size < limit) {
                    coroutineContext.ensureActive()
                    val page = iterator.next().getOrElse { error("The book could not be searched. Try again.") } ?: break
                    results += page.locators.take(limit - results.size)
                }
                results
            } finally { iterator.close() }
        }
    }
}
