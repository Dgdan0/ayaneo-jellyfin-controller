package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.ReadingDiscoverRow
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.SearchHit
import org.junit.Assert.*
import org.junit.Test

class DiscoverFeaturePolicyTest {
    @Test fun `feature uses the leading item once and leaves the remaining shelf intact`() {
        val first = ReadingItem(key = "1", title = "Dark Matter", author = "Blake Crouch", cover = "/cover")
        val second = first.copy(key = "2", title = "Recursion")
        val row = ReadingDiscoverRow(items = listOf(first, second))
        assertEquals(first, DiscoverFeaturePolicy.readingFeature(row, 700))
        assertEquals(listOf(second), DiscoverFeaturePolicy.readingShelf(row, 700))
        assertEquals(listOf(first, second), DiscoverFeaturePolicy.readingShelf(row, 500))
    }

    @Test fun `missing art or metadata keeps every item in the shelf`() {
        val noArt = ReadingItem(key = "1", title = "Book", author = "Author")
        val noMeta = ReadingItem(key = "2", title = "Book", cover = "/cover")
        assertNull(DiscoverFeaturePolicy.readingFeature(ReadingDiscoverRow(items = listOf(noArt)), 700))
        assertNull(DiscoverFeaturePolicy.readingFeature(ReadingDiscoverRow(items = listOf(noMeta)), 700))
    }

    @Test fun `media feature also removes only the featured poster`() {
        val first = SearchHit(media = MediaRef(key = "1", title = "Movie", backdrop = "/back"), overview = "Story")
        val second = first.copy(media = first.media.copy(key = "2"))
        val row = DiscoverRow(items = listOf(first, second))
        assertEquals(first, DiscoverFeaturePolicy.mediaFeature(row, 700))
        assertEquals(listOf(second), DiscoverFeaturePolicy.mediaShelf(row, 700))
        assertNull(DiscoverFeaturePolicy.mediaFeature(row, 500))
    }
}
