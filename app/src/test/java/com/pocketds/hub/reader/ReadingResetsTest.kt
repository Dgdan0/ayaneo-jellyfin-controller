package com.pocketds.hub.reader

import com.pocketds.hub.model.EpubPositionBody
import com.pocketds.hub.model.EpubPositionResponse
import com.pocketds.hub.model.ReadingAudioPositionResponse
import com.pocketds.hub.model.ReadingPublicationManifest
import com.pocketds.hub.model.ReadingPublicationProgressBody
import com.pocketds.hub.model.ReadingStartOverResponse
import com.pocketds.hub.model.ReadingWork
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Start over (#60), as a device sees it: the hub says a work was started over at a stamp, and anything this
 * device kept of the place (its checkpoint, its outbox entry, its downloaded copy's place) from before it is gone.
 */
class ReadingResetsTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = "server-and-profile"
    private val ebook = ReadingCheckpointKey(scope, "work", "ebook-12", "epub")
    private val listening = ReadingCheckpointKey(scope, "work", "audio-13", AudioPlace.KIND)
    private val readAlong = ReadingCheckpointKey(scope, "work", "aligned-14", "epub")
    private val comic = ReadingCheckpointKey(scope, "work", "6", "pages")
    private val elsewhere = ReadingCheckpointKey(scope, "another work", "ebook-20", "epub")
    private val otherProfile = ReadingCheckpointKey("someone else", "work", "ebook-12", "epub")

    private class MemoryLedger : ResetLedger {
        val values = mutableMapOf<String, Long>()
        override fun get(key: String) = values[key] ?: 0L
        override fun put(key: String, value: Long) { values[key] = value }
    }

    private fun store() = ReadingCheckpointStore(directory.root)
    private fun page(n: Int) = ReadingLocation(pageIndex = n)
    private fun text(progress: Double) = ReadingLocation(locator = buildJsonObject {
        put("href", "chapter.xhtml")
        put("locations", buildJsonObject { put("totalProgression", progress) })
    })

    private fun keep(store: ReadingCheckpointStore, vararg keys: ReadingCheckpointKey) =
        keys.forEach { store.save(it, if (it.kind == "pages") page(7) else text(0.4), 100) }

    @Test fun `a start over this device has not seen drops every place it kept of that work and of nothing else`() {
        val store = store()
        keep(store, ebook, listening, readAlong, comic.copy(workId = "work"), elsewhere, otherProfile)
        val resets = ReadingResets(MemoryLedger(), store)

        assertTrue(resets.apply(scope, "work", 5_000))

        for (key in listOf(ebook, listening, readAlong, comic)) assertNull("${key.kind} ${key.sourceItemId}", store.read(key))
        assertNotNull("another work's place stays", store.read(elsewhere))
        assertNotNull("another profile's place stays", store.read(otherProfile))
        assertEquals(5_000, resets.seen(scope, "work"))
        assertEquals(0, resets.seen(scope, "another work"))
    }

    @Test fun `the same start over twice, or an older one, drops nothing and a place read after it stays`() {
        val store = store()
        val resets = ReadingResets(MemoryLedger(), store)
        assertTrue(resets.apply(scope, "work", 5_000))
        keep(store, ebook)
        assertFalse("seen already", resets.apply(scope, "work", 5_000))
        assertFalse("older than seen", resets.apply(scope, "work", 4_000))
        assertFalse("never started over", resets.apply(scope, "work", 0))
        assertNotNull(store.read(ebook))
        // A later one is news again.
        assertTrue(resets.apply(scope, "work", 9_000))
        assertNull(store.read(ebook))
    }

    @Test fun `what else a device keeps goes with it, once`() {
        val resets = ReadingResets(MemoryLedger(), store())
        var dropped = 0
        assertTrue(resets.apply(scope, "work", 5_000) { dropped++ })
        assertFalse(resets.apply(scope, "work", 5_000) { dropped++ })
        assertEquals(1, dropped)
    }

    @Test fun `a place not yet sent is not a conflict with a book started over, and is not sent`() = runBlocking {
        val store = store()
        val resets = ReadingResets(MemoryLedger(), store)
        // Read offline on this phone: a place waiting in the outbox, based on the place the hub had.
        store.reconcile(ebook, RemoteReadingPosition.Available(text(0.2)))
        store.save(ebook, text(0.4), 100)
        assertTrue(store.read(ebook)!!.pending)

        var sent = 0
        // The way ReadingProgress.fetch wires it: the hub's answer carries the start over, and it is applied first.
        val sync = ReadingCheckpointSync(store,
            { key -> resets.apply(scope, key.workId, 5_000); RemoteReadingPosition.Available(null, resetAt = 5_000) },
            { sent++; true })

        assertEquals(CheckpointSyncResult.SYNCED, sync.sync(ebook))
        assertEquals("nothing is sent from a place that went away", 0, sent)
        assertFalse(store.read(ebook)?.pending ?: false)
        assertNull(store.read(ebook)?.local)
        assertFalse(store.read(ebook)?.conflicted ?: false)
    }

    @Test fun `the part of a place kept before the hub kept any is found by the digest of its book`() {
        val keys = ReadingResets.legacyAudioKeys(scope, "work", listOf("audio-13", "audio-15"))
        val digest = ReadingCheckpointKey.digest("$scope:work:audio-13")
        assertTrue("$digest:part" in keys && "$digest:ms" in keys)
        assertEquals(4, keys.size)
        assertEquals(emptyList<String>(), ReadingResets.legacyAudioKeys(scope, "work", emptyList()))
    }

    @Test fun `the hub's stamp is sent with every write so the hub can tell a place from before a start over`() {
        val locator = buildJsonObject { put("href", "a.xhtml") }
        val body = Json.encodeToString(EpubPositionBody(locator, 100, checkBase = true, resetSeen = 5_000))
        assertEquals(5_000, Json.parseToJsonElement(body).jsonObject["resetSeen"]!!.jsonPrimitive.content.toLong())
        // A device that never saw one says so with 0, not by leaving it out: the hub reads a missing one as an older app.
        val none = Json.encodeToString(EpubPositionBody(locator, 100, resetSeen = 0))
        assertEquals(0, Json.parseToJsonElement(none).jsonObject["resetSeen"]!!.jsonPrimitive.content.toLong())

        val pages = Json.encodeToString(ReadingPublicationProgressBody(4, 3, resetSeen = 5_000))
        assertEquals(5_000, Json.parseToJsonElement(pages).jsonObject["resetSeen"]!!.jsonPrimitive.content.toLong())

        val place = AudioPlace("t_0123456789ab", 1500, false)
        val checkpoint = ReadingCheckpoint(listening, local = place.location(), base = place.location(), baseKnown = true)
        val audio: JsonObject = requireNotNull(AudioPlace.body(checkpoint, resetSeen = 5_000))
        assertEquals(5_000, audio["resetSeen"]!!.jsonPrimitive.content.toLong())
        assertNull("without a stamp nothing is added", AudioPlace.body(checkpoint)!!["resetSeen"])
    }

    @Test fun `what the hub answers carries when the book was started over`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(7_000, json.decodeFromString<ReadingWork>("""{"id":"rw_a","resetAt":7000}""").resetAt)
        assertEquals(0, json.decodeFromString<ReadingWork>("""{"id":"rw_a"}""").resetAt)
        assertEquals(7_000, json.decodeFromString<EpubPositionResponse>("""{"workId":"rw_a","locator":null,"resetAt":7000}""").resetAt)
        assertEquals(7_000, json.decodeFromString<ReadingAudioPositionResponse>("""{"workId":"rw_a","position":null,"resetAt":7000}""").resetAt)
        assertEquals(7_000, json.decodeFromString<ReadingPublicationManifest>("""{"workId":"rw_a","resetAt":7000}""").resetAt)
        val over = json.decodeFromString<ReadingStartOverResponse>("""{"ok":true,"action":"start_over","workId":"rw_a","resetAt":7000,"you":{"rating":4,"readCount":2}}""")
        assertEquals(7_000, over.resetAt)
        assertEquals(4, over.you!!.rating)
        assertNull(json.decodeFromString<ReadingStartOverResponse>("""{"ok":true,"workId":"rw_a","resetAt":7000,"you":null}""").you)
    }

    @Test fun `a reply to a read of the place says when it was started over`() {
        val answer = RemoteReadingPosition.Available(null, resetAt = 7_000)
        assertEquals(7_000, answer.resetAt)
        assertEquals(0, RemoteReadingPosition.Available(page(1)).resetAt)
    }
}
