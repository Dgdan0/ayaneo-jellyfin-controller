package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.DocumentPath
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PageSpan
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadAlongPosition
import com.pocketds.hub.reader.ReadAlongSegment
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
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/**
 * Read along in a book whose files are named like Mistborn's (#61): `Author - [Series 01] - Title_split_010.htm`. The page's
 * file (Readium's, percent-encoded) and the narration's (the SMIL's, raw) were compared as they came, so every page was
 * "unnarrated" and Play said "No aligned sentence on this page". A generated book with such names and silence, on a local
 * hub; nothing here opens a real book or reaches a real server.
 */
@RunWith(AndroidJUnit4::class)
class ReadAlongNamedFilesTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()

    private suspend fun until(what: String, timeoutMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(60) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private class Open(val activity: ReaderFixtureActivity, val screen: EpubReaderScreen, val root: View, val notes: MutableList<String>)

    private suspend fun withBook(block: suspend Open.() -> Unit) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(ReaderFixtures.namedEpub())
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        val notes = mutableListOf<String>()
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; "notify" -> { notes += args[0].toString(); null }; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, ScreenComfort())
            lateinit var root: View
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "named-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the narration and the first page looked at") { screen!!.field<Any?>("narration") != null && screen!!.field<Any?>("pageSpan") != null }
            delay(600)
            Open(activity, screen!!, root, notes).block()
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "readalong-named-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            shell("screencap -p /sdcard/Download/readalong-named-failure.png")
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    private fun Open.voice(): ReadAlongPlayback = screen.field("narration")
    private fun Open.span(): PageSpan? = screen.field("pageSpan")
    private fun Open.document(): String? = screen.field<Locator?>("latestLocator")?.let { DocumentPath.of(it.href.toString()) }
    private fun Open.segments(): List<ReadAlongSegment> = voice().timeline.tracks.flatMap { it.segments }
    private fun Open.positionOf(segment: ReadAlongSegment) = voice().timeline.find(segment.textHref, segment.fragment)!!

    /** Chooses an entry of Contents as a finger would, and waits for the page it names to be in front, looked at. */
    private suspend fun Open.openChapter(title: String, document: String) {
        withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Table of contents" }.performClick() }
        val overlay: SidePanelView = screen.field("overlay")
        until("Contents") { overlay.isOpen && overlay.rows.size == 4 }
        withContext(Dispatchers.Main) {
            overlay.rows.first { row -> all(row).filterIsInstance<TextView>().first().text.toString().trim() == title }.performClick()
        }
        until("$title in front, looked at") { document() == document && span()?.href == document }
    }

    /** What the reader says on Play, as the dock's button does. */
    private suspend fun Open.pressPlay() {
        withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Play narration" }.performClick() }
    }

    private suspend fun Open.pausedFor(what: String) {
        withContext(Dispatchers.Main) { if (voice().isOn) all(root).first { it.contentDescription == "Pause narration" }.performClick() }
        until("the voice paused $what") { !voice().isOn }
    }

    @Test fun everyPageOfABookWithSpacesAndBracketsInItsFileNamesIsNarratedAndFollowed(): Unit = runBlocking {
        withBook {
            val chapterOne = ReaderFixtures.NAMED_ONE
            val chapterTwo = ReaderFixtures.NAMED_TWO
            // The book opens on its title page, which has no narration, and the page goes to where the voice begins (the
            // first sentence): Readium's href for the chapter, decoded, is the file's name as the zip holds it.
            until("the page at the start of the narration") { document() == chapterOne && span()?.href == chapterOne }

            // 1. A narrated chapter is a narrated page, found by its sentences, though its name has spaces and brackets in it.
            openChapter("Chapter one", chapterOne)
            val first = withContext(Dispatchers.Main) { span()!! }
            assertTrue("the page's sentences are the narration's: $first", first.narrated && "a0" in first.visible)
            assertTrue("it has a first word to wait for: $first", first.start != null)
            assertEquals(chapterOne, first.href)
            assertEquals(listOf("a", "b"), withContext(Dispatchers.Main) { segments().map { it.fragment.take(1) }.distinct() })

            // 2. The sentence being read is washed on the page: its locator is a valid href in the book's own spelling.
            withContext(Dispatchers.Main) {
                val method = screen.javaClass.getDeclaredMethod("highlightNarration", ReadAlongSegment::class.java).apply { isAccessible = true }
                method.invoke(screen, segments().first { it.fragment == "a1" })
            }
            delay(1_400)
            val navigator: EpubNavigatorFragment = screen.field("navigator")
            val boxes = withContext(Dispatchers.Main) { navigator.evaluateJavascript("document.querySelectorAll('.pocket-narration').length") }
            assertTrue("a box on the sentence's lines: $boxes", (boxes?.trim('"')?.toIntOrNull() ?: 0) > 0)
            ins.waitForIdleSync()
            shell("screencap -p /sdcard/Download/readalong-named-1-highlight.png")

            // 3. The voice reads on into the next chapter, whose name has a space, brackets and an accent: the page follows it there.
            val last = withContext(Dispatchers.Main) { segments().last { it.fragment.startsWith("a") } }
            withContext(Dispatchers.Main) {
                val at = positionOf(last)
                voice().seek(ReadAlongPosition(at.track, at.offsetMs + 1_000))
            }
            pressPlay()
            until("the page to follow the voice into the next chapter", 30_000) { document() == chapterTwo }
            until("the new page looked at") { span()?.href == chapterTwo && span()!!.narrated }
            assertTrue("b0 is on the page: ${span()}", withContext(Dispatchers.Main) { "b0" in span()!!.visible })
            pausedFor("in the second chapter")

            // 4. Play on a page with no narration (the title page) starts at the nearest narrated sentence, after it, and the page goes there.
            openChapter("Title page", ReaderFixtures.NAMED_TITLE)
            assertFalse(withContext(Dispatchers.Main) { span()!!.narrated })
            withContext(Dispatchers.Main) { notes.clear() }
            pressPlay()
            until("the voice to start at the first narrated sentence", 30_000) { voice().isOn && document() == chapterOne }
            val start = withContext(Dispatchers.Main) { voice().position }
            assertTrue("the first sentence of the first narrated chapter, playing: $start", start.track == 0 && start.offsetMs in 0..2_900)
            assertTrue("a note says so: $notes", withContext(Dispatchers.Main) { notes.any { "no narration" in it && "nearest" in it } })
            until("the page looked at") { span()?.href == chapterOne && span()!!.narrated }
            ins.waitForIdleSync()
            shell("screencap -p /sdcard/Download/readalong-named-2-play-from-title.png")
            pausedFor("at the start")

            // 5. And from the back matter, with nothing narrated after it: the last sentence before it.
            openChapter("Back matter", ReaderFixtures.NAMED_BACK)
            withContext(Dispatchers.Main) { notes.clear() }
            pressPlay()
            // The last sentence is three seconds long: the voice may have finished it by the time the page is looked at.
            until("the voice to start at the last narrated sentence", 30_000) { document() == chapterTwo }
            val tail = withContext(Dispatchers.Main) { voice().position }
            val lastSentence = withContext(Dispatchers.Main) { positionOf(segments().last()) }
            assertTrue("at the last sentence: $tail against $lastSentence", tail.track == lastSentence.track && tail.offsetMs >= lastSentence.offsetMs)
            assertTrue("a note says so: $notes", withContext(Dispatchers.Main) { notes.any { "no narration" in it } })
            pausedFor("at the end")
        }
    }
}
