package com.pocketds.hub.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineDictionaryDeviceTest {
    @Test fun bundledDictionaryAnswersLocallyAndCachesWarmLookups() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionary = OfflineEnglishDictionary(context)
        val first = dictionary.lookup("book")
        assertTrue(first.definitions.isNotEmpty())
        assertEquals("book", first.headword)
        val started = android.os.SystemClock.elapsedRealtime()
        assertEquals(first, dictionary.lookup("book"))
        assertTrue("warm lookup took too long", android.os.SystemClock.elapsedRealtime() - started < 100)
        assertTrue(dictionary.lookup("qzxyzz-nonword").definitions.isEmpty())
    }
}
