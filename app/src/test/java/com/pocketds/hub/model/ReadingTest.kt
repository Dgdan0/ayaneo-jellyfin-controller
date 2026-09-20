package com.pocketds.hub.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes normalized reading discovery without provider urls`() {
        val body = json.decodeFromString<ReadingDiscoverResponse>(
            """{"rows":[{"id":"ebook-trending","title":"Trending books","contentType":"ebook","page":1,"hasMore":true,"items":[{"key":"reading:abc","contentType":"ebook","title":"Red Rising","author":"Pierce Brown","cover":"/v1/img/reading/abc","inLibrary":true,"actions":["detail"]}]}],"partial":[],"cache":{"hit":false}}"""
        )
        val item = body.rows.single().items.single()
        assertEquals("Red Rising", item.title)
        assertEquals("Pierce Brown", item.author)
        assertTrue(item.inLibrary)
        assertTrue(item.cover.startsWith("/v1/img/reading/"))
        assertFalse(item.cover.startsWith("http"))
    }

    @Test
    fun `reading type labels are stable and unknown values remain visible`() {
        assertEquals("Books", ReadingType.label("ebook"))
        assertEquals("Light novels", ReadingType.label("light_novel"))
        assertEquals("Future type", ReadingType.label("future_type"))
    }
}
