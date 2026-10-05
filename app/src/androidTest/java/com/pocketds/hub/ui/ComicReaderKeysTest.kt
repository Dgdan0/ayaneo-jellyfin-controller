package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AudiobookScreen
import com.pocketds.hub.reader.ComicFit
import com.pocketds.hub.reader.ComicView
import com.pocketds.hub.reader.ComicZoom
import com.pocketds.hub.reader.EndOfIssueCard
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.reader.PagedImageState
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.HubSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
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

/**
 * The comic reader's keys and its way of reading (#16), against a local
 * hub: two generated issues, pages drawn in bands so a screenshot shows which
 * part is on screen. Nothing here reaches a real server or a real book.
 */
@RunWith(AndroidJUnit4::class)
class ComicReaderKeysTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun page(width: Int, height: Int, label: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bands = listOf(Color.rgb(170, 40, 50), Color.rgb(40, 110, 170), Color.rgb(60, 140, 70), Color.rgb(170, 130, 40))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = width / 9f; color = Color.WHITE; textAlign = Paint.Align.CENTER }
        val parts = 4
        for (i in 0 until parts) {
            paint.color = bands[i]
            canvas.drawRect(0f, height * i / parts.toFloat(), width.toFloat(), height * (i + 1) / parts.toFloat(), paint)
            paint.color = Color.WHITE
            canvas.drawText("$label · ${i + 1}/$parts", width / 2f, height * (i + 0.55f) / parts, paint)
        }
        // A column of marks down the left, so a pan across shows.
        paint.color = Color.BLACK
        for (y in 0 until height step 120) canvas.drawRect(0f, y.toFloat(), width * 0.06f, y + 60f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private data class Issue(val id: String, val number: String, val pages: List<Pair<Int, Int>>, val next: String = "", val previous: String = "")

    @Test fun keysThirdsZoomAndTheEndCardInTheRealReader(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val work = "fixture-comics-${System.nanoTime()}"
        val issues = listOf(
            Issue("issue-51", "51", listOf(1000 to 1540, 2000 to 1540, 1000 to 1540), next = "issue-52"),
            Issue("issue-52", "52", listOf(1000 to 1540, 1000 to 1540), previous = "issue-51"),
            Issue("issue-53", "53", listOf(1000 to 1540))
        ).associateBy { it.id }
        val images = ConcurrentHashMap<String, ByteArray>()
        val saved = ConcurrentHashMap<String, Int>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val issueId = path.substringAfter("/publications/", "").substringBefore('/')
                val issue = issues[issueId] ?: return MockResponse().setResponseCode(404)
                if (path.contains("/pages/")) {
                    // A page, or its thumbnail as the hub makes one (#18: the reader looks for its margins on it).
                    val thumb = path.endsWith("/thumb")
                    val index = path.removeSuffix("/thumb").substringAfterLast('/').toInt()
                    val (w, h) = issue.pages[index]
                    val full = images.getOrPut("$issueId/$index") { page(w, h, "#${issue.number} p${index + 1}") }
                    val bytes = if (!thumb) full else images.getOrPut("$issueId/$index/thumb") { ReaderFixtures.thumbnail(full, request.requestUrl?.queryParameter("w")?.toIntOrNull() ?: 160) }
                    return MockResponse().setHeader("Content-Type", if (thumb) "image/jpeg" else "image/png").setBody(Buffer().write(bytes))
                }
                if (request.method == "POST") {
                    saved[issueId] = JSONObject(request.body.readUtf8()).optInt("pageIndex")
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                }
                val pages = issue.pages.mapIndexed { i, (w, h) -> JSONObject().put("index", i).put("width", w).put("height", h).put("isWide", w > h) }
                val manifest = JSONObject().put("workId", work).put("source", "kavita").put("sourceItemId", issueId).put("kind", "comic")
                    .put("title", "Chapter ${issue.number}").put("seriesTitle", "Fixture Comics").put("number", issue.number)
                    .put("pageCount", issue.pages.size).put("currentPage", saved[issueId] ?: 0).put("direction", "ltr")
                    .put("pages", org.json.JSONArray(pages)).put("nextSourceItemId", issue.next).put("previousSourceItemId", issue.previous)
                return MockResponse().setHeader("Content-Type", "application/json").setBody(manifest.toString())
            }
        }
        server.start()
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var exits = 0
        val notices = mutableListOf<String>()
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) {
                "getViewContext" -> activity
                "back" -> { exits++; true }
                "notify" -> { notices += args!![0] as String; null }
                else -> null
            }
        } as ScreenHost
        var screen: PagedImageReaderScreen? = null
        lateinit var root: View
        suspend fun until(what: String, check: () -> Boolean) {
            try { withTimeout(20_000) { while (!withContext(Dispatchers.Main) { check() }) delay(100) } }
            catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
        }
        suspend fun shot(name: String) {
            ins.waitForIdleSync(); delay(450)
            File(activity.getExternalFilesDir(null), "comic-keys-$name.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        suspend fun pad(vararg actions: PadAction) = withContext(Dispatchers.Main) { actions.forEach { assertTrue(screen!!.onPad(it)) } }
        fun state(): PagedImageState = screen!!.field("state")
        // The page in front (#16, C3: the pages either side wait decoded behind it).
        fun surface(): com.pocketds.hub.reader.PageSurface = screen!!.field("surface")
        fun image(): SubsamplingScaleImageView = surface().front.view
        fun zoom(): ComicZoom = screen!!.field("zoom")
        suspend fun ready(page: Int) = until("page ${page + 1}") {
            screen!!.field<PagedImageState?>("state")?.pageIndex == page && surface().front.key?.page == page &&
                surface().front.ready && image().isReady
        }
        suspend fun open(issue: String) {
            withContext(Dispatchers.Main) {
                screen = PagedImageReaderScreen(HubClient(activity), work, issue, "Fixture Comics", { true })
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
        }
        try {
            // A new series opens as every series does: Thirds unless chosen otherwise.
            DomainPreferences.setComicDefaultFit(activity, ComicView.DEFAULT_FIT)
            open("issue-51")
            ready(0)
            // The steps come from the page's shape and the view's (three on the Pocket's full
            // screen; this fixture's window keeps the system bars, so a little shorter).
            val portrait = withContext(Dispatchers.Main) {
                assertEquals(ComicFit.THIRDS, screen!!.field<ComicView>("reading").fit)
                assertEquals(com.pocketds.hub.reader.ViewportStepPlanner.count(1000, 1540, image().width, image().height), state().viewportSteps)
                assertEquals(0, state().viewportIndex)
                state().viewportSteps
            }
            assertTrue(portrait >= 3)
            shot("01-open-part1")
            // A reads on through the thirds, B back.
            pad(PadAction.Activate)
            withContext(Dispatchers.Main) { assertEquals(1, state().viewportIndex) }
            shot("02-part2")
            repeat(portrait - 1) { pad(PadAction.Activate) }
            ready(1)
            // The spread takes two steps.
            withContext(Dispatchers.Main) { assertEquals(2, state().viewportSteps); assertEquals(0, state().viewportIndex) }
            shot("03-spread-part1")
            pad(PadAction.Back)
            ready(0)
            delay(300)
            withContext(Dispatchers.Main) { assertEquals(portrait - 1, state().viewportIndex) }
            shot("04-back-to-part3")
            // The controls with the keys' row, and the Controls sheet.
            pad(PadAction.Menu)
            until("the controls") { screen!!.field<Boolean>("controlsVisible") }
            shot("05-controls-with-keys")
            withContext(Dispatchers.Main) {
                val chips = all(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
                assertTrue("The keys' row says what A does: $chips", "Choose" in chips)
                assertTrue("Select leaves: $chips", "Leave" in chips)
            }
            pad(PadAction.Click(Stick.RIGHT))
            until("the Controls sheet") { all(root).any { it.isShown && it.contentDescription?.toString()?.startsWith("Ⓐ") == true } }
            shot("06-controls-sheet")
            pad(PadAction.Back)
            pad(PadAction.Back)
            until("the controls to close") { !screen!!.field<Boolean>("controlsVisible") }
            // A zoom stays from page to page, at the same place across, at the next page's top.
            pad(PadAction.Activate)
            ready(1)
            pad(PadAction.Section(1), PadAction.Section(1))
            until("the zoom") { zoom().active }
            val factor = withContext(Dispatchers.Main) { zoom().factor }
            delay(400)
            shot("07-zoomed")
            pad(PadAction.Primary)
            ready(2)
            delay(400)
            withContext(Dispatchers.Main) {
                assertTrue(zoom().active)
                assertEquals(factor, zoom().factor, 0.01f)
                val base = image().width.toFloat() / image().sWidth
                assertEquals(base * factor, image().scale, 0.02f)
                val top = image().center!!.y
                assertEquals("At the top of the next page", image().height / image().scale / 2f, top, 4f)
            }
            shot("08-next-page-same-zoom")
            // The right stick pans; L3 held is a magnifier.
            val before = withContext(Dispatchers.Main) { image().center!!.y }
            repeat(20) { pad(PadAction.Pan(0f, 0.02f)); delay(16) }
            delay(200)
            withContext(Dispatchers.Main) { assertTrue(image().center!!.y > before) }
            shot("09-panned")
            val scale = withContext(Dispatchers.Main) { image().scale }
            pad(PadAction.Click(Stick.LEFT))
            delay(400)
            withContext(Dispatchers.Main) { assertEquals(scale * 2f, image().scale, 0.05f) }
            shot("10-magnifier")
            pad(PadAction.Click(Stick.LEFT, down = false))
            delay(400)
            withContext(Dispatchers.Main) { assertEquals(scale, image().scale, 0.05f) }
            // Back to the fit: Display, Read in thirds.
            withContext(Dispatchers.Main) {
                screen!!.javaClass.getDeclaredMethod("setFit", ComicFit::class.java).apply { isAccessible = true }.invoke(screen, ComicFit.THIRDS)
                assertFalse(zoom().active)
            }
            // The last page read to its end: the card, naming the next issue.
            repeat(portrait) { pad(PadAction.Activate) }
            until("the end card") { screen!!.field<EndOfIssueCard>("endCard").isOpen }
            until("the next issue's name") { all(root).filterIsInstance<TextView>().any { it.isShown && it.text == "Next: #52" } }
            withContext(Dispatchers.Main) {
                assertTrue(all(root).filterIsInstance<TextView>().any { it.isShown && it.text == "End of Fixture Comics #51" })
            }
            shot("11-end-card")
            // B stays; A again shows the card, and A continues.
            pad(PadAction.Back)
            withContext(Dispatchers.Main) { assertFalse(screen!!.field<EndOfIssueCard>("endCard").isOpen); assertEquals(2, state().pageIndex) }
            pad(PadAction.Activate)
            until("the end card again") { screen!!.field<EndOfIssueCard>("endCard").isOpen }
            pad(PadAction.Activate)
            until("issue 52") { screen!!.field<String>("currentSourceItemId") == "issue-52" && surface().front.key?.publication == "issue-52" && surface().front.ready }
            shot("12-next-issue")
            // The third comes back: leave on page 1, part 2, and open the issue again.
            pad(PadAction.Activate)
            until("part 2") { state().viewportIndex == 1 }
            withContext(Dispatchers.Main) { screen!!.onHide(); screen!!.onDestroyView(); screen = null }
            open("issue-52")
            ready(0)
            withContext(Dispatchers.Main) { assertEquals(1, state().viewportIndex) }
            shot("13-third-restored")
            // Select leaves.
            pad(PadAction.Refresh)
            assertEquals(1, exits)
            // The series keeps its fit: chosen here, it opens that way next time.
            withContext(Dispatchers.Main) {
                screen!!.javaClass.getDeclaredMethod("setFit", ComicFit::class.java).apply { isAccessible = true }.invoke(screen, ComicFit.WIDTH)
                screen!!.onHide(); screen!!.onDestroyView(); screen = null
            }
            // Another issue of the series, never opened, so no saved place asks which to keep.
            open("issue-53")
            ready(0)
            withContext(Dispatchers.Main) { assertEquals(ComicFit.WIDTH, screen!!.field<ComicView>("reading").fit) }
            shot("14-series-keeps-width")
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "comic-keys-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    /** Every key stays in the audiobook: none reaches the app, whose shoulders switch tabs. */
    @Test fun theAudiobookKeepsEveryKey() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        val calls = mutableListOf<String>()
        val api = Proxy.newProxyInstance(com.pocketds.hub.net.HubApi::class.java.classLoader, arrayOf(com.pocketds.hub.net.HubApi::class.java)) { _, m, _ ->
            calls += m.name
            com.pocketds.hub.net.HubResult.Failed(com.pocketds.hub.net.FailureKind.NO_NETWORK)
        } as com.pocketds.hub.net.HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, m, _ ->
            calls += "host." + m.name
            when (m.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        val edition = com.pocketds.hub.model.ReadingEdition(sourceItemId = "fixture-audio", kind = "audiobook")
        var screen: Screen? = null
        try {
            ins.runOnMainSync {
                screen = AudiobookScreen(api, "fixture-work", edition, "Fixture Audiobook", { true }, listOf(edition), null, emptyList())
                activity.setContentView(screen!!.onCreateView(host, FrameLayout(activity)))
            }
            ins.runOnMainSync {
                listOf(PadAction.Section(-1), PadAction.Section(1), PadAction.Page(Direction.UP), PadAction.Page(Direction.DOWN),
                    PadAction.Primary, PadAction.Secondary, PadAction.Refresh, PadAction.Pan(0.1f, 0f),
                    PadAction.Click(Stick.LEFT), PadAction.Click(Stick.LEFT, down = false)).forEach { action ->
                    assertTrue("$action stays in the audiobook", screen!!.onPad(action))
                }
                assertFalse(calls.any { it == "host.switchSection" || it == "host.back" })
                // B leaves.
                assertTrue(screen!!.onPad(PadAction.Back))
                assertTrue("host.back" in calls)
            }
        } finally {
            ins.runOnMainSync { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
        }
    }
}
