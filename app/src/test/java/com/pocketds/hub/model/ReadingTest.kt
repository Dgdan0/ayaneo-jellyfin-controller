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
    fun `reading collection decodes ordered child works`() {
        val work = json.decodeFromString<ReadingWork>(
            """{
                "id":"rw_collection","entityType":"collection","kind":"book","title":"Red Rising",
                "authors":["Pierce Brown"],"bookCount":2,"genres":[],"languages":[],"editions":[],"availability":["ebook"],
                "sections":[{"id":"books","title":"Books","items":[
                    {"workId":"rw_one","sourceItemId":"1","title":"Red Rising","number":"1","kind":"book","artwork":"/cover/1","authors":["Pierce Brown"]},
                    {"workId":"rw_two","sourceItemId":"2","title":"Golden Son","number":"2","kind":"book","artwork":"/cover/2","authors":["Pierce Brown"]}
                ]}]
            }""".trimIndent()
        )

        assertEquals("collection", work.entityType)
        assertEquals(2, work.bookCount)
        assertEquals(listOf("rw_one", "rw_two"), work.sections.single().items.map { it.workId })
    }

    @Test
    fun `reading collection preserves available and missing books plus continue artwork`() {
        val work = json.decodeFromString<ReadingWork>(
            """{
                "id":"rw_collection","entityType":"collection","kind":"book","title":"Red Rising",
                "artwork":"/series","authors":["Pierce Brown"],"bookCount":1,"genres":[],"languages":[],"editions":[],
                "availability":["ebook"],
                "sections":[{"id":"books","title":"Books","items":[
                    {"workId":"rw_one","sourceItemId":"1","title":"Red Rising","number":"1","kind":"book","artwork":"/cover/1","availability":"available"},
                    {"title":"Golden Son","number":"2","kind":"book","artwork":"/cover/2","availability":"missing"}
                ]}],
                "continue":{"workId":"rw_one","source":"storyteller","sourceItemId":"1","title":"Red Rising","number":"1","artwork":"/cover/1","percentage":0.25}
            }""".trimIndent()
        )

        assertTrue(work.sections.single().items.first().isAvailable)
        assertFalse(work.sections.single().items.last().isAvailable)
        assertEquals("/cover/1", work.continueAt?.artwork)
        assertEquals("rw_one", work.continueAt?.workId)
    }

    @Test
    fun `work subtitle uses series then author then kind`() {
        assertEquals(
            "Red Rising · Pierce Brown",
            ReadingWork(title = "Golden Son", series = "Red Rising", authors = listOf("Pierce Brown")).subtitle
        )
        assertEquals("Manga", ReadingWork(title = "Lab Manga", kind = "manga").subtitle)
    }

    @Test
    fun `decodes request choices and normalized reading transfers`() {
        val options = json.decodeFromString<ReadingRequestOptions>(
            """{"key":"reading:abc","contentType":"ebook","title":"Red Rising","author":"Pierce Brown","modes":[{"id":"single","label":"This book"},{"id":"series","label":"Choose books from series","requiresSeriesPreview":true}],"qualityProfiles":[{"id":7,"label":"English EPUB","default":true,"preferCompleteBatches":true}],"monitoring":["all","none"]}"""
        )
        assertEquals("series", options.modes.last().id)
        assertTrue(options.modes.last().requiresSeriesPreview)
        assertEquals(7, options.qualityProfiles.single().id)
        assertEquals(0, options.defaultProfileIndex)

        val transfers = json.decodeFromString<ReadingDownloadsResponse>(
            """{"items":[{"id":"rt_abc","seriesId":4,"contentType":"ebook","title":"Red Rising","releaseTitle":"Red Rising EPUB","status":"failed","progressPercent":25,"downloadSpeedBytesPerSecond":4096,"etaSeconds":90,"sizeBytes":12345,"failed":true,"actions":["retry","cancel"]}]}"""
        )
        val transfer = transfers.items.single()
        assertEquals("Red Rising", transfer.title)
        assertEquals(0.25, transfer.progress, 0.0001)
        assertEquals(listOf("retry", "cancel"), transfer.actions)
        assertEquals(listOf(ReadingTransferAction.RETRY, ReadingTransferAction.CANCEL), transfer.availableActions)
    }

    @Test
    fun `reading request body retains exact series selection and selected profile`() {
        val encoded = json.encodeToString(
            ReadingCreateRequestBody.serializer(),
            ReadingCreateRequestBody(
                key = "reading:abc", mode = "series", seriesId = "OL100L",
                bookIds = listOf("OL1W", "OL3W"),
                qualityProfileId = 7, monitoring = "all"
            )
        )
        val decoded = json.decodeFromString<ReadingCreateRequestBody>(encoded)
        assertEquals("series", decoded.mode)
        assertEquals("OL100L", decoded.seriesId)
        assertEquals(listOf("OL1W", "OL3W"), decoded.bookIds)
        assertEquals(7, decoded.qualityProfileId)
    }

    @Test
    fun `publication manifest decodes sanitized comic pages and navigation`() {
        val manifest = json.decodeFromString<ReadingPublicationManifest>(
            """{
                "workId":"rw_0123456789abcdef0123456789abcdef","source":"kavita","sourceItemId":"6",
                "kind":"manga","title":"Chapter 1","seriesTitle":"Lab Manga","number":"1",
                "pageCount":3,"currentPage":1,"direction":"rtl",
                "pages":[{"index":0,"width":1200,"height":1800},{"index":1,"width":2400,"height":1600,"isWide":true},{"index":2}],
                "doublePairs":{"0":1},"nextSourceItemId":"7"
            }""".trimIndent()
        )

        assertEquals("rtl", manifest.direction)
        assertEquals(1, manifest.currentPage)
        assertEquals(3, manifest.pages.size)
        assertTrue(manifest.pages[1].isWide)
        assertEquals(1, manifest.doublePairs["0"])
        assertEquals("7", manifest.nextSourceItemId)
        assertEquals("Page 2 of 3", manifest.positionLabel)
    }

    @Test
    fun `publication progress encodes only zero based page index`() {
        val encoded = json.encodeToString(
            ReadingPublicationProgressBody.serializer(),
            ReadingPublicationProgressBody(pageIndex = 17)
        )

        assertEquals("{\"pageIndex\":17}", encoded)
    }
}
