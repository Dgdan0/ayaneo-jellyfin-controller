package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.ReadingLocation
import com.pocketds.hub.reader.ReadingProgress
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A book's page under the owner's layout "1" (#39), through the real client against a stand-in hub that
 * answers a work with `community`, `you` and `genres` and takes `PATCH …/you` by the hub's rules.
 * Generated cover and book; nothing here reaches a real server, book or place.
 *
 * The right of the cover is the eyebrow, title and author, then "2006 · 541 pages · 24h 38m · 4.5 from
 * readers", the formats as icon and name (Read along grey, since this book has none), the Resume pill with
 * the chapter and a round ⋯, and the genres on one line. Under the cover are the stars, "Finished Sep 2025 ·
 * 2nd time" and the shelves. The stars rate by pad and by finger, Finished asks "When did you finish?" in a
 * small centred card, and a save the hub refuses puts the page back as the hub holds it.
 */
@RunWith(AndroidJUnit4::class)
class ReadingBookPageViewTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun start(): ReaderFixtureActivity =
        (ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity)
            .also { check(it.packageName.endsWith(".uitest")) }

    private suspend fun until(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync()
        delay(700)
        File(activity.getExternalFilesDir(null), "book-page-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** A row of a side sheet by its words, as a finger would press it. */
    private fun row(root: View, words: String): View? {
        var view: View? = all(root).firstOrNull { it is TextView && it.isShown && it.text.toString() == words } ?: return null
        while (view != null && !view.isClickable) view = view.parent as? View
        return view
    }

    private fun shown(root: View, words: String) = all(root).any { it is TextView && it.isShown && it.text.toString() == words }

    private fun work(id: String) = JSONObject()
        .put("id", id).put("libraryId", "storyteller:books").put("entityType", "work").put("kind", "book")
        .put("title", "The Last Observatory").put("authors", JSONArray(listOf("A. Fixture")))
        .put("authorRefs", JSONArray().put(JSONObject().put("id", "a1").put("name", "A. Fixture")))
        .put("overview", "A generated novel about a lighthouse that watches the sky instead of the sea.")
        .put("artwork", "/v1/img/fixture/cover").put("genres", JSONArray(listOf("Fantasy", "Epic fantasy", "Magic systems")))
        .put("year", 2006)
        .put("editions", JSONArray()
            .put(JSONObject().put("id", "e1").put("workId", id).put("source", "storyteller").put("sourceItemId", "book1")
                .put("kind", "ebook").put("format", "epub").put("pageCount", 541).put("availability", "available"))
            .put(JSONObject().put("id", "e2").put("workId", id).put("source", "storyteller").put("sourceItemId", "book1")
                .put("kind", "audiobook").put("narrator", "A generated voice").put("durationMs", 88_680_000L).put("availability", "available")))
        .put("progress", JSONObject().put("percentage", 0.32).put("completed", false))
        .put("community", JSONObject().put("rating", 4.46).put("count", 1200).put("source", "hardcover"))

    @Test fun aBookPageShowsItsFormatsStarsAndFinishDateAndSavesThemToTheHub(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val oldUser = HubSettings.userId(activity) to HubSettings.userName(activity)
        val workId = "book-page-${System.nanoTime()}"
        val hub = StandInHub(workId, "book1", listOf(60))
        hub.cover = ReaderFixtures.cover(300, 450, "The Last Observatory")
        hub.page = work(workId)
        hub.you.put("rating", 4).put("finished", "2025-09").put("readCount", 2).put("status", "read")
            .put("shelves", JSONArray(listOf("cosmere", "favorites")))
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Book page test")
        // The chapter the text was left in, as the reader saves it: the Resume pill names it.
        val progress = ReadingProgress.get(activity)
        val checkpointKey = progress.session().key(workId, "book1", "epub")
        progress.store.save(checkpointKey, ReadingLocation(locator = buildJsonObject {
            put("href", "ch14.xhtml"); put("type", "application/xhtml+xml"); put("title", "Chapter 14")
            put("locations", buildJsonObject { put("progression", 0.5); put("totalProgression", 0.32) })
        }), System.currentTimeMillis())
        val pushed = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) {
                "getViewContext" -> activity
                "push" -> { pushed += args[0].javaClass.simpleName; null }
                "notify" -> { notices += args[0] as String; null }
                "back" -> true
                else -> null
            }
        } as ScreenHost
        val screen = ReadingWorkScreen(HubClient(activity), workId, "The Last Observatory", ringVisible = { true })
        lateinit var root: View
        fun header() = screen.field<DetailHeaderView>("detailHeader")
        fun actions() = screen.field<LinkedHashMap<String, View>>("actionViews")
        fun stars() = header().ratingView
        fun resume() = all(root).filterIsInstance<TextView>().first { it.isShown && (it.text.startsWith("Resume") || it.text.endsWith("again")) }.text.toString()
        /** A finger put on star [n] of the row and lifted. */
        fun touchStar(n: Int) {
            val view = stars()
            val pad = 3 * view.resources.displayMetrics.density
            val x = pad + (view.width - 2 * pad) / 5f * (n - 0.5f)
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val now = SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, action, x, view.height / 2f, 0).also { view.dispatchTouchEvent(it); it.recycle() }
            }
        }
        suspend fun openMore() {
            withContext(Dispatchers.Main) { actions()["list:more"]!!.performClick() }
            until("the more menu") { shown(root, "Add to a list") }
        }
        /** The ⋯ menu's Reading status row (#63), then one of the four in the list it opens. */
        suspend fun chooseStatus(current: String, next: String) {
            openMore()
            withContext(Dispatchers.Main) { row(root, "Reading status · $current")!!.performClick() }
            until("the statuses") { shown(root, "Not reading") && shown(root, "Want to read") }
            withContext(Dispatchers.Main) { row(root, next)!!.performClick() }
        }
        try {
            withContext(Dispatchers.Main) {
                root = screen.onCreateView(host, FrameLayout(activity))
                // The app's own window is dark (the Glass page paints its colours over it); this host's is not.
                activity.setContentView(FrameLayout(activity).apply { setBackgroundColor(0xFF161A26.toInt()); addView(root) })
                screen.onShow()
            }
            until("the book's page") { runCatching { header().titleView.text.isNotEmpty() && header().ratingView.rating == 4 }.getOrDefault(false) }

            // Right of the cover: eyebrow, title and author, the facts, the formats, Resume and the ⋯, the genres.
            withContext(Dispatchers.Main) {
                val facts = header().metadataView.text.toString()
                assertTrue(facts, facts.startsWith("2006 · 541 pages · ") && facts.endsWith(" · 4.5 from readers"))
                assertEquals("Fantasy · Epic fantasy · Magic systems", header().genresView.text.toString())
                assertTrue(header().genresView.isShown)
                assertEquals("Resume · Chapter 14 · 32%", resume())
                assertNotNull("The ⋯ is a round glass toggle", actions()["list:more"])
                // The formats: the two the book has are controls, the one it lacks is quiet and no stop for the pad.
                val chips = header().formatRow.chipViews
                assertEquals(listOf("ebook", "audiobook", "readaloud"), chips.keys.toList())
                assertTrue(chips["audiobook"]!!.isFocusable && chips["ebook"]!!.isFocusable)
                assertFalse(chips["readaloud"]!!.isFocusable)
                assertEquals(setOf("list:format:ebook", "list:format:audiobook"), actions().keys.filter { it.startsWith("list:format:") }.toSet())
                // Under the cover: your stars, when you finished and your shelves.
                assertEquals(4, stars().rating)
                assertEquals("Finished Sep 2025 · 2nd time", header().finishedView.text.toString())
                assertEquals("cosmere · favorites", header().shelvesView.text.toString())
                assertTrue(stars().isShown && header().finishedView.isShown && header().shelvesView.isShown)
            }
            shot(activity, "1-page")

            // The pad reaches every part: the stars and Resume are each other's neighbours, the formats are above
            // Resume, and the ⋯ is beside it.
            withContext(Dispatchers.Main) {
                val entry = actions()["entry"]!!
                val chips = header().formatRow.chipViews
                assertEquals(entry, stars().focusSearch(View.FOCUS_RIGHT))
                assertEquals(stars(), entry.focusSearch(View.FOCUS_LEFT))
                assertEquals(actions()["list:more"], entry.focusSearch(View.FOCUS_RIGHT))
                assertEquals(entry, chips["ebook"]!!.focusSearch(View.FOCUS_DOWN))
                assertTrue(chips.values.contains(entry.focusSearch(View.FOCUS_UP)))
                assertEquals(chips["audiobook"], chips["ebook"]!!.focusSearch(View.FOCUS_RIGHT))
            }

            // Your stars by pad: focus, Right moves the cursor to the fifth, Ⓐ rates, Ⓐ on it again takes it away.
            withContext(Dispatchers.Main) { stars().requestFocus() }
            withContext(Dispatchers.Main) {
                assertEquals("Ⓐ says what it does", "Remove rating", screen.hints().first { it.glyph == "Ⓐ" }.label)
                screen.onPad(PadAction.Step(Direction.RIGHT))
                assertEquals("Rate 5 stars", screen.hints().first { it.glyph == "Ⓐ" }.label)
                screen.onPad(PadAction.Activate)
                assertEquals("The stars answer at once", 5, stars().rating)
            }
            until("the rating sent") { hub.youWrites.size == 1 }
            assertEquals(5, hub.youWrites[0].getInt("rating"))
            assertEquals("A rating is all it writes", 1, hub.youWrites[0].length())
            withContext(Dispatchers.Main) { screen.onPad(PadAction.Activate) }
            until("the rating taken away") { hub.youWrites.size == 2 }
            assertTrue("Cleared is null, not left out", hub.youWrites[1].has("rating") && hub.youWrites[1].isNull("rating"))
            withContext(Dispatchers.Main) {
                assertEquals(0, stars().rating)
                assertTrue("A finished book nobody has rated asks for a rating", header().finishedView.text.endsWith("rate it?"))
            }
            // By finger: the second star.
            withContext(Dispatchers.Main) { touchStar(2) }
            until("the second star sent") { hub.youWrites.size == 3 }
            assertEquals(2, hub.youWrites[2].getInt("rating"))
            until("the hub's answer shown") { stars().rating == 2 && !header().finishedView.text.endsWith("rate it?") }

            // ⋯ (#63): one Reading status row, which says what the book is (finished, by the import), a list and the offline copy.
            // Finished, Mark unread and Want to read are choices of the status list, not rows of this menu.
            openMore()
            withContext(Dispatchers.Main) {
                listOf("Reading status · Finished", "Finished Sep 2025 · change the date", "Add to a list", "Remove offline copy")
                    .forEach { assertTrue(it, shown(root, it)) }
                listOf("Finished", "Mark unread", "Want to read").forEach { assertFalse("$it is a status, not a row", shown(root, it)) }
            }
            shot(activity, "2-more")

            // The row opens the four, the current one checked; Back closes it and writes nothing.
            withContext(Dispatchers.Main) { row(root, "Reading status · Finished")!!.performClick() }
            until("the statuses") { shown(root, "Not reading") && shown(root, "Want to read") }
            withContext(Dispatchers.Main) {
                listOf("Want to read", "Reading", "Finished", "Not reading").forEach { assertTrue(it, shown(root, it)) }
                assertTrue("Not reading says it keeps the place", shown(root, "Take it off Continue reading and Home; your place stays"))
            }
            shot(activity, "2b-status")
            withContext(Dispatchers.Main) { screen.onPad(PadAction.Back) }
            withContext(Dispatchers.Main) { assertEquals("Back writes nothing", 3, hub.youWrites.size) }

            // Reading, from finished by the import: the status alone is written, the month and the count are the book's history.
            chooseStatus("Finished", "Reading")
            until("the status sent") { hub.youWrites.size == 4 }
            assertEquals("A status is all it writes", 1, hub.youWrites[3].length())
            assertEquals("reading", hub.youWrites[3].getString("status"))
            until("the page reading") { header().finishedView.text.toString() == "2nd time" }
            withContext(Dispatchers.Main) {
                assertEquals("Resume · Chapter 14 · 32%", resume())
                assertEquals("Reading status · Reading", notices.last())
            }

            // Finished: a small centred card, preset to this month; the year one back; Cancel first.
            chooseStatus("Reading", "Finished")
            val now = YearMonth.now()
            val monthName = Month.of(now.monthValue).getDisplayName(TextStyle.FULL, Locale.US)
            until("the card") { shown(root, "When did you finish?") }
            withContext(Dispatchers.Main) {
                assertTrue("Month is preset to this month", all(root).any { it is TextView && it.isShown && it.text.toString().contains(monthName) })
                assertTrue("Year is preset to this year", all(root).any { it is TextView && it.isShown && it.text.toString().contains(now.year.toString()) })
                assertTrue(shown(root, "Cancel") && shown(root, "Mark finished"))
                assertEquals("Choose", screen.hints().first { it.glyph == "Ⓐ" }.label)
            }
            shot(activity, "3-finished-card")
            withContext(Dispatchers.Main) { screen.onPad(PadAction.Back) }
            withContext(Dispatchers.Main) {
                assertFalse("Back closes the card", shown(root, "When did you finish?"))
                assertEquals("Cancel writes nothing", 4, hub.youWrites.size)
            }
            chooseStatus("Reading", "Finished")
            until("the card again") { shown(root, "When did you finish?") }
            val earlier = now.minusYears(1)
            withContext(Dispatchers.Main) {
                screen.onPad(PadAction.Step(Direction.DOWN))      // Year
                screen.onPad(PadAction.Step(Direction.LEFT))      // a year back
                screen.onPad(PadAction.Step(Direction.DOWN))      // Cancel
                screen.onPad(PadAction.Step(Direction.RIGHT))     // Mark finished, beside it
                screen.onPad(PadAction.Activate)
            }
            until("the finish sent") { hub.youWrites.size == 5 }
            assertEquals(earlier.toString(), hub.youWrites[4].getString("finished"))
            assertEquals("Read before and not finished now, so read again", 3, hub.youWrites[4].getInt("readCount"))
            assertEquals("It says the status as well as the month", "finished", hub.youWrites[4].getString("status"))
            until("the page finished") { resume() == "Read again" }
            withContext(Dispatchers.Main) {
                assertFalse(shown(root, "When did you finish?"))
                val month = earlier.month.getDisplayName(TextStyle.SHORT, Locale.US) + " " + earlier.year
                assertEquals("Finished $month · 3rd time", header().finishedView.text.toString())
                assertTrue(notices.last(), notices.last().startsWith("Marked finished"))
            }
            shot(activity, "4-finished")

            // Choosing another status in the same visit puts back what the finish changed, and says the new status.
            chooseStatus("Finished", "Not reading")
            until("the finish undone") { hub.youWrites.size == 6 }
            assertEquals("2025-09", hub.youWrites[5].getString("finished"))
            assertEquals(2, hub.youWrites[5].getInt("readCount"))
            assertEquals("not-reading", hub.youWrites[5].getString("status"))
            until("the page put down") { resume() == "Resume · Chapter 14 · 32%" }
            withContext(Dispatchers.Main) { assertEquals("2nd time", header().finishedView.text.toString()) }
            openMore()
            withContext(Dispatchers.Main) { assertTrue(shown(root, "Reading status · Not reading")) }
            shot(activity, "4b-not-reading")
            withContext(Dispatchers.Main) { screen.onPad(PadAction.Back) }

            // A hub that cannot save: the page says so and shows what the hub holds.
            hub.youRefused = 500 to "internal"
            withContext(Dispatchers.Main) {
                stars().requestFocus()
                screen.onPad(PadAction.Step(Direction.RIGHT))
                screen.onPad(PadAction.Activate)
            }
            until("the refusal said") { notices.any { it.startsWith("Your rating could not be saved") } }
            until("the page as the hub holds it") { stars().rating == 2 }
            hub.youRefused = null

            // The formats open at your place; a grey one says why not.
            withContext(Dispatchers.Main) {
                header().formatRow.chipViews["readaloud"]!!.performClick()
                assertEquals("No read along for this book yet", notices.last())
                header().formatRow.chipViews["ebook"]!!.requestFocus()
                assertEquals("Read", screen.hints().first { it.glyph == "Ⓐ" }.label)
                header().formatRow.chipViews["ebook"]!!.performClick()
                assertEquals("EpubReaderScreen", pushed.last())
                header().formatRow.chipViews["audiobook"]!!.performClick()
                assertEquals("AudiobookScreen", pushed.last())
            }
            shot(activity, "5-end")
        } finally {
            withContext(Dispatchers.Main) { screen.onHide(); screen.onDestroyView(); activity.finish() }
            File(activity.filesDir, "reading-checkpoints/${checkpointKey.fileName}.json").delete()
            HubSettings.save(activity, oldUrl, oldToken)
            HubSettings.selectUser(activity, oldUser.first, oldUser.second)
            hub.shutdown()
        }
    }
}
