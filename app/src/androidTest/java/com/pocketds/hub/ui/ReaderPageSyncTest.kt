package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubColumns
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.NarrationHost
import com.pocketds.hub.reader.PageSpan
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadAlongPosition
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page and the voice move each other (#49): the narration turns the page when the voice reaches the next page's
 * first word, a page turned by hand sends the voice to the first word on it unless the sentence being read is
 * still there, and the voice goes on with the screen off. A generated book and silence on a local hub, in one
 * column and in two; nothing here opens a real book or reaches a real server.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPageSyncTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private suspend fun until(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(40) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync(); delay(400)
        File(activity.getExternalFilesDir(null), "page-sync-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** A book open in the reader, the menu closed, the first page looked at; and the voice, not yet playing. */
    private inner class Book(val activity: ReaderFixtureActivity, val screen: EpubReaderScreen) {
        fun voice(): ReadAlongPlayback = screen.field("narration")
        fun span(): PageSpan? = screen.field("pageSpan")
        fun key(): String? = screen.field("pageKey")
        fun position(): ReadAlongPosition = voice().position
        fun segmentAt(position: ReadAlongPosition) = voice().timeline.active(position.track, position.offsetMs)

        /** Turns the page the way the pad does and waits for the new page to have been looked at. */
        suspend fun turn(direction: Direction) {
            val before = withContext(Dispatchers.Main) { key() }
            withContext(Dispatchers.Main) { assertTrue(screen.onPad(PadAction.Step(direction))) }
            until("the page after a turn $direction") { key() != before && span() != null }
        }

        suspend fun playing(): Boolean = withContext(Dispatchers.Main) { voice().isOn }
    }

    private suspend fun withBook(twoColumns: Boolean = false, sentenceSeconds: Int = 4, block: suspend Book.() -> Unit) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(ReaderFixtures.longEpub(sentenceSeconds = sentenceSeconds, twoColumns = twoColumns))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences(columns = if (twoColumns) EpubColumns.TWO else EpubColumns.ONE))
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "page-sync-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                val root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            val book = Book(activity, screen!!)
            until("the narration and the first page looked at") { screen!!.field<Any?>("narration") != null && screen!!.field<Any?>("pageSpan") != null }
            // Start closes the menu: nothing over the page, the corners showing.
            withContext(Dispatchers.Main) { if (screen!!.field<Boolean>("controlsVisible")) assertTrue(screen!!.onPad(PadAction.Menu)) }
            until("the menu to close") { !screen!!.field<Boolean>("controlsVisible") }
            delay(600)
            book.block()
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "page-sync-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    // ------------------------------------------------------------------ the voice turns the page

    private suspend fun theVoiceTurnsThePage(book: Book, label: String) = with(book) {
        val first = withContext(Dispatchers.Main) { span()!! }
        val end = first.end
        assertNotNull("The page has a next page's first word to wait for: $first", end)
        assertTrue("There is narrated text on the page: ${first.visible}", first.visible.size > 2)
        shot(activity, "$label-1-before")
        val keyBefore = withContext(Dispatchers.Main) { key() }
        // The voice two seconds before the next page's first word, on this page; then it plays.
        val from = ReadAlongPosition(end!!.track, maxOf(first.start?.offsetMs ?: 0L, end.offsetMs - 2_000))
        withContext(Dispatchers.Main) { voice().seek(from); voice().toggle() }
        // The page must hold until the voice gets there (nothing turns it early), then turn.
        var turnedAt = -1L
        withTimeout(20_000) {
            while (turnedAt < 0) {
                delay(30)
                val (key, position) = withContext(Dispatchers.Main) { key() to position().offsetMs }
                if (key != keyBefore) turnedAt = position
            }
        }
        assertTrue("$label: the page turned early, at $turnedAt of ${end.offsetMs}", turnedAt >= end.offsetMs)
        assertTrue("$label: the page turned late, at $turnedAt of ${end.offsetMs}", turnedAt <= end.offsetMs + 1_500)
        until("the new page looked at") { span() != null && key() != keyBefore }
        val next = withContext(Dispatchers.Main) { span()!! }
        // The first word of the new page is where the old page said it would be.
        assertEquals("$label: the first word on the new page", end.offsetMs.toDouble(), next.start!!.offsetMs.toDouble(), 150.0)
        assertTrue("$label: the voice was left playing", playing())
        assertTrue("$label: not restarted by the turn", withContext(Dispatchers.Main) { position().offsetMs } >= end.offsetMs)
        shot(activity, "$label-2-after")
        // And again on the next page: the voice does not stop turning pages.
        val keyAfter = withContext(Dispatchers.Main) { key() }
        val second = next.end
        if (second != null) {
            withContext(Dispatchers.Main) { voice().seek(ReadAlongPosition(second.track, maxOf(next.start!!.offsetMs, second.offsetMs - 1_500))) }
            until("the next turn", 15_000) { key() != keyAfter && span() != null }
            assertTrue("$label: still reading", playing())
        }
    }

    @Test fun theVoiceTurnsThePageWhenItReachesTheNextPagesFirstWord(): Unit = runBlocking {
        withBook { theVoiceTurnsThePage(this, "one-column") }
    }

    @Test fun theVoiceTurnsTheSpreadInTwoColumns(): Unit = runBlocking {
        withBook(twoColumns = true) { theVoiceTurnsThePage(this, "two-columns") }
    }

    // ------------------------------------------------------------------ the page turned by hand

    @Test fun aPageTurnedByHandWhileTheVoiceReadsSendsTheVoiceToTheFirstWordOnItUnlessItsSentenceIsStillThere(): Unit = runBlocking {
        withBook {
            // Reading from the top of the first page.
            withContext(Dispatchers.Main) { voice().toggle() }
            until("the voice") { voice().isPlaying && voice().position.offsetMs > 700 }
            val firstPage = withContext(Dispatchers.Main) { span()!! }

            // Turned forward by hand: the voice goes to the first word on the new page, and reads on.
            turn(Direction.RIGHT)
            val second = withContext(Dispatchers.Main) { span()!! }
            val at = withContext(Dispatchers.Main) { position().offsetMs }
            assertTrue("The voice went to the new page's first word (${second.start}): at $at", at >= second.start!!.offsetMs && at < second.start!!.offsetMs + 1_500)
            assertTrue("The voice reads on", playing())
            shot(activity, "hand-1-forward")

            // The voice on a sentence the first page does not show, and the page turned back: to the first word of the first page.
            val onlyHere = second.visible.first { it !in firstPage.visible }
            withContext(Dispatchers.Main) {
                val located = voice().timeline.locate(second.href, onlyHere)!!
                voice().seek(voice().timeline.positionAt(located.track, located.segment.beginMs + 100))
            }
            delay(300)
            turn(Direction.LEFT)
            val back = withContext(Dispatchers.Main) { position().offsetMs }
            assertTrue("Back on the first page the voice is at its first word (${firstPage.start}): at $back",
                back >= firstPage.start!!.offsetMs && back < firstPage.start!!.offsetMs + 1_500)

            // A page whose last sentence the break cuts through, well into it: the one a turn must not restart.
            var page = withContext(Dispatchers.Main) { span()!! }
            var cut: com.pocketds.hub.reader.ReadAlongSegment? = null
            for (n in 0 until 14) {
                val end = page.end ?: break
                val segment = segmentAt(end)
                val share = if (segment == null) 0.0 else (end.offsetMs - segment.beginMs).toDouble() / (segment.endMs - segment.beginMs)
                if (segment != null && share in 0.5..0.85) { cut = segment; break }
                turn(Direction.RIGHT)
                page = withContext(Dispatchers.Main) { span()!! }
            }
            assertNotNull("Some page is cut through a sentence", cut)
            val cutAt = cut!!
            // The voice early in that sentence, which both pages show.
            withContext(Dispatchers.Main) { voice().seek(ReadAlongPosition(0, cutAt.beginMs + 100)) }
            delay(300)
            turn(Direction.RIGHT)
            val kept = withContext(Dispatchers.Main) { position().offsetMs }
            val breakAt = page.end!!.offsetMs
            assertTrue("The sentence being read is still on the next page: nothing restarted (at $kept, the break at $breakAt)",
                kept > cutAt.beginMs && kept < breakAt - 100)
            assertTrue("It reads on", playing())
            shot(activity, "hand-2-kept")

            // Turned back to the page the voice is still reading: nothing restarts either.
            val beforeBack = withContext(Dispatchers.Main) { position().offsetMs }
            turn(Direction.LEFT)
            val afterBack = withContext(Dispatchers.Main) { position().offsetMs }
            assertTrue("Back to the page it is on: not restarted ($beforeBack, then $afterBack)", afterBack >= beforeBack && afterBack < breakAt - 100)
        }
    }

    @Test fun theSliderAndTheContentsSendTheVoiceToTheNewPageToo(): Unit = runBlocking {
        withBook {
            withContext(Dispatchers.Main) { voice().toggle() }
            until("the voice") { voice().isPlaying && voice().position.offsetMs > 700 }
            val before = withContext(Dispatchers.Main) { key() }
            withContext(Dispatchers.Main) {
                val seek = screen.field<SeekBar>("bookSeek")
                seek.progress = 55
                val method = screen.javaClass.getDeclaredMethod("seekBook").apply { isAccessible = true }
                method.invoke(screen)
            }
            until("the page after the slider") { key() != before && span() != null }
            val span = withContext(Dispatchers.Main) { span()!! }
            val at = withContext(Dispatchers.Main) { position().offsetMs }
            val firstWord = span.start!!.offsetMs
            assertTrue("The voice is at the first word on the page the slider chose ($firstWord): at $at", at >= firstWord && at < firstWord + 1_500)
            assertTrue("The voice reads on", playing())
            shot(activity, "slider")
        }
    }

    @Test fun pausedThePageTurnedByHandMovesNothingAndPlayStartsFromIt(): Unit = runBlocking {
        withBook {
            val start = withContext(Dispatchers.Main) { position().offsetMs }
            turn(Direction.RIGHT)
            turn(Direction.RIGHT)
            assertEquals("A page turned while paused leaves the voice where it was", start, withContext(Dispatchers.Main) { position().offsetMs })
            assertTrue("and does not start it", !playing())
            val span = withContext(Dispatchers.Main) { span()!! }
            // Play starts from the first word on the page.
            withContext(Dispatchers.Main) {
                val dock = screen.field<com.pocketds.hub.reader.ReadAlongDock>("narrationDock")
                dock.onPlay()
            }
            until("the voice") { voice().isOn }
            val at = withContext(Dispatchers.Main) { position().offsetMs }
            assertTrue("Play started at the first word on the page (${span.start}): at $at", at >= span.start!!.offsetMs && at < span.start!!.offsetMs + 2_000)
        }
    }

    // ------------------------------------------------------------------ the screen off

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().readText()

    @Test fun theVoicePlaysOnWithTheScreenOffAndThePageCatchesUpWhenTheAppIsBack(): Unit = runBlocking {
        withBook {
            val first = withContext(Dispatchers.Main) { span()!! }
            val end = first.end!!
            withContext(Dispatchers.Main) {
                voice().seek(ReadAlongPosition(end.track, maxOf(first.start!!.offsetMs, end.offsetMs - 4_000)))
                voice().toggle()
            }
            until("the voice") { voice().isPlaying }
            // The service that holds it, with its media session.
            until("the narration service", 10_000) { NarrationHost.isHeld }
            val sessions = shell("dumpsys media_session")
            assertTrue("The voice has a media session of its own: ${sessions.take(400)}", sessions.contains("read-along-narration"))
            // And its notification, a media one, from this app.
            val notifications = shell("dumpsys notification --noredact")
            assertTrue("The voice has a notification: ${notifications.take(300)}", notifications.contains("pkg=com.pocketds.hub.uitest") && notifications.contains("android.mediaSession"))

            // The app leaves the front, as HubActivity.onPause does: the book is told, then hidden.
            val keyBefore = withContext(Dispatchers.Main) { key() }
            val positionAtLeaving = withContext(Dispatchers.Main) { position().offsetMs }
            withContext(Dispatchers.Main) { screen.onAppBackgrounded(); screen.onHide() }
            delay(6_000)
            val whileAway = withContext(Dispatchers.Main) { position().offsetMs }
            assertTrue("The voice played on with the book hidden ($positionAtLeaving to $whileAway)", withContext(Dispatchers.Main) { voice().isOn } && whileAway > positionAtLeaving + 3_000)
            assertEquals("The page stayed where it was", keyBefore, withContext(Dispatchers.Main) { key() })
            assertTrue("It is past the page's last word, which the page did not follow", whileAway > end.offsetMs)

            // Back: the page catches up to the voice.
            withContext(Dispatchers.Main) { screen.onShow() }
            until("the page to catch up", 15_000) { key() != keyBefore && span() != null }
            val now = withContext(Dispatchers.Main) { span()!! }
            val active = withContext(Dispatchers.Main) { segmentAt(position()) }
            assertTrue("The page shows the sentence being read (${active?.fragment}): ${now.visible}", active == null || active.fragment in now.visible)
            assertTrue("The voice never stopped", playing())
            shot(activity, "catch-up")
            // Paused, it stays paused.
            withContext(Dispatchers.Main) { voice().pause() }
            withContext(Dispatchers.Main) { screen.onAppBackgrounded(); screen.onHide() }
            delay(1_000)
            val paused = withContext(Dispatchers.Main) { position().offsetMs }
            delay(1_500)
            assertEquals("A voice that was paused stays paused", paused, withContext(Dispatchers.Main) { position().offsetMs })
            withContext(Dispatchers.Main) { screen.onShow() }
        }
    }
}
