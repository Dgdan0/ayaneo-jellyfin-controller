package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingDiscoverRow
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingDiscoverRowsTest {
    private val book = listOf(ReadingItem(key = "a", title = "Atomic Habits"))
    // As BookKeeprr names them under All, on the Pocket.
    private val rows = listOf(
        ReadingDiscoverRow(id = "trending", title = "Trending now", contentType = ReadingType.EBOOK, items = book),
        ReadingDiscoverRow(id = "librivox", title = "Free audiobooks", contentType = ReadingType.AUDIOBOOK),
        ReadingDiscoverRow(id = "trending", title = "Trending now", contentType = ReadingType.MANGA, items = book),
        ReadingDiscoverRow(id = "popular", title = "Popular", contentType = ReadingType.MANGA, items = book),
        ReadingDiscoverRow(id = "fresh", title = "New light novels", contentType = ReadingType.LIGHT_NOVEL, items = book)
    )

    @Test fun `under All each row says what it holds, and an empty row is left out`() {
        assertEquals(listOf("Trending now · Ebooks", "Trending now · Manga", "Popular · Manga", "New light novels"),
            ReadingDiscoverRows.shown(rows, ReadingType.ALL).map { it.title })
    }

    @Test fun `under one kind the rows keep their own names`() {
        val manga = rows.filter { it.contentType == ReadingType.MANGA }
        assertEquals(listOf("Trending now", "Popular"), ReadingDiscoverRows.shown(manga, ReadingType.MANGA).map { it.title })
    }
}
