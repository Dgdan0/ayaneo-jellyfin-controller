package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingDiscoverRow
import com.pocketds.hub.model.ReadingType

/**
 * The rows Books Discover shows (#11). BookKeeprr names its rows per kind of
 * book ("Trending now", "Popular", "New this week"), and under All the hub
 * lists every kind's rows one after another: on the Pocket two rows read
 * "Trending now", one of ebooks and one of manga. Under All a row whose name
 * does not say what it holds says it after a dot ("Trending now · Manga"), as
 * the prototype's rows say "Trending manga". A row with nothing in it is left
 * out: "Free audiobooks" stood as a heading over nothing.
 */
object ReadingDiscoverRows {
    fun shown(rows: List<ReadingDiscoverRow>, filter: String): List<ReadingDiscoverRow> =
        rows.filter { it.items.isNotEmpty() }.map { row ->
            if (filter != ReadingType.ALL || saysWhatItHolds(row)) row
            else row.copy(title = "${row.title} · ${ReadingType.label(row.contentType)}")
        }

    private fun saysWhatItHolds(row: ReadingDiscoverRow): Boolean {
        val word = when (row.contentType) {
            ReadingType.LIGHT_NOVEL -> "novel"
            else -> row.contentType
        }
        return word.isBlank() || row.title.contains(word, ignoreCase = true)
    }
}
