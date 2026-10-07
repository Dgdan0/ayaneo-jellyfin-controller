package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
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
import com.pocketds.hub.reader.SleepChoice
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.Prefs
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An audiobook that goes by the book's own chapters (#31), against a stand-in hub with the hub's
 * rules and generated silence: three tracks of five minutes and chapters of source "book" that
 * begin in one track and run on into the next. The contents list them by title, Previous and Next
 * step by chapter across a track's end (back from three seconds in restarts the chapter, the
 * seconds counted across it), the line under the title, its times, the timeline and the time left
 * are the chapter's, and the sleep timer's end of the part is the end of the chapter, which does
 * not stop with the track. Nothing reaches a real server, book or place.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookChaptersTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun host(activity: ReaderFixtureActivity) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost

    private fun start(): ReaderFixtureActivity =
        (ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity)
            .also { check(it.packageName.endsWith(".uitest")) }

    private suspend fun until(what: String, timeoutMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
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

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync()
        delay(600)
        File(activity.getExternalFilesDir(null), "audiobook-chapters-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun anAlignedAudiobookGoesByTheBooksChaptersAcrossItsTracks(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "chapters-${System.nanoTime()}"
        val book = "book-${System.nanoTime()}"
        // Three tracks of five minutes. Chapter 2 starts a second and a half before the first track ends and
        // runs on two and a half minutes into the second; Chapter 4 goes on from the second into the third and
        // ends five seconds into it.
        val hub = StandInHub(work, book, listOf(300, 300, 300), chapterSource = "book", chapters = listOf(
            Triple("Prologue", 0, 20_000L), Triple("Chapter 1", 0, 120_000L), Triple("Chapter 2", 0, 298_500L),
            Triple("Chapter 3", 1, 150_000L), Triple("Chapter 4", 1, 240_000L), Triple("Chapter 5", 2, 5_000L)))
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        val readingWork = ReadingWork(id = work, title = "The Last Observatory", authors = listOf("A. Fixture"))
        val edition = ReadingEdition(id = book, workId = work, source = "storyteller", sourceItemId = book, kind = "audiobook", narrator = "A generated voice")
        val positions = ReadingAudio.positions(activity)
        var current: AudiobookScreen? = null
        lateinit var root: View
        fun line() = current!!.field<TextView>("partTitle").text.toString()
        fun left() = current!!.field<TextView>("left").text.toString()
        fun position() = current!!.field<TextView>("position").text.toString()
        fun remaining() = current!!.field<TextView>("remaining").text.toString()
        fun button(description: String) = all(root).first { it.contentDescription == description }
        suspend fun at(part: Int, positionMs: Long) {
            withContext(Dispatchers.Main) { ReadingAudio.seekTo(part, positionMs) }
            until("the place $part at $positionMs") { state().part == part && abs(state().positionMs - positionMs) < 1_500 && player().currentMediaItemIndex == part }
        }
        /** A finger put on the timeline at [fraction] of its length and lifted. */
        fun touchTimeline(fraction: Float) {
            val bar = current!!.field<SeekBar>("timeline")
            val x = bar.paddingLeft + (bar.width - bar.paddingLeft - bar.paddingRight) * fraction
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val now = SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, action, x, bar.height / 2f, 0).also { bar.dispatchTouchEvent(it); it.recycle() }
            }
        }
        try {
            withContext(Dispatchers.Main) {
                current = AudiobookScreen(HubClient(activity), work, edition, "The Last Observatory", { true }, listOf(edition), null, emptyList(), work = readingWork)
                root = current!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); current!!.onShow()
            }
            until("the book on the player") { state().book?.checkpoint != null && state().ready && state().partsMs.size == 3 }

            // The line names the chapter, not the file, and the credits before the Prologue are its.
            until("the chapter's line") { line() == "Prologue" }
            withContext(Dispatchers.Main) {
                assertEquals("chapter", state().noun)
                assertEquals("0:00 / 2:00", position())
                assertEquals("−2:00", remaining())
                assertEquals("2 min left in chapter · 15 min in book", left())
                assertNotNull("The steps are named for what they step through", all(root).firstOrNull { it.contentDescription == "Next chapter" })
                assertNull(all(root).firstOrNull { it.contentDescription == "Next part" })
                assertTrue(all(root).any { it is TextView && it.text == "Chapters" })
                assertFalse(all(root).any { it is TextView && it.text == "Parts" })
            }
            shot(activity, "1-line")

            // The contents list the book's chapters by title, with their lengths across the tracks.
            withContext(Dispatchers.Main) { all(root).first { it is TextView && it.text == "Chapters" }.performClick() }
            until("the contents") { row(root, "6. Chapter 5") != null }
            withContext(Dispatchers.Main) {
                assertTrue(all(root).any { it is TextView && it.isShown && it.text == "The Last Observatory · 6 chapters" })
                listOf("1. Prologue", "2. Chapter 1", "3. Chapter 2", "4. Chapter 3", "5. Chapter 4").forEach { assertNotNull(it, row(root, it)) }
                assertTrue("No file's name among them", all(root).none { it is TextView && it.isShown && it.text.startsWith("Track") })
                // Chapter 2 is 2:31 from the first track into the second; Chapter 4 is 1:05, from the second into the third.
                assertTrue(all(root).any { it is TextView && it.isShown && it.text == "2:31" })
                assertTrue(all(root).any { it is TextView && it.isShown && it.text == "1:05" })
            }
            shot(activity, "2-contents")
            withContext(Dispatchers.Main) { row(root, "4. Chapter 3")!!.performClick() }
            until("Chapter 3") { player().currentMediaItemIndex == 1 && abs(player().currentPosition - 150_000) < 1_500 }
            until("Chapter 3's line") { line() == "Chapter 3" }

            // The time left is the chapter's, counted across the tracks, with the book's beside it. A hundred seconds into
            // the second track Chapter 2, which began in the first, has fifty left; at its start 2:30.
            at(1, 100_000)
            until("Chapter 2's time") { left() == "1 min left in chapter · 8 min in book" }
            withContext(Dispatchers.Main) {
                // Still Chapter 2, 1:41 in: this is the second track, but the line is not "part 2 of 3".
                assertEquals("Chapter 2", line())
                assertEquals("1:41 / 2:31", position())
                assertEquals("−0:50", remaining())
            }
            // The media notification and the lock screen say it too: the book, and under it the chapter, not "Track 02".
            until("the player's item to name the chapter") { player().mediaMetadata.artist?.toString() == "Chapter 2" }
            withContext(Dispatchers.Main) { assertEquals("The Last Observatory", player().mediaMetadata.title?.toString()) }
            try { withTimeout(10_000) { while (!shell("dumpsys media_session").contains("Chapter 2")) delay(300) } }
            catch (e: Exception) { throw AssertionError("The media session never named the chapter: " + shell("dumpsys media_session").take(3_000), e) }
            at(1, 0)
            until("Chapter 2's time at the track's start") { left() == "2 min left in chapter · 10 min in book" }
            shot(activity, "3-across-tracks")

            // The timeline is the chapter's: a finger in the middle of it is 1:15 into Chapter 2, which is in the second track.
            withContext(Dispatchers.Main) { touchTimeline(0.5f) }
            until("the timeline's place across the tracks") { player().currentMediaItemIndex == 1 && abs(player().currentPosition - 74_250) < 3_500 }
            withContext(Dispatchers.Main) { assertEquals("Chapter 2", line()) }

            // Next and Previous go by chapter. From Chapter 3's start Previous reaches Chapter 2, which began in
            // the other track; Next comes back.
            at(1, 150_000)
            until("Chapter 3's line again") { line() == "Chapter 3" }
            withContext(Dispatchers.Main) { button("Previous chapter").performClick() }
            until("Chapter 2, in the first track") { player().currentMediaItemIndex == 0 && abs(player().currentPosition - 298_500) < 1_500 }
            until("Chapter 2's line") { line() == "Chapter 2" }
            withContext(Dispatchers.Main) { button("Next chapter").performClick() }
            until("Chapter 3, in the second track") { player().currentMediaItemIndex == 1 && abs(player().currentPosition - 150_000) < 1_500 }

            // Back from three seconds in: Chapter 2 began 1.5 seconds before the track ended. Half a second into
            // the second track it is two seconds in, so Previous goes to Chapter 1; two seconds in it is 3.5, and
            // Previous restarts Chapter 2, in the first track.
            at(1, 500)
            until("Chapter 2's line in the second track") { line() == "Chapter 2" }
            withContext(Dispatchers.Main) { button("Previous chapter").performClick() }
            until("Chapter 1") { player().currentMediaItemIndex == 0 && abs(player().currentPosition - 120_000) < 1_500 }
            at(1, 2_000)
            until("Chapter 2's line again") { line() == "Chapter 2" }
            withContext(Dispatchers.Main) { button("Previous chapter").performClick() }
            until("Chapter 2 restarted") { player().currentMediaItemIndex == 0 && abs(player().currentPosition - 298_500) < 1_500 }

            // The sleep timer: its end of the part is the end of the chapter, and says so.
            withContext(Dispatchers.Main) { all(root).first { it is TextView && it.text == "Sleep" }.performClick() }
            until("the sleep choices") { row(root, "End of this chapter") != null }
            withContext(Dispatchers.Main) {
                assertNull("No part to end", row(root, "End of this part"))
                assertNotNull(row(root, "15 minutes"))
            }
            shot(activity, "4-sleep")
            // Chapter 4 has fifty seconds left, forty-five of them in the second track. Three times as fast, it must
            // not stop with the track, but five seconds into the third, where the chapter ends.
            at(1, 255_000)
            withContext(Dispatchers.Main) {
                row(root, "End of this chapter")!!.performClick()
                assertEquals(SleepChoice.EndOfPart, state().sleep?.choice)
                assertEquals(50_000L, state().sleep?.remainingMs)
            }
            until("the button to say so") { all(root).any { it is TextView && it.text == "Sleep · end of chapter" } }
            withContext(Dispatchers.Main) { ReadingAudio.setSpeed(3f); ReadingAudio.play() }
            until("playing") { state().playing }
            // The second track ends under it, and the timer goes on counting into the third.
            until("on into the third track, the timer still counting", timeoutMs = 40_000) { state().part == 2 && state().sleep != null }
            until("asleep, and not with the track", timeoutMs = 40_000) { !state().playing && state().sleep == null }
            withContext(Dispatchers.Main) {
                // Back over what faded: half a minute before where the chapter ended, five seconds into the third
                // track, is in the second. It did not stop with the second track, and it is not clamped at the third's start.
                assertEquals("In the second track, half a minute before the chapter's end", 1, player().currentMediaItemIndex)
                assertTrue("Stepped back over the fade: ${player().currentPosition}", player().currentPosition in 273_000L..278_000L)
                assertEquals(1f, player().volume)
                assertEquals("Chapter 4", line())
            }
            shot(activity, "5-asleep")

            withContext(Dispatchers.Main) { ReadingAudio.stop() }
            until("the player let go") { state().book == null && service() == null }
            assertEquals("Never downloaded whole", 0, hub.fileReads())
        } catch (failure: Throwable) {
            try { shot(activity, "failure") } catch (_: Throwable) {}
            throw failure
        } finally {
            withContext(Dispatchers.Main) {
                if (state().book != null) ReadingAudio.stop()
                current?.let { it.onHide(); it.onDestroyView() }
                activity.finish()
            }
            Prefs.of(activity).edit().remove("listening_speed:$work").apply()
            positions.edit().apply { positions.all.keys.filter { it.contains(work) }.forEach(::remove) }.apply()
            AudioStreams.remove(activity, listOf(book))
            HubSettings.save(activity, oldUrl, oldToken)
            hub.shutdown()
        }
    }

    /** Marks inside the files, which an older hub's chapters are, stay within them as they always did. */
    @Test fun aBookWhoseChaptersAreMarksInsideItsFilesStaysWithinThem(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "marks-${System.nanoTime()}"
        val book = "book-${System.nanoTime()}"
        // Two minutes, two minutes with two marks, twenty seconds: no source, as an older hub sends them.
        val hub = StandInHub(work, book, listOf(120, 120, 20), chapters = listOf(Triple("The ridge", 1, 0L), Triple("The summit", 1, 60_000L)))
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        val edition = ReadingEdition(id = book, workId = work, source = "storyteller", sourceItemId = book, kind = "audiobook", narrator = "A generated voice")
        var current: AudiobookScreen? = null
        lateinit var root: View
        try {
            withContext(Dispatchers.Main) {
                current = AudiobookScreen(HubClient(activity), work, edition, "The Last Observatory", { true }, listOf(edition), null, emptyList(),
                    work = ReadingWork(id = work, title = "The Last Observatory", authors = listOf("A. Fixture")))
                root = current!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); current!!.onShow()
            }
            until("the book on the player") { state().book?.checkpoint != null && state().ready && state().partsMs.size == 3 }
            until("the first entry's line") { current!!.field<TextView>("partTitle").text.toString() == "Track 01" }
            withContext(Dispatchers.Main) {
                // A chapter inside a file never runs into the next one, and a file without marks stays one entry.
                assertEquals("chapter", state().noun)
                assertEquals("0:00 / 2:00", current!!.field<TextView>("position").text.toString())
                assertEquals("2 min left in chapter · 4 min in book", current!!.field<TextView>("left").text.toString())
                assertEquals(listOf("Track 01", "The ridge", "The summit", "Track 03"), state().contents.map { it.title })
                assertEquals(listOf(120_000L, 60_000L, 60_000L, 20_000L), state().contents.map { it.durationMs })
            }
            withContext(Dispatchers.Main) { all(root).first { it is TextView && it.text == "Chapters" }.performClick() }
            until("the contents") { row(root, "4. Track 03") != null }
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
