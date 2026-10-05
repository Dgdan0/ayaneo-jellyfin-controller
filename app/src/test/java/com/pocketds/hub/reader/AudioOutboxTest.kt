package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioPosition
import com.pocketds.hub.model.ReadingAudioTrack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The listening place through the durable outbox (#19, A4), against a stand-in
 * for the hub that keeps its rules: a write is checked against `expected` (absent
 * skips, null means nothing is saved, a place must be the place held now) and
 * stamped with the hub's own clock, which runs anywhere it likes.
 */
class AudioOutboxTest {
    @get:Rule val directory = TemporaryFolder()
    private val key = ReadingCheckpointKey("profile", "rw_1", "3726292328809367", AudioPlace.KIND)
    private val tracks = listOf(
        ReadingAudioTrack(0, "t_aaaaaaaaaaaa", "Track 01", 600_000),
        ReadingAudioTrack(1, "t_bbbbbbbbbbbb", "Track 02", 1_200_000)
    )

    private class Hub(val tracks: List<ReadingAudioTrack>) {
        var held: AudioPlace? = null
        var clock = 1_764_000_000_000L
        var writes = mutableListOf<JsonObject>()
        /** What another device does between this one's read and its write. */
        var beforeWrite: (() -> Unit)? = null

        fun get(): RemoteReadingPosition {
            clock -= 86_400_000 // the hub's clock means nothing here: it may even run backwards
            val place = held ?: return RemoteReadingPosition.Available(null)
            val answer = ReadingAudioPosition(trackId = place.trackId, track = tracks.indexOfFirst { it.id == place.trackId },
                offsetMs = place.offsetMs, completed = place.completed, exact = true, form = "audio", timestamp = clock)
            return RemoteReadingPosition.Available(AudioPlace.fromServer(answer)!!.location())
        }

        fun post(body: JsonObject): Boolean {
            beforeWrite?.invoke(); beforeWrite = null
            writes += body
            val expected = body["expected"]
            if (expected != null) {
                val ok = if (expected is JsonNull) held == null else {
                    val want = expected.jsonObject
                    held?.trackId == want["trackId"]!!.jsonPrimitive.content && held?.offsetMs == want["offsetMs"]!!.jsonPrimitive.long
                }
                if (!ok) return false // 409 reading_position_conflict
            }
            val completed = body["completed"]?.jsonPrimitive?.booleanOrNull == true
            held = if (completed) AudioPlace(tracks.last().id, tracks.last().durationMs, true)
                else AudioPlace(body["trackId"]!!.jsonPrimitive.content, body["offsetMs"]!!.jsonPrimitive.long)
            return true
        }
    }

    private val store by lazy { ReadingCheckpointStore(directory.root) }
    private fun sync(hub: Hub) = ReadingCheckpointSync(store, { hub.get() }, { hub.post(requireNotNull(AudioPlace.body(it))) })
    private fun listen(part: Int, offsetMs: Long, completed: Boolean = false, now: Long = 100) =
        store.save(key, AudioPlace.canonical(tracks, part, offsetMs, completed)!!.location(), now)

    @Test fun `a place moves on from the one read, checked against it`() = runBlocking {
        val hub = Hub(tracks).apply { held = AudioPlace("t_aaaaaaaaaaaa", 10_000) }
        store.reconcile(key, hub.get())
        listen(0, 50_000)
        assertEquals(CheckpointSyncResult.SYNCED, sync(hub).sync(key))
        assertEquals(AudioPlace("t_aaaaaaaaaaaa", 50_000), hub.held)
        val sent = hub.writes.single()
        assertEquals(10_000L, sent["expected"]!!.jsonObject["offsetMs"]!!.jsonPrimitive.long)
        // The next stretch is based on what was written.
        listen(1, 5_000)
        assertEquals(CheckpointSyncResult.SYNCED, sync(hub).sync(key))
        assertEquals(50_000L, hub.writes.last()["expected"]!!.jsonObject["offsetMs"]!!.jsonPrimitive.long)
        assertFalse(store.read(key)!!.pending)
    }

    @Test fun `another device's move is a choice, never overwritten`() = runBlocking {
        val hub = Hub(tracks).apply { held = AudioPlace("t_aaaaaaaaaaaa", 10_000) }
        store.reconcile(key, hub.get())
        listen(0, 50_000)
        hub.held = AudioPlace("t_bbbbbbbbbbbb", 700_000)
        assertEquals(CheckpointSyncResult.CONFLICT, sync(hub).sync(key))
        assertTrue("Nothing is sent over the other place", hub.writes.isEmpty())
        val checkpoint = store.read(key)!!
        assertEquals(AudioPlace("t_aaaaaaaaaaaa", 50_000), AudioPlace.of(checkpoint.local))
        assertEquals(AudioPlace("t_bbbbbbbbbbbb", 700_000), AudioPlace.of(checkpoint.remote))
        // Listening on keeps the question open.
        listen(0, 60_000)
        assertEquals(CheckpointSyncResult.CONFLICT, sync(hub).sync(key))
        // This device's place chosen: it goes out based on the other one.
        store.chooseLocal(key)
        assertEquals(CheckpointSyncResult.SYNCED, sync(hub).sync(key))
        assertEquals(AudioPlace("t_aaaaaaaaaaaa", 60_000), hub.held)
        assertEquals(700_000L, hub.writes.last()["expected"]!!.jsonObject["offsetMs"]!!.jsonPrimitive.long)
    }

    @Test fun `a move between the read and the write is refused, then asked about`() = runBlocking {
        val hub = Hub(tracks).apply { held = AudioPlace("t_aaaaaaaaaaaa", 10_000) }
        store.reconcile(key, hub.get())
        listen(0, 50_000)
        hub.beforeWrite = { hub.held = AudioPlace("t_bbbbbbbbbbbb", 1_000) }
        assertEquals(CheckpointSyncResult.RETRY, sync(hub).sync(key))
        assertEquals(AudioPlace("t_bbbbbbbbbbbb", 1_000), hub.held)
        assertEquals(CheckpointSyncResult.CONFLICT, sync(hub).sync(key))
    }

    @Test fun `the hub's clock never decides which place is newer`() = runBlocking {
        val hub = Hub(tracks).apply { held = AudioPlace("t_aaaaaaaaaaaa", 10_000) }
        store.reconcile(key, hub.get())
        // Read twice, its clock far apart, at the same place: no conflict, and no write is needed.
        assertFalse(store.reconcile(key, hub.get()).conflict)
        assertFalse(store.read(key)!!.pending)
        // A local place not yet sent wins over the same old place, whatever the clock says.
        listen(0, 20_000)
        hub.clock = Long.MAX_VALUE / 2
        assertFalse(store.reconcile(key, hub.get()).conflict)
        assertEquals(CheckpointSyncResult.SYNCED, sync(hub).sync(key))
        assertEquals(AudioPlace("t_aaaaaaaaaaaa", 20_000), hub.held)
    }

    @Test fun `finishing writes completed, and the hub's reading of it is the same place`() = runBlocking {
        val hub = Hub(tracks)
        store.reconcile(key, hub.get())
        listen(1, 1_199_000)
        val local = AudioPlace.of(store.read(key)!!.local)!!
        assertTrue("The last two seconds are the end", local.completed)
        assertEquals(CheckpointSyncResult.SYNCED, sync(hub).sync(key))
        assertEquals("true", hub.writes.single()["completed"]!!.jsonPrimitive.content)
        // Read back as the hub holds it: the same, so no question and nothing to send.
        assertFalse(store.reconcile(key, hub.get()).conflict)
        assertEquals(local, AudioPlace.of(store.read(key)!!.local))
        assertFalse(store.read(key)!!.pending)
    }

    @Test fun `an old device place goes out when the hub has none, and is asked about when it has one`() = runBlocking {
        val hub = Hub(tracks)
        assertTrue(store.seed(key, AudioPlace("t_bbbbbbbbbbbb", 300_000).location(), 50))
        assertFalse("Seeded once", store.seed(key, AudioPlace("t_aaaaaaaaaaaa", 1).location(), 51))
        val resume = store.reconcile(key, hub.get())
        assertFalse(resume.conflict)
        assertEquals(AudioPlace("t_bbbbbbbbbbbb", 300_000), AudioPlace.of(resume.location))
        assertEquals(CheckpointSyncResult.SYNCED, sync(hub).sync(key))
        assertEquals(JsonNull, hub.writes.single()["expected"])

        val other = Hub(tracks).apply { held = AudioPlace("t_aaaaaaaaaaaa", 42_000) }
        val second = ReadingCheckpointKey("profile", "rw_2", "2878103166016296", AudioPlace.KIND)
        assertTrue(store.seed(second, AudioPlace("t_bbbbbbbbbbbb", 300_000).location(), 50))
        val asked = store.reconcile(second, other.get())
        assertTrue("The hub has a place of its own: the person chooses", asked.conflict)
        assertNull(other.held?.takeIf { it.offsetMs != 42_000L })
    }
}
