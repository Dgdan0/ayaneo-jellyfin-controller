package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AudioHandoff
import com.pocketds.hub.reader.AudioSource
import com.pocketds.hub.reader.AudiobookScreen
import com.pocketds.hub.reader.ComicFit
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PageGrid
import com.pocketds.hub.reader.PageGridView
import com.pocketds.hub.reader.PageSurface
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.reader.ReadAlongDock
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadingAudio
import com.pocketds.hub.reader.ReadingAudioService
import com.pocketds.hub.reader.SleepChoice
import com.pocketds.hub.reader.SleepTimer
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.ListeningSettings
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.settings.Prefs
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Listening and finding your place (#16, run B2), against a local hub and
 * generated content: a comic's scrubber and Pages grid draw the hub's
 * thumbnails, never the full scans (C4); an audiobook plays on without its
 * screen, keeps the device awake, gives way to video, and has its speed, sleep
 * timer, time left and parts (A1, A2); read along keeps its speed per book,
 * steps by sentence and says whether the page follows the voice (A5). Every
 * save goes to the local hub or this app's own storage; nothing here reaches a
 * real server, book or recording.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderListeningTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun host(activity: ReaderFixtureActivity, exits: () -> Unit = {}) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> { exits(); true }; else -> null }
        } as ScreenHost

    private fun start(): ReaderFixtureActivity =
        (ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity)
            .also { check(it.packageName.endsWith(".uitest")) }

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(25_000) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync(); delay(500)
        File(activity.getExternalFilesDir(null), "reader-listening-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun failureShot(activity: ReaderFixtureActivity, name: String) {
        File(activity.getExternalFilesDir(null), "reader-listening-$name-failure.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private suspend fun untilShell(what: String, command: String, text: String) {
        try { withTimeout(10_000) { while (!shell(command).contains(text)) delay(250) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    /** A row of a side sheet by its words, as a finger would press it. */
    private fun row(root: View, words: String): View? {
        var view: View? = all(root).firstOrNull { it is TextView && it.isShown && it.text.toString() == words } ?: return null
        while (view != null && !view.isClickable) view = view.parent as? View
        return view
    }

    // ------------------------------------------------------------------ C4

    @Test fun thePreviewAndThePagesGridDrawThumbnailsNotPages(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val oldFit = DomainPreferences.comicDefaultFit(activity)
        val work = "thumbs-${System.nanoTime()}"
        val pages = 24
        val requests = CopyOnWriteArrayList<String>()
        val full = ConcurrentHashMap<Int, ByteArray>()
        val thumbs = ConcurrentHashMap<Int, ByteArray>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path.contains("/pages/")) {
                        requests += request.path.orEmpty()
                        val thumb = path.endsWith("/thumb")
                        val index = path.removeSuffix("/thumb").substringAfterLast('/').toInt()
                        // The hub's answer past the last page.
                        if (index !in 0 until pages) return MockResponse().setResponseCode(400)
                        val bytes = if (thumb) thumbs.getOrPut(index) { ReaderFixtures.page(240, 368, "t${index + 1}") }
                            else full.getOrPut(index) { ReaderFixtures.page(1000, 1536, "p${index + 1}") }
                        return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
                    }
                    if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                    val list = (0 until pages).map { JSONObject().put("index", it).put("width", 1000).put("height", 1536).put("isWide", false) }
                    val manifest = JSONObject().put("workId", work).put("source", "kavita").put("sourceItemId", "issue-1").put("kind", "comic")
                        .put("title", "Chapter 7").put("seriesTitle", "Thumb Comics").put("number", "7").put("pageCount", pages)
                        .put("currentPage", 0).put("direction", "ltr").put("pages", JSONArray(list))
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(manifest.toString())
                }
            }
            start()
        }
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: PagedImageReaderScreen? = null
        lateinit var root: View
        // The scrubber's and the grid's thumbnails, at the grid's width; the reader also asks for small
        // ones to find the margins on (#18, C5), which are not what these checks count.
        fun isThumb(path: String) = path.substringBefore('?').endsWith("/thumb") && path.endsWith("w=${PageGrid.THUMB_WIDTH}")
        fun isMarginThumb(path: String) = path.substringBefore('?').endsWith("/thumb") && path.endsWith("w=${com.pocketds.hub.reader.PageBounds.THUMB_WIDTH}")
        fun isFull(path: String) = !path.substringBefore('?').endsWith("/thumb")
        fun pageOf(path: String) = path.substringBefore('?').removeSuffix("/thumb").substringAfterLast('/').toInt()
        try {
            DomainPreferences.setComicDefaultFit(activity, ComicFit.WHOLE)
            withContext(Dispatchers.Main) {
                screen = PagedImageReaderScreen(HubClient(activity), work, "issue-1", "Thumb Comics", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            fun surface(): PageSurface = screen!!.field("surface")
            fun held(page: Int) = surface().slots.any { it.key?.page == page && it.ready }
            fun controls() = screen!!.field<Boolean>("controlsVisible")
            until("page 1") { surface().front.key?.page == 0 && surface().front.ready }
            until("page 2 decoded behind it") { held(1) }
            withContext(Dispatchers.Main) { assertTrue(screen!!.onPad(PadAction.Menu)) }
            until("the controls") { controls() }
            delay(300)

            // The scrubber, dragged from page 4 past page 12 to page 18: once the thumb rests, page 18's
            // thumbnail, at the grid's width; the pages it passed are never asked for, and no full page.
            val seek: SeekBar = screen!!.field("seek")
            val down = SystemClock.uptimeMillis()
            val y = withContext(Dispatchers.Main) { seek.height / 2f }
            val x = withContext(Dispatchers.Main) {
                val track = seek.width - seek.paddingLeft - seek.paddingRight
                (0 until pages).map { seek.paddingLeft + track * it / seek.max.toFloat() }
            }
            withContext(Dispatchers.Main) {
                assertTrue(seek.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x[3], y, 0)))
                seek.dispatchTouchEvent(MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_MOVE, x[11], y, 0))
                seek.dispatchTouchEvent(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_MOVE, x[17], y, 0))
                assertEquals(17, seek.progress)
                assertTrue("The preview shows while the thumb is held", screen!!.field<View>("previewCard").isShown)
            }
            val preview: ImageView = screen!!.field("previewImage")
            until("page 18's thumbnail in the preview") {
                requests.any { it.endsWith("/pages/17/thumb?w=${PageGrid.THUMB_WIDTH}") } && preview.drawable != null
            }
            withContext(Dispatchers.Main) {
                assertEquals("Only the page the thumb rested on", listOf(17), requests.filter(::isThumb).map(::pageOf))
                assertFalse("No full page for a preview", requests.filter(::isFull).map(::pageOf).contains(17))
            }
            shot(activity, "10-scrubber-thumbnail")
            // Let go: the page opens, now in full.
            withContext(Dispatchers.Main) { seek.dispatchTouchEvent(MotionEvent.obtain(down, down + 900, MotionEvent.ACTION_UP, x[17], y, 0)) }
            until("page 18") { surface().front.key?.page == 17 && surface().front.ready }
            until("its neighbours") { held(16) && held(18) }

            // The Pages grid: every page as its thumbnail, opening on the page you are on.
            if (!withContext(Dispatchers.Main) { controls() }) withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Menu) }
            until("the controls") { controls() }
            val before = requests.size
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Pages" && it.isShown }.performClick() }
            val grid: PageGridView = screen!!.field("pageGrid")
            until("the bars to step aside") { !controls() && !screen!!.field<com.pocketds.hub.reader.ReaderBars>("bars").top.isShown }
            val list: RecyclerView = grid.field("grid")
            fun shown(): List<Int> = (0 until list.childCount).map { list.getChildAdapterPosition(list.getChildAt(it)) }.filter { it >= 0 }
            fun asked(page: Int) = requests.any { it.endsWith("/pages/$page/thumb?w=${PageGrid.THUMB_WIDTH}") }
            until("the grid's thumbnails") { grid.isOpen && shown().isNotEmpty() && shown().all(::asked) }
            withContext(Dispatchers.Main) {
                assertEquals(17, grid.selected)
                assertTrue("The cell of the page you are on says so", all(grid).any { it is TextView && it.isShown && it.text == "18 · Reading" })
                assertTrue("The grid's keys at its foot", all(grid).any { it is TextView && it.isShown && it.text == "Open page" })
                assertTrue("The grid asks only for thumbnails", requests.drop(before).filterNot(::isMarginThumb).all { isThumb(it) && it.endsWith("?w=${PageGrid.THUMB_WIDTH}") })
                val density = activity.resources.displayMetrics.density
                assertEquals("Seven across the Pocket's width", PageGrid.columns(((root.width / density) - 36).toInt()),
                    grid.field<GridLayoutManager>("layout").spanCount)
            }
            val columns = withContext(Dispatchers.Main) { grid.field<GridLayoutManager>("layout").spanCount }
            val chosen = 16 - columns
            // The D-pad moves the cursor: left a page, up a row.
            withContext(Dispatchers.Main) {
                assertTrue(screen!!.onPad(PadAction.Step(Direction.LEFT)))
                assertTrue(screen!!.onPad(PadAction.Step(Direction.UP)))
                assertEquals(chosen, grid.selected)
            }
            until("the row above drawn") { shown().contains(chosen) && shown().all(::asked) }
            withContext(Dispatchers.Main) { assertTrue(requests.drop(before).all(::isThumb)) }
            shot(activity, "11-pages-grid")
            // Ⓐ opens the page under the cursor.
            withContext(Dispatchers.Main) {
                assertTrue(screen!!.onPad(PadAction.Activate))
                assertFalse(grid.isOpen)
            }
            until("the page chosen") { surface().front.key?.page == chosen && surface().front.ready }
            // Ⓑ closes it, back to the controls it was opened from, on the page you were on.
            withContext(Dispatchers.Main) {
                if (!controls()) screen!!.onPad(PadAction.Menu)
                all(root).first { it.contentDescription == "Pages" && it.isShown }.performClick()
                assertTrue(grid.isOpen)
                assertFalse("The bars step aside for the grid", controls())
                assertEquals(chosen, grid.selected)
                assertTrue(screen!!.onPad(PadAction.Back))
                assertFalse(grid.isOpen)
                assertTrue(controls())
                assertEquals(chosen, surface().front.key?.page)
            }
        } catch (failure: Throwable) {
            failureShot(activity, "thumbnails")
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            DomainPreferences.setComicDefaultFit(activity, oldFit)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    // ------------------------------------------------------------------ A1, A2

    private fun service(): ReadingAudioService? =
        ReadingAudio::class.java.getDeclaredField("service").apply { isAccessible = true }.get(null) as ReadingAudioService?

    private fun player(): ExoPlayer = service()!!.player

    @Test fun anAudiobookPlaysOnWithoutItsScreenWithItsListeningControls(): Unit = runBlocking {
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val oldSeek = PlaybackSettings.seekSeconds(activity)
        val oldComfort = ComfortSettings.load(activity)
        val work = "listening-${System.nanoTime()}"
        val edition = ReadingEdition(id = "audio-1", workId = work, source = "storyteller", sourceItemId = "audio-1",
            kind = "audiobook", format = "Audiobook", narrator = "A generated voice")
        val other = edition.copy(id = "audio-2", sourceItemId = "audio-2", narrator = "Another generated voice")
        // Three parts of generated silence, a minute each, and a generated cover.
        val parts = listOf("01 Opening.wav" to 60, "02 The ridge.wav" to 60, "03 The observatory.wav" to 60)
        val archive = ReaderFixtures.audiobook(parts)
        val coverImage = ReaderFixtures.cover(600, "The Last Observatory")
        val book = com.pocketds.hub.model.ReadingWork(id = work, title = "The Last Observatory", authors = listOf("A. Fixture"),
            series = "Observatory", seriesIndex = 2.0, artwork = "/v1/img/reading/fixture-cover/$work", editions = listOf(edition, other))
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        path.endsWith("/file") -> {
                            downloads.incrementAndGet()
                            MockResponse().setHeader("Content-Type", "application/zip").setBody(Buffer().write(archive))
                        }
                        path.startsWith("/v1/img/reading/fixture-cover/") ->
                            MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(coverImage))
                        path == "/v1/reading/works/$work" -> MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(JSONObject().put("id", work).put("title", book.title).put("authors", JSONArray(book.authors))
                                .put("series", book.series).put("seriesIndex", book.seriesIndex).put("artwork", book.artwork).toString())
                        request.method == "POST" -> MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                        else -> MockResponse().setHeader("Content-Type", "application/json").setBody("{\"locator\":null}")
                    }
                }
            }
            start()
        }
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        // From the book's page the screen is given the book; from a reader it reads it (the first screen here).
        fun screen(of: ReadingEdition = edition, given: Boolean = true): AudiobookScreen = AudiobookScreen(HubClient(activity), work, of,
            "The Last Observatory", { true }, listOf(edition, other).filter { it == of }, null, emptyList(),
            work = book.takeIf { given })
        var current: AudiobookScreen? = null
        lateinit var root: View
        suspend fun show(next: AudiobookScreen) = withContext(Main) {
            current?.let { it.onHide(); it.onDestroyView() }
            current = next
            root = next.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); next.onShow()
        }
        val keys = mutableListOf<String>()
        val positions = ReadingAudio.positions(activity)
        fun state() = ReadingAudio.state.value
        try {
            ComfortSettings.save(activity, ScreenComfort())
            PlaybackSettings.setSeekSeconds(activity, 15)
            // What the app shows behind a full-screen reader on Glass: its frame's dark.
            withContext(Main) { activity.window.decorView.setBackgroundColor(com.pocketds.hub.ui.glass.ArtworkPalette.NEUTRAL.dark) }
            show(screen(given = false))
            until("the book on the reading-audio player") { state().book?.workId == work && state().ready }
            until("each part's length") { state().partsMs.size == 3 && state().partsMs.all { (it ?: 0) > 55_000 } }
            val positionKey = state().book!!.positionKey.also(keys::add)
            // Glass (#11): the cover beside its words, read from the hub when the screen was not given the book.
            until("the cover") { current!!.field<android.widget.ImageView?>("cover")?.drawable.let { it != null && it.intrinsicWidth > 0 } }
            withContext(Main) {
                assertEquals("Audiobook · Book 2 · Observatory", current!!.field<TextView?>("eyebrow")!!.text.toString())
                assertEquals("A. Fixture · read by A generated voice", current!!.field<TextView?>("facts")!!.text.toString())
                assertEquals("The page is the cover's", book.artwork, current!!.pageArtwork)
                // The transport's jump is the player's seek step: 15 seconds here, written on the discs.
                assertNotNull(all(root).firstOrNull { it.contentDescription == "Back 15 seconds" && (it as TextView).text == "−15" })
                assertNotNull(all(root).firstOrNull { it.contentDescription == "Forward 15 seconds" && (it as TextView).text == "+15" })
                // Every control on screen, clear of the keys' row.
                val keysTop = root.height - Styler.dpInt(activity, com.pocketds.hub.reader.ReaderKeys.ROW_DP.toFloat())
                current!!.field<List<View>>("controls").forEach { control ->
                    val at = IntArray(2).also(control::getLocationInWindow)
                    val rootAt = IntArray(2).also(root::getLocationInWindow)
                    assertTrue("${control.contentDescription} is shown", control.isShown)
                    assertTrue("${control.contentDescription} sits above the keys", at[1] - rootAt[1] + control.height <= keysTop)
                    assertTrue("${control.contentDescription} sits inside the screen", at[0] >= rootAt[0] && at[0] + control.width <= rootAt[0] + root.width)
                }
                assertFalse("Opening a book does not start it", state().playing)
                all(root).first { it.contentDescription == "Play audiobook" }.performClick()
            }
            until("playing") { state().playing }
            withContext(Main) {
                val at = player().currentPosition
                all(root).first { it.contentDescription == "Forward 15 seconds" }.performClick()
                assertTrue("Forward is the seek step: ${player().currentPosition - at}", player().currentPosition - at in 14_000L..16_500L)
            }

            // A2: the speed, kept for this book.
            withContext(Main) { all(root).first { it is TextView && it.text == "Speed 1×" }.performClick() }
            until("the speeds") { row(root, "1.5×") != null }
            withContext(Main) { row(root, "1.5×")!!.performClick() }
            until("one and a half") { state().speed == 1.5f }
            withContext(Main) {
                assertEquals(1.5f, ListeningSettings.speed(activity, work))
                assertEquals(1.5f, player().playbackParameters.speed)
            }
            until("the button to say so") { all(root).any { it is TextView && it.text == "Speed 1.5×" } }
            // The time left in the part and the book, as heard at that speed.
            until("the time left") { current!!.field<TextView>("left").text.let { it.contains("left in part") && it.contains("in book") } }
            shot(activity, "12-audiobook-listening")

            // A1: video starting pauses the book, through the one hook the video service gained.
            withContext(Main) { AudioHandoff.started(activity, AudioSource.VIDEO); AudioHandoff.stopped(AudioSource.VIDEO) }
            until("paused for the video") { !state().playing }
            withContext(Main) { assertFalse(player().isPlaying); ReadingAudio.play() }
            until("playing again") { state().playing }

            // A1: the screen goes, and the book plays on, in the foreground, keeping the device awake.
            withContext(Main) {
                current!!.onHide(); current!!.onDestroyView(); current = null
                activity.setContentView(FrameLayout(activity))
            }
            val gone = withContext(Main) { player().currentPosition }
            delay(1_500)
            withContext(Main) {
                assertTrue("Still playing with its screen gone", state().playing && player().currentPosition > gone + 1_000)
            }
            val component = "${activity.packageName}/com.pocketds.hub.reader.ReadingAudioService"
            untilShell("the service in the foreground", "dumpsys activity services $component", "isForeground=true")
            untilShell("the player's wake lock", "dumpsys power", "ExoPlayer:WakeLockManager")

            // A second screen for the book finds it playing: nothing is fetched again.
            val fetched = downloads.get()
            show(screen())
            until("the second screen on the player") { current!!.field<TextView>("partTitle").text.startsWith("Part ${state().part + 1} of 3 · ") }
            withContext(Main) {
                assertEquals("Nothing downloaded again", fetched, downloads.get())
                assertFalse("A part's name is shown without its file's extension", current!!.field<TextView>("partTitle").text.endsWith(".wav"))
                assertNotNull(all(root).firstOrNull { it.contentDescription == "Pause audiobook" })
            }

            // A2: the parts, each with its length; the third opens at its start.
            withContext(Main) { all(root).first { it is TextView && it.text == "Parts" }.performClick() }
            until("the parts") { row(root, "3. 03 The observatory") != null }
            shot(activity, "13-audiobook-parts")
            withContext(Main) { row(root, "3. 03 The observatory")!!.performClick() }
            until("part 3") { state().part == 2 && player().currentMediaItemIndex == 2 }

            // A2: the sleep timer fades the last of it, pauses, and steps back over what faded.
            withContext(Main) { ReadingAudio.seekTo(1, 40_000); ReadingAudio.play() }
            until("playing part 2") { state().playing && state().part == 1 }
            withContext(Main) { ReadingAudio.runSleep(SleepTimer(SleepChoice.Minutes(5), remainingMs = 2_500)) }
            until("the fade") { state().sleep?.fading == true && player().volume < 0.1f }
            until("the button to say so") { all(root).any { it is TextView && it.text == "Sleep · fading" } }
            until("asleep") { !state().playing && state().sleep == null }
            withContext(Main) {
                assertEquals(1, player().currentMediaItemIndex)
                // About 44 seconds in at one and a half, less the half minute that faded.
                assertTrue("Stepped back over the fade: ${player().currentPosition}", player().currentPosition in 10_000L..17_500L)
                assertEquals(1f, player().volume)
                assertEquals(1, positions.getInt("$positionKey:part", -1))
                assertTrue("The place is kept", abs(positions.getLong("$positionKey:ms", -1) - player().currentPosition) < 1_000)
            }

            // A2: to the end of this part: it stops as the part ends, back over what faded at its end.
            withContext(Main) { ReadingAudio.seekTo(0, 56_000); ReadingAudio.play() }
            until("playing near the end of part 1") { state().playing && state().part == 0 && state().positionMs >= 56_000 }
            withContext(Main) {
                ReadingAudio.setSleep(SleepChoice.EndOfPart)
                assertEquals(SleepChoice.EndOfPart, state().sleep?.choice)
            }
            until("stopped with the part") { !state().playing && state().sleep == null }
            withContext(Main) {
                assertEquals("Back in the part that ended", 0, player().currentMediaItemIndex)
                assertTrue("Half a minute before its end: ${player().currentPosition}", player().currentPosition in 28_500L..31_000L)
                assertEquals(1f, player().volume)
            }
            shot(activity, "14-audiobook-asleep")

            // Stop: off the player, its place kept, and the service gone.
            withContext(Main) { all(root).first { it is TextView && it.text == "Stop" }.performClick() }
            until("the player let go") { state().book == null && service() == null }
            assertEquals(0, positions.getInt("$positionKey:part", -1))
            assertTrue(positions.getLong("$positionKey:ms", -1) in 28_500L..31_000L)

            // Another narration goes on the player, then this one again: it opens at its place, and
            // changing books never saves one's place as the other's.
            show(screen(other))
            until("the other narration on the player") { state().book?.sourceItemId == other.sourceItemId && state().ready }
            val otherKey = state().book!!.positionKey.also(keys::add)
            withContext(Main) {
                assertFalse(state().playing)
                ReadingAudio.seekTo(2, 12_000)
            }
            until("its place kept") { positions.getInt("$otherKey:part", -1) == 2 }
            show(screen())
            until("this narration back on the player") { state().book?.sourceItemId == edition.sourceItemId && state().ready }
            withContext(Main) {
                assertEquals("At its place", 0, player().currentMediaItemIndex)
                assertTrue("At its place: ${player().currentPosition}", player().currentPosition in 28_500L..31_000L)
                assertEquals("The other narration's place is its own", 2, positions.getInt("$otherKey:part", -1))
                assertTrue(positions.getLong("$otherKey:ms", -1) in 11_500L..12_500L)
                ReadingAudio.stop()
            }
            until("the player let go again") { state().book == null && service() == null }
        } catch (failure: Throwable) {
            failureShot(activity, "audiobook")
            throw failure
        } finally {
            withContext(Main) {
                if (ReadingAudio.state.value.book != null) ReadingAudio.stop()
                current?.let { it.onHide(); it.onDestroyView() }
                activity.finish()
            }
            Prefs.of(activity).edit().remove("listening_speed:$work").apply()
            keys.forEach { positions.edit().remove("$it:part").remove("$it:ms").apply() }
            PlaybackSettings.setSeekSeconds(activity, oldSeek)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    /**
     * The cover fills its square whatever its shape (#18): a portrait cover
     * and a landscape one, each in three bands, are cut from the middle, so
     * the square shows the middle of each and no page colour under or beside
     * it. Built only: nothing is downloaded or played.
     */
    @Test fun anAudiobookCoverFillsItsSquareWhateverItsShape(): Unit = runBlocking {
        val context = ins.targetContext
        val oldLook = com.pocketds.hub.settings.LookSettings.get(context)
        com.pocketds.hub.settings.LookSettings.set(context, com.pocketds.hub.settings.Look.GLASS)
        val activity = start()
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val red = android.graphics.Color.rgb(220, 40, 40)
        val blue = android.graphics.Color.rgb(40, 60, 200)
        val green = android.graphics.Color.rgb(40, 180, 70)
        /** Three bands down a portrait cover, or across a landscape one. */
        fun banded(width: Int, height: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            listOf(red, blue, green).forEachIndexed { i, colour ->
                paint.color = colour
                if (height > width) canvas.drawRect(0f, height * i / 3f, width.toFloat(), height * (i + 1) / 3f, paint)
                else canvas.drawRect(width * i / 3f, 0f, width * (i + 1) / 3f, height.toFloat(), paint)
            }
            return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        val covers = mapOf("portrait" to banded(400, 600), "landscape" to banded(600, 400))
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val name = request.path.orEmpty().substringBefore('?').substringAfterLast('/')
                    return covers[name]?.let { MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(it)) }
                        ?: MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: AudiobookScreen? = null
        fun near(pixel: Int, colour: Int) = abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(colour)) < 40 &&
            abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(colour)) < 40 &&
            abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(colour)) < 40
        try {
            for ((shape, edges) in listOf("portrait" to listOf(red, green, blue, blue), "landscape" to listOf(blue, blue, red, green))) {
                val work = "cover-$shape-${System.nanoTime()}"
                val edition = ReadingEdition(id = "audio", workId = work, source = "storyteller", sourceItemId = "audio", kind = "audiobook")
                val book = com.pocketds.hub.model.ReadingWork(id = work, title = "The Last Observatory", authors = listOf("A. Fixture"),
                    artwork = "/v1/img/reading/fixture-cover/$shape", editions = listOf(edition))
                withContext(Main) {
                    screen?.onDestroyView()
                    screen = AudiobookScreen(HubClient(activity), work, edition, "The Last Observatory", { true }, listOf(edition), null, emptyList(), work = book)
                    activity.setContentView(screen!!.onCreateView(host(activity), FrameLayout(activity)))
                }
                until("the $shape cover") { screen!!.field<android.widget.ImageView?>("cover")?.drawable.let { it != null && it.intrinsicWidth > 0 } }
                // The cross-fade done.
                delay(700)
                shot(activity, "19-cover-$shape")
                // Where the cover is on screen, read off a screenshot (its bitmap lives on the GPU).
                val (x, y, size) = withContext(Main) {
                    val cover = screen!!.field<android.widget.ImageView?>("cover")!!
                    assertEquals("The cover is square", cover.width, cover.height)
                    val at = IntArray(2).also(cover::getLocationOnScreen)
                    Triple(at[0], at[1], cover.width)
                }
                val screenshot = ins.uiAutomation.takeScreenshot()
                val (top, bottom, left, right) = edges
                val inset = 6
                fun at(dx: Int, dy: Int) = screenshot.getPixel(x + dx, y + dy)
                // Cut from the middle: the top and the foot (or the sides) show the outer bands, the middle the centre.
                assertTrue("$shape: top edge", near(at(size / 2, inset), top))
                assertTrue("$shape: foot, no band under the cover", near(at(size / 2, size - 1 - inset), bottom))
                assertTrue("$shape: left edge", near(at(inset, size / 2), left))
                assertTrue("$shape: right edge", near(at(size - 1 - inset, size / 2), right))
                assertTrue("$shape: the middle", near(at(size / 2, size / 2), blue))
                screenshot.recycle()
            }
        } finally {
            withContext(Main) { screen?.onDestroyView(); activity.finish() }
            HubSettings.save(activity, oldUrl, oldToken)
            com.pocketds.hub.settings.LookSettings.set(context, oldLook)
            server.shutdown()
        }
    }

    // ------------------------------------------------------------------ A5

    @Test fun readAlongKeepsItsSpeedStepsBySentenceAndSaysWhetherThePageFollows(): Unit = runBlocking {
        val activity = start()
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldSeek = PlaybackSettings.seekSeconds(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "read-along-${System.nanoTime()}"
        // Eight narrated sentences of four seconds each.
        val server = ReaderFixtures.fileServer(ReaderFixtures.epub(aligned = true, sentenceSeconds = 4))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        suspend fun open() = withContext(Main) {
            screen = EpubReaderScreen(HubClient(activity), work, "edition-aligned", "The Last Observatory", { true },
                readAlong = true, readAlongAvailable = true)
            root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
        }
        fun narration(): ReadAlongPlayback? = screen!!.field("narration")
        fun dock(): ReadAlongDock = screen!!.field("narrationDock")
        fun pill(): TextView = screen!!.field("narrationPill")
        fun sentence(): String? = narration()!!.let { it.timeline.active(it.position.track, it.position.offsetMs)?.fragment }
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, ScreenComfort())
            PlaybackSettings.setSeekSeconds(activity, 15)
            open()
            until("the narration dock") { narration() != null && dock().isShown }
            withContext(Main) {
                // The dock's jumps are the player's seek step.
                assertNotNull(all(dock()).firstOrNull { it.contentDescription == "Back 15 seconds" })
                all(dock()).first { it.contentDescription == "Forward 15 seconds" }.performClick()
                assertEquals(15_000L, narration()!!.position.offsetMs)
                all(dock()).first { it.contentDescription == "Back 15 seconds" }.performClick()
                assertEquals(0L, narration()!!.position.offsetMs)
                // A5: the speed, a press of the dock's pill, kept for the book.
                assertEquals(1f, narration()!!.speed)
                all(dock()).first { it.contentDescription == "Change narration speed" }.performClick()
                assertEquals(1.1f, narration()!!.speed, 0.001f)
                assertEquals(1.1f, ListeningSettings.speed(activity, work), 0.001f)
            }
            // Opened again, the book keeps it.
            withContext(Main) { screen!!.onHide(); screen!!.onDestroyView() }
            open()
            until("the narration again") { narration() != null && dock().isShown }
            withContext(Main) { assertEquals(1.1f, narration()!!.speed, 0.001f) }
            until("the dock to say so") { all(dock()).any { it is TextView && it.text == "1.1×" } }

            // Play, then Start hides the menu: the pill says the page follows the voice, at its speed.
            withContext(Main) { all(dock()).first { it.contentDescription == "Play narration" }.performClick() }
            until("narrating") { narration()!!.isPlaying && narration()!!.position.offsetMs > 300 }
            withContext(Main) { assertTrue(screen!!.onPad(PadAction.Menu)) }
            until("the menu to close") { !screen!!.field<Boolean>("controlsVisible") }
            until("the pill") { pill().isShown && pill().text == "▶  Following · 1.1×" }
            shot(activity, "15-read-along-following")

            // R1 and L1 step through the narration a sentence at a time.
            withContext(Main) {
                val from = sentence()!!.removePrefix("s").toInt()
                assertTrue(screen!!.onPad(PadAction.Section(1)))
                assertEquals("s${from + 1}", sentence())
                assertTrue(screen!!.onPad(PadAction.Section(1)))
                assertEquals("s${from + 2}", sentence())
                // Back at once: the sentence before (further into one, back is its own start).
                assertTrue(screen!!.onPad(PadAction.Section(-1)))
                assertEquals("s${from + 1}", sentence())
                // A step leaves the player buffering for a moment; it is still on.
                assertTrue("Still narrating", narration()!!.isOn)
            }

            // Turning the page while it reads: the voice carries on, and the pill says you read on your own.
            withContext(Main) { assertTrue(screen!!.onPad(PadAction.Step(Direction.RIGHT))) }
            until("Reading") { pill().text == "▶  Reading · 1.1×" }
            until("the narration carrying on") { narration()!!.isPlaying }
            shot(activity, "16-read-along-reading")
            // L3: back to the voice.
            withContext(Main) {
                assertTrue(screen!!.onPad(PadAction.Click(Stick.LEFT, down = true)))
                screen!!.onPad(PadAction.Click(Stick.LEFT, down = false))
            }
            until("Following") { pill().text == "▶  Following · 1.1×" }
            // With the menu open the dock says it, and the pill steps aside.
            withContext(Main) { assertTrue(screen!!.onPad(PadAction.Menu)) }
            until("the dock's heading") { dock().isShown && all(dock()).any { it is TextView && it.text == "Read along · Following" } && !pill().isShown }
            shot(activity, "17-read-along-dock")

            // A1: video starting pauses the narration.
            withContext(Main) { AudioHandoff.started(activity, AudioSource.VIDEO); AudioHandoff.stopped(AudioSource.VIDEO) }
            until("paused for the video") { !narration()!!.isPlaying }
        } catch (failure: Throwable) {
            failureShot(activity, "read-along")
            throw failure
        } finally {
            withContext(Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            Prefs.of(activity).edit().remove("listening_speed:$work").apply()
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            PlaybackSettings.setSeekSeconds(activity, oldSeek)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    private companion object {
        val Main = Dispatchers.Main
    }
}
