package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.ReadingDiscoverRow
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.SearchHit

/** Feature the actual first browse result only when there is enough room and honest artwork. */
object DiscoverFeaturePolicy {
    private const val MIN_WIDTH_DP = 640

    fun readingFeature(row: ReadingDiscoverRow, widthDp: Int): ReadingItem? = row.items.firstOrNull()?.takeIf {
        widthDp >= MIN_WIDTH_DP && it.title.isNotBlank() && it.cover.isNotBlank() &&
            (it.author.isNotBlank() || it.description.isNotBlank())
    }

    fun readingShelf(row: ReadingDiscoverRow, widthDp: Int): List<ReadingItem> =
        if (readingFeature(row, widthDp) == null) row.items else row.items.drop(1)

    fun mediaFeature(row: DiscoverRow, widthDp: Int): SearchHit? = row.items.firstOrNull()?.takeIf {
        widthDp >= MIN_WIDTH_DP && it.media.title.isNotBlank() &&
            (it.media.backdrop.isNotBlank() || it.media.poster.isNotBlank()) &&
            (it.subtitle.isNotBlank() || it.overview.isNotBlank() || it.media.year > 0)
    }

    fun mediaShelf(row: DiscoverRow, widthDp: Int): List<SearchHit> =
        if (mediaFeature(row, widthDp) == null) row.items else row.items.drop(1)
}
