package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.SearchHit
import org.junit.Assert.*
import org.junit.Test

class DiscoverFeaturePolicyTest {
    @Test fun `media feature also removes only the featured poster`() {
        val first = SearchHit(media = MediaRef(key = "1", title = "Movie", backdrop = "/back"), overview = "Story")
        val second = first.copy(media = first.media.copy(key = "2"))
        val row = DiscoverRow(items = listOf(first, second))
        assertEquals(first, DiscoverFeaturePolicy.mediaFeature(row, 700))
        assertEquals(listOf(second), DiscoverFeaturePolicy.mediaShelf(row, 700))
        assertNull(DiscoverFeaturePolicy.mediaFeature(row, 500))
    }
}
