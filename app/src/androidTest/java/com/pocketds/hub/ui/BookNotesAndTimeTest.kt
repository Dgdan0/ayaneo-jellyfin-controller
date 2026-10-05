package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.FootnoteCard
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.Prefs
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * A book read with the sticks, its time left, its notes and links (#18, E1,
 * E3, E5), in the real reader on a generated EPUB from a local hub: a gentle
 * push on the right stick moves the text, scrolling on at the end of a chapter
 * goes into the next, the time left shows under the title, a footnote opens as
 * a card that leaves the page where it was, and a link followed can be undone
 * with "Return to previous place". No real book is opened.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class BookNotesAndTimeTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    @Test fun sticksTimeLeftNotesAndLinksInTheRealReader(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "notes-${System.nanoTime()}"
        val server = ReaderFixtures.fileServer(ReaderFixtures.notedEpub())
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        val notices = CopyOnWriteArrayList<String>()
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) {
                "getViewContext" -> activity
                "back" -> true
                "notify" -> { notices += args!![0] as String; null }
                else -> null
            }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        suspend fun until(what: String, check: suspend () -> Boolean) {
            try { withTimeout(20_000) { while (!check()) delay(100) } } catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
        }
        suspend fun shot(name: String) {
            ins.waitForIdleSync(); delay(450)
            File(activity.getExternalFilesDir(null), "book-notes-$name.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        fun web(): WebView? = all(root).filterIsInstance<WebView>().filter { it.isShown }.maxByOrNull {
            val r = android.graphics.Rect(); if (it.getGlobalVisibleRect(r)) r.width() * r.height() else 0
        }
        suspend fun locator(): Locator? = withContext(Dispatchers.Main) { screen!!.field<Locator?>("latestLocator") }
        suspend fun chapter(): String = locator()?.href?.toString().orEmpty()
        suspend fun pad(vararg actions: PadAction) = withContext(Dispatchers.Main) { actions.forEach { assertTrue(screen!!.onPad(it)) } }
        /** A real tap on the element [id] in the page on screen, where the page draws it. */
        suspend fun tap(id: String) {
            val (x, y) = withContext(Dispatchers.Main) {
                val box = JSONArray(JSONArray("[" + reader()!!.evaluateJavascript(
                    "(function(){var r=document.getElementById('$id').getBoundingClientRect();return JSON.stringify([r.left+r.width/2,r.top+r.height/2]);})()") + "]").getString(0))
                val view = web()!!
                val at = IntArray(2).also(view::getLocationOnScreen)
                val scale = view.resources.displayMetrics.density
                (at[0] + box.getDouble(0) * scale).toFloat() to (at[1] + box.getDouble(1) * scale).toFloat()
            }
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(down, down + index * 60L, action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                ins.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
        }
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences(scroll = true))
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), work, "edition", "The Last Observatory", { true })
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { withContext(Dispatchers.Main) { reader()?.evaluateJavascript("document.body.innerText.includes('pines')") == "true" } }
            delay(800)

            // E3: the time left, under the title, from the positions before any reading has calibrated it.
            pad(PadAction.Back)
            until("the time left") { withContext(Dispatchers.Main) { screen!!.field<TextView>("timeLeftView").let { it.isShown && it.text.contains(" left in chapter · ") && it.text.endsWith(" in book") } } }
            shot("01-time-left")
            // Start closes the menu (Ⓑ again would leave the book); the page grows back to its size.
            pad(PadAction.Menu)
            suspend fun pageAtFullSize() = until("the page at its full size") { withContext(Dispatchers.Main) {
                !screen!!.field<Boolean>("controlsVisible") && screen!!.field<FrameLayout>("navigatorContainer").let { it.scaleX == 1f && it.scaleY == 1f }
            } }
            pageAtFullSize()
            delay(500)

            // E5: the note reference opens the note as a card; the page stays.
            val before = locator()
            tap(com.pocketds.hub.ui.ReaderFixtures.NOTE_REF)
            until("the note's card") { withContext(Dispatchers.Main) { screen!!.field<FootnoteCard>("footnoteCard").isOpen } }
            withContext(Dispatchers.Main) {
                assertEquals(ReaderFixtures.NOTE_TEXT, screen!!.field<FootnoteCard>("footnoteCard").text.toString())
                assertEquals("Close note", screen!!.hints().last().label)
            }
            shot("02-footnote-card")
            // #20: the card's face is solid. Away from its own words and buttons it is one colour,
            // however many lines of the page lie under it (they showed through at a twentieth).
            val (panelAt, panelSize, drawn) = withContext(Dispatchers.Main) {
                val card = screen!!.field<FootnoteCard>("footnoteCard")
                val panel = card.getChildAt(0) as ViewGroup
                val at = IntArray(2).also(panel::getLocationOnScreen)
                // What the card draws itself: its eyebrow, each line of the note, its two buttons.
                fun box(view: View, left: Int = 0, top: Int = 0, right: Int = view.width, bottom: Int = view.height) =
                    android.graphics.Rect(left, top, right, bottom).also { panel.offsetDescendantRectToMyCoords(view, it) }
                val words = card.field<TextView>("words")
                val lines = (0 until words.layout.lineCount).map { line ->
                    box(words, words.paddingLeft + words.layout.getLineLeft(line).toInt(), words.layout.getLineTop(line),
                        words.paddingLeft + words.layout.getLineRight(line).toInt() + 1, words.layout.getLineBottom(line))
                }
                Triple(at, panel.width to panel.height,
                    listOf(box(panel.getChildAt(0)), box(card.field<View>("follow")), box(card.field<View>("close"))) + lines)
            }
            val screenshot = ins.uiAutomation.takeScreenshot()
            val edge = (16 * activity.resources.displayMetrics.density).toInt()
            var lightest = 0
            var darkest = 255
            var sampled = 0
            for (y in edge until panelSize.second - edge step 2) for (x in edge until panelSize.first - edge step 3) {
                // Clear of what the card draws, focus ring and all.
                if (drawn.any { android.graphics.Rect(it).apply { inset(-edge / 2, -edge / 2) }.contains(x, y) }) continue
                val pixel = screenshot.getPixel(panelAt[0] + x, panelAt[1] + y)
                val luma = (android.graphics.Color.red(pixel) * 299 + android.graphics.Color.green(pixel) * 587 + android.graphics.Color.blue(pixel) * 114) / 1000
                lightest = maxOf(lightest, luma); darkest = minOf(darkest, luma); sampled++
            }
            screenshot.recycle()
            assertTrue("Enough of the card's face was looked at: $sampled", sampled > 1_000)
            assertTrue("Nothing of the page shows through the note's card: luma $darkest..$lightest", lightest - darkest <= 3)
            pad(PadAction.Back)
            withContext(Dispatchers.Main) { assertTrue(!screen!!.field<FootnoteCard>("footnoteCard").isOpen) }
            assertEquals("The page did not move", before?.href, locator()?.href)
            assertNull(withContext(Dispatchers.Main) { screen!!.field<Locator?>("returnLocator") })

            // E5: a link is followed, and Return to previous place goes back.
            tap(ReaderFixtures.LINK_ID)
            until("the second chapter") { chapter().endsWith("two.xhtml") }
            until("a way back") { withContext(Dispatchers.Main) { screen!!.field<Locator?>("returnLocator") != null } }
            assertTrue(notices.joinToString(), notices.any { it.contains("Return to previous place") })
            pad(PadAction.Back)
            until("the way back in the menu") { withContext(Dispatchers.Main) { all(root).any { it.isShown && it.contentDescription == "Return to previous place" } } }
            shot("03-return-to-previous-place")
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Return to previous place" }.performClick() }
            until("the first chapter again") { chapter().endsWith("one.xhtml") }
            pad(PadAction.Menu)
            pageAtFullSize()

            // E1: a gentle push on the right stick moves the text: under a pixel a frame, carried.
            val start = withContext(Dispatchers.Main) { web()!!.scrollY }
            repeat(60) { pad(PadAction.Pan(0f, 0.0004f)); delay(16) }
            delay(200)
            val gentle = withContext(Dispatchers.Main) { web()!!.scrollY }
            assertTrue("A gentle push moved the text: $start -> $gentle", gentle > start)

            // E1: the D-pad scrolls on to the end of the chapter, and then into the next.
            var presses = 0
            while (chapter().endsWith("one.xhtml") && presses < 80) {
                pad(PadAction.Step(Direction.DOWN)); delay(220); presses++
            }
            assertTrue("Scrolling on went into the next chapter after $presses presses", chapter().endsWith("two.xhtml"))
            shot("04-next-chapter")
            assertNotNull(screen)
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "book-notes-failure.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            Prefs.of(activity).edit().remove("reading_pace:$work:edition").apply()
            EpubAppearanceStore.save(activity, original); HubSettings.save(activity, oldUrl, oldToken); server.shutdown()
        }
    }
}
