package com.pocketds.hub.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingCheckpointDeviceTest {
    @Test fun androidFilesystemDurablyReplacesCheckpointWithoutLosingPendingPosition() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uitest"))
        val directory=File(context.filesDir,"checkpoint-test-${UUID.randomUUID()}")
        val key=ReadingCheckpointKey("test-profile","fixture","edition","pages")
        val store=ReadingCheckpointStore(directory)
        store.reconcile(key,RemoteReadingPosition.Available(ReadingLocation(pageIndex=2)))
        store.save(key,ReadingLocation(pageIndex=5),100)
        store.save(key,ReadingLocation(pageIndex=6),101)
        val reopened=ReadingCheckpointStore(directory)
        assertEquals(6,reopened.read(key)!!.local!!.pageIndex)
        assertTrue(reopened.read(key)!!.pending)
        assertFalse(File(directory,key.fileName+".tmp").exists())
        assertEquals(6,reopened.reconcile(key,RemoteReadingPosition.Unavailable).location!!.pageIndex)
    }

    @Test fun conflictChoicePersistsBothAlternativesOnAndroid() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uitest"))
        val directory=File(context.filesDir,"checkpoint-test-${UUID.randomUUID()}")
        val key=ReadingCheckpointKey("test-profile","fixture","edition","pages")
        val store=ReadingCheckpointStore(directory)
        store.reconcile(key,RemoteReadingPosition.Available(ReadingLocation(pageIndex=2)))
        store.save(key,ReadingLocation(pageIndex=6),100)
        assertTrue(store.reconcile(key,RemoteReadingPosition.Available(ReadingLocation(pageIndex=15))).conflict)
        store.chooseRemote(key)
        val restored=ReadingCheckpointStore(directory).read(key)!!
        assertEquals(15,restored.local!!.pageIndex)
        assertEquals(setOf(6,15),restored.savedAlternatives.map { it.pageIndex }.toSet())
        assertFalse(restored.pending)
    }
}
