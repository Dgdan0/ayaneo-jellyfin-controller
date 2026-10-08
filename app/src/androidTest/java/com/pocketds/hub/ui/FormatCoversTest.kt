package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.model.ReadingLibraryItemsResponse
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.ReadingLibraryGridScreen
import com.pocketds.hub.screens.library.SeriesBookStrip
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a book's cover says of its formats (#54), on a device the Pocket's size, on generated covers and a fixture
 * hub: an ebook is tall, an audiobook square, a book that is both tall with a small round mark (headphones, or the
 * book with sound once it is aligned for read along); covers line up along their foot so the captions stay on one
 * line; and the mark never sits on the finished tick or the progress bar.
 */
@RunWith(AndroidJUnit4::class)
class FormatCoversTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun shot(activity: android.app.Activity, name: String) {
        // The app's page is dark behind glass; this window is the fixture's own.
        ins.runOnMainSync { activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(com.pocketds.hub.ui.glass.ArtworkPalette.NEUTRAL.dark)) }
        ins.waitForIdleSync(); Thread.sleep(700)
        File(activity.getExternalFilesDir(null), "format-covers-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Covers by path: a generated one for any title, tall or square as the path says. */
    private fun covers(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val title = path.substringAfterLast('/').substringBefore('?').replace('-', ' ')
                val square = path.contains("audio")
                val bytes = if (square) ReaderFixtures.cover(480, 480, title) else ReaderFixtures.cover(400, 600, title)
                return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
            }
        }
        start()
    }

    private fun work(id: String, title: String, kind: String, availability: List<String>, progress: ReadingProgress? = null) =
        ReadingWork(id = id, entityType = "work", kind = kind, title = title, authors = listOf("Ann Writer"),
            artwork = "/cover/${if (kind == "audiobook") "audio-" else ""}${title.lowercase().replace(' ', '-')}", availability = availability, progress = progress)

    private fun dp(value: Float) = ins.targetContext.resources.displayMetrics.density * value

    @Test fun theBooksGridShowsTheFormatsOnEachCoverAndLinesThemUpAlongTheirFoot() {
        val server = covers()
        // Asked for off the main thread: it looks the host up.
        val base = server.url("/").toString().trimEnd('/')
        val context = ins.targetContext
        val before = ContentModeSettings.get(context)
        val oldView = DomainPreferences.readingView(context)
        ContentModeSettings.set(context, ContentMode.BOOKS)
        DomainPreferences.setReadingView(context, "books")
        val activity = ins.startActivitySync(Intent(context, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        try {
            val works = listOf(
                work("w1", "Ebook Only", "book", listOf("ebook"), ReadingProgress(percentage = 0.4)),
                work("w2", "Audiobook Only", "audiobook", listOf("audiobook")),
                work("w3", "Headphones Too", "book", listOf("ebook", "audiobook"), ReadingProgress(percentage = 0.62)),
                work("w4", "Reads Along", "book", listOf("ebook", "audiobook", "readaloud"), ReadingProgress(percentage = 1.0, completed = true)),
                work("w5", "Another Audiobook", "audiobook", listOf("audiobook"), ReadingProgress(completed = true, percentage = 1.0)),
                work("w6", "Plain Ebook", "book", listOf("ebook")),
                work("w7", "Reads Along Too", "book", listOf("ebook", "audiobook", "readaloud"), ReadingProgress(percentage = 0.15)),
                work("w8", "Headphones Again", "book", listOf("ebook", "audiobook"))
            )
            val api = FixtureHub.of(
                "readingLibraryItems" to { _ -> HubResult.Ok(ReadingLibraryItemsResponse(libraryId = "books", items = works, total = works.size, totalPages = 1)) },
                "imageUrl" to { args -> base + (args[0] as String) }
            )
            val library = ReadingLibrary(id = "books", title = "Books",
                capabilities = listOf("sort:title", "sort:series", "sort:author", "sort:added", "sort:last_read"))
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(ReadingLibraryGridScreen(api, library) { true }) }
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && all(harness.stage).filterIsInstance<PosterCardView>().count { it.isShown } < works.size) Thread.sleep(150)
            Thread.sleep(2_000)
            shot(activity, "01-books-grid")
            ins.runOnMainSync {
                val cards = all(harness.stage).filterIsInstance<PosterCardView>().filter { it.isShown }
                assertEquals(works.size, cards.size)
                fun card(title: String) = cards.first { it.contentDescription?.startsWith(title) == true }
                fun slot(card: PosterCardView) = card.getChildAt(0) as ViewGroup
                fun cover(card: PosterCardView) = slot(card).getChildAt(0) as ViewGroup
                fun mark(card: PosterCardView) = card.field<ImageView>("poster").foreground
                // Covers: ebook tall, audiobook square; a both book tall, whatever it is marked with.
                works.forEach { w ->
                    val c = card(w.title)
                    val shape = if (w.kind == "audiobook") "square" else "tall"
                    val width = cover(c).width.toFloat()
                    assertTrue("${w.title}: a cover with room", width > dp(60f))
                    val expectedHeight = if (shape == "square") width else width * 1.5f
                    assertEquals("${w.title} is $shape", expectedHeight, cover(c).height.toFloat(), 2f)
                    // Along the foot of the tall one's place, so a square sits lower.
                    assertEquals("${w.title}'s cover is at the foot of its place", slot(c).height, cover(c).bottom)
                    assertEquals("every place is as tall as a tall cover", width * 1.5f, slot(c).height.toFloat(), 2f)
                }
                // The captions stay on one line: every title at the same height in its card, as every place is as tall.
                val titles = cards.map { it.getChildAt(1) as TextView }
                // (A grid's cells differ by a pixel in width, and so in height: not more.)
                assertTrue("the titles share one line: ${titles.map { it.top }}", titles.maxOf { it.top } - titles.minOf { it.top } <= 3)
                // Marks: only the two that are both, drawn over the picture; a square or an ebook has none.
                assertNull(mark(card("Ebook Only")))
                assertNull(mark(card("Audiobook Only")))
                assertNotNull(mark(card("Headphones Too")))
                assertNotNull(mark(card("Reads Along,")))
                assertNull(mark(card("Plain Ebook")))
                assertNotNull(mark(card("Reads Along Too")))
                assertTrue(card("Reads Along Too").contentDescription!!.contains("read along"))
                assertTrue(card("Headphones Too").contentDescription!!.contains("ebook and audiobook"))
                // The mark is 18dp, 6dp in from the top left; the tick and the bar are never under it.
                val markBox = Rect(dp(FormatMark.EDGE_DP).toInt(), dp(FormatMark.EDGE_DP).toInt(),
                    dp(FormatMark.EDGE_DP + FormatMark.SIZE_DP).toInt(), dp(FormatMark.EDGE_DP + FormatMark.SIZE_DP).toInt())
                works.filter { it.availability.size > 1 }.forEach { w ->
                    val c = card(w.title)
                    val wrap = cover(c)
                    val badge = c.field<TextView>("badge")
                    if (badge.visibility == View.VISIBLE) {
                        val badgeBox = Rect(badge.left, badge.top, badge.right, badge.bottom)
                        assertFalse("${w.title}: the tick $badgeBox is clear of the mark $markBox", Rect.intersects(badgeBox, markBox))
                        assertTrue("${w.title}: the tick keeps the top right", badge.right > wrap.width / 2)
                    }
                    val bar = c.field<View>("progressBar")
                    if (bar.visibility == View.VISIBLE) {
                        assertFalse("${w.title}: the bar is clear of the mark", Rect.intersects(Rect(bar.left, bar.top, bar.right, bar.bottom), markBox))
                        assertTrue("${w.title}: the bar keeps the foot", bar.top > wrap.height / 2)
                    }
                }
                assertEquals(View.VISIBLE, card("Reads Along,").field<TextView>("badge").visibility)
                assertEquals("✓", card("Reads Along,").field<TextView>("badge").text.toString())
            }
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
            ContentModeSettings.set(context, before)
            DomainPreferences.setReadingView(context, oldView ?: "series")
            server.shutdown()
        }
    }

    @Test fun aSeriesRowAndAnAuthorsRowKeepTheirCoversLevelAtTheFootWithTheSameMarks() {
        val server = covers()
        // Asked for off the main thread: it looks the host up.
        val base = server.url("/").toString().trimEnd('/')
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val colors = Theme.colors(activity)
            val items = listOf(
                ReadingSectionItem(sourceItemId = "1", workId = "w1", title = "Ebook Only", number = "1", kind = "book", artwork = "/cover/ebook-only",
                    formats = listOf("ebook"), progress = ReadingProgress(percentage = 0.3)),
                ReadingSectionItem(sourceItemId = "2", workId = "w2", title = "Audiobook Only", number = "2", kind = "audiobook", artwork = "/cover/audio-audiobook-only",
                    formats = listOf("audiobook")),
                ReadingSectionItem(sourceItemId = "3", workId = "w3", title = "Headphones Too", number = "3", kind = "book", artwork = "/cover/headphones-too",
                    formats = listOf("ebook", "audiobook"), progress = ReadingProgress(percentage = 0.5)),
                ReadingSectionItem(sourceItemId = "4", workId = "w4", title = "Reads Along", number = "4", kind = "book", artwork = "/cover/reads-along",
                    formats = listOf("ebook", "audiobook", "readaloud"), progress = ReadingProgress(percentage = 1.0, completed = true))
            )
            val api = FixtureHub.of("imageUrl" to { args -> base + (args[0] as String) })
            lateinit var strip: View
            ins.runOnMainSync {
                strip = SeriesBookStrip.create(activity, colors, { true }, api, items) { _, _ -> }
                activity.setContentView(FrameLayout(activity).apply {
                    setBackgroundColor(colors.background)
                    addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(80f).toInt() })
                })
            }
            Thread.sleep(2_500)
            shot(activity, "02-series-row")
            ins.runOnMainSync {
                val cards = all(strip).filterIsInstance<DetailArtworkCardView>()
                assertEquals(items.size, cards.size)
                // The covers share a foot: a square sits lower, and every caption starts at the same height.
                assertEquals("the covers' feet are level: ${cards.map { it.image.bottom }}", 1, cards.map { it.image.bottom }.toSet().size)
                assertEquals("the captions are level: ${cards.map { it.titleView.top }}", 1, cards.map { it.titleView.top }.toSet().size)
                assertEquals("the square is as wide as it is tall", cards[1].image.width.toFloat(), cards[1].image.height.toFloat(), 2f)
                assertEquals("the tall covers are 2:3", cards[0].image.width * 1.5f, cards[0].image.height.toFloat(), 3f)
                assertTrue("the square sits lower than the tall", cards[1].image.top > cards[0].image.top + dp(20f))
                val marked = cards.map { it.image.foreground }
                assertNotNull("a book with sound has its mark, ${marked[3]}", marked[3])
                assertNotNull("headphones have theirs", marked[2])
                // A tall cover with only its progress bar carries the bar and nothing else of the mark.
                assertTrue(cards[0].image.foreground != null && cards[0].image.foreground !== marked[2])
            }
        } finally {
            ins.runOnMainSync { activity.finish() }
            server.shutdown()
        }
    }
}
