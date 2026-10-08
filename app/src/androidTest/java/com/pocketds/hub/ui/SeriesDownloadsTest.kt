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
import com.pocketds.hub.model.LibraryEpisodesResponse
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryItemResponse
import com.pocketds.hub.model.LibraryRef
import com.pocketds.hub.model.LibrarySeasonsResponse
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.OfflinePrepareBody
import com.pocketds.hub.model.OfflinePrepareResponse
import com.pocketds.hub.model.OfflineSelectionItem
import com.pocketds.hub.model.OfflineSelectionResponse
import com.pocketds.hub.model.OfflineSelectionSeason
import com.pocketds.hub.model.OfflineSource
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.EpisodeDownloadMarks
import com.pocketds.hub.offline.KeepReadyRunner
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.offline.OfflineState
import com.pocketds.hub.screens.library.LibraryDetailScreen
import com.pocketds.hub.screens.library.SeasonEpisodesView
import com.pocketds.hub.screens.library.SeriesDownloads
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.OfflineSettings
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A series' downloads on the series page itself (#48): the corners of its episodes, the season's button, the choices
 * panel, select mode, the storage bar and Keep ready, on a generated series in .uitest only. The hub is a fixture and
 * the files come from a local server that serves zeros, slowly enough to see a ring fill; no title is played or
 * fetched from a real hub, and every row written is removed again.
 */
