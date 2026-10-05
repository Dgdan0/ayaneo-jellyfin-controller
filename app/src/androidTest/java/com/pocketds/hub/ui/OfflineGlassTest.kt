package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.OfflineSelectionItem
import com.pocketds.hub.model.OfflineSelectionResponse
import com.pocketds.hub.model.OfflineSelectionSeason
import com.pocketds.hub.model.OfflineSource
import com.pocketds.hub.nav.PageArtwork
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.OfflineCatalog
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.offline.OfflineState
import com.pocketds.hub.screens.offline.OfflineScreen
import com.pocketds.hub.screens.offline.OfflineSeasonScreen
import com.pocketds.hub.screens.offline.OfflineSelectionScreen
import com.pocketds.hub.screens.offline.OfflineSeriesScreen
import com.pocketds.hub.ui.glass.GlassButtonBackground
import com.pocketds.hub.ui.glass.GlassPanelDrawable
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline pages on Glass (#22), on fixtures in .uitest only: no hub, no
 * download, and every row written here is removed again. Nothing is played.
 */
@RunWith(AndroidJUnit4::class)
class OfflineGlassTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    @Test fun aDownloadedSeriesAndItsSeasonAreGlassPages() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val series = "glass-series-${System.nanoTime()}"
        val repository = OfflineRepository.get(activity)
        val ids = listOf(1 to 1, 1 to 2, 2 to 1).map { (season, episode) -> episode(repository, series, season, episode) }
        val host = host(activity)
        val api = noHub()
        val page = OfflineSeriesScreen(api, series, "Example series") { true }
        var season: OfflineSeasonScreen? = null
        try {
            lateinit var root: View
            ins.runOnMainSync { root = page.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); page.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue("the header names the series", page.showsOwnTitle)
                assertEquals(PageArtwork.backdrop(series), page.pageArtwork)
                val texts = all(root).filterIsInstance<TextView>()
                val play = texts.first { it.text == "Play S1E1" }
                assertTrue("Play is the white pill", (play.background as? GlassButtonBackground)?.lit == true)
                val more = texts.first { it.contentDescription?.startsWith("More actions for") == true }
                assertTrue("More is a round glass toggle, off", (more.background as? GlassButtonBackground)?.lit == false)
                val card = all(root).filterIsInstance<ContinuationCardView>().single()
                assertTrue("the next episode is a glass continue card", card.background is GlassPanelDrawable)
                assertEquals("Play S1E1", card.titleView.text.toString())
                assertTrue(texts.any { it.text.startsWith("Downloaded seasons") })
                assertEquals(2, all(root).count { it is DetailArtworkCardView })
            }
            val first = OfflineCatalog.seasons(series, repository.completed()).first { it.number == 1 }
            val shown = OfflineSeasonScreen(api, "Example series", first) { true }.also { season = it }
            ins.runOnMainSync { root = shown.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); shown.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue(shown.showsOwnTitle)
                assertEquals(PageArtwork.backdrop(ids.first(), series), shown.pageArtwork)
                val texts = all(root).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(texts.contains("Season 1"))
                assertTrue(texts.any { it.startsWith("Example series · 2 downloaded episodes") })
                assertEquals(2, all(root).count { it is EpisodeCardView })
                assertTrue(shown.requestInitialFocus())
                assertTrue("Y opens More", shown.onPad(PadAction.Secondary))
                assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Audio & subtitles" })
                assertTrue("B closes it", shown.onPad(PadAction.Back))
            }
        } finally {
            ins.runOnMainSync {
                season?.let { it.onHide(); it.onDestroyView() }
                page.onHide(); page.onDestroyView(); activity.finish()
            }
            ids.forEach { id -> repository.forItem(id)?.let { repository.remove(it.id) } }
        }
    }

    /**
     * Back from a season lands on that season (#22, #23), and Back from a series on
     * its poster in the catalogue, through the host's own way of changing pages. The
     * host used to hand focus to the view nearest the scroll position while it hid
     * a page, and the page's first layout coming back took that same view.
     */
    @Test fun backFromASeasonAndFromASeriesLandsWhereYouWere() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val stamp = System.nanoTime()
        val repository = OfflineRepository.get(activity)
        val ids = listOf(1 to 1, 2 to 1).map { (season, episode) -> episode(repository, "glass-a-$stamp", season, episode, "Example A $stamp") } +
            episode(repository, "glass-b-$stamp", 1, 1, "Example B $stamp")
        lateinit var harness: PageHarness
        val catalogue = OfflineScreen(noHub(), { true })
        try {
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(catalogue) }
            ins.waitForIdleSync()
            fun poster(title: String) = all(harness.stage).filterIsInstance<PosterCardView>().first { it.isShown && it.contentDescription?.startsWith(title) == true }
            // Into the second series, not the first poster the page would start on.
            ins.runOnMainSync { assertTrue(poster("Example B $stamp").requestFocus()); assertTrue(catalogue.onPad(PadAction.Activate)) }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertTrue(harness.back()) }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertTrue("Back from a series lands on its poster", poster("Example B $stamp").isFocused) }

            // A series whose page scrolls: into its second season and back.
            ins.runOnMainSync { assertTrue(poster("Example A $stamp").requestFocus()); assertTrue(catalogue.onPad(PadAction.Activate)) }
            ins.waitForIdleSync()
            fun season(number: Int) = all(harness.stage).filterIsInstance<DetailArtworkCardView>()
                .first { it.isShown && it.contentDescription?.startsWith("Season $number,") == true }
            lateinit var series: com.pocketds.hub.nav.Screen
            ins.runOnMainSync {
                assertTrue(season(2).requestFocus())
                series = harness.top()
                assertTrue(series.onPad(PadAction.Activate))
            }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertTrue(harness.top() is OfflineSeasonScreen); assertTrue(harness.back()) }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertTrue("Back from a season lands on that season", season(2).isFocused) }
            assertTrue("nothing was played", harness.asked.isEmpty())
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
            ids.forEach { id -> repository.forItem(id)?.let { repository.remove(it.id) } }
        }
    }

    @Test fun theDownloadPickerIsAGlassPageAndItsChoicesASideSheet() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val series = "glass-picker-${System.nanoTime()}"
        val selection = OfflineSelectionResponse(
            series = LibraryItem(id = series, type = "series", title = "Example series", backdrop = "/fixture/backdrop"),
            seasons = listOf(OfflineSelectionSeason(
                season = LibraryItem(id = "$series-s1", type = "season", title = "Season 1", seasonNumber = 1),
                episodes = (1..2).map { n ->
                    OfflineSelectionItem(LibraryItem(id = "$series-e$n", type = "episode", title = "Episode $n", seasonNumber = 1, indexNumber = n),
                        estimatedSizeBytes = 1_000_000L, available = true)
                }
            ))
        )
        // Only the selection may be read; preparing a download would fail the test.
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            when (method.name) {
                "offlineSelection" -> HubResult.Ok(selection)
                "imageUrl" -> ""
                else -> error("Unexpected ${method.name}")
            }
        } as HubApi
        val picker = OfflineSelectionScreen(api, series, "Example series") { true }
        try {
            lateinit var root: View
            ins.runOnMainSync { root = picker.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); picker.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val texts = all(activity.window.decorView).filterIsInstance<TextView>()
                assertTrue("the quick choices open at once", texts.any { it.text == "All available episodes" })
                assertTrue("B cancels them", picker.onPad(PadAction.Back))
                assertEquals("/fixture/backdrop", picker.pageArtwork)
                val download = texts.first { it.contentDescription == "Download selected episodes" }
                assertTrue("Download is the white pill", (download.background as? GlassButtonBackground)?.lit == true)
                assertTrue("each season under a glass heading", texts.any { it.text.startsWith("Season 1") && it.text.contains("2 episodes") })
                assertEquals(2, all(root).count { it is EpisodeCardView })
            }
        } finally {
            ins.runOnMainSync { picker.onHide(); picker.onDestroyView(); activity.finish() }
        }
    }

    @Test fun theManagersRowsCarryTheirStateAsChips() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val key = "glass-queue-${System.nanoTime()}"
        val repository = OfflineRepository.get(activity)
        val manifests = (1..2).map { n ->
            OfflineManifest(batchKey = key, clientItemKey = "$key-$n",
                item = LibraryItem(id = "$key-item$n", type = "movie", title = "Example film $n"),
                source = OfflineSource(id = "source", container = "mp4", sizeBytes = 1_000_000L))
        }
        assertEquals(2, repository.enqueue("Example films", "", manifests))
        // Paused at once, so nothing here is ever fetched; the second fails with a reason.
        repository.setBatchPaused(key, true)
        repository.updateProgress("$key-1", 400_000L, OfflineState.PAUSED)
        repository.setState("$key-2", OfflineState.FAILED, "The download link expired")
        val screen = OfflineScreen(noHub(), { true })
        try {
            lateinit var root: View
            ins.runOnMainSync { root = screen.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen.openManager() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val texts = all(root).filterIsInstance<TextView>()
                assertTrue("the paused row and its batch say so on a chip", texts.count { it.text == "Paused" } >= 2)
                assertTrue(texts.any { it.text == "Failed" })
                val reason = texts.first { it.text == "The download link expired" }
                assertEquals(View.VISIBLE, reason.visibility)
                assertTrue(texts.any { it.text.contains(" of ") && it.text.startsWith(com.pocketds.hub.state.Fmt.bytes(400_000L)) })
            }
        } finally {
            ins.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
            repository.removeBatch(key)
        }
    }

    /** A downloaded film's card says its size once (#23): on the line with ⋯, its caption the year. */
    @Test fun aDownloadedFilmSaysItsSizeOnce() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val id = "glass-film-${System.nanoTime()}"
        val repository = OfflineRepository.get(activity)
        // The batch names the title in the catalogue.
        repository.enqueue("Example film $id", "", listOf(OfflineManifest(batchKey = "$id-batch", clientItemKey = id,
            item = LibraryItem(id = id, type = "movie", title = "Example film $id", year = 2008, runtimeSeconds = 6_960,
                library = com.pocketds.hub.model.LibraryRef("fixture-films", "Fixture films")),
            source = OfflineSource(id = "source", container = "mp4", sizeBytes = 4))))
        val row = checkNotNull(repository.forItem(id))
        repository.mediaFile(row).writeText("film")
        repository.finish(row.id)
        val screen = OfflineScreen(noHub(), { true })
        try {
            lateinit var root: View
            ins.runOnMainSync { root = screen.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val card = all(root).filterIsInstance<PosterCardView>().first { it.contentDescription?.startsWith("Example film $id") == true }
                val size = com.pocketds.hub.state.Fmt.bytes(checkNotNull(repository.forItem(id)).totalBytes)
                val words = all(card.parent as View).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
                assertEquals("the size is said once: $words", 1, words.count { it.contains(size) })
                assertTrue("the caption is the year, as a poster's: $words", words.contains("2008"))
            }
        } finally {
            ins.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
            repository.forItem(id)?.let { repository.remove(it.id) }
        }
    }

    /**
     * A downloaded series of three seasons (#23): Down from the continue card is
     * the first season, not the one under the card's middle; and on the page
     * scrolled down to its seasons, Play in focus brings the whole header into
     * view, the title over it.
     */
    @Test fun downFromTheContinueCardAndUpToTheHeader() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val series = "glass-down-${System.nanoTime()}"
        val repository = OfflineRepository.get(activity)
        val ids = (1..3).map { season -> episode(repository, series, season, 1) }
        val page = OfflineSeriesScreen(noHub(), series, "Example series") { true }
        fun season(root: View, number: Int) = all(root).filterIsInstance<DetailArtworkCardView>()
            .first { it.isShown && it.contentDescription?.startsWith("Season $number,") == true }
        try {
            lateinit var root: View
            ins.runOnMainSync { root = page.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); page.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val card = all(root).filterIsInstance<ContinuationCardView>().single()
                assertTrue(card.requestFocus())
                assertSame("Down from the continue card is the first season", season(root, 1), card.focusSearch(View.FOCUS_DOWN))
                assertTrue(season(root, 1).requestFocus())
            }
            ins.waitForIdleSync()
            lateinit var scroll: FocusScrollView
            ins.runOnMainSync {
                scroll = all(root).filterIsInstance<FocusScrollView>().first()
                assertTrue("the page scrolled down to its seasons", scroll.scrollY > 0)
                assertTrue(all(root).filterIsInstance<TextView>().first { it.text == "Play S1E1" }.requestFocus())
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val title = all(root).filterIsInstance<DetailHeaderView>().single().titleView
                val bounds = android.graphics.Rect().also { title.getDrawingRect(it); scroll.offsetDescendantRectToMyCoords(title, it) }
                assertTrue("the title is in view over Play: its top ${bounds.top}, the page at ${scroll.scrollY}", bounds.top >= scroll.scrollY)
            }
        } finally {
            ins.runOnMainSync { page.onHide(); page.onDestroyView(); activity.finish() }
            ids.forEach { id -> repository.forItem(id)?.let { repository.remove(it.id) } }
        }
    }

    /** A downloaded episode on this device: a tiny file, its row finished, nothing fetched. */
    private fun episode(repository: OfflineRepository, series: String, season: Int, number: Int,
                        title: String = "Example series"): String {
        val id = "$series-s${season}e$number"
        repository.enqueue(title, series, listOf(OfflineManifest(batchKey = "$series-batch", clientItemKey = id,
            item = LibraryItem(id = id, type = "episode", title = "Episode $number", seriesId = series, seriesTitle = title,
                seasonId = "$series-season$season", seasonNumber = season, indexNumber = number, runtimeSeconds = 2_400,
                // Named, so the catalogue never asks the hub which library it is in.
                library = com.pocketds.hub.model.LibraryRef("fixture-library", "Fixtures")),
            source = OfflineSource(id = "source", container = "mp4", sizeBytes = 4))))
        val row = checkNotNull(repository.forItem(id))
        repository.mediaFile(row).writeText("film")
        repository.finish(row.id)
        return id
    }

    private fun host(activity: android.app.Activity) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            if (method.name == "getViewContext") activity else null
        } as ScreenHost

    /** No hub: a picture's address is all a page may ask for, and it gets none; a library lookup finds nothing. */
    private fun noHub() = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
        when (method.name) {
            "imageUrl" -> ""
            "libraryItem" -> HubResult.Failed(com.pocketds.hub.net.FailureKind.NO_NETWORK, "fixture")
            else -> error("Unexpected ${method.name}")
        }
    } as HubApi

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()
}
