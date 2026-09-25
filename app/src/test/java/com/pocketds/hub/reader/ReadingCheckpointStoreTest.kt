package com.pocketds.hub.reader

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingCheckpointStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val key = ReadingCheckpointKey("server-and-profile", "work", "edition", "pages")
    private fun page(n: Int) = ReadingLocation(pageIndex = n)
    private fun store() = ReadingCheckpointStore(directory.root)

    @Test fun `settled location survives recreation before any network request`() {
        val original = store()
        original.reconcile(key, RemoteReadingPosition.Available(page(2)))
        original.save(key, page(8), 100)
        val reopened = store()
        assertEquals(page(8), reopened.read(key)!!.local)
        assertTrue(reopened.read(key)!!.pending)
        assertEquals(page(8), reopened.reconcile(key, RemoteReadingPosition.Unavailable).location)
    }

    @Test fun `failed lookup without local position is not a new book`() {
        val result = store().reconcile(key, RemoteReadingPosition.Unavailable)
        assertTrue(result.unavailable)
        assertNull(result.location)
        assertNull(store().read(key))
    }

    @Test fun `unchanged server allows local continuation but divergent server prompts`() {
        val s = store()
        s.reconcile(key, RemoteReadingPosition.Available(page(2)))
        s.save(key, page(8), 100)
        assertFalse(s.reconcile(key, RemoteReadingPosition.Available(page(2))).conflict)
        assertTrue(s.reconcile(key, RemoteReadingPosition.Available(page(19))).conflict)
        assertEquals(page(8), s.read(key)!!.local)
        assertEquals(page(19), s.read(key)!!.remote)
        assertTrue(s.read(key)!!.pending)
    }

    @Test fun `old acknowledgement cannot delete a newer local checkpoint`() {
        val s = store()
        s.reconcile(key, RemoteReadingPosition.Available(page(2)))
        val sent = s.save(key, page(8), 100)
        s.save(key, page(9), 101)
        s.acknowledge(key, sent.revision, page(8))
        val latest = s.read(key)!!
        assertEquals(page(9), latest.local)
        assertEquals(page(8), latest.base)
        assertTrue(latest.pending)
        assertFalse(s.reconcile(key, RemoteReadingPosition.Available(page(8))).conflict)
    }

    @Test fun `identical render callbacks neither create writes nor pending work`() {
        val s = store()
        s.reconcile(key, RemoteReadingPosition.Available(page(2)))
        val before = s.read(key)!!
        val after = s.save(key, page(2), 100)
        assertEquals(before, after)
        assertTrue(s.pending(key.scope).isEmpty())
    }

    @Test fun `profile server edition and format boundaries isolate checkpoints`() {
        val s = store()
        s.save(key, page(8), 100)
        listOf(key.copy(scope="another-profile"), key.copy(workId="another-work"),
            key.copy(sourceItemId="another-edition"),key.copy(kind="epub")).forEach {
            assertNull(s.read(it))
        }
        assertTrue(s.pending("another-profile").isEmpty())
    }

    @Test fun `resolving conflict keeps both positions and rebases explicitly`() {
        val s = store()
        s.reconcile(key, RemoteReadingPosition.Available(page(2)))
        s.save(key,page(8),100)
        s.reconcile(key,RemoteReadingPosition.Available(page(19)))
        s.chooseLocal(key)
        assertEquals(page(19),s.read(key)!!.base)
        assertEquals(page(8),s.read(key)!!.local)
        assertEquals(setOf(page(8),page(19)),s.read(key)!!.savedAlternatives.toSet())
        assertFalse(s.read(key)!!.conflicted)
        assertTrue(s.read(key)!!.pending)
    }

    @Test fun `server choice and successful acknowledgement clear only selected work`() {
        val s=store()
        s.save(key,page(8),100)
        val other=key.copy(sourceItemId="other")
        s.save(other,page(4),100)
        s.reconcile(key,RemoteReadingPosition.Available(page(19)))
        s.chooseRemote(key)
        assertEquals(page(19),s.read(key)!!.local)
        assertFalse(s.read(key)!!.pending)
        val sent=s.read(other)!!
        s.acknowledge(other,sent.revision,page(4))
        assertTrue(s.pending(key.scope).isEmpty())
    }

    @Test fun `reading offline without a baseline cannot overwrite existing server progress`() {
        val s=store()
        s.save(key,page(8),100)
        assertTrue(s.reconcile(key,RemoteReadingPosition.Available(page(19))).conflict)
    }

    @Test fun `acknowledgement arriving after a server choice cannot revive discarded work`() {
        val s=store()
        s.reconcile(key,RemoteReadingPosition.Available(page(2)))
        val sent=s.save(key,page(8),100)
        s.reconcile(key,RemoteReadingPosition.Available(page(19)))
        s.chooseRemote(key)
        s.acknowledge(key,sent.revision,sent.local)
        assertEquals(page(19),s.read(key)!!.local)
        assertEquals(page(19),s.read(key)!!.base)
        assertFalse(s.read(key)!!.pending)
    }
}
