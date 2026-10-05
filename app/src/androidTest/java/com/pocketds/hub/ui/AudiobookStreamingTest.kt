package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AudioStreams
import com.pocketds.hub.reader.AudiobookScreen
import com.pocketds.hub.reader.ReadingAudio
import com.pocketds.hub.reader.ReadingAudioService
import com.pocketds.hub.reader.ReadingCheckpointKey
import com.pocketds.hub.reader.ReadingProgress
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An audiobook streamed from the hub (#19, A3 and A4), against a stand-in hub
 * with the hub's rules and generated silence: it plays at once from the
 * tracks, by Range and with the bearer, the next track's head fetched ahead;
 * its place goes to the hub through the outbox, checked against the place it
 * was based on, no faster than every 15 seconds; a stale revision reads the
 * manifest again and plays on; finishing writes it finished; the place this
 * device kept before is moved over once; a place worked out from a reader's
 * page is asked about. Nothing reaches a real server, book or place.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookStreamingTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun host(activity: ReaderFixtureActivity) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost

    private fun start(): ReaderFixtureActivity =
        (ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity)
            .also { check(it.packageName.endsWith(".uitest")) }

    private suspend fun until(what: String, timeoutMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(100) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private fun service(): ReadingAudioService? =
        ReadingAudio::class.java.getDeclaredField("service").apply { isAccessible = true }.get(null) as ReadingAudioService?

    private fun player(): ExoPlayer = service()!!.player
    private fun state() = ReadingAudio.state.value

    /** A row of a side sheet by its words, as a finger would press it. */
    private fun row(root: View, words: String): View? {
        var view: View? = all(root).firstOrNull { it is TextView && it.isShown && it.text.toString() == words } ?: return null
        while (view != null && !view.isClickable) view = view.parent as? View
        return view
    }

    private fun failureShot(activity: ReaderFixtureActivity, name: String) {
        File(activity.getExternalFilesDir(null), "audiobook-streaming-$name-failure.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun screen(hub: StandInHub, work: String, book: String) = AudiobookScreen(HubClient(ins.targetContext), work,
        ReadingEdition(id = book, workId = work, source = "storyteller", sourceItemId = book, kind = "audiobook", narrator = "A generated voice"),
        "The Last Observatory", { true }, emptyList(), null, emptyList(),
        work = ReadingWork(id = work, title = "The Last Observatory", authors = listOf("A. Fixture")))

    @Test fun aStreamedAudiobookPlaysFromTheHubAndItsPlaceFollowsIt(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "stream-${System.nanoTime()}"
        val book = "book-${System.nanoTime()}"
        // Two minutes, two minutes with two chapters, twenty seconds: longer than the player buffers
        // ahead (50 s), so the next track's head comes from the prefetch alone.
        val hub = StandInHub(work, book, listOf(120, 120, 20), chapters = listOf(Triple("The ridge", 1, 0L), Triple("The summit", 1, 60_000L)))
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        val positions = ReadingAudio.positions(activity)
        val identity = ReadingProgress.get(activity).session().identity
        val legacy = ReadingCheckpointKey.digest("$identity:$work:$book")
        var current: AudiobookScreen? = null
        lateinit var root: View
        try {
            // The place this device kept before (A1): the second part, five seconds in.
            positions.edit().putInt("$legacy:part", 1).putLong("$legacy:ms", 5_000).apply()
            withContext(Dispatchers.Main) {
                current = screen(hub, work, book)
                root = current!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); current!!.onShow()
            }
            until("the streamed book on the player") { state().book?.checkpoint != null && state().ready }
            withContext(Dispatchers.Main) {
                // It opens at the place kept before, moved over once and gone from the old store.
                assertEquals(1, player().currentMediaItemIndex)
                assertTrue("At the old place: ${player().currentPosition}", abs(player().currentPosition - 5_000) < 1_500)
                assertFalse(positions.contains("$legacy:part"))
                assertFalse(positions.contains("$legacy:ms"))
                assertEquals("Nothing downloaded whole", 0, hub.fileReads())
                assertEquals(listOf(120_000L, 120_000L, 20_000L), state().partsMs)
            }
            // The tracks with the bearer, under the manifest's revision (read from the start, then by Range).
            until("the track's bytes") { hub.trackReads(1).isNotEmpty() }
            hub.trackReads(1).forEach {
                assertEquals("Bearer fixture", it.authorization)
                assertEquals("rev=aaaaaaaaaaaa", it.query)
            }

            // The sheet lists the chapters inside the tracks; the summit opens where it starts.
            withContext(Dispatchers.Main) { all(root).first { it is TextView && it.text == "Parts" }.performClick() }
            until("the chapters") { row(root, "3. The summit") != null }
            withContext(Dispatchers.Main) {
                assertTrue(all(root).any { it is TextView && it.isShown && it.text == "Chapters" })
                assertTrue(row(root, "1. Track 01") != null && row(root, "2. The ridge") != null && row(root, "4. Track 03") != null)
                row(root, "3. The summit")!!.performClick()
            }
            until("the summit") { player().currentMediaItemIndex == 1 && abs(player().currentPosition - 60_000) < 1_000 }
            // The first place goes out: based on nothing kept on the hub, as the old place was made.
            until("the first place on the hub") { hub.writes.isNotEmpty() }
            val (first, firstAt) = hub.writes.first()
            assertEquals(hub.tracks[1].id, first.getString("trackId"))
            assertEquals(JSONObject.NULL, first.get("expected"))
            assertFalse("No clock is sent", first.has("timestamp"))
            assertEquals(StandInHub.Place(hub.tracks[1].id, first.getLong("offsetMs")), hub.held)

            // Playing: the next track's head is fetched ahead.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Play audiobook" }.performClick() }
            until("playing") { state().playing }
            // The next track's head is kept on the device (the player reads ahead into it too).
            val nextKey = com.pocketds.hub.reader.AudiobookStream.cacheKey(book,
                com.pocketds.hub.model.ReadingAudioTrack(2, hub.tracks[2].id, etag = "\"e2\"", bytes = hub.tracks[2].bytes.size.toLong()))
            val head = minOf(AudioStreams.PREFETCH_BYTES, hub.tracks[2].bytes.size.toLong())
            until("the next track's head on the device") { AudioStreams.cache(activity).isCached(nextKey, 0, head) }
            assertEquals("Bearer fixture", hub.trackReads(2).first().authorization)

            // Paused: the place is kept at once on the device and sent no sooner than 15 seconds after the last.
            delay(1_500)
            withContext(Dispatchers.Main) { ReadingAudio.pause() }
            until("the second place on the hub", timeoutMs = 30_000) { hub.writes.size >= 2 }
            val (second, secondAt) = hub.writes[1]
            assertTrue("No faster than every 15 seconds: ${secondAt - firstAt} ms", secondAt - firstAt >= 14_000)
            assertEquals(first.getLong("offsetMs"), second.getJSONObject("expected").getLong("offsetMs"))
            assertEquals(hub.tracks[1].id, second.getJSONObject("expected").getString("trackId"))

            // The book's files change (a rescan): a track asked for under the old revision is refused,
            // the manifest is read again and it plays on from the same place.
            hub.revision = "bbbbbbbbbbbb"
            withContext(Dispatchers.Main) { ReadingAudio.seekTo(0, 2_000); ReadingAudio.play() }
            until("the manifest read again") { hub.manifestReads() >= 2 }
            until("the first track under the new revision") { hub.trackReads(0).any { it.query == "rev=bbbbbbbbbbbb" } }
            until("playing on") { state().playing && player().currentMediaItemIndex == 0 }
            withContext(Dispatchers.Main) {
                assertTrue("A 412 for the old revision", hub.trackReads(0).any { it.query == "rev=aaaaaaaaaaaa" })
                assertEquals("", state().problem)
            }

            // To the end: finishing writes the book finished.
            withContext(Dispatchers.Main) { ReadingAudio.seekTo(2, 16_000); ReadingAudio.play() }
            until("the end", timeoutMs = 20_000) { !state().playing && player().playbackState == androidx.media3.common.Player.STATE_ENDED }
            until("the finished place on the hub", timeoutMs = 30_000) { hub.writes.any { it.first.optBoolean("completed") } }
            assertEquals(StandInHub.Place(hub.tracks[2].id, 20_000, completed = true), hub.held)

            withContext(Dispatchers.Main) { ReadingAudio.stop() }
            until("the player let go") { state().book == null && service() == null }
            assertEquals("Never downloaded whole", 0, hub.fileReads())
        } catch (failure: Throwable) {
            failureShot(activity, "stream")
            throw failure
        } finally {
            withContext(Dispatchers.Main) {
                if (state().book != null) ReadingAudio.stop()
                current?.let { it.onHide(); it.onDestroyView() }
                activity.finish()
            }
            positions.edit().remove("$legacy:part").remove("$legacy:ms").apply()
            AudioStreams.remove(activity, listOf(book))
            HubSettings.save(activity, oldUrl, oldToken)
            hub.shutdown()
        }
    }

    /** The head of a track fetched ahead into the cache, by Range and with the bearer, and not fetched twice. */
    @Test fun thePrefetchKeepsTheHeadOfATrack(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "prefetch-${System.nanoTime()}"
        val book = "book-${System.nanoTime()}"
        // Over three minutes of silence: more than the head.
        val hub = StandInHub(work, book, listOf(200))
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        try {
            val track = hub.tracks[0]
            val part = com.pocketds.hub.reader.AudiobookPart("Track 01",
                uri = com.pocketds.hub.net.HubEndpoints.readingAudioTrack(HubSettings.baseUrl(activity), work, book, 0, hub.revision),
                trackId = track.id, cacheKey = com.pocketds.hub.reader.AudiobookStream.cacheKey(book,
                    com.pocketds.hub.model.ReadingAudioTrack(0, track.id, etag = "\"e0\"", bytes = track.bytes.size.toLong())))
            assertTrue(AudioStreams.prefetch(activity, part))
            val read = hub.trackReads(0).single()
            assertEquals("bytes=0-${AudioStreams.PREFETCH_BYTES - 1}", read.range)
            assertEquals("Bearer fixture", read.authorization)
            assertEquals(AudioStreams.PREFETCH_BYTES, AudioStreams.cachedBytes(activity, listOf(book)))
            // Kept: asked again, nothing goes out.
            assertTrue(AudioStreams.prefetch(activity, part))
            assertEquals(1, hub.trackReads(0).size)
            // Remove offline copy lets it go.
            assertTrue(AudioStreams.remove(activity, listOf(book)))
            assertEquals(0L, AudioStreams.cachedBytes(activity, listOf(book)))
        } finally {
            AudioStreams.remove(activity, listOf(book))
            withContext(Dispatchers.Main) { activity.finish() }
            HubSettings.save(activity, oldUrl, oldToken)
            hub.shutdown()
        }
    }

    @Test fun aPlaceWorkedOutFromAReadersPageIsAskedAbout(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "estimate-${System.nanoTime()}"
        val book = "book-${System.nanoTime()}"
        val hub = StandInHub(work, book, listOf(30, 30))
        // A reader's page in a book the hub cannot align: a proportion of the whole.
        hub.held = StandInHub.Place(hub.tracks[1].id, 12_000, exact = false)
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        var current: AudiobookScreen? = null
        lateinit var root: View
        try {
            withContext(Dispatchers.Main) {
                current = screen(hub, work, book)
                root = current!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); current!!.onShow()
            }
            until("the question") { row(root, "Start at the beginning") != null }
            withContext(Dispatchers.Main) {
                assertTrue(all(root).any { it is TextView && it.isShown && it.text == "Listen from where you were reading?" })
                assertTrue("Where it would go", all(root).any { it is TextView && it.isShown && it.text == "Part 2 of 2 · 0:12" })
                row(root, "Start at the beginning")!!.performClick()
            }
            until("the book at its start") { state().book?.checkpoint != null && state().ready }
            withContext(Dispatchers.Main) {
                assertEquals(0, player().currentMediaItemIndex)
                assertTrue(player().currentPosition < 1_000)
            }
            assertTrue("Nothing written by opening", hub.writes.isEmpty())
            withContext(Dispatchers.Main) { ReadingAudio.stop() }
            until("the player let go") { state().book == null && service() == null }
        } catch (failure: Throwable) {
            failureShot(activity, "estimate")
            throw failure
        } finally {
            withContext(Dispatchers.Main) {
                if (state().book != null) ReadingAudio.stop()
                current?.let { it.onHide(); it.onDestroyView() }
                activity.finish()
            }
            AudioStreams.remove(activity, listOf(book))
            HubSettings.save(activity, oldUrl, oldToken)
            hub.shutdown()
        }
    }
}
