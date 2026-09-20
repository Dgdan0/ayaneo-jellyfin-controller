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

    @Test
    fun `decodes catalog library work editions hierarchy and continue point`() {
        val libraries = json.decodeFromString<ReadingLibrariesResponse>(
            """{"libraries":[{"id":"kavita:2","source":"kavita","kind":"comic","title":"Comics","capabilities":["browse","details","progress"]}],"partial":[],"cache":{"hit":true}}"""
        )
        assertEquals("Comics", libraries.libraries.single().title)
        assertEquals("comic", libraries.libraries.single().kind)

        val page = json.decodeFromString<ReadingLibraryItemsResponse>(
            """{"libraryId":"kavita:2","page":1,"pageSize":60,"total":1,"totalPages":1,"hasMore":false,"items":[{"id":"rw_0123456789abcdef0123456789abcdef","libraryId":"kavita:2","kind":"comic","title":"Saga","authors":["Brian K. Vaughan"],"artwork":"/v1/img/reading/kavita/9","genres":[],"languages":["en"],"editions":[],"progress":{"percentage":0.25,"current":3,"total":12},"availability":["comic"],"cache":{"hit":false}}],"partial":[],"cache":{"hit":false}}"""
        )
        val summary = page.items.single()
        assertEquals("Saga", summary.title)
        assertEquals("Brian K. Vaughan", summary.byline)
        assertEquals(0.25, summary.progress?.percentage ?: 0.0, 0.0001)
        assertFalse(page.hasMore)

        val detail = json.decodeFromString<ReadingWork>(
            """{"id":"rw_0123456789abcdef0123456789abcdef","kind":"comic","title":"Saga","authors":["Brian K. Vaughan"],"genres":["Science Fiction"],"languages":["en"],"editions":[{"id":"re_one","workId":"rw_0123456789abcdef0123456789abcdef","source":"kavita","kind":"comic","format":"archive","pageCount":12,"availability":"available"}],"availability":["comic"],"sections":[{"id":"volume:1","title":"Volume 1","number":1,"items":[{"sourceItemId":"6","title":"Issue 1","number":"1","kind":"comic","pageCount":12,"progress":{"percentage":0.25}}]}],"continue":{"source":"kavita","sourceItemId":"6","title":"Issue 1","number":"1","percentage":0.25},"partial":[],"cache":{"hit":false}}"""
        )
        assertEquals("archive", detail.editions.single().format)
        assertEquals("Issue 1", detail.sections.single().items.single().title)
        assertEquals("Issue 1", detail.continueAt?.title)
    }

    @Test
    fun `work subtitle uses series then author then kind`() {
        assertEquals(
            "Red Rising · Pierce Brown",
            ReadingWork(title = "Golden Son", series = "Red Rising", authors = listOf("Pierce Brown")).subtitle
        )
        assertEquals("Manga", ReadingWork(title = "Lab Manga", kind = "manga").subtitle)
    }
}
