package com.pocketds.hub.net

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentResponseCacheTest {

    @Test
    fun `successful body survives a new cache instance without exposing its key`() {
        val root = Files.createTempDirectory("discover-cache").toFile()
        var now = 1_000L
        PersistentResponseCache(root, now = { now }).put("hub|token|user|discover", "{\"rows\":[1]}")

        val files = root.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files.single().name.matches(Regex("[0-9a-f]{64}\\.cache")))
        assertTrue(!files.single().readText().contains("token"))

        now += 30_000L
        val restored = PersistentResponseCache(root, now = { now })
            .read("hub|token|user|discover", maxAgeMillis = 60_000L)
        assertEquals("{\"rows\":[1]}", restored?.body)
        assertEquals(30_000L, restored?.ageMillis)
    }

    @Test
    fun `different identities cannot read each other's discover response`() {
        val root = Files.createTempDirectory("discover-cache-identity").toFile()
        val cache = PersistentResponseCache(root, now = { 5_000L })
        cache.put("hub|token-a|user-a|discover", "alpha")

        assertNull(cache.read("hub|token-b|user-a|discover", 60_000L))
        assertNull(cache.read("hub|token-a|user-b|discover", 60_000L))
        assertEquals("alpha", cache.read("hub|token-a|user-a|discover", 60_000L)?.body)
    }

    @Test
    fun `expired and corrupt entries are ignored`() {
        val root = Files.createTempDirectory("discover-cache-expiry").toFile()
        var now = 10_000L
        val cache = PersistentResponseCache(root, now = { now })
        cache.put("expired", "old")
        now += 61_000L
        assertNull(cache.read("expired", 60_000L))

        cache.put("corrupt", "valid")
        root.listFiles().orEmpty().single().writeText("not-a-cache-entry")
        assertNull(cache.read("corrupt", 60_000L))
    }

    @Test
    fun `oldest files are pruned to the configured byte budget`() {
        val root = Files.createTempDirectory("discover-cache-bound").toFile()
        var now = 100L
        val cache = PersistentResponseCache(root, maxBytes = 55L, maxEntries = 10, now = { now })
        cache.put("first", "a".repeat(20))
        now++
        cache.put("second", "b".repeat(20))
        now++
        cache.put("third", "c".repeat(20))

        assertNull(cache.read("first", Long.MAX_VALUE))
        assertEquals("c".repeat(20), cache.read("third", Long.MAX_VALUE)?.body)
        assertTrue(root.listFiles().orEmpty().sumOf(File::length) <= 55L)
    }
}
