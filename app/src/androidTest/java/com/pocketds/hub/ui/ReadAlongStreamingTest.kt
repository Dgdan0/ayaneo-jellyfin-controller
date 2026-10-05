package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AudioStreams
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadingProgress
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read along streamed (#19, A3), against a stand-in hub and generated silence:
 * the edition is opened without its audio (`audio=omit`), the narration comes
 * from the audiobook's own track where the hub mapped the edition's audio file
 * (three seconds into it here), nothing is taken out of an EPUB, and the voice
 * reads with the bearer. Nothing reaches a real server, book or place.
 */
@RunWith(AndroidJUnit4::class)
class ReadAlongStreamingTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(25_000) { while (!withContext(Dispatchers.Main) { check() }) delay(100) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    @Test fun readAlongOpensTheEditionWithoutItsAudioAndStreamsTheNarration(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "ra-stream-${System.nanoTime()}"
        val book = "book-${System.nanoTime()}"
        // Eight sentences of four seconds narrated from a file that begins three seconds into the track.
        val hub = StandInHub(work, book, listOf(3 + 32), alignment = listOf(Triple("EPUB/voice.wav", 0, 3_000L)),
            whole = ReaderFixtures.epub(aligned = true, sentenceSeconds = 4),
            slim = ReaderFixtures.epub(aligned = true, sentenceSeconds = 4, withAudio = false))
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        val identity = ReadingProgress.get(activity).session().identity
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        fun narration(): ReadAlongPlayback? = screen!!.field("narration")
        fun player(): ExoPlayer = narration()!!.field("player")
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, ScreenComfort())
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), work, book, "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the narration") { narration() != null }
            withContext(Dispatchers.Main) {
                // Only the edition without its audio was asked for.
                assertEquals(listOf("format=readaloud&audio=omit"), hub.fileQueries())
                // The narration is the audiobook's track, clipped where the hub said the edition's file begins.
                val item = player().getMediaItemAt(0)
                assertTrue(item.localConfiguration!!.uri.toString().contains("/audio/tracks/0?rev=aaaaaaaaaaaa"))
                assertEquals(3_000L, item.clippingConfiguration.startPositionMs)
                assertEquals(3_000L + 32_000L, item.clippingConfiguration.endPositionMs)
                // Nothing was taken out of an EPUB.
                val editions = File(activity.cacheDir, "reading-epub/$identity")
                assertFalse("No audio taken out", editions.walkTopDown().any { it.isDirectory && it.name.endsWith("-audio") && it.name.startsWith(work) })
                assertTrue("The slim edition kept apart", File(editions, "aligned-slim").listFiles().orEmpty().any { it.name.startsWith(work) })
            }
            withContext(Dispatchers.Main) {
                screen!!.field<View>("narrationDock").let { dock ->
                    fun all(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
                    all(dock).first { it.contentDescription == "Play narration" }.performClick()
                }
            }
            until("the voice reading on") { narration()!!.isPlaying && narration()!!.position.offsetMs > 1_000 }
            withContext(Dispatchers.Main) {
                val sentence = narration()!!.let { it.timeline.active(it.position.track, it.position.offsetMs)?.fragment }
                assertTrue("A sentence of the book: $sentence", sentence?.startsWith("s") == true)
            }
            assertTrue("The track with the bearer", hub.trackReads(0).isNotEmpty() && hub.trackReads(0).all { it.authorization == "Bearer fixture" })
            withContext(Dispatchers.Main) { narration()!!.pause() }
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "read-along-streaming-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            AudioStreams.remove(activity, listOf(book))
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            hub.shutdown()
        }
    }
}