@RunWith(AndroidJUnit4::class)
class SeriesDownloadsTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private suspend fun until(what: String, timeoutMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(60) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private suspend fun shot(activity: android.app.Activity, name: String) {
        ins.waitForIdleSync(); delay(450)
        File(activity.getExternalFilesDir(null), "series-downloads-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** The generated series: season 1 of five episodes, season 2 of three; the first two watched, the third to play next. */
    private class Series(val id: String) {
        val watched = linkedSetOf(episodeId(1, 1), episodeId(1, 2))
        fun episodeId(season: Int, number: Int) = "s${season}e$number-$id"
        fun seasonId(season: Int) = "$id-season$season"
        fun episode(season: Int, number: Int) = LibraryItem(
            id = episodeId(season, number), type = "episode", title = "Episode $number", seriesId = id, seriesTitle = "Example series",
            seasonId = seasonId(season), seasonNumber = season, indexNumber = number, runtimeSeconds = 2_400,
            played = episodeId(season, number) in watched, library = LibraryRef("fixture-library", "Fixtures"))
        val counts = mapOf(1 to 5, 2 to 3)
        fun episodes(season: Int) = (1..counts.getValue(season)).map { episode(season, it) }
        val every get() = counts.keys.flatMap { s -> episodes(s) }
    }

    private class Rig(
        val activity: android.app.Activity, val series: Series, val screen: LibraryDetailScreen, val server: MockWebServer,
        val notes: MutableList<String>, val played: MutableList<String>, val opened: MutableList<String>
    ) {
        lateinit var root: View
    }

    private val sizeOfEpisode = 300_000L

    private suspend fun withSeries(block: suspend Rig.() -> Unit) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val series = Series("dl-${System.nanoTime()}")
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val oldWifi = OfflineSettings.wifiOnly(activity)
        val repository = OfflineRepository.get(activity)
        // Files from a local server: zeros, a ring's worth of time each.
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return if (path.startsWith("/media/")) MockResponse().setBody(Buffer().write(ByteArray(sizeOfEpisode.toInt())))
                        .throttleBody(75_000, 1, java.util.concurrent.TimeUnit.SECONDS)
                    else MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        OfflineSettings.setWifiOnly(activity, false)
        val notes = mutableListOf<String>()
        val played = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val api = FixtureHub.of(
            "libraryItem" to { _ -> HubResult.Ok(LibraryItemResponse(item = LibraryItem(id = series.id, type = "series", title = "Example series",
                year = 2020, unplayedCount = 6, overview = "A generated series for the download tests."))) },
            "librarySeasons" to { _ -> HubResult.Ok(LibrarySeasonsResponse(seriesId = series.id, items = series.counts.keys.map {
                LibraryItem(id = series.seasonId(it), type = "season", title = "Season $it", seasonNumber = it, seriesId = series.id) })) },
            "libraryEpisodes" to { args ->
                val season = series.counts.keys.first { series.seasonId(it) == args[1] }
                HubResult.Ok(LibraryEpisodesResponse(seriesId = series.id, seasonId = series.seasonId(season), page = 1, totalPages = 1,
                    total = series.counts.getValue(season), items = series.episodes(season)))
            },
            "seriesPlayTarget" to { _ -> HubResult.Ok(SeriesPlayTargetResponse(series.id, "start", series.episode(1, 3))) },
            "offlineSelection" to { _ ->
                HubResult.Ok(OfflineSelectionResponse(
                    series = LibraryItem(id = series.id, type = "series", title = "Example series"),
                    seasons = series.counts.keys.map { s ->
                        OfflineSelectionSeason(LibraryItem(id = series.seasonId(s), type = "season", seasonNumber = s, title = "Season $s"),
                            series.episodes(s).map { OfflineSelectionItem(it, estimatedSizeBytes = sizeOfEpisode, available = true) })
                    },
                    episodeCount = series.every.size, estimatedSizeBytes = sizeOfEpisode * series.every.size,
                    playTargetId = series.every.firstOrNull { !it.played }?.id.orEmpty()))
            },
            "prepareOffline" to { args ->
                val body = args[0] as OfflinePrepareBody
                HubResult.Ok(OfflinePrepareResponse(body.batchKey, body.items.map { entry ->
                    val episode = series.every.first { it.id == entry.itemId }
                    OfflineManifest(grantId = "grant", batchKey = body.batchKey, clientItemKey = entry.clientItemKey,
                        item = episode, source = OfflineSource(id = "source", container = "mp4", sizeBytes = sizeOfEpisode),
                        mediaUrl = "/media/${episode.id}")
                }))
            }
        )
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) {
                "getViewContext" -> activity
                "notify" -> { notes += args!![0] as String; null }
                "playItem" -> { played += args!![0] as String; null }
                "openOfflineManager" -> { opened += "downloads"; null }
                "back" -> true
                else -> null
            }
        } as ScreenHost
        val screen = LibraryDetailScreen(api, series.id, "Example series", "series") { true }
        val rig = Rig(activity, series, screen, server, notes, played, opened)
        try {
            withContext(Dispatchers.Main) {
                rig.root = screen.onCreateView(host, FrameLayout(activity)); activity.setContentView(rig.root); screen.onShow()
            }
            until("the episodes and the season's button") {
                all(rig.root).count { it is EpisodeCardView } >= 5 && seasonButton(rig)?.text?.startsWith("Season 1") == true
            }
            rig.block()
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "series-downloads-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen.onHide(); screen.onDestroyView(); activity.finish() }
            repository.keepReadySeries().filter { it.first == series.id }.forEach { repository.clearKeepReady(it.first) }
            repository.forItems(series.every.map { it.id }).values.forEach { repository.remove(it.id) }
            OfflineSettings.setWifiOnly(activity, oldWifi)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    /** Where the bar's top is on the screen, and how low the lowest episode card reaches. */
    private fun dockTop(rig: Rig): Int = IntArray(2).also(downloads(rig).dock::getLocationOnScreen)[1]
    private fun lowestCard(rig: Rig): Int = cards(rig).filter { it.isShown }.maxOf { card -> IntArray(2).also(card::getLocationOnScreen)[1] + card.height }

    /** No card, the one in focus included, is under the bar. */
    private fun stripClear(rig: Rig): Boolean = lowestCard(rig) <= dockTop(rig)

    private fun seasonButton(rig: Rig): TextView? = all(rig.root).filterIsInstance<TextView>().firstOrNull { it.tag == "seasonDownload" && it.visibility == View.VISIBLE }
    private fun cards(rig: Rig) = all(rig.root).filterIsInstance<EpisodeCardView>()
    private fun badges(rig: Rig) = all(rig.root).filterIsInstance<DownloadBadgeView>().filter { it.visibility == View.VISIBLE }
    private fun downloads(rig: Rig): SeriesDownloads = rig.screen.field("downloads")
    private fun repository(rig: Rig) = OfflineRepository.get(rig.activity)

    // ------------------------------------------------------------------ the quick taps

    @Test fun aTapOnAnEpisodesCornerDownloadsAtOnceAndTheBarRisesAndFades(): Unit = runBlocking {
        withSeries {
            withContext(Dispatchers.Main) {
                assertEquals("every episode has its corner", 5, badges(this@withSeries).size)
                assertEquals("Season 1 · 1.4 MB", seasonButton(this@withSeries)!!.text.toString())
                assertFalse("no bar before anything is coming", downloads(this@withSeries).dock.raised)
            }
            shot(activity, "01-page")
            val target = series.episodeId(1, 3)
            // A tap on the third episode's corner: no question, it is on the queue.
            withContext(Dispatchers.Main) {
                val card = cards(this@withSeries).first { it.contentDescription.toString().contains("Episode 3") }
                all(card).filterIsInstance<DownloadBadgeView>().single().performClick()
            }
            until("the bar to rise") { downloads(this@withSeries).dock.raised }
            // The page makes room: every card, and its words, ends above the bar (the Pocket's 853 x 456 dp).
            until("the strip to clear the bar") { downloads(this@withSeries).dock.height > 0 && stripClear(this@withSeries) }
            until("the ring to fill") { repository(this@withSeries).forItem(target)?.let { it.state == OfflineState.DOWNLOADING && it.progress > 0.25f } == true }
            shot(activity, "02-ring-and-bar")
            withContext(Dispatchers.Main) {
                assertTrue("no card under the bar: the lowest reaches ${lowestCard(this@withSeries)}, the bar starts at ${dockTop(this@withSeries)}", stripClear(this@withSeries))
                val line = downloads(this@withSeries).dock.storage.line
                assertTrue("the bar says what is coming: $line", line.startsWith("1 coming"))
                assertTrue(notes.any { it.startsWith("Added 1 episode") })
            }
            until("the download to finish") { repository(this@withSeries).forItem(target)?.state == OfflineState.COMPLETE }
            val finishedAt = System.currentTimeMillis()
            until("the tick") { badges(this@withSeries).any { it.contentDescription == "On this device" } }
            shot(activity, "03-tick")
            withContext(Dispatchers.Main) {
                assertTrue("the bar stays a few seconds", downloads(this@withSeries).dock.raised)
                assertEquals("1 on this Pocket", downloads(this@withSeries).dock.storage.line)
            }
            until("the bar to fade", 10_000) { !downloads(this@withSeries).dock.raised }
            val lingered = System.currentTimeMillis() - finishedAt
            assertTrue("about three seconds after the last finished, not $lingered ms", lingered in 2_500..6_500)
            until("the bar to go") { downloads(this@withSeries).dock.visibility == View.GONE }
        }
    }

    @Test fun aWatchedEpisodeThatIsOnTheDeviceShowsTheWatchedTickAndTheDoneMarkApart(): Unit = runBlocking {
        withSeries {
            // The second episode is watched. Download it from its corner.
            withContext(Dispatchers.Main) {
                val card = cards(this@withSeries).first { it.contentDescription.toString().contains("Episode 2") }
                all(card).filterIsInstance<DownloadBadgeView>().single().performClick()
            }
            val watchedAndHere = series.episodeId(1, 2)
            until("it to arrive", 30_000) { repository(this@withSeries).forItem(watchedAndHere)?.state == OfflineState.COMPLETE }
            until("the done mark") { badges(this@withSeries).any { it.contentDescription == "On this device" } }
            until("the strip clear of the bar") { stripClear(this@withSeries) }
            withContext(Dispatchers.Main) {
                val card = cards(this@withSeries).first { it.contentDescription.toString().contains("Episode 2") }
                val badge = all(card).filterIsInstance<DownloadBadgeView>().single()
                assertEquals("the corner says it is on the device", EpisodeDownloadMarks.Mark.DONE, badge.field<EpisodeDownloadMarks.Badge>("badge").mark)
                // The watched tick is still there, and is a different thing: an accent disc with a tick, over to one side.
                val tick = all(card).filterIsInstance<TextView>().first { it.text == "✓" && it.visibility == View.VISIBLE }
                assertTrue("the watched tick is beside the download mark, not under it", tick.isShown)
                val tickAt = IntArray(2).also(tick::getLocationOnScreen)[0]
                val markAt = IntArray(2).also(badge::getLocationOnScreen)[0]
                assertTrue("the tick ($tickAt) is left of the mark ($markAt)", tickAt < markAt)
            }
            shot(activity, "13-watched-and-downloaded")
            until("the bar to fade", 10_000) { !downloads(this@withSeries).dock.raised }
        }
    }

    @Test fun theSeasonsButtonFetchesWhatIsLeftAndThenSaysItIsAllHere(): Unit = runBlocking {
        withSeries {
            withContext(Dispatchers.Main) { seasonButton(this@withSeries)!!.performClick() }
            until("the season to be queued") { repository(this@withSeries).forItems(series.episodes(1).map { it.id }).size == 5 }
            until("the first transfer") { downloads(this@withSeries).dock.raised && downloads(this@withSeries).dock.storage.line.contains("coming") }
            shot(activity, "04-season-coming")
            until("the season to arrive", 40_000) { series.episodes(1).all { repository(this@withSeries).forItem(it.id)?.state == OfflineState.COMPLETE } }
            until("the button to say so") { seasonButton(this@withSeries)?.text == "Season 1 on this Pocket" }
            shot(activity, "05-season-here")
            // Season 2 is another button, with its own size.
            withContext(Dispatchers.Main) {
                val chips = all(root).filterIsInstance<TextView>().first { it.text.toString().startsWith("Season 2") }
                chips.performClick()
            }
            until("season 2's button") { seasonButton(this@withSeries)?.text?.startsWith("Season 2 ·") == true }
        }
    }

    // ------------------------------------------------------------------ the pad

    private fun rowTexts(rig: Rig): List<String> = all(rig.root).filterIsInstance<TextView>().map { it.text.toString() }

    /** The rows of the sheet that is open: the card's menu, or the choices panel. */
    private fun sheetRows(rig: Rig): List<String> =
        all(rig.root).filterIsInstance<com.pocketds.hub.ui.SidePanelView>().firstOrNull { it.isOpen }?.rows?.map { it.contentDescription.toString() }.orEmpty()

    /** The hint bar's buttons: the glyph and the words. */
    private fun hints(rig: Rig) = rig.screen.hints().map { it.glyph to it.label }

    private suspend fun pad(rig: Rig, vararg actions: PadAction) {
        for (action in actions) {
            withContext(Dispatchers.Main) { rig.screen.onPad(action) }
            delay(140)
        }
    }

    private val down = PadAction.Step(com.pocketds.hub.input.Direction.DOWN)

    private suspend fun focusEpisodes(rig: Rig) {
        withContext(Dispatchers.Main) { rig.screen.field<SeasonEpisodesView>("episodes").focusEpisode() }
        until("an episode in focus") { cards(rig).any { it.hasFocus() } }
    }

    @Test fun aCardsMenuDownloadsAndSelectModeTicksAndQueues(): Unit = runBlocking {
        withSeries {
            focusEpisodes(this)
            withContext(Dispatchers.Main) {
                assertEquals(listOf("Play", "Episode details", "Menu", "Back", "Refresh"), hints(this@withSeries).map { it.second })
            }
            // Ⓨ on a card: Play, Download, Select episodes.
            pad(this, PadAction.Secondary)
            until("the card's menu") { sheetRows(this@withSeries).contains("Select episodes") }
            withContext(Dispatchers.Main) { assertEquals(listOf("Play", "Download", "Select episodes"), sheetRows(this@withSeries)) }
            shot(activity, "06-card-menu")
            pad(this, down, PadAction.Activate)
            val target = series.episodeId(1, 3)
            until("the episode on the queue") { repository(this@withSeries).forItem(target) != null }
            // The menu again: it can be stopped now. Choose episodes from it.
            pad(this, PadAction.Secondary)
            until("the menu again") { sheetRows(this@withSeries).contains("Stop download") }
            pad(this, down, down, PadAction.Activate)
            until("select mode") { downloads(this@withSeries).selecting }
            withContext(Dispatchers.Main) {
                assertEquals(listOf("Ⓐ" to "Tick episode", "Ⓧ" to "Select season", "Start" to "Download", "Ⓑ" to "Cancel"), hints(this@withSeries))
                assertEquals("0 selected", rowTexts(this@withSeries).first { it.endsWith(" selected") })
            }
            // Something already coming cannot be ticked.
            pad(this, PadAction.Activate)
            withContext(Dispatchers.Main) { assertTrue(downloads(this@withSeries).selection.isEmpty) }
            // Ⓧ takes the rest of the season: the four that are not here.
            pad(this, PadAction.Primary)
            withContext(Dispatchers.Main) {
                assertEquals(4, downloads(this@withSeries).selection.count)
                assertTrue("the season's pill counts them: ${rowTexts(this@withSeries)}", rowTexts(this@withSeries).any { it == "Season 1 · 4/4" })
                assertEquals("4 selected", rowTexts(this@withSeries).first { it.endsWith(" selected") })
            }
            until("the strip clear of the select bar") { stripClear(this@withSeries) }
            withContext(Dispatchers.Main) {
                val focused = cards(this@withSeries).first { it.hasFocus() }
                val bottom = IntArray(2).also(focused::getLocationOnScreen)[1] + focused.height
                assertTrue("the focused card ($bottom) is above the bar (${dockTop(this@withSeries)})", bottom <= dockTop(this@withSeries))
            }
            shot(activity, "07-select-mode")
            // Start downloads them and leaves select mode.
            pad(this, PadAction.Menu)
            until("the four on the queue") { repository(this@withSeries).forItems(series.episodes(1).map { it.id }).size == 5 }
            withContext(Dispatchers.Main) { assertFalse(downloads(this@withSeries).selecting) }
            // Ⓑ in select mode cancels it, and stays on the page.
            pad(this, PadAction.Secondary)
            until("the menu once more") { sheetRows(this@withSeries).contains("Select episodes") }
            pad(this, down, down, PadAction.Activate)
            until("select mode again") { downloads(this@withSeries).selecting }
            pad(this, PadAction.Back)
            withContext(Dispatchers.Main) { assertFalse(downloads(this@withSeries).selecting) }
        }
    }

    @Test fun aLongPressOrARightClickOnACardOpensItsMenuAndDoesNotPlayIt(): Unit = runBlocking {
        withSeries {
            focusEpisodes(this)
            val card = withContext(Dispatchers.Main) { cards(this@withSeries).first { it.contentDescription.toString().contains("Episode 4") } }
            suspend fun touch(action: Int, at: Long, buttons: Int = 0) = withContext(Dispatchers.Main) {
                val properties = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER })
                val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 60f; y = 60f; pressure = 1f; size = 1f })
                val event = android.view.MotionEvent.obtain(downAt, at, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                card.dispatchTouchEvent(event); event.recycle()
            }
            downAt = android.os.SystemClock.uptimeMillis()
            touch(android.view.MotionEvent.ACTION_DOWN, downAt)
            until("the menu after a long press") { sheetRows(this@withSeries).contains("Select episodes") }
            touch(android.view.MotionEvent.ACTION_UP, android.os.SystemClock.uptimeMillis())
            delay(200)
            assertTrue("the long press did not play the episode", played.isEmpty())
            shot(activity, "12-long-press-menu")
            // Put the menu away and try a right click: at once.
            pad(this, PadAction.Back)
            until("the menu gone") { sheetRows(this@withSeries).isEmpty() }
            downAt = android.os.SystemClock.uptimeMillis()
            touch(android.view.MotionEvent.ACTION_DOWN, downAt, buttons = android.view.MotionEvent.BUTTON_SECONDARY)
            until("the menu from a right click") { sheetRows(this@withSeries).contains("Select episodes") }
            touch(android.view.MotionEvent.ACTION_UP, android.os.SystemClock.uptimeMillis())
            assertTrue(played.isEmpty())
            pad(this, PadAction.Back)
            // A plain tap still plays.
            val tap = android.os.SystemClock.uptimeMillis()
            downAt = tap
            touch(android.view.MotionEvent.ACTION_DOWN, tap)
            touch(android.view.MotionEvent.ACTION_UP, tap + 60)
            until("the episode to play") { played.contains(series.episodeId(1, 4)) }
        }
    }

    private var downAt = 0L

    // ------------------------------------------------------------------ the choices panel

    private fun downloadAction(rig: Rig) = all(rig.root).filterIsInstance<TextView>().first { it.tag == "download" }

    @Test fun theDownloadPanelShowsEachChoicesCountAndSizeAndPreviewsItInTheBar(): Unit = runBlocking {
        withSeries {
            withContext(Dispatchers.Main) { downloadAction(this@withSeries).performClick() }
            until("the panel") { rowTexts(this@withSeries).contains("Whole series") }
            withContext(Dispatchers.Main) {
                val rows = downloads(this@withSeries).panel.rowTexts
                assertTrue(rows.toString(), rows.any { it.startsWith("Keep the next 3 ready") && it.contains("3 episodes") })
                assertTrue(rows.any { it.startsWith("Rest of Season 1") && it.contains("3 episodes") })
                assertTrue(rows.any { it.startsWith("Everything unwatched") && it.contains("6 episodes") })
                assertTrue(rows.any { it.startsWith("Whole series") && it.contains("8 episodes") })
                assertTrue(rows.any { it.startsWith("Choose episodes") })
                assertFalse("no way to turn Keep ready off while it is off", rows.any { it.startsWith("Turn off Keep ready") })
            }
            shot(activity, "08-panel")
            // The cursor on a row previews what it adds, in the bar under the rows.
            val whole = withContext(Dispatchers.Main) { downloads(this@withSeries).panel.rows.first { it.contentDescription.toString().startsWith("Whole series") } }
            withContext(Dispatchers.Main) { whole.requestFocus() }
            until("the preview") { rowTexts(this@withSeries).any { it.startsWith("Adds 8") } }
            shot(activity, "09-panel-preview")
        }
    }

    @Test fun keepReadyIsSetFromThePanelAndTurnedOffKeepingTheFiles(): Unit = runBlocking {
        withSeries {
            withContext(Dispatchers.Main) { downloadAction(this@withSeries).performClick() }
            until("the panel") { rowTexts(this@withSeries).contains("Whole series") }
            // The stepper: one more makes it four.
            withContext(Dispatchers.Main) { all(downloads(this@withSeries).panel).filterIsInstance<android.widget.SeekBar>().first().requestFocus() }
            pad(this, PadAction.Step(com.pocketds.hub.input.Direction.RIGHT))
            withContext(Dispatchers.Main) {
                assertTrue(downloads(this@withSeries).panel.rowTexts.toString(),
                    downloads(this@withSeries).panel.rowTexts.any { it.startsWith("Keep the next 4 ready") && it.contains("4 episodes") })
            }
            val keep = withContext(Dispatchers.Main) { downloads(this@withSeries).panel.rows.first { it.contentDescription.toString().startsWith("Keep the next 4") } }
            withContext(Dispatchers.Main) { keep.performClick() }
            until("Keep ready on") { repository(this@withSeries).keepReady(series.id) == 4 }
            until("the next four fetched", 30_000) { repository(this@withSeries).forItems(series.every.map { it.id }).size == 4 }
            withContext(Dispatchers.Main) {
                val rows = repository(this@withSeries).forItems(series.every.map { it.id })
                assertEquals(setOf(series.episodeId(1, 3), series.episodeId(1, 4), series.episodeId(1, 5), series.episodeId(2, 1)), rows.keys)
                assertTrue("Keep ready's own", rows.values.all { it.origin == OfflineRepository.ORIGIN_KEEP_READY })
                val action = downloadAction(this@withSeries)
                assertEquals("Download, keeping the next 4 ready", action.contentDescription.toString())
                assertEquals("the number on the button", 4, (action.foreground as CountBadgeDrawable).count)
            }
            shot(activity, "10-keep-ready-on")
            // Opened again, it offers to turn it off; the files stay and are the owner's.
            withContext(Dispatchers.Main) { downloadAction(this@withSeries).performClick() }
            until("the panel again") { rowTexts(this@withSeries).any { it == "Turn off Keep ready" } }
            shot(activity, "11-panel-keep-on")
            val off = withContext(Dispatchers.Main) { downloads(this@withSeries).panel.rows.first { it.contentDescription.toString().startsWith("Turn off Keep ready") } }
            withContext(Dispatchers.Main) { off.performClick() }
            until("Keep ready off") { repository(this@withSeries).keepReady(series.id) == null }
            withContext(Dispatchers.Main) {
                val rows = repository(this@withSeries).forItems(series.every.map { it.id })
                assertEquals("the files stay", 4, rows.size)
                assertTrue("and are the owner's now", rows.values.all { it.origin == OfflineRepository.ORIGIN_OWN })
                assertTrue(downloadAction(this@withSeries).foreground == null)
            }
        }
    }

    // ------------------------------------------------------------------ Keep ready's rules against the real queue

    @Test fun keepReadyFetchesAheadAndRemovesOnlyWhenTheNextOneIsFinishedAndNothingPlays(): Unit = runBlocking {
        withSeries {
            val repo = repository(this@withSeries)
            repo.setKeepReady(series.id, 3)
            val a = series.episodeId(1, 3); val b = series.episodeId(1, 4); val c = series.episodeId(1, 5)
            val d = series.episodeId(2, 1); val e = series.episodeId(2, 2)
            suspend fun run() = withContext(Dispatchers.Main) { KeepReadyRunner.run(activity, hub(this@withSeries)) }
            suspend fun have() = withContext(Dispatchers.Main) { repo.forItems(series.every.map { it.id }).keys }
            // A removal of a file still arriving goes through the download service, a moment later.
            suspend fun expect(what: String, expected: Set<String>) = until(what, 30_000) { repo.forItems(series.every.map { it.id }).keys == expected }
            // Turned on at the start: A, B and C.
            run()
            expect("A, B and C", setOf(a, b, c))
            // A finished: D is fetched, and A stays.
            series.watched += a
            run()
            expect("D fetched and A kept", setOf(a, b, c, d))
            // B finished: E is fetched, and A, the one before it, goes.
            series.watched += b
            run()
            expect("E fetched and A gone", setOf(b, c, d, e))
            // C is finished too, but while something plays nothing is removed; fetching goes on.
            series.watched += c
            withContext(Dispatchers.Main) { KeepReadyRunner.playerOpened() }
            run()
            delay(1_500)
            assertTrue("B stays while the player is open: ${have()}", b in have())
            withContext(Dispatchers.Main) { KeepReadyRunner.playerClosed() }
            run()
            until("B to go once the player is left", 30_000) { b !in repo.forItems(series.every.map { it.id }).keys }
            assertTrue("C, the one just finished, stays", c in have())
        }
    }

    /** The fixture hub the page was given, for Keep ready to ask the same questions. */
    private fun hub(rig: Rig): com.pocketds.hub.net.HubApi = rig.screen.field("api")
}
