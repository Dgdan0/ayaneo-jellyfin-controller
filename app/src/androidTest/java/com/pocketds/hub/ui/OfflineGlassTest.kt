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

    /** A downloaded episode on this device: a tiny file, its row finished, nothing fetched. */
    private fun episode(repository: OfflineRepository, series: String, season: Int, number: Int): String {
        val id = "$series-s${season}e$number"
        repository.enqueue("Example series", series, listOf(OfflineManifest(batchKey = "$series-batch", clientItemKey = id,
            item = LibraryItem(id = id, type = "episode", title = "Episode $number", seriesId = series, seriesTitle = "Example series",
                seasonId = "$series-season$season", seasonNumber = season, indexNumber = number, runtimeSeconds = 2_400),
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

    /** No hub: a picture's address is all a page may ask for, and it gets none. */
    private fun noHub() = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
        if (method.name == "imageUrl") "" else error("Unexpected ${method.name}")
    } as HubApi

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()
}
