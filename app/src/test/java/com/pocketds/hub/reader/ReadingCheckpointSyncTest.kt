package com.pocketds.hub.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingCheckpointSyncTest {
    @get:Rule val directory=TemporaryFolder()
    private val key=ReadingCheckpointKey("profile","work","edition","pages")
    private val first=ReadingLocation(pageIndex=2)
    private val local=ReadingLocation(pageIndex=8)
    private fun pending():ReadingCheckpointStore=ReadingCheckpointStore(directory.root).also {
        it.reconcile(key,RemoteReadingPosition.Available(first)); it.save(key,local,100)
    }

    @Test fun `failed reads never send and retain durable work`()= runBlocking {
        val store=pending(); var writes=0
        val sync=ReadingCheckpointSync(store,{RemoteReadingPosition.Unavailable},{writes++;true})
        assertEquals(CheckpointSyncResult.RETRY,sync.sync(key))
        assertEquals(0,writes);assertTrue(store.read(key)!!.pending)
    }
    @Test fun `changed server is a conflict not last writer wins`()= runBlocking {
        val store=pending();var writes=0
        val sync=ReadingCheckpointSync(store,{RemoteReadingPosition.Available(ReadingLocation(pageIndex=17))},{writes++;true})
        assertEquals(CheckpointSyncResult.CONFLICT,sync.sync(key))
        assertEquals(0,writes);assertEquals(local,store.read(key)!!.local)
    }
    @Test fun `failed conditional write remains pending and later success clears it`()= runBlocking {
        val store=pending();var succeed=false
        val sync=ReadingCheckpointSync(store,{RemoteReadingPosition.Available(first)},{sent ->
            assertEquals(first,sent.base);succeed
        })
        assertEquals(CheckpointSyncResult.RETRY,sync.sync(key))
        succeed=true
        assertEquals(CheckpointSyncResult.SYNCED,sync.sync(key))
        assertFalse(store.read(key)!!.pending)
    }
    @Test fun `new reading while a write is in flight survives the acknowledgement`()= runBlocking {
        val store=pending()
        val sync=ReadingCheckpointSync(store,{RemoteReadingPosition.Available(first)},{
            store.save(key,ReadingLocation(pageIndex=9),101);true
        })
        assertEquals(CheckpointSyncResult.RETRY,sync.sync(key))
        assertEquals(9,store.read(key)!!.local!!.pageIndex)
        assertEquals(local,store.read(key)!!.base)
    }
}
