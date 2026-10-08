package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingPublicationManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingManifestCacheTest {
    @get:Rule val directory=TemporaryFolder()
    private val key=ReadingCheckpointKey("profile","work","edition","pages")
    @Test fun `a cached copy keeps its pages but forgets where you were when the book is started over (#60)`() {
        val cache=ReadingManifestCache(directory.root)
        val other=key.copy(workId="other")
        cache.save(key,ReadingPublicationManifest(workId="work",sourceItemId="edition",pageCount=12,currentPage=9))
        cache.save(key.copy(sourceItemId="issue 2"),ReadingPublicationManifest(workId="work",sourceItemId="issue 2",pageCount=20,currentPage=4))
        cache.save(other,ReadingPublicationManifest(workId="other",sourceItemId="edition",pageCount=5,currentPage=2))
        assertEquals(2,cache.dropPlace("work"))
        assertEquals(0,cache.read(key)!!.currentPage)
        assertEquals(12,cache.read(key)!!.pageCount)
        assertEquals(0,cache.read(key.copy(sourceItemId="issue 2"))!!.currentPage)
        assertEquals(2,cache.read(other)!!.currentPage)
        assertEquals(0,cache.dropPlace("work"))
    }

    @Test fun `manifest survives recreation and cannot cross accounts or editions`() {
        val manifest=ReadingPublicationManifest(workId="work",sourceItemId="edition",pageCount=12)
        ReadingManifestCache(directory.root).save(key,manifest)
        val reopened=ReadingManifestCache(directory.root)
        assertEquals(manifest,reopened.read(key))
        assertNull(reopened.read(key.copy(scope="other")))
        assertNull(reopened.read(key.copy(sourceItemId="other")))
    }
}
