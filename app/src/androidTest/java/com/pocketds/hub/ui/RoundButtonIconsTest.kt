package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.playback.PlayerChrome
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.reader.ReadAlongDock
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picture on a round button sits in the middle of its disc (#53). The book reader's bars drew theirs about
 * 17 px left of centre: the disc's face takes over a view's padding, and the bar had set its own before dressing
 * the disc. Measured in the pixels, so a face that moves the picture shows whatever the cause: the disc is found
 * from the view, its colour from the rim (where no picture reaches), and the picture is every pixel inside that
 * differs from it. The book reader's bars and read-along dock, the comic reader's bars and the player's controls.
 */
@RunWith(AndroidJUnit4::class)
class RoundButtonIconsTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(25_000) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private fun host(activity: ReaderFixtureActivity) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost

    /** Where one round button's picture is, from the middle of its disc, in dp. */
    data class Offset(val name: String, val dx: Float, val dy: Float)

    private fun description(view: View): String = view.contentDescription?.toString() ?: (view as? android.widget.TextView)?.text?.toString() ?: view.javaClass.simpleName

    /** Round controls on screen: focusable, about 44dp across and as high as they are wide. */
    private fun roundControls(root: View, density: Float): List<View> = all(root).filter {
        it.isShown && it.isFocusable && it.width >= 40 * density && it.width <= 64 * density && abs(it.width - it.height) <= 2 &&
            // The cast button draws nothing where there are no routes, as on this emulator.
            it !is androidx.mediarouter.app.MediaRouteButton
    }

    private fun offsets(shot: Bitmap, views: List<View>, density: Float): List<Offset> = views.map { view ->
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        val cx = at[0] + view.width / 2f
        val cy = at[1] + view.height / 2f
        // Inside the ring's room and the face's edge.
        val radius = min(view.width, view.height) / 2f - 6 * density
        fun pixel(x: Float, y: Float) = shot.getPixel(x.toInt().coerceIn(0, shot.width - 1), y.toInt().coerceIn(0, shot.height - 1))
        // The face, from a rim no picture reaches (a 20dp symbol's corner is 14dp out).
        var red = 0; var green = 0; var blue = 0; var count = 0
        for (degrees in 0 until 360 step 10) {
            val angle = Math.toRadians(degrees.toDouble())
            val colour = pixel(cx + ((radius - density) * cos(angle)).toFloat(), cy + ((radius - density) * sin(angle)).toFloat())
            red += Color.red(colour); green += Color.green(colour); blue += Color.blue(colour); count++
        }
        val face = Color.rgb(red / count, green / count, blue / count)
        var left = Int.MAX_VALUE; var right = Int.MIN_VALUE; var top = Int.MAX_VALUE; var bottom = Int.MIN_VALUE
        val limit = radius * radius
        for (y in (cy - radius).toInt()..(cy + radius).toInt()) for (x in (cx - radius).toInt()..(cx + radius).toInt()) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > limit) continue
            val colour = pixel(x.toFloat(), y.toFloat())
            if (abs(Color.red(colour) - Color.red(face)) + abs(Color.green(colour) - Color.green(face)) + abs(Color.blue(colour) - Color.blue(face)) > 150) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        if (left > right) Offset(description(view), Float.NaN, Float.NaN)
        else Offset(description(view), ((left + right) / 2f - cx) / density, ((top + bottom) / 2f - cy) / density)
    }

    /** Takes the picture, saves it, and says which buttons draw their picture off the middle of the disc. */
    private fun problems(activity: ReaderFixtureActivity, name: String, views: List<View>, wanted: List<String>): List<String> {
        ins.waitForIdleSync()
        Thread.sleep(500)
        val density = activity.resources.displayMetrics.density
        val shot = ins.uiAutomation.takeScreenshot()
        File(activity.getExternalFilesDir(null), "round-icons-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val found = offsets(shot, views, density)
        shot.recycle()
        val report = found.joinToString("; ") { "${it.name} (${it.dx}, ${it.dy})" }
        android.util.Log.i("ROUNDICONS", "$name: $report")
        val missing = wanted.filter { want -> found.none { it.name == want } }.map { want -> "$name: no round button named $want among: $report" }
        val off = found.filter { it.dx.isNaN() || it.dy.isNaN() || abs(it.dx) > TOLERANCE_DP || abs(it.dy) > TOLERANCE_DP }
            .map { "$name: ${it.name} is drawn (${it.dx}, ${it.dy}) dp from the middle of its disc" }
        return missing + off
    }

    private companion object {
        /**
         * The symbols are drawn to a 24-unit grid with a little optical offset (a skip's bar, a play triangle): up to
         * two dp measured on controls that were right. The book reader's were 8dp out.
         */
        const val TOLERANCE_DP = 3f
    }

    private suspend fun bookProblems(readAlong: Boolean, name: String, wanted: List<String>): List<String> {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(if (readAlong) ReaderFixtures.longEpub(sentences = 20) else ReaderFixtures.epub(aligned = false))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, ScreenComfort())
            lateinit var root: View
            withContext(Dispatchers.Main) {
                screen = if (readAlong) EpubReaderScreen(HubClient(activity), "icons-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                else EpubReaderScreen(HubClient(activity), "icons-${System.nanoTime()}", "edition", "The Last Observatory", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            if (readAlong) until("the narration dock") { screen!!.field<ReadAlongDock>("narrationDock").isShown }
            else until("the book") { screen!!.field<View>("loading").visibility != View.VISIBLE }
            delay(1_200)
            withContext(Dispatchers.Main) { if (!screen!!.field<Boolean>("controlsVisible")) screen!!.onPad(PadAction.Menu) }
            until("the menu") { screen!!.field<Boolean>("controlsVisible") }
            delay(600)
            val views = withContext(Dispatchers.Main) { roundControls(root, activity.resources.displayMetrics.density) }
            return withContext(Dispatchers.Default) { problems(activity, name, views, wanted) }
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    /** The reader's top bar, as the issue lists it, and read along's dock under it. */
    @Test fun theBookReadersTopBarAndReadAlongDockCentreTheirIcons(): Unit = runBlocking {
        val problems = bookProblems(readAlong = true, name = "book-top-bar", wanted = listOf(
            "Close reader", "Table of contents", "Search this book", "Reading mode: Read along. Choose another", "Add bookmark", "Reading appearance", "Comfort", "Keys"
        ))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** The lower bar's page turns, on a book without narration (the dock takes the bar's place with it). */
    @Test fun theBookReadersPageTurnsCentreTheirIcons(): Unit = runBlocking {
        val problems = bookProblems(readAlong = false, name = "book-bottom-bar", wanted = listOf("Previous page", "Next page"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun theComicReadersBarsCentreTheirIcons(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val oldComfort = ComfortSettings.load(activity)
        val work = "icons-comics-${System.nanoTime()}"
        val pages = 3
        val images = ConcurrentHashMap<Int, ByteArray>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path.contains("/pages/")) {
                    val thumb = path.endsWith("/thumb")
                    val index = path.removeSuffix("/thumb").substringAfterLast('/').toInt()
                    val full = images.getOrPut(index) { ReaderFixtures.page(1988, 3056, "p${index + 1}") }
                    val bytes = if (thumb) ReaderFixtures.thumbnail(full, request.requestUrl?.queryParameter("w")?.toIntOrNull() ?: 160) else full
                    return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
                }
                if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                val list = (0 until pages).map { JSONObject().put("index", it).put("width", 1988).put("height", 3056).put("isWide", false) }
                val manifest = JSONObject().put("workId", work).put("source", "kavita").put("sourceItemId", "issue-1").put("kind", "comic")
                    .put("title", "Chapter 1").put("seriesTitle", "Icon Comics").put("number", "1").put("pageCount", pages)
                    .put("currentPage", 0).put("direction", "ltr").put("pages", org.json.JSONArray(list))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(manifest.toString())
            }
        }
        server.start()
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: PagedImageReaderScreen? = null
        try {
            ComfortSettings.save(activity, ScreenComfort())
            lateinit var root: View
            withContext(Dispatchers.Main) {
                screen = PagedImageReaderScreen(HubClient(activity), work, "issue-1", "Icon Comics", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            delay(2_000)
            withContext(Dispatchers.Main) { if (!screen!!.field<Boolean>("controlsVisible")) screen!!.onPad(PadAction.Menu) }
            until("the controls") { screen!!.field<Boolean>("controlsVisible") }
            delay(600)
            val views = withContext(Dispatchers.Main) { roundControls(root, activity.resources.displayMetrics.density) }
            val problems = withContext(Dispatchers.Default) { problems(activity, "comic-bars", views, emptyList()) }
            assertTrue("no round buttons found in the comic reader", views.size >= 2)
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    /** The player's round controls: back, cast, lock, picture in picture, previous and next. */
    @Test fun thePlayersControlsCentreTheirIcons(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        try {
            val actions = Proxy.newProxyInstance(PlayerChrome.Actions::class.java.classLoader, arrayOf(PlayerChrome.Actions::class.java)) { _, _, _ -> null } as PlayerChrome.Actions
            val seek = object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
            lateinit var frame: FrameLayout
            withContext(Dispatchers.Main) {
                val chrome = PlayerChrome(activity, Theme.colors(activity), actions, 10, seek)
                frame = FrameLayout(activity).apply { setBackgroundColor(0xFF1A1D24.toInt()) }
                frame.addView(chrome.top, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
                frame.addView(chrome.center, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
                activity.setContentView(frame)
            }
            delay(1_000)
            val views = withContext(Dispatchers.Main) { roundControls(frame, activity.resources.displayMetrics.density) }
            assertTrue("no round buttons found in the player: ${views.size}", views.size >= 4)
            val problems = withContext(Dispatchers.Default) { problems(activity, "player", views, emptyList()) }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }
}
