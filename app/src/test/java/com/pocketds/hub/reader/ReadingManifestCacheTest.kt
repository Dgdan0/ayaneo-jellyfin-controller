package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingPublicationManifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingManifestCacheTest {
    @get:Rule val directory=TemporaryFolder()
    private val key=ReadingCheckpointKey("profile","work","edition","pages")
    @Test fun `manifest survives recreation and cannot cross accounts or editions`() {
        val manifest=ReadingPublicationManifest(workId="work",sourceItemId="edition",pageCount=12)
        ReadingManifestCache(directory.root).save(key,manifest)
        val reopened=ReadingManifestCache(directory.root)
        assertEquals(manifest,reopened.read(key))
        assertNull(reopened.read(key.copy(scope="other")))
        assertNull(reopened.read(key.copy(sourceItemId="other")))
    }
}
