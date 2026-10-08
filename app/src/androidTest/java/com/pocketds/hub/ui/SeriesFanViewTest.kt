package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.model.ReadingLibraryItemsResponse
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingSeriesBook
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.MissingReadingItemScreen
import com.pocketds.hub.screens.library.ReadingLibraryGridScreen
import com.pocketds.hub.screens.home.ReadingShelves
import com.pocketds.hub.screens.home.SeriesStackView
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.screens.library.SeriesFan
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.state.ContentMode
import java.io.File
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Books library's Series view as fans (#54), at the Pocket's size, on generated covers and a fixture hub: a
 * series not started, one in the middle, one at the end, a finished one and one with books you do not have, each a
 * fan of up to five covers with its caption and its bar, and a tap that opens the series at the book you are on or
 * the request page of a front book you do not have. Nothing is read or written: no book is opened.
 */
@RunWith(AndroidJUnit4::class)
class SeriesFanViewTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    private fun shot(activity: android.app.Activity, name: String) {
        ins.runOnMainSync { activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(com.pocketds.hub.ui.glass.ArtworkPalette.NEUTRAL.dark)) }
        ins.waitForIdleSync(); Thread.sleep(900)
        File(activity.getExternalFilesDir(null), "series-fans-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** A cover for any path: the book's number big on a gradient, square for an audiobook's. */
    private fun covers(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val label = path.substringAfterLast('/').replace('-', ' ')
                val bytes = if (path.contains("audio")) ReaderFixtures.cover(480, 480, label) else ReaderFixtures.cover(400, 600, label)
                return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
            }
        }
        start()
    }

    private fun book(series: String, n: Int, state: String = "", owned: Boolean = true, audio: Boolean = false) =
        ReadingSeriesBook(number = n.toString(), title = "$series $n", kind = if (audio) "audiobook" else "book", owned = owned, state = state,
            cover = "/c/${if (audio) "audio-" else ""}${series.lowercase().replace(' ', '-')}-$n")

    private fun series(id: String, title: String, count: Int, on: Int = 0, finished: Boolean = false, missing: Set<Int> = emptySet(), audio: Set<Int> = emptySet()) =
        ReadingWork(id = id, entityType = "collection", title = title, authors = listOf("Ann Writer"), bookCount = count,
            artwork = "/c/${title.lowercase().replace(' ', '-')}-1",
            seriesBooks = (1..count).map { n ->
                book(title, n, when { finished && n !in missing -> "read"; n == on -> "on"; on > 0 && n < on && n !in missing -> "read"; else -> "" },
                    owned = n !in missing, audio = n in audio)
            })

    private val density get() = ins.targetContext.resources.displayMetrics.density

    /** Where a fan's covers reach after their lean, scale and lift, in the pixels of the view the fan stands in: left, top, right, bottom. */
    private fun reachOf(fan: ViewGroup): android.graphics.RectF {
        val reach = android.graphics.RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until fan.childCount) {
            val cover = fan.getChildAt(i)
            if (cover.visibility != View.VISIBLE) continue
            val corners = floatArrayOf(0f, 0f, cover.width.toFloat(), 0f, 0f, cover.height.toFloat(), cover.width.toFloat(), cover.height.toFloat())
            cover.matrix.mapPoints(corners)
            for (k in 0 until 4) {
                val x = fan.left + cover.left + corners[2 * k]
                val y = fan.top + cover.top + corners[2 * k + 1]
                reach.left = minOf(reach.left, x); reach.right = maxOf(reach.right, x)
                reach.top = minOf(reach.top, y); reach.bottom = maxOf(reach.bottom, y)
            }
        }
        return reach
    }

    /**
     * An opened fan stays in its card and a small margin round it, so it does not reach into the next card nor, in the
     * first and last columns, toward the screen's edge; and its covers stay above the series' name.
     */
    private fun assertInsideItsCard(card: SeriesFanCardView, plan: SeriesFan.Plan, what: String) {
        val fan = all(card).filterIsInstance<CoverFanView>().single()
        val reach = reachOf(fan)
        val half = card.width / 2f
        val room = half + SeriesFan.SPREAD_MARGIN_DP * density
        // A lit book at an outer slot is already as wide as that at rest; focus must not add to it.
        val rest = SeriesFan.reachDp(plan, SeriesFanCardView.COVER_DP.toFloat(), 1f) * density
        val limit = maxOf(room, rest) + 2f
        assertTrue("$what: ${half - reach.left}px left of the middle, ${reach.right - half}px right, room $limit", half - reach.left <= limit && reach.right - half <= limit)
        assertTrue("$what: covers over the name: ${reach.bottom} > ${fan.bottom} + 8dp", reach.bottom <= fan.bottom + 8 * density)
        assertTrue("$what: above the card: ${reach.top}", reach.top >= -1f)
    }

    /**
     * Where a focused card's fan reaches on the screen, left then right in px: the card is lifted (grown about its
     * middle) while it has focus, and the fan with it.
     */
    private fun visibleSpan(card: View): Pair<Float, Float> {
        val reach = reachOf(all(card).filterIsInstance<CoverFanView>().single())
        val at = IntArray(2); card.getLocationOnScreen(at)
        val scale = card.scaleX
        val middle = at[0] + card.width * scale / 2f
        val half = card.width / 2f
        return (middle - (half - reach.left) * scale) to (middle + (reach.right - half) * scale)
    }

    @Test fun anOpenedFanStaysInsideItsCardAtTheEdgesOfTheGrid() {
        val server = covers()
        val base = server.url("/").toString().trimEnd('/')
        val context = ins.targetContext
        val activity = ins.startActivitySync(Intent(context, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val api = FixtureHub.of("imageUrl" to { args -> base + (args[0] as String) })
            // The Pocket's grid: four cards of 195dp with 5dp round each, 16.5dp in from each side; the first with its book lit at the left-hand slot, the last at the right-hand one.
            val shown = listOf(series("s-a", "Start Of It", 9, on = 1), series("s-b", "Not Started", 8), series("s-c", "Finished", 7, finished = true), series("s-d", "At The End", 9, on = 9))
            lateinit var cards: List<SeriesFanCardView>
            ins.runOnMainSync {
                // The grid does not clip a card to its bounds, nor does Home's row: a fan leans out of its box.
                val row = android.widget.LinearLayout(activity).apply { clipChildren = false; clipToPadding = false; setPadding((16.5f * density).toInt(), (60 * density).toInt(), (16.5f * density).toInt(), 0) }
                cards = shown.map { work ->
                    SeriesFanCardView(activity, Theme.colors(activity)) { true }.also { card ->
                        card.bind(work, checkNotNull(SeriesFan.plan(work)), Artwork.loader(api, activity), api::imageUrl)
                        row.addView(card, android.widget.LinearLayout.LayoutParams((195 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins((5 * density).toInt(), 0, (5 * density).toInt(), 0)
                        })
                    }
                }
                activity.setContentView(row)
            }
            ins.waitForIdleSync(); Thread.sleep(1_500)
            cards.forEachIndexed { i, card ->
                ins.runOnMainSync { assertTrue(card.requestFocus()) }
                ins.waitForIdleSync(); Thread.sleep(400)
                ins.runOnMainSync {
                    val plan = checkNotNull(SeriesFan.plan(shown[i]))
                    assertInsideItsCard(card, plan, shown[i].title)
                    // On the screen too, the lift included: nowhere near being cut off at its edge.
                    val (left, right) = visibleSpan(card)
                    val screen = activity.resources.displayMetrics.widthPixels
                    assertTrue("${shown[i].title} reaches ${left}px from the left", left >= 28f)
                    assertTrue("${shown[i].title} reaches ${screen - right}px from the right", screen - right >= 28f)
                }
                if (i == 0 || i == cards.size - 1) shot(activity, "0${7 + minOf(i, 1)}-focus-${if (i == 0) "first" else "last"}")
            }
        } finally {
            ins.runOnMainSync { activity.finish() }
            server.shutdown()
        }
    }

    @Test fun theSeriesPageHeaderFansItsBooksAndOneKnownCoverStandsUprightInTheMiddle() {
        val server = covers()
        val base = server.url("/").toString().trimEnd('/')
        val context = ins.targetContext
        val activity = ins.startActivitySync(Intent(context, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        try {
            val series = series("s-mid", "In The Middle", 10, on = 5)
            fun page(withCovers: Boolean) = series.copy(sections = listOf(ReadingSection(id = "books", title = "Books", items = (1..10).map { n ->
                ReadingSectionItem(sourceItemId = "$n", workId = "w$n", title = "Book $n", number = "$n", kind = "book", availability = "available",
                    artwork = if (withCovers) "/c/in-the-middle-$n" else "")
            })))
            for ((withCovers, name) in listOf(true to "04-header-covers", false to "05-header-one-cover")) {
                val api = FixtureHub.of("readingWork" to { _ -> HubResult.Ok(page(withCovers)) }, "imageUrl" to { args -> base + (args[0] as String) })
                ins.runOnMainSync {
                    harness = PageHarness(activity); harness.tabs.alpha = 0f
                    harness.push(ReadingWorkScreen(api, "s-mid", "In The Middle", { true }))
                }
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && all(harness.stage).filterIsInstance<CoverFanView>().none { it.isShown }) Thread.sleep(150)
                Thread.sleep(1_500)
                ins.runOnMainSync {
                    val fan = all(harness.stage).filterIsInstance<CoverFanView>().single { it.isShown }
                    val visible = fan.covers.filter { it.visibility == View.VISIBLE }
                    val at = IntArray(2); fan.getLocationOnScreen(at)
                    val reach = reachOf(fan)
                    if (withCovers) {
                        // The prototype's fan, as it was: four covers leaning -13, -5, 4 and 12 degrees, the book you are on last, on top.
                        assertEquals(4, visible.size)
                        assertEquals(listOf(12f, -13f, -5f, 4f), visible.map { it.rotation })
                    } else {
                        // One known cover: upright, and in the middle of its box.
                        assertEquals(1, visible.size)
                        assertEquals(0f, visible[0].rotation, 0f)
                        val box = CoverFanView.sizeDp(CoverFanView.COVER_DP)
                        val (left, top) = SeriesFan.centred(box.first.toFloat(), box.second.toFloat(), CoverFanView.COVER_DP.toFloat())
                        assertEquals(left * density, visible[0].left.toFloat(), 1.5f)
                        assertEquals(top * density, visible[0].top.toFloat(), 1.5f)
                        assertEquals("centred across its box", (fan.width - visible[0].width) / 2f, visible[0].left.toFloat(), 1.5f)
                        assertEquals("centred down its box", (fan.height - visible[0].height) / 2f, visible[0].top.toFloat(), 1.5f)
                    }
                    // Nothing is clipped, at the top or at the left, and the fan's box does not clip it.
                    assertTrue("left of the screen: ${at[0] + reach.left}", at[0] + reach.left >= 0f)
                    assertTrue("top of the screen: ${at[1] + reach.top}", at[1] + reach.top >= 0f)
                    assertTrue("the fan's own top: ${reach.top} (the leaning covers rise at most 5dp over their box)", reach.top >= -5 * density)
                    // And no ancestor that clips its children has the fan's lean outside its bounds.
                    val onScreen = android.graphics.RectF(reach).apply { offset((at[0] - fan.left).toFloat(), (at[1] - fan.top).toFloat()) }
                    var parent = fan.parent
                    while (parent is ViewGroup && parent !== harness.stage) {
                        if (parent.clipChildren) {
                            val where = IntArray(2); parent.getLocationOnScreen(where)
                            assertTrue("${parent.javaClass.simpleName} clips the fan: ${onScreen.left} < ${where[0]}", onScreen.left >= where[0] - 1f)
                            assertTrue("${parent.javaClass.simpleName} clips the fan: ${onScreen.top} < ${where[1]}", onScreen.top >= where[1] - 1f)
                        }
                        parent = parent.parent
                    }
                }
                shot(activity, name)
                ins.runOnMainSync { harness.close() }
            }
        } finally {
            ins.runOnMainSync { activity.finish() }
            server.shutdown()
        }
    }

    @Test fun booksHomeSeriesFanLooksAsItDid() {
        val server = covers()
        val base = server.url("/").toString().trimEnd('/')
        val context = ins.targetContext
        val activity = ins.startActivitySync(Intent(context, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val api = FixtureHub.of("imageUrl" to { args -> base + (args[0] as String) })
            fun item(title: String, count: Int) = ReadingShelves.SeriesShelfItem(title, title, (1..count).map { "/c/${title.lowercase().replace(' ', '-')}-$it" }, "$count books · on #$count")
            lateinit var stacks: List<SeriesStackView>
            ins.runOnMainSync {
                val row = android.widget.LinearLayout(activity).apply { clipChildren = false; clipToPadding = false; setPadding((30 * density).toInt(), (80 * density).toInt(), 0, 0) }
                stacks = listOf(item("Four Covers", 4), item("Two Covers", 2), item("One Cover", 1)).map { shelf ->
                    SeriesStackView(activity, Theme.colors(activity)) { true }.also { stack ->
                        stack.bind(shelf, Artwork.loader(api, activity), api::imageUrl)
                        row.addView(stack, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (24 * density).toInt() })
                    }
                }
                activity.setContentView(row)
            }
            ins.waitForIdleSync(); Thread.sleep(1_500)
            ins.runOnMainSync {
                fun covers(stack: SeriesStackView) = all(stack).filterIsInstance<CoverFanView>().single().covers.filter { it.visibility == View.VISIBLE }
                // Four covers, as the prototype leans them: the book you are on (first) last and on top at the right.
                assertEquals(listOf(12f, -13f, -5f, 4f), covers(stacks[0]).map { it.rotation })
                val lefts = covers(stacks[0]).map { (it.layoutParams as ViewGroup.MarginLayoutParams).leftMargin.toFloat() }
                listOf(64f, 0f, 22f, 44f).forEachIndexed { i, dp -> assertEquals("cover $i stands $dp dp in", dp * density, lefts[i], 1f) }
                // Two lean like the first two of four.
                assertEquals(listOf(-5f, -13f), covers(stacks[1]).map { it.rotation })
                // One known cover is not a fan: upright, in the middle of its box.
                assertEquals(listOf(0f), covers(stacks[2]).map { it.rotation })
                // Focus opens four of them further, as it did: -13 to -18 and 12 to 17.
                assertTrue(stacks[0].requestFocus())
            }
            ins.waitForIdleSync(); Thread.sleep(500)
            ins.runOnMainSync {
                val four = all(stacks[0]).filterIsInstance<CoverFanView>().single().covers.filter { it.visibility == View.VISIBLE }
                assertEquals(listOf(17f, -18f, -5f, 4f), four.map { it.rotation })
            }
            shot(activity, "06-home-fans")
        } finally {
            ins.runOnMainSync { activity.finish() }
            server.shutdown()
        }
    }

    @Test fun theSeriesViewIsAGridOfFansEachWithItsCaptionAndBarAndATapOpensTheRightPage() {
        val server = covers()
        val base = server.url("/").toString().trimEnd('/')
        val context = ins.targetContext
        val before = ContentModeSettings.get(context)
        val oldView = DomainPreferences.readingView(context)
        ContentModeSettings.set(context, ContentMode.BOOKS)
        DomainPreferences.setReadingView(context, "series")
        val activity = ins.startActivitySync(Intent(context, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        try {
            val items = listOf(
                series("s-new", "Not Started", 8),
                series("s-mid", "In The Middle", 10, on = 5, audio = setOf(4)),
                series("s-end", "At The End", 9, on = 9),
                series("s-done", "Finished", 7, finished = true),
                series("s-missing", "Missing Books", 8, on = 3, missing = setOf(1, 6, 7, 8)),
                series("s-front", "Front Missing", 6, missing = setOf(1, 2)),
                series("s-long", "Long Series", 40, on = 17),
                ReadingWork(id = "alone", entityType = "work", title = "A Standalone", authors = listOf("Ann Writer"), artwork = "/c/a-standalone",
                    availability = listOf("ebook"), progress = ReadingProgress(percentage = 0.3)),
                series("s-two", "Just Two", 2, on = 2)
            )
            // The page the fan opens: the series' books in a row, the fifth of them the book you are on.
            val middlePage = items[1].copy(sections = listOf(ReadingSection(id = "books", title = "Books", items = (1..10).map { n ->
                ReadingSectionItem(sourceItemId = "$n", workId = "w$n", title = "Book $n", number = "$n", kind = "book", availability = "available")
            })))
            val api = FixtureHub.of(
                "readingLibraryItems" to { _ -> HubResult.Ok(ReadingLibraryItemsResponse(libraryId = "books", items = items, total = items.size, totalPages = 1)) },
                "readingWork" to { _ -> HubResult.Ok(middlePage) },
                "imageUrl" to { args -> base + (args[0] as String) }
            )
            val library = ReadingLibrary(id = "books", title = "Books", capabilities = listOf("sort:title", "sort:series", "sort:author", "sort:added", "sort:last_read"))
            ins.runOnMainSync { harness = PageHarness(activity); harness.tabs.alpha = 0f; harness.push(ReadingLibraryGridScreen(api, library) { true }) }
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && all(harness.stage).filterIsInstance<SeriesFanCardView>().count { it.isShown } < 4) Thread.sleep(150)
            Thread.sleep(2_500)
            shot(activity, "01-grid")
            // Focus rings the front cover and opens the fan a little.
            ins.runOnMainSync {
                val middle = all(harness.stage).filterIsInstance<SeriesFanCardView>().first { it.contentDescription?.startsWith("In The Middle,") == true }
                assertTrue(middle.requestFocus())
                // The fan opens a little (as far as its card has room for), and the lit cover carries the ring over its gold edge.
                val fanView = middle.javaClass.getDeclaredField("fan").apply { isAccessible = true }.get(middle) as CoverFanView
                assertTrue("the outer cover leans further than at rest (-16): ${fanView.covers[0].rotation}", fanView.covers[0].rotation < -16.2f)
                assertTrue("the lit cover has its edge and the ring: ${fanView.covers[2].foreground}", fanView.covers[2].foreground is android.graphics.drawable.LayerDrawable)
            }
            shot(activity, "03-focus")
            // The first column and the fourth, opened: inside their cards, so inside the page's gutter.
            for ((title, name) in listOf("Not Started" to "09-grid-focus-first-column", "Finished" to "10-grid-focus-fourth-column")) {
                ins.runOnMainSync {
                    val card = all(harness.stage).filterIsInstance<SeriesFanCardView>().first { it.contentDescription?.startsWith("$title,") == true }
                    assertTrue(card.requestFocus())
                }
                ins.waitForIdleSync(); Thread.sleep(500)
                ins.runOnMainSync {
                    val card = all(harness.stage).filterIsInstance<SeriesFanCardView>().first { it.contentDescription?.startsWith("$title,") == true }
                    assertInsideItsCard(card, checkNotNull(SeriesFan.plan(items.first { it.title == title })), title)
                    val (left, right) = visibleSpan(card)
                    val screen = activity.resources.displayMetrics.widthPixels
                    assertTrue("$title: ${left}px from the left edge of the screen", left >= 28f)
                    assertTrue("$title: ${screen - right}px from the right edge of the screen", screen - right >= 28f)
                }
                shot(activity, name)
            }
            ins.runOnMainSync {
                val fans = all(harness.stage).filterIsInstance<SeriesFanCardView>()
                fun fan(title: String) = fans.first { it.contentDescription?.startsWith("$title,") == true }
                assertEquals("Not Started, 8 books", fan("Not Started").contentDescription)
                assertEquals("In The Middle, 10 books · on #5", fan("In The Middle").contentDescription)
                assertEquals("At The End, 9 books · on #9", fan("At The End").contentDescription)
                assertEquals("Finished, 7 books · finished", fan("Finished").contentDescription)
                assertEquals("Missing Books, 8 books · on #3", fan("Missing Books").contentDescription)
                assertEquals("Long Series, 40 books · on #17", fan("Long Series").contentDescription)
                // Four fans to a row: the first four share a top.
                val first = fans.take(4)
                assertEquals("four to a row: ${first.map { it.top }}", 1, first.map { it.top }.toSet().size)
                assertTrue("a fan has room: ${first[0].width}", first[0].width >= (150 * ins.targetContext.resources.displayMetrics.density).toInt())
                // The standalone book stays a cover.
                assertTrue(all(harness.stage).filterIsInstance<PosterCardView>().any { it.contentDescription?.startsWith("A Standalone") == true })
                // A tap on a series opens it, at the book you are on.
                assertEquals(com.pocketds.hub.screens.library.SeriesFan.Target.OpenSeries("5"), fan("In The Middle").target)
                assertEquals(com.pocketds.hub.screens.library.SeriesFan.Target.OpenSeries(null), fan("Not Started").target)
                assertTrue(fan("Front Missing").target is com.pocketds.hub.screens.library.SeriesFan.Target.Request)
                fan("Front Missing").performClick()
                assertTrue("the front book is one you do not have: its request page", harness.top() is MissingReadingItemScreen)
                assertTrue(harness.back())
                fan("In The Middle").performClick()
                assertTrue(harness.top() is ReadingWorkScreen)
            }
            Thread.sleep(2_000)
            ins.runOnMainSync {
                val focused = harness.stage.findFocus()
                assertNotNull("a book has the cursor", focused)
                assertTrue("the book you are on has it: ${focused?.contentDescription}", focused?.contentDescription?.toString()?.startsWith("Book 5,") == true)
            }
            shot(activity, "02-series-page")
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
            ContentModeSettings.set(context, before)
            DomainPreferences.setReadingView(context, oldView ?: "series")
            server.shutdown()
        }
    }
}
