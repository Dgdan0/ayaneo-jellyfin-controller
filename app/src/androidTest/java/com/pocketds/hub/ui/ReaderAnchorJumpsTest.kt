package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PageInfoChoice
import com.pocketds.hub.reader.PageInfoCorner
import com.pocketds.hub.reader.PageInfoView
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * Going to a place in ANOTHER file of the book lands where it says, every time (#59). Readium's own `go(locator)` is not
 * reliable across files when the locator names a place in the file (the page reached varied from run to run: from the
 * Prologue an entry on page 94 landed on 80 to 96, from the Epilogue entries on pages 48 to 164 landed on 99, 254, 169 and
 * 258, the end of the file). The reader goes to the file first, waits for it to be laid out, then to the place ([com.pocketds.hub.reader.AnchorJump]).
 *
 * The generated book of #55 ([ReaderFixtures.contentsEpub], 300 pages by the hub's count): Contents entries into the long
 * second part from the Prologue before it and the Epilogue after it, repeatedly; a bookmark and a search result in it, the
 * same. Nothing here opens a real book or reaches a real server.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderAnchorJumpsTest {
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

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /** A Contents row as it is on screen: its words, and the page at its right edge (null where it has none). */
    private fun contentsRow(row: View): Pair<String, Int?> {
        val texts = all(row).filterIsInstance<TextView>()
        val figure = texts.lastOrNull()?.takeIf { it.fontFeatureSettings == "tnum" && it.visibility == View.VISIBLE }
        return texts.first().text.toString() to figure?.text?.toString()?.toIntOrNull()
    }

    private inner class Book(val activity: ReaderFixtureActivity, val screen: EpubReaderScreen, val root: View, val log: MutableList<String>) {
        val overlay: SidePanelView get() = screen.field("overlay")

        /** The corner's page, "Page 96 of 300" -> 96. */
        fun corner(): Int? {
            val texts = all(screen.field<PageInfoView>("pageInfo")).filterIsInstance<TextView>()
            return Regex("""Page (\d+) of \d+""").matchEntire(texts[2].text.toString())?.groupValues?.get(1)?.toInt()
        }

        fun locator(): Locator? = screen.field("latestLocator")

        /** The page the book has settled on: the corner and the file unchanged for half a second, the page showing. */
        suspend fun settled(): Int {
            var last = ""
            var since = System.currentTimeMillis()
            val start = since
            while (System.currentTimeMillis() - start < 12_000) {
                val now = withContext(Dispatchers.Main) { "${corner()} ${locator()?.href} ${screen.field<View>("pageHost").alpha}" }
                if (now != last) { last = now; since = System.currentTimeMillis() }
                else if (System.currentTimeMillis() - since >= 600 && !now.endsWith(" 0.0")) break
                delay(80)
            }
            return withContext(Dispatchers.Main) { corner() ?: throw AssertionError("no page: $last") }
        }

        suspend fun contents(): List<Pair<String, Int?>> {
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Table of contents" }.performClick() }
            until("Contents") { overlay.isOpen && overlay.rows.size == ReaderFixtures.CONTENTS_TITLES.size }
            return withContext(Dispatchers.Main) { overlay.rows.map(::contentsRow) }
        }

        /** Chooses an entry of an open Contents, and waits for the page it names to have settled. */
        suspend fun choose(title: String): Int {
            val begun = System.currentTimeMillis()
            withContext(Dispatchers.Main) { overlay.rows.first { contentsRow(it).first.trim() == title }.performClick() }
            // How long the page is hidden while the file is gone to first (a jump within a file hides nothing): kept in the log.
            var hiddenAt = -1L
            while (System.currentTimeMillis() - begun < 6_000) {
                val alpha = withContext(Dispatchers.Main) { screen.field<View>("pageHost").alpha }
                val now = System.currentTimeMillis() - begun
                if (alpha == 0f && hiddenAt < 0) hiddenAt = now
                if (hiddenAt >= 0 && alpha == 1f) { android.util.Log.i("ANCHOR", "$title: the page was hidden for ${now - hiddenAt} ms"); break }
                if (hiddenAt < 0 && now > 700) break
                delay(10)
            }
            until("Contents to close") { !overlay.isOpen }
            return settled()
        }

        suspend fun goTo(title: String): Int { contents(); return choose(title) }
    }

    private suspend fun withBook(block: suspend Book.() -> Unit) {
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
        val log = mutableListOf<String>()
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, ScreenComfort())
            PageInfoSettings.save(activity, PageInfoChoice(corner = PageInfoCorner.PAGE_IN_BOOK))
            lateinit var root: View
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "anchor-${System.nanoTime()}", "edition", "The Last Observatory", { true }, bookPages = 300)
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().isNotEmpty() && screen!!.field<View>("loading").visibility != View.VISIBLE }
            val book = Book(activity, screen!!, root, log)
            until("the corners to know the place") { book.corner() != null }
            delay(800)
            book.block()
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-anchor-jumps-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            shell("screencap -p /sdcard/Download/anchor-jumps-failure.png")
            throw AssertionError((failure.message ?: failure.toString()) + "\n" + log.joinToString("\n"), failure)
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            PageInfoSettings.save(activity, oldInfo)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    private val anchors = listOf("2. The ridge", "1. First light", "3. The lens", "The keeper's log")

    /**
     * Every Contents entry into the long second part, chosen from the Prologue (an earlier file) and from the Epilogue (a later
     * one): the same page each time, the page it lands on from the file itself (a jump within a file, which was always exact),
     * and the page its row names, to within the estimate a row's number is.
     */
    @Test fun aContentsEntryIntoAnotherFileLandsOnTheSamePageEveryTime(): Unit = runBlocking {
        withBook {
            contents()
            until("the numbers") { overlay.rows.map(::contentsRow).filter { it.first.trim() != "Author's note" }.all { it.second != null } }
            val rows = withContext(Dispatchers.Main) { overlay.rows.map(::contentsRow) }.associate { it.first.trim() to it.second }
            withContext(Dispatchers.Main) { overlay.cancel() }
            val total = withContext(Dispatchers.Main) { Regex("""of (\d+)""").find(all(screen.field<PageInfoView>("pageInfo")).filterIsInstance<TextView>()[2].text)!!.groupValues[1].toInt() }
            val tolerance = maxOf(3, total / 50)
            val landings = mutableListOf<String>()
            for (anchor in anchors) {
                // Where the row lands from inside the file: the page every other start must equal.
                goTo("Part one")
                val inFile = goTo(anchor)
                landings += "$anchor (row ${rows[anchor]}): inside the file $inFile"
                assertTrue("$anchor: row ${rows[anchor]} against the page $inFile within the file", Math.abs(inFile - rows.getValue(anchor)!!) <= tolerance)
                for (origin in listOf("Prologue", "Epilogue")) {
                    val repeats = if (anchor == "2. The ridge") 10 else 3
                    val pages = (1..repeats).map { goTo(origin); goTo(anchor) }
                    landings += "$anchor from $origin: $pages"
                    pages.forEachIndexed { index, page ->
                        assertEquals("$anchor from $origin, jump ${index + 1} of $repeats: $pages (inside the file $inFile)", inFile, page)
                    }
                }
            }
            log += landings
            val shown = withContext(Dispatchers.Main) { screen.field<View>("pageHost").alpha }
            assertEquals("the page is shown, not left hidden", 1f, shown, 0f)
        }
    }

    /** A bookmark's page, however far away it was taken from: the page it was made on. */
    @Test fun aBookmarkInAnotherFileLandsOnItsPageEveryTime(): Unit = runBlocking {
        withBook {
            goTo("3. The lens")
            val made = settled()
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Add bookmark" }.performClick() }
            until("the bookmark") { all(root).any { it.contentDescription == "Remove bookmark" } }
            val landings = mutableListOf<String>()
            for (origin in listOf("Prologue", "Epilogue")) repeat(4) {
                goTo(origin)
                withContext(Dispatchers.Main) {
                    screen.javaClass.getDeclaredMethod("showBookmarks").apply { isAccessible = true }.invoke(screen)
                }
                until("the bookmark's row") { overlay.isOpen && overlay.rows.size == 1 }
                withContext(Dispatchers.Main) { overlay.rows.first().performClick() }
                until("Go to bookmark") { overlay.rows.size == 2 }
                withContext(Dispatchers.Main) { overlay.rows.first().performClick() }
                until("the panel to close") { !overlay.isOpen }
                val page = settled()
                landings += "from $origin: $page (made on $made)"
                log += landings.last()
                assertEquals("the bookmark made on page $made, from $origin: $landings", made, page)
            }
            assertEquals(1f, withContext(Dispatchers.Main) { screen.field<View>("pageHost").alpha }, 0f)
        }
    }

    /** A search result's page: the same from any file, and the page the words are on. */
    @Test fun aSearchResultInAnotherFileLandsOnItsPageEveryTime(): Unit = runBlocking {
        withBook {
            suspend fun search(): Int {
                withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Search this book" }.performClick() }
                until("the search panel") { overlay.isOpen && all(root).any { it is EditText && it.isShown } }
                withContext(Dispatchers.Main) {
                    all(root).filterIsInstance<EditText>().first { it.isShown }.setText("The lens")
                    overlay.rows.first().performClick()
                }
                until("a result") { overlay.rows.size >= 2 && overlay.rows.first().let { contentsRow(it).first == "Search again" } }
                withContext(Dispatchers.Main) { overlay.rows[1].performClick() }
                until("the panel to close") { !overlay.isOpen }
                return settled()
            }
            goTo("Part one")
            val inFile = search()
            log += "search from inside the file: $inFile"
            val landings = mutableListOf<Int>()
            for (origin in listOf("Prologue", "Epilogue")) repeat(4) {
                goTo(origin)
                landings += search()
                log += "search from $origin: $landings"
                assertEquals("the result inside the file $inFile, from $origin: $landings", inFile, landings.last())
            }
            assertEquals(1f, withContext(Dispatchers.Main) { screen.field<View>("pageHost").alpha }, 0f)
        }
    }
}
