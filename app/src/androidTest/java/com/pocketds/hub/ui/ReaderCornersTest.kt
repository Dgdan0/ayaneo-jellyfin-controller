package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PageInfo
import com.pocketds.hub.reader.PageInfoChoice
import com.pocketds.hub.reader.PageInfoCorner
import com.pocketds.hub.reader.PageInfoView
import com.pocketds.hub.reader.ReadAlongDock
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.PageInfoSettings
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
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Kindle's corners over a book's page (#42), against a local hub and a generated
 * book: the time at the top right, what the bottom left chooses, the percentage at
 * the bottom right, kept clear of the text, hidden while the bars are up, moved on by
 * a tap or L3, changed from the appearance sheet, and shown reading along.
 * Nothing here reaches a real server or a real book.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderCornersTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun host(activity: ReaderFixtureActivity) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(25_000) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync(); delay(500)
        File(activity.getExternalFilesDir(null), "reader-corners-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** The corners' words as they are on screen: top right, bottom left, bottom right ("" where hidden). */
    private fun words(screen: EpubReaderScreen): Triple<String, String, String> {
        val view: PageInfoView = screen.field("pageInfo")
        val texts = all(view).filterIsInstance<TextView>()
        fun at(index: Int) = texts[index].takeIf { it.visibility == View.VISIBLE }?.text?.toString().orEmpty()
        return Triple(at(0), at(1), at(2))
    }

    @Test fun cornersShowWhereYouAreAndMoveOnWithATapOrAKey(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldInfo = PageInfoSettings.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(ReaderFixtures.epub(aligned = false))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            PageInfoSettings.save(activity, PageInfoChoice())
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "corners-${System.nanoTime()}", "edition", "The Last Observatory", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { reader() != null && screen!!.field<View>("loading").visibility != View.VISIBLE }
            until("the corners to know the place") { words(screen!!).second.startsWith("Page ") }
            delay(1200)
            shot(activity, "01-all-three")

            withContext(Dispatchers.Main) {
                val (clock, left, right) = words(screen!!)
                assertTrue("a time: $clock", Regex("""\d{1,2}:\d{2}( [AP]M)?""").matches(clock))
                assertTrue("a page of the book: $left", Regex("""Page \d+ of \d+""").matches(left))
                assertTrue("a percentage: $right", Regex("""\d+%""").matches(right))
                // The page keeps clear of the corners: the navigator is inset by the strips.
                val strip = Styler.dpInt(activity, PageInfo.STRIP_DP.toFloat())
                val host: View = screen!!.field("pageHost")
                assertEquals("the top strip", strip, host.top)
                assertEquals("the bottom strip", strip, (host.parent as View).height - host.bottom)
            }

            // A tap on the bottom left moves to the next choice, and the choice is kept.
            val seen = mutableListOf<String>()
            repeat(5) {
                withContext(Dispatchers.Main) {
                    seen += words(screen!!).second
                    all(screen!!.field<PageInfoView>("pageInfo")).filterIsInstance<TextView>()[1].performClick()
                }
                delay(200)
            }
            withContext(Dispatchers.Main) {
                assertTrue(seen.toString(), seen[0].startsWith("Page ") && seen[0].contains(" of ") && !seen[0].endsWith("chapter"))
                assertTrue(seen.toString(), seen[1].endsWith("in chapter"))
                assertTrue(seen.toString(), seen[2].endsWith("left in chapter"))
                assertTrue(seen.toString(), seen[3].endsWith("left in book"))
                assertEquals("None", "", seen[4])
                // Five taps are a whole round: back to where it began, and kept.
                assertEquals(PageInfoCorner.PAGE_IN_BOOK, PageInfoSettings.load(activity).corner)
            }
            // L3 does the same from the pad: two presses from the start is the time left in the chapter.
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Click(Stick.LEFT)) }
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Click(Stick.LEFT)) }
            delay(400)
            withContext(Dispatchers.Main) { assertEquals(PageInfoCorner.TIME_IN_CHAPTER, PageInfoSettings.load(activity).corner) }
            shot(activity, "02-time-left-in-chapter")

            // The menu replaces the corners.
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Menu) }
            until("the menu") { screen!!.field<Boolean>("controlsVisible") }
            delay(500)
            withContext(Dispatchers.Main) { assertFalse("hidden while the bars are up", screen!!.field<PageInfoView>("pageInfo").isShown) }
            shot(activity, "03-menu-up")

            // The appearance sheet: a Page info part, and the page shows the change at once.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Reading appearance" && it.isShown }.performClick() }
            until("the sheet") { all(root).any { it is TextView && it.isShown && it.text == "Page info" } }
            withContext(Dispatchers.Main) {
                all(root).first { it is TextView && it.isShown && it.text == "Page info" }.performClick()
            }
            until("the Page info part") { all(root).any { it is TextView && it.isShown && it.text == "Time left in book" } }
            delay(600)
            shot(activity, "04-page-info-sheet")
            withContext(Dispatchers.Main) {
                // The corners show, shrunk with the page, while the sheet is open.
                assertTrue(screen!!.field<PageInfoView>("pageInfo").isShown)
                fun press(tag: String) {
                    all(root).first { it.tag == tag && it.isShown }.performClick()
                }
                press("info:clock")
                assertFalse(PageInfoSettings.load(activity).clock)
                press("info:TIME_IN_BOOK")
                assertEquals(PageInfoCorner.TIME_IN_BOOK, PageInfoSettings.load(activity).corner)
                press("info:percentage")
                assertFalse(PageInfoSettings.load(activity).percentage)
            }
            delay(600)
            shot(activity, "05-clock-and-percentage-off")
            withContext(Dispatchers.Main) {
                val (clock, left, right) = words(screen!!)
                assertEquals("", clock); assertEquals("", right)
                assertTrue(left, left.endsWith("left in book"))
                // No top strip now, and the foot keeps one for the bottom left alone.
                val host: View = screen!!.field("pageHost")
                assertEquals(0, host.top)
            }
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-corners-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            PageInfoSettings.save(activity, oldInfo)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    @Test fun readingAlongShowsTheCornersToo(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldInfo = PageInfoSettings.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(ReaderFixtures.epub(aligned = true))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            PageInfoSettings.save(activity, PageInfoChoice(corner = PageInfoCorner.TIME_IN_BOOK))
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "corners-ra-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                val root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the narration dock") { screen!!.field<ReadAlongDock>("narrationDock").isShown }
            // The menu is up when the narration is ready; Start puts it away and the corners are the page's.
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Menu) }
            until("the menu away") { !screen!!.field<Boolean>("controlsVisible") }
            until("the time left, from the narration") { words(screen!!).second.endsWith("left in book") }
            delay(800)
            withContext(Dispatchers.Main) {
                assertTrue("shown reading along", screen!!.field<PageInfoView>("pageInfo").isShown)
                // L3 is the voice's here, not the corner's: it follows the narration and leaves the choice alone.
                screen!!.onPad(PadAction.Click(Stick.LEFT))
                assertEquals(PageInfoCorner.TIME_IN_BOOK, PageInfoSettings.load(activity).corner)
            }
            shot(activity, "06-read-along")
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-corners-ra-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            PageInfoSettings.save(activity, oldInfo)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }
}
