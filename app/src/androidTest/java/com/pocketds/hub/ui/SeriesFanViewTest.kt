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
import com.pocketds.hub.screens.library.ReadingWorkScreen
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
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(ReadingLibraryGridScreen(api, library) { true }) }
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && all(harness.stage).filterIsInstance<SeriesFanCardView>().count { it.isShown } < 4) Thread.sleep(150)
            Thread.sleep(2_500)
            shot(activity, "01-grid")
            // Focus rings the front cover and opens the fan a little.
            ins.runOnMainSync {
                val middle = all(harness.stage).filterIsInstance<SeriesFanCardView>().first { it.contentDescription?.startsWith("In The Middle,") == true }
                assertTrue(middle.requestFocus())
                // The fan opens a little, and the lit cover carries the ring over its gold edge.
                val fanView = middle.javaClass.getDeclaredField("fan").apply { isAccessible = true }.get(middle) as CoverFanView
                assertTrue("the outer cover leans further: ${fanView.covers[0].rotation}", fanView.covers[0].rotation < -17f)
                assertTrue("the lit cover has its edge and the ring: ${fanView.covers[2].foreground}", fanView.covers[2].foreground is android.graphics.drawable.LayerDrawable)
            }
            shot(activity, "03-focus")
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
