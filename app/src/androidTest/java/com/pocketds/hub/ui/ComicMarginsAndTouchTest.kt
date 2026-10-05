package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.ComicFit
import com.pocketds.hub.reader.ComicView
import com.pocketds.hub.reader.PageBounds
import com.pocketds.hub.reader.PageContent
import com.pocketds.hub.reader.PageKey
import com.pocketds.hub.reader.PageSurface
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.reader.PagedImageState
import com.pocketds.hub.reader.ViewportStepPlanner
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A comic's margins trimmed and its page read by touch (#18, C5, C7), in the
 * real reader against a local hub: generated pages with a known paper border,
 * their thumbnails made as the hub makes them, the border found on the
 * thumbnail and left out of the fit and the steps, and what that gains
 * measured; then taps in the thirds, a tap in the middle and swipes, which do
 * nothing while the page is zoomed. Nothing reaches a real server or comic.
 */
@RunWith(AndroidJUnit4::class)
class ComicMarginsAndTouchTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    @Test fun marginsTrimmedOnTheThumbnailAndThePageReadByTouch(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val oldFit = DomainPreferences.comicDefaultFit(activity)
        val work = "fixture-margins-${System.nanoTime()}"
        // Three 1000 x 1540 pages: 5% of paper each side and 4% top and foot, the third full bleed.
        val borders = listOf(0.05f to 0.04f, 0.05f to 0.04f, 0f to 0f)
        val pages = borders.mapIndexed { i, (x, y) -> ReaderFixtures.borderedPage(1000, 1540, x, y, "p${i + 1}") }
        val thumbnails = ConcurrentHashMap<String, ByteArray>()
        val thumbAsks = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path.endsWith("/thumb")) {
                    thumbAsks.incrementAndGet()
                    val index = path.substringBeforeLast("/thumb").substringAfterLast('/').toInt()
                    val width = request.requestUrl?.queryParameter("w")?.toIntOrNull() ?: 160
                    val bytes = thumbnails.getOrPut("$index/$width") { ReaderFixtures.thumbnail(pages[index], width) }
                    return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
                }
                if (path.contains("/pages/")) {
                    val index = path.substringAfterLast('/').toInt()
                    return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(pages[index]))
                }
                if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                val manifest = JSONObject().put("workId", work).put("source", "kavita").put("sourceItemId", "issue-1").put("kind", "comic")
                    .put("title", "Chapter 1").put("seriesTitle", "Fixture Margins").put("number", "1")
                    .put("pageCount", pages.size).put("currentPage", 0).put("direction", "ltr")
                    .put("pages", JSONArray(pages.indices.map { JSONObject().put("index", it).put("width", 1000).put("height", 1540).put("isWide", false) }))
                    .put("nextSourceItemId", "").put("previousSourceItemId", "")
                return MockResponse().setHeader("Content-Type", "application/json").setBody(manifest.toString())
            }
        }
        server.start()
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        var screen: PagedImageReaderScreen? = null
        lateinit var root: View
        suspend fun until(what: String, check: () -> Boolean) {
            try { withTimeout(20_000) { while (!withContext(Dispatchers.Main) { check() }) delay(100) } }
            catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
        }
        suspend fun shot(name: String) {
            ins.waitForIdleSync(); delay(450)
            File(activity.getExternalFilesDir(null), "comic-margins-$name.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun state(): PagedImageState = screen!!.field("state")
        fun image(): SubsamplingScaleImageView = screen!!.field<PageSurface>("surface").front.view
        fun contents(): Map<PageKey, PageContent> = screen!!.field("contents")
        fun controls(): Boolean = screen!!.field("controlsVisible")
        suspend fun ready(page: Int) = until("page ${page + 1}") {
            screen!!.field<PagedImageState?>("state")?.pageIndex == page && screen!!.field<PageSurface>("surface").front.let { it.key?.page == page && it.ready } && image().isReady
        }
        suspend fun settled() { ins.waitForIdleSync(); delay(450) }
        suspend fun pad(action: PadAction) = withContext(Dispatchers.Main) { assertTrue(screen!!.onPad(action)) }
        /** A finger on the page at [x], [y] (fractions of the view), down and up, or dragged to [toX]. */
        suspend fun touch(x: Float, y: Float, toX: Float = x) {
            val (left, top, width, height) = withContext(Dispatchers.Main) {
                val at = IntArray(2).also(image()::getLocationOnScreen)
                listOf(at[0].toFloat(), at[1].toFloat(), image().width.toFloat(), image().height.toFloat())
            }
            val down = SystemClock.uptimeMillis()
            val steps = if (toX == x) 0 else 6
            fun send(action: Int, at: Long, fx: Float) {
                val event = MotionEvent.obtain(down, at, action, left + width * fx, top + height * y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                ins.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, down, x)
            for (i in 1..steps) send(MotionEvent.ACTION_MOVE, down + i * 12L, x + (toX - x) * i / steps)
            send(MotionEvent.ACTION_UP, down + (steps + 1) * 12L, toX)
            // A tap is only sure once a second tap has not come.
            delay(650)
        }
        try {
            DomainPreferences.setComicDefaultFit(activity, ComicView.DEFAULT_FIT)
            withContext(Dispatchers.Main) {
                screen = PagedImageReaderScreen(HubClient(activity), work, "issue-1", "Fixture Margins", { true })
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            ready(0)
            until("the first page's margins") { contents()[PageKey("issue-1", 0)]?.trimmed == true }
            settled()
            // C5: found on the 96-pixel thumbnail, within a pixel or two of the border drawn.
            val found = withContext(Dispatchers.Main) { contents()[PageKey("issue-1", 0)]!! }
            assertEquals(0.05, found.left, 2.0 / PageBounds.THUMB_WIDTH)
            assertEquals(0.95, found.right, 2.0 / PageBounds.THUMB_WIDTH)
            assertEquals(0.04, found.top, 2.0 / 148)
            assertEquals(0.96, found.bottom, 2.0 / 148)
            assertTrue("Only thumbnails were asked for the margins", thumbAsks.get() >= 1)
            // What it gains: the content's width fills the screen, so the page reads larger.
            val (trimmedScale, untrimmedScale, trimmedSteps) = withContext(Dispatchers.Main) {
                Triple(image().scale, image().width.toFloat() / image().sWidth, state().viewportSteps)
            }
            val gain = trimmedScale / untrimmedScale
            Log.i("ComicMargins", "fit-width gain with trimmed margins: ${"%.3f".format(gain)} (border drawn 5%; ideal ${"%.3f".format(1 / 0.9)}); steps $trimmedSteps")
            assertEquals(1.0 / found.width, gain.toDouble(), 0.01)
            assertTrue("It reads larger: $gain", gain > 1.05f)
            withContext(Dispatchers.Main) {
                assertEquals(ViewportStepPlanner.count((1000 * found.width).toInt(), (1540 * found.height).toInt(), image().width, image().height), trimmedSteps)
            }
            shot("01-trimmed")

            // Trim margins is the series' to turn off, in Display.
            pad(PadAction.Menu)
            until("the controls") { controls() }
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Reading options" }.performClick() }
            until("the Display sheet") { all(root).any { it.isShown && it.contentDescription?.toString()?.startsWith("Trim margins") == true } }
            shot("02-display-trim")
            withContext(Dispatchers.Main) { all(root).first { it.isShown && it.contentDescription?.toString()?.startsWith("Trim margins") == true }.performClick() }
            until("the page untrimmed") { abs(image().scale - image().width.toFloat() / image().sWidth) < 0.001f }
            assertFalse(DomainPreferences.comicView(activity, work).trim)
            withContext(Dispatchers.Main) { all(root).first { it.isShown && it.contentDescription?.toString()?.startsWith("Trim margins") == true }.performClick() }
            until("trimmed again") { abs(image().scale - trimmedScale) < 0.001f }
            // The sheet closes as Back closes it; then Start hides the controls.
            withContext(Dispatchers.Main) { assertTrue(screen!!.onSystemBack()) }
            pad(PadAction.Menu)
            until("the controls gone") { !controls() }
            settled()

            // C7: a tap in the right third reads on a step, the left third back.
            touch(0.88f, 0.5f)
            until("the second step") { state().viewportIndex == 1 }
            touch(0.12f, 0.5f)
            until("back to the first step") { state().viewportIndex == 0 }
            // The middle shows the controls; a tap on the page hides them again.
            touch(0.5f, 0.5f)
            until("the controls by touch") { controls() }
            shot("03-touch-controls")
            touch(0.5f, 0.5f)
            until("the controls hidden by touch") { !controls() }
            // A swipe across turns the page.
            touch(0.8f, 0.5f, toX = 0.2f)
            ready(1)
            // Zoomed in, a swipe pans and turns nothing.
            pad(PadAction.Section(1))
            settled()
            touch(0.8f, 0.5f, toX = 0.2f)
            delay(400)
            withContext(Dispatchers.Main) { assertEquals("A zoomed page does not turn", 1, state().pageIndex) }
            shot("04-zoomed-swipe")
            // The full-bleed page is left whole.
            pad(PadAction.Section(-1))
            settled()
            pad(PadAction.Primary)
            ready(2)
            until("the third page looked at") { contents().containsKey(PageKey("issue-1", 2)) }
            withContext(Dispatchers.Main) { assertFalse(contents()[PageKey("issue-1", 2)]!!.trimmed) }
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "comic-margins-failure.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            DomainPreferences.setComicDefaultFit(activity, oldFit)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }
}
