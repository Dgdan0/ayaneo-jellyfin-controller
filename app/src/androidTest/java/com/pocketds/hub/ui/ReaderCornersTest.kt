package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.EpubPagePalette
import com.pocketds.hub.reader.PageGeometry
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
import org.junit.Assert.assertNull
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
        return Triple(at(0), at(2), at(3))
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
                // Kindle's margins (#47): the inset at each side is the outer margin less Readium's own gutter.
                val inset = Styler.dpInt(activity, PageGeometry.insetDp(1f).toFloat())
                assertEquals("the left inset", inset, host.left)
                assertEquals("the right inset", inset, (host.parent as View).width - host.right)
                // The title in the middle of the top line, in capitals, the corners in the page's own ink.
                val title = all(screen!!.field<PageInfoView>("pageInfo")).filterIsInstance<TextView>()[1]
                assertEquals("THE LAST OBSERVATORY", title.text.toString())
                assertEquals(PageInfo.ink(EpubPagePalette.of(EpubReaderPreferences().theme).second), title.currentTextColor)
                assertEquals(Gravity.CENTER_HORIZONTAL, title.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            }

            // A tap on the bottom left moves to the next choice, and the choice is kept.
            val seen = mutableListOf<String>()
            repeat(5) {
                withContext(Dispatchers.Main) {
                    seen += words(screen!!).second
                    all(screen!!.field<PageInfoView>("pageInfo")).filterIsInstance<TextView>()[2].performClick()
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
                // The title still keeps the top clear, and the foot keeps a strip for the bottom left alone.
                val host: View = screen!!.field("pageHost")
                assertEquals(Styler.dpInt(activity, PageInfo.STRIP_DP.toFloat()), host.top)
                all(root).first { it.tag == "info:title" && it.isShown }.performClick()
                assertFalse(PageInfoSettings.load(activity).title)
            }
            delay(400)
            withContext(Dispatchers.Main) {
                // No top strip once the title and the clock are both off.
                assertEquals(0, screen!!.field<View>("pageHost").top)
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

    /**
     * "Page in book" counts the book's own pages from the hub, as its page and Resume do, and Readium's positions only
     * where the hub has none; "Page in chapter" is the chapter's share of the same pages.
     */
    @Test fun pagesAreTheBooksOwnWhereTheHubCountedThem(): Unit = runBlocking {
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
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        suspend fun open(pages: Int) {
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "corners-pages-${System.nanoTime()}", "edition", "The Last Observatory", { true }, bookPages = pages)
                val root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { reader() != null && screen!!.field<View>("loading").visibility != View.VISIBLE }
            until("the corners to know the place") { words(screen!!).second.startsWith("Page ") }
            delay(1000)
        }
        suspend fun close() { withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView() } }
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            PageInfoSettings.save(activity, PageInfoChoice(corner = PageInfoCorner.PAGE_IN_BOOK))
            open(765)
            withContext(Dispatchers.Main) { assertEquals("Page 1 of 765", words(screen!!).second) }
            // A page on: how far through the book it is, times 765, and the percentage beside it says the same.
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Activate) }
            until("the page to turn") { words(screen!!).second != "Page 1 of 765" }
            delay(600)
            val (_, left, right) = withContext(Dispatchers.Main) { words(screen!!) }
            val page = Regex("""Page (\d+) of 765""").matchEntire(left)?.groupValues?.get(1)?.toInt() ?: throw AssertionError("not a page of 765: $left")
            val percent = Regex("""(\d+)%""").matchEntire(right)?.groupValues?.get(1)?.toInt() ?: throw AssertionError("not a percentage: $right")
            assertTrue("page $page of 765 is $percent%", page in (percent * 765 / 100)..((percent + 1) * 765 / 100 + 1))
            shot(activity, "07-pages-from-the-hub")
            // The chapter's pages are its share of the same 765: the fixture is one chapter, the whole book.
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Click(Stick.LEFT)) }
            delay(400)
            withContext(Dispatchers.Main) {
                // The fixture is one chapter, so the chapter's page is the book's.
                assertEquals("Page $page of 765 in chapter", words(screen!!).second)
            }
            shot(activity, "08-page-in-chapter")
            close()

            // The hub has no page count: Readium's positions, a handful in this book.
            PageInfoSettings.save(activity, PageInfoChoice(corner = PageInfoCorner.PAGE_IN_BOOK))
            open(0)
            withContext(Dispatchers.Main) {
                val total = Regex("""Page 1 of (\d+)""").matchEntire(words(screen!!).second)?.groupValues?.get(1)?.toInt()
                    ?: throw AssertionError("not a first page: ${words(screen!!).second}")
                assertTrue("positions, not a hub count: $total", total in 1..40)
            }
            shot(activity, "09-pages-from-positions")
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-corners-pages-failure.png").outputStream().use {
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

    /** A screenshot kept on the device's shared storage, where it outlives the test app (Gradle uninstalls it after the run). */
    private suspend fun keep(name: String) {
        ins.waitForIdleSync(); delay(600)
        shell("screencap -p /sdcard/Download/$name.png")
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /** A Contents row as it is on screen: its words, and the page at its right edge (null where it has none). */
    private fun contentsRow(row: View): Pair<String, Int?> {
        val texts = all(row).filterIsInstance<TextView>()
        val figure = texts.lastOrNull()?.takeIf { it.fontFeatureSettings == "tnum" && it.visibility == View.VISIBLE }
        return texts.first().text.toString() to figure?.text?.toString()?.toIntOrNull()
    }

    /**
     * Contents ends each chapter's row with the page it starts on (#55), in the count of the corner's "Page X of Y": chosen,
     * a row that names a file lands on exactly that page, and one that points into a file within a few pages of it. Done with
     * the hub's own page count and with Readium's positions; a row whose file is not in the reading order has no number.
     */
    @Test fun contentsEndsEachRowWithThePageItStartsOn(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldInfo = PageInfoSettings.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(ReaderFixtures.contentsEpub())
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        fun overlay() = screen!!.field<SidePanelView>("overlay")
        suspend fun open(pages: Int) {
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "contents-pages-${System.nanoTime()}", "edition", "The Last Observatory", { true }, bookPages = pages)
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { reader() != null && screen!!.field<View>("loading").visibility != View.VISIBLE }
            until("the corners to know the place") { words(screen!!).second.startsWith("Page ") }
            delay(800)
        }
        suspend fun close() { withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView() } }
        suspend fun openContents(): List<Pair<String, Int?>> {
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Table of contents" }.performClick() }
            until("Contents") { overlay().isOpen && overlay().rows.size == ReaderFixtures.CONTENTS_TITLES.size }
            return withContext(Dispatchers.Main) { overlay().rows.map(::contentsRow) }
        }
        /** The page the corner says, once the page has settled after a jump. */
        suspend fun cornerPage(): Int {
            delay(1600)
            return withContext(Dispatchers.Main) {
                Regex("""Page (\d+) of \d+""").matchEntire(words(screen!!).second)?.groupValues?.get(1)?.toInt()
                    ?: throw AssertionError("not a page: ${words(screen!!).second}")
            }
        }
        suspend fun choose(title: String) {
            withContext(Dispatchers.Main) { overlay().rows.first { contentsRow(it).first.trim() == title }.performClick() }
            until("Contents to close") { !overlay().isOpen }
        }
        suspend fun verify(bookPages: Int) {
            open(bookPages)
            // Opened at once, the numbers that need no reading are there; the others arrive on their own.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Table of contents" }.performClick() }
            until("Contents") { overlay().isOpen && overlay().rows.size == ReaderFixtures.CONTENTS_TITLES.size }
            until("the anchors' pages") {
                val rows = overlay().rows.map(::contentsRow)
                rows.filter { it.first.trim().let { t -> t != "Author's note" } }.all { it.second != null }
            }
            val listed = withContext(Dispatchers.Main) { overlay().rows.map(::contentsRow) }
            assertEquals(ReaderFixtures.CONTENTS_TITLES, listed.map { it.first })
            val page = listed.associate { it.first.trim() to it.second }
            // The entry for a file that is not in the reading order has no number.
            assertNull(page["Author's note"])
            val numbers = listed.mapNotNull { it.second }
            assertEquals("in order: $listed", numbers.sorted(), numbers)
            assertEquals("the book starts on its first page", 1, page["Prologue"])
            // Four entries into one file, four different pages.
            val inside = listOf("1. First light", "2. The ridge", "3. The lens", "The keeper's log").map { page.getValue(it)!! }
            assertEquals("each its own: $inside", inside.size, inside.distinct().size)
            assertTrue("after the file's own start: $listed", inside.first() >= page.getValue("Part one")!!)
            assertTrue("before the epilogue: $listed", inside.last() <= page.getValue("Epilogue")!!)
            // Reopened, the numbers are there at once: they are kept for the open book.
            withContext(Dispatchers.Main) { overlay().cancel() }
            assertEquals(listed, openContents())
            val total = withContext(Dispatchers.Main) { Regex("""Page \d+ of (\d+)""").matchEntire(words(screen!!).second)!!.groupValues[1].toInt() }
            val seen = mutableListOf<String>()
            // Choosing a file's row lands on exactly the page it names.
            for (title in listOf("Epilogue", "Part one", "Prologue", "Epilogue")) {
                choose(title)
                val landed = cornerPage()
                seen += "$title ${page[title]} -> $landed of $total"
                assertEquals("$title: $seen", page[title], landed)
                openContents()
            }
            // An entry into a file is on, or very near, the page it says: the anchor's share of the file is an estimate.
            // Measured from the file's own top, where Readium's jump to an anchor is steady: across files it can
            // settle short or long of the anchor, whatever the number says.
            for (title in listOf("1. First light", "2. The ridge", "3. The lens", "The keeper's log")) {
                choose("Part one"); cornerPage(); openContents()
                choose(title)
                val landed = cornerPage()
                seen += "$title ${page[title]} -> $landed of $total"
                assertTrue("$title: $seen", Math.abs(landed - page[title]!!) <= maxOf(3, total / 50))
                openContents()
            }
            DebugLog.log("reader", "contents pages ($bookPages): $seen")
            // The current section keeps its words and its number.
            choose("Part one")
            openContents()
            withContext(Dispatchers.Main) {
                val part = overlay().rows.first { contentsRow(it).first == "Part one" }
                assertTrue("Current section", all(part).filterIsInstance<TextView>().any { it.text == "Current section" })
                assertEquals(page["Part one"], contentsRow(part).second)
            }
            keep(if (bookPages > 0) "toc-pages-hub-count" else "toc-pages-positions")
            withContext(Dispatchers.Main) { overlay().cancel() }
            close()
        }
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            PageInfoSettings.save(activity, PageInfoChoice(corner = PageInfoCorner.PAGE_IN_BOOK))
            verify(300)
            verify(0)
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-corners-contents-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            shell("screencap -p /sdcard/Download/contents-failure.png")
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
