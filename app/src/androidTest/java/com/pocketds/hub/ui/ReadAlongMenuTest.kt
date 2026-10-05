package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.ReadAlongDock
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReaderBars
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read along is the book with a player (#21). Its menu is the book's: a tap on
 * the page shows and hides it and leaves the voice alone (Readium reports a drag
 * End with every tap, which used to pause the narration as the menu closed),
 * Start does too, and Ⓑ and Back leave as they leave a book. The narration's
 * dock is the menu's lower bar: the page makes room for it with the menu open
 * and fills the screen with the pill once it closes. Generated book and
 * silence on a local hub; nothing reaches a real server.
 */
@RunWith(AndroidJUnit4::class)
class ReadAlongMenuTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(20_000) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    /** A finger on the screen at [fx], [fy] of [view] as it is drawn (scaled and moved by the menu). */
    private fun tap(view: View, fx: Float = .5f, fy: Float = .5f) {
        val at = IntArray(2)
        ins.runOnMainSync { view.getLocationOnScreen(at) }
        val x = at[0] + view.width * view.scaleX * fx
        val y = at[1] + view.height * view.scaleY * fy
        val down = SystemClock.uptimeMillis()
        ins.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        ins.sendPointerSync(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync(); delay(500)
        File(activity.getExternalFilesDir(null), "read-along-menu-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun readAlongIsTheBookWithAPlayerAndItsMenuClosesAsABooksDoes(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        // Eight sentences of eight seconds: a minute of narration, longer than anything here waits.
        val server = ReaderFixtures.fileServer(ReaderFixtures.epub(aligned = true, sentenceSeconds = 8))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        val exits = AtomicInteger()
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> { exits.incrementAndGet(); true }; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        fun menu() = screen!!.field<Boolean>("controlsVisible")
        fun page() = screen!!.field<FrameLayout>("navigatorContainer")
        fun narration(): ReadAlongPlayback? = screen!!.field("narration")
        fun dock() = screen!!.field<ReadAlongDock>("narrationDock")
        fun bars() = screen!!.field<ReaderBars>("bars")
        fun pill() = screen!!.field<TextView>("narrationPill")
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, ScreenComfort())
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "ra-menu-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the narration and its menu") { narration() != null && menu() && dock().isShown }
            delay(800)

            // The book's menu with the player as its lower bar: in the bars, above the keys, where the book's row is.
            withContext(Dispatchers.Main) {
                val bars = bars()
                assertSame("The dock is the menu's lower bar", dock(), bars.lowerBar)
                assertSame(bars.bottom, dock().parent)
                assertEquals(View.GONE, bars.bottomRow.visibility)
                assertTrue(bars.keys.isShown)
                val dockAt = IntArray(2).also(dock()::getLocationOnScreen)
                val keysAt = IntArray(2).also(bars.keys::getLocationOnScreen)
                assertTrue("The player stands over the keys", dockAt[1] + dock().height <= keysAt[1])
                // The page makes room: shrunk, and clear of the player.
                assertTrue("The page shrinks with the menu open: ${page().scaleY}", page().scaleY < 0.95f)
                val pageAt = IntArray(2).also(page()::getLocationOnScreen)
                assertTrue("The page stops above the player", pageAt[1] + page().height * page().scaleY <= dockAt[1] + 1)
            }
            shot(activity, "01-menu-with-player")

            // Start closes the menu and opens it again.
            withContext(Dispatchers.Main) { assertTrue(screen!!.onPad(PadAction.Menu)) }
            until("Start to close the menu") { !menu() && !dock().isShown }
            until("the page to fill the screen") { page().scaleY == 1f && page().translationY == 0f }
            withContext(Dispatchers.Main) { assertTrue(screen!!.onPad(PadAction.Menu)) }
            until("Start to open the menu") { menu() && dock().isShown }
            delay(600)

            // Play from the menu's player, then a tap on the page closes the menu and the voice reads on.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Play narration" && it.isShown }.performClick() }
            until("narrating") { narration()!!.isPlaying }
            delay(1_000)
            tap(page())
            until("a tap to close the menu") { !menu() }
            until("the page to fill the screen") { page().scaleY == 1f }
            until("the pill") { pill().isShown && pill().text.contains("Following") }
            delay(1_500)
            withContext(Dispatchers.Main) {
                assertTrue("The tap left the voice reading", narration()!!.isPlaying)
                assertFalse("The menu stays closed while the voice reads on", menu())
            }
            shot(activity, "02-closed-with-pill")

            // A tap at the page's edge turns no page, and leaves the voice alone too.
            tap(page(), fx = .1f)
            delay(1_200)
            withContext(Dispatchers.Main) {
                assertTrue("An edge tap left the voice reading", narration()!!.isPlaying)
                assertFalse(menu())
            }

            // A tap opens the menu again, the player in it, still reading.
            tap(page())
            until("a tap to open the menu") { menu() && dock().isShown }
            withContext(Dispatchers.Main) { assertTrue(narration()!!.isPlaying) }
            // Pause and play from the menu.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Pause narration" && it.isShown }.performClick() }
            until("paused") { !narration()!!.isPlaying }
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Play narration" && it.isShown }.performClick() }
            until("reading again") { narration()!!.isPlaying }

            // Ⓑ and Back with the menu open leave, as they leave a book.
            withContext(Dispatchers.Main) {
                assertFalse("Back has no sheet to close: the reader goes", screen!!.onSystemBack())
                assertTrue(screen!!.onPad(PadAction.Back))
                assertEquals("Ⓑ on the menu leaves the book", 1, exits.get())
            }
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "read-along-menu-failure.png").outputStream().use {
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
}
