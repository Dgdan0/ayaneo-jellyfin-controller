package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.ComicFit
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PageSurface
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.reader.PagedImageState
import com.pocketds.hub.reader.ReadAlongDock
import com.pocketds.hub.reader.ReaderBars
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.HubSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The readers' look and comfort (#16, run B1), against a local hub and
 * generated books: a comic's glass bars float over a page that keeps its size
 * and its pages turn without a blank (C3); a book's page makes room for its
 * menu; read along, the glass dock takes the lower bar's place and the
 * sentence glows in the accent; Comfort dims, warms and blacks out the page.
 * Nothing here reaches a real server or a real book.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderGlassTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun host(activity: ReaderFixtureActivity, exits: () -> Unit = {}) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> { exits(); true }; else -> null }
        } as ScreenHost

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(25_000) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync(); delay(500)
        File(activity.getExternalFilesDir(null), "reader-glass-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun page(width: Int, height: Int, label: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bands = listOf(Color.rgb(196, 64, 52), Color.rgb(242, 214, 140), Color.rgb(52, 118, 170), Color.rgb(250, 250, 244))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = width / 10f; textAlign = Paint.Align.CENTER }
        for (i in 0 until 4) {
            paint.color = bands[i]
            canvas.drawRect(0f, height * i / 4f, width.toFloat(), height * (i + 1) / 4f, paint)
            paint.color = if (i == 3 || i == 1) Color.rgb(20, 20, 24) else Color.WHITE
            canvas.drawText("$label · ${i + 1}/4", width / 2f, height * (i + 0.55f) / 4f, paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray().also { bitmap.recycle() }
    }

    @Test fun comicBarsFloatOverThePageAndPagesTurnWithoutABlank(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldFit = DomainPreferences.comicDefaultFit(activity)
        val work = "glass-comics-${System.nanoTime()}"
        // The shape of a real scan: 1988 x 3056, as JPEG.
        val pages = 5
        val images = ConcurrentHashMap<Int, ByteArray>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path.contains("/pages/")) {
                    val index = path.substringAfterLast('/').toInt()
                    val bytes = images.getOrPut(index) { page(1988, 3056, "p${index + 1}") }
                    return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
                }
                if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                val list = (0 until pages).map { JSONObject().put("index", it).put("width", 1988).put("height", 3056).put("isWide", false) }
                val manifest = JSONObject().put("workId", work).put("source", "kavita").put("sourceItemId", "issue-1").put("kind", "comic")
                    .put("title", "Chapter 1").put("seriesTitle", "Glass Comics").put("number", "1").put("pageCount", pages)
                    .put("currentPage", 0).put("direction", "ltr").put("pages", org.json.JSONArray(list))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(manifest.toString())
            }
        }
        server.start()
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: PagedImageReaderScreen? = null
        lateinit var root: View
        val report = StringBuilder()
        try {
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            DomainPreferences.setComicDefaultFit(activity, ComicFit.THIRDS)
            // The pages are made before the reader opens, so making them is not counted as its memory.
            (0 until pages).forEach { index -> images.getOrPut(index) { page(1988, 3056, "p${index + 1}") } }
            suspend fun heap(): Long {
                Runtime.getRuntime().gc(); delay(400); Runtime.getRuntime().gc()
                return android.os.Debug.getNativeHeapAllocatedSize()
            }
            val heapBefore = heap()
            withContext(Dispatchers.Main) {
                screen = PagedImageReaderScreen(HubClient(activity), work, "issue-1", "Glass Comics", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            fun surface(): PageSurface = screen!!.field("surface")
            fun state(): PagedImageState = screen!!.field("state")
            fun loading(): TextView = screen!!.field("loading")
            fun held(page: Int) = surface().slots.any { it.key?.page == page && it.ready }
            suspend fun pad(vararg actions: PadAction) = withContext(Dispatchers.Main) { actions.forEach { assertTrue(screen!!.onPad(it)) } }
            until("page 1") { surface().front.key?.page == 0 && surface().front.ready }
            until("page 2 decoded behind it") { held(1) }
            shot(activity, "01-comic-page")

            // C3: the next page is already drawn, so the turn swaps at once: no loading, no black.
            withContext(Dispatchers.Main) {
                assertTrue(screen!!.onPad(PadAction.Primary))
                assertEquals(1, surface().front.key?.page)
                assertTrue("The next page shows at once", surface().front.ready && surface().front.view.isReady)
                assertTrue("Nothing covers it while it loads", loading().visibility != View.VISIBLE)
            }
            until("both neighbours decoded") { held(0) && held(2) }
            delay(600)
            val heapThree = heap()
            // And back again, as quickly: the page before stayed decoded.
            withContext(Dispatchers.Main) {
                assertTrue(screen!!.onPad(PadAction.Secondary))
                assertEquals(0, surface().front.key?.page)
                assertTrue(surface().front.ready && loading().visibility != View.VISIBLE)
            }
            // A jump keeps the page you were on until the new one is ready.
            withContext(Dispatchers.Main) {
                state().seek(4)
                screen!!.javaClass.getDeclaredMethod("loadPage").apply { isAccessible = true }.invoke(screen)
                assertEquals("The page you were on stays", 0, surface().front.key?.page)
                assertTrue(loading().visibility != View.VISIBLE)
            }
            until("the page jumped to") { surface().front.key?.page == 4 && surface().front.ready }
            report.append("native heap: ${heapBefore / 1024 / 1024} MB before the reader, ${heapThree / 1024 / 1024} MB with three ")
                .append("1988 x 3056 pages decoded (the page shown and both neighbours): ${(heapThree - heapBefore) / 1024 / 1024} MB for the reader\n")

            // X7: the bars float over the page, which keeps its size.
            pad(PadAction.Menu)
            until("the controls") { screen!!.field<Boolean>("controlsVisible") }
            delay(300)
            withContext(Dispatchers.Main) {
                val page = surface()
                assertEquals(1f, page.scaleX, 0f)
                assertEquals(1f, page.scaleY, 0f)
                assertEquals(0f, page.translationX, 0f)
                assertEquals(0f, page.translationY, 0f)
                val bars: ReaderBars = screen!!.field("bars")
                assertTrue(bars.top.isShown && bars.bottom.isShown)
                if (bars.glass) {
                    // Floating: in from the edges, over the page rather than beside it.
                    assertTrue(bars.topRow.left > 0 && bars.topRow.top > 0)
                    val thirds = all(root).filterIsInstance<TextView>().first { it.contentDescription == "Read each page in thirds" }
                    assertEquals("Thirds is lit while on", com.pocketds.hub.ui.glass.GlassColors.INK, thirds.currentTextColor)
                }
            }
            shot(activity, "02-comic-bars-over-page")

            // X3: Comfort, the same sheet in every reader.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Comfort" && it.isShown }.performClick() }
            until("the Comfort sheet") { all(root).any { it is TextView && it.isShown && it.text.startsWith("Brightness") } }
            // The sheet starts on the brightness slider: left and right change it at once, held or pressed.
            until("the brightness slider in focus") { root.findFocus().let { it is SeekBar && it.contentDescription == "Brightness" } }
            repeat(6) { pad(PadAction.Step(Direction.LEFT)) }
            // Down to warmth, and warmer.
            pad(PadAction.Step(Direction.DOWN))
            withContext(Dispatchers.Main) {
                val focus = root.findFocus()
                assertTrue("Down reaches the warmth slider: $focus", focus is SeekBar && focus.contentDescription == "Warmth")
            }
            repeat(5) { pad(PadAction.Step(Direction.RIGHT)) }
            withContext(Dispatchers.Main) {
                val kept = ComfortSettings.load(activity)
                assertEquals(0.7f, kept.brightness, 0.01f)
                assertEquals(0.5f, kept.warmth, 0.01f)
                val layer: ComfortLayerView = screen!!.field("comfortLayer")
                assertEquals(View.VISIBLE, layer.visibility)
                assertEquals(kept, layer.comfort)
            }
            shot(activity, "03-comic-comfort-sheet")
            pad(PadAction.Back)
            pad(PadAction.Back)
            until("the controls to close") { !screen!!.field<Boolean>("controlsVisible") }
            shot(activity, "04-comic-comfort-applied")
            File(activity.getExternalFilesDir(null), "reader-glass-memory.txt").writeText(report.toString())
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-glass-comic-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            ComfortSettings.save(activity, oldComfort)
            DomainPreferences.setComicDefaultFit(activity, oldFit)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    /** Classic keeps flat bars edge to edge; its 44dp controls still fit them whole. */
    @Test fun classicBarsStillFitTheirControls(): Unit = runBlocking {
        val context = ins.targetContext
        val oldLook = com.pocketds.hub.settings.LookSettings.get(context)
        com.pocketds.hub.settings.LookSettings.set(context, com.pocketds.hub.settings.Look.CLASSIC)
        val activity = ins.startActivitySync(Intent(context, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "classic-comics-${System.nanoTime()}"
        val bytes = page(1000, 1540, "classic")
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path.contains("/pages/")) return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
                    if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                    val manifest = JSONObject().put("workId", work).put("source", "kavita").put("sourceItemId", "issue-1").put("kind", "comic")
                        .put("title", "Chapter 1").put("seriesTitle", "Classic Comics").put("number", "1").put("pageCount", 2)
                        .put("currentPage", 0).put("direction", "ltr")
                        .put("pages", org.json.JSONArray((0 until 2).map { JSONObject().put("index", it).put("width", 1000).put("height", 1540) }))
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(manifest.toString())
                }
            }
            start()
        }
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: PagedImageReaderScreen? = null
        lateinit var root: View
        try {
            withContext(Dispatchers.Main) {
                screen = PagedImageReaderScreen(HubClient(activity), work, "issue-1", "Classic Comics", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the page") { screen!!.field<PageSurface>("surface").front.ready }
            withContext(Dispatchers.Main) { assertTrue(screen!!.onPad(PadAction.Menu)) }
            until("the controls") { screen!!.field<Boolean>("controlsVisible") }
            delay(300)
            withContext(Dispatchers.Main) {
                val bars: ReaderBars = screen!!.field("bars")
                assertFalse(bars.glass)
                val size = (44 * activity.resources.displayMetrics.density).toInt()
                listOf(bars.topRow, (bars.bottomRow.getChildAt(0) as ViewGroup)).forEach { row ->
                    (0 until row.childCount).map(row::getChildAt).filter { it.isShown && it.isFocusable }.forEach { control ->
                        assertTrue("${control.contentDescription} keeps its height: ${control.height} of $size", control.height >= size - 1)
                        assertTrue("${control.contentDescription} sits inside its bar", control.top >= 0 && control.bottom <= row.height)
                    }
                }
            }
            shot(activity, "09-classic-bars")
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            HubSettings.save(activity, oldUrl, oldToken)
            com.pocketds.hub.settings.LookSettings.set(context, oldLook)
            server.shutdown()
        }
    }

    @Test fun bookPageMakesRoomAndComfortBlacksItOut(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val archive = epub(aligned = false)
        val server = epubServer(archive)
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "glass-${System.nanoTime()}", "edition", "The Last Observatory", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { reader() != null && screen!!.field<View>("loading").visibility != View.VISIBLE }
            delay(1200)
            // B: the menu, and the page makes room for it (the owner's choice for books).
            withContext(Dispatchers.Main) { assertTrue(screen!!.onPad(PadAction.Back)) }
            until("the menu") { screen!!.field<Boolean>("controlsVisible") }
            delay(400)
            withContext(Dispatchers.Main) {
                val page: View = screen!!.field("navigatorContainer")
                assertTrue("The page shrinks with the menu round it: ${page.scaleX}", page.scaleX < 0.95f)
                val bars: ReaderBars = screen!!.field("bars")
                assertTrue(bars.top.isShown && bars.bottomRow.isShown)
            }
            shot(activity, "05-book-page-makes-room")
            // Comfort's black page.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Comfort" && it.isShown }.performClick() }
            until("the Comfort sheet") { all(root).any { it is TextView && it.isShown && it.text == "Black page" } }
            withContext(Dispatchers.Main) {
                var row: View? = all(root).first { it is TextView && it.isShown && it.text == "Black page" }
                while (row != null && !row.isClickable) row = row.parent as? View
                row!!.performClick()
            }
            until("the black page") { reader()?.settings?.value?.backgroundColor?.int == com.pocketds.hub.ui.ScreenComfort.BLACK_PAGE }
            withContext(Dispatchers.Main) { assertTrue(ComfortSettings.load(activity).blackPage) }
            delay(600)
            shot(activity, "06-book-comfort-black-page")
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Back) }
            delay(600)
            shot(activity, "07-book-black-page-menu")
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-glass-book-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    @Test fun readAlongDockIsGlassAndTheSentenceGlows(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val archive = epub(aligned = true)
        val server = epubServer(archive)
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "glass-ra-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the narration dock") { screen!!.field<ReadAlongDock>("narrationDock").isShown }
            delay(800)
            withContext(Dispatchers.Main) {
                val bars: ReaderBars = screen!!.field("bars")
                assertEquals("The dock takes the lower bar's place", View.GONE, bars.bottomRow.visibility)
                assertTrue(bars.keys.isShown)
            }
            // Play: the sentence being read glows in the accent, and the screen stays on.
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Play narration" && it.isShown }.performClick() }
            val accent = withContext(Dispatchers.Main) { Theme.colors(activity).accent }
            val rgb = "${(accent shr 16) and 0xFF}, ${(accent shr 8) and 0xFF}, ${accent and 0xFF}"
            var glow = ""
            try {
                withTimeout(15_000) {
                    while (!glow.contains(rgb)) {
                        delay(150)
                        glow = withContext(Dispatchers.Main) {
                            reader()?.evaluateJavascript("(function(){var e=document.querySelector('.pocket-narration');return e?getComputedStyle(e).boxShadow:'';})()")
                        }.orEmpty()
                    }
                }
            } catch (e: Exception) { throw AssertionError("The sentence never glowed in $rgb: $glow", e) }
            withContext(Dispatchers.Main) { assertTrue("The screen stays on while narrating", root.keepScreenOn) }
            delay(300)
            shot(activity, "08-read-along-glow")
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Pause narration" && it.isShown }.performClick() }
            until("the screen to be let sleep") { !root.keepScreenOn }
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-glass-read-along-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    private fun epubServer(archive: ByteArray) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path.endsWith("/file")) return MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(archive))
                if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"locator\":null}")
            }
        }
        start()
    }

    /** A short generated book; [aligned] adds a media overlay over eight seconds of generated silence. */
    private fun epub(aligned: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val sentences = (0 until 8).map { "The pines marked the quiet path, and Mara followed the lantern toward the ridge, sentence ${it + 1}." }
        ZipOutputStream(output).use { zip ->
            val text = sentences.chunked(2).joinToString("") { pair ->
                "<p>" + pair.joinToString(" ") { s -> "<span id=\"s${sentences.indexOf(s)}\">$s</span>" } + "</p>"
            } + (1..30).joinToString("") { "<p>The observatory kept its light on through the night. This is passage $it.</p>" }
            val overlay = if (aligned) " media-overlay=\"mo1\"" else ""
            val extra = if (aligned) """<item id="mo1" href="one.smil" media-type="application/smil+xml"/><item id="voice" href="voice.wav" media-type="audio/wav"/>""" else ""
            val files = mutableMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
                "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-glass</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-05T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"$overlay/>$extra<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
                "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title></head><body><h1>A light beyond the ridge</h1>$text</body></html>""".toByteArray(),
                "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li></ol></nav></body></html>""".toByteArray()
            )
            if (aligned) {
                val pars = sentences.indices.joinToString("") { "<par id=\"p$it\"><text src=\"one.xhtml#s$it\"/><audio src=\"voice.wav\" clipBegin=\"${it}s\" clipEnd=\"${it + 1}s\"/></par>" }
                files["EPUB/one.smil"] = """<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq epub:textref="one.xhtml">$pars</seq></body></smil>""".toByteArray()
                files["EPUB/voice.wav"] = silence(seconds = sentences.size)
            }
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    /** Generated silence, never a real recording. */
    private fun silence(seconds: Int): ByteArray {
        val bytes = 8000 * 2 * seconds
        return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes)
        }.array()
    }
}
