package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubColumns
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadAlongSegment
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sentence being read, drawn as one even tint (#52), checked in the pixels of the page itself. A sentence over
 * several lines has a box for each line; where those overlap, or where Readium's boxes are a few pixels apart, the
 * wash and the glow must not add up to a darker band or leave a lighter one. The words are hidden for the
 * measurement, so what is measured is the highlight alone: a three-line sentence at 130% and 200%, with the lines
 * 1.3, 1.5 and 1.8 apart, in one column and in two.
 */
@RunWith(AndroidJUnit4::class)
class ReadAlongHighlightTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private suspend fun until(what: String, timeoutMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(60) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    /** What one configuration looked like. */
    data class Reading(
        val lines: Int, val columns: Int,
        /** How far the tint varies inside the sentence, in levels of a colour channel: 0 is one even tint. */
        val interior: Int,
        /** How far the glow beside the sentence varies down its length. */
        val glow: Int,
        /** How far the tint is from the page's own colour: the highlight is there. */
        val contrast: Int
    )

    /** Opens the book with the first sentence over [firstSentenceChars], highlights it and measures it. */
    private suspend fun measure(scale: Float, spacing: Float, columns: EpubColumns, chars: Int, name: String): Reading {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val two = columns == EpubColumns.TWO
        val server = ReaderFixtures.fileServer(ReaderFixtures.longEpub(sentences = 20, twoColumns = two, firstSentenceChars = chars))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences(columns = columns, fontScale = scale, lineHeight = spacing))
            ComfortSettings.save(activity, ScreenComfort())
            lateinit var root: View
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "hl-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the narration") { screen!!.field<Any?>("narration") != null && screen!!.field<Any?>("pageSpan") != null }
            withContext(Dispatchers.Main) { if (screen!!.field<Boolean>("controlsVisible")) screen!!.onPad(PadAction.Menu) }
            until("the menu to close") { !screen!!.field<Boolean>("controlsVisible") }
            delay(500)
            withContext(Dispatchers.Main) {
                val audio: ReadAlongPlayback = screen!!.field("narration")
                val method = screen!!.javaClass.getDeclaredMethod("highlightNarration", ReadAlongSegment::class.java).apply { isAccessible = true }
                method.invoke(screen, audio.timeline.tracks[0].segments[0])
            }
            delay(1_200)
            val navigator: org.readium.r2.navigator.epub.EpubNavigatorFragment = screen!!.field("navigator")
            // The page as a reader sees it, for looking at.
            ins.uiAutomation.takeScreenshot().let { words ->
                File(activity.getExternalFilesDir(null), "highlight-$name-words.png").outputStream().use { words.compress(Bitmap.CompressFormat.PNG, 100, it) }
                words.recycle()
            }
            // The words hidden, the boxes of the sentence read off the page.
            val raw = withContext(Dispatchers.Main) {
                navigator.evaluateJavascript("""(function(){
                    Array.from(document.querySelectorAll('body, body *')).forEach(function(e){
                        e.style.setProperty('color','transparent','important');
                        e.style.setProperty('-webkit-text-fill-color','transparent','important');
                        e.style.setProperty('text-shadow','none','important');});
                    var boxes=Array.from(document.querySelectorAll('.pocket-narration')).map(function(b){var r=b.getBoundingClientRect();return [r.left,r.top,r.width,r.height];});
                    return JSON.stringify({dpr:window.devicePixelRatio,vw:window.innerWidth,boxes:boxes});})()""")
            }
            delay(400)
            val json = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
            val dpr = json.getDouble("dpr")
            val vw = json.getDouble("vw")
            // Only the lines on this page: a sentence that goes on to the next has boxes beyond the edge.
            val boxes = (0 until json.getJSONArray("boxes").length()).map { i ->
                val b = json.getJSONArray("boxes").getJSONArray(i)
                doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
            }.filter { it[0] >= 0 && it[0] + it[2] <= vw + 1 }
            // Where the page's web view is on the screen.
            val web = withContext(Dispatchers.Main) {
                all(root).filterIsInstance<android.webkit.WebView>().first { it.isShown && it.width > 0 }.let { view ->
                    IntArray(2).also(view::getLocationOnScreen)
                }
            }
            val shot = ins.uiAutomation.takeScreenshot()
            File(activity.getExternalFilesDir(null), "highlight-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fun pixel(cssX: Double, cssY: Double): Int = shot.getPixel((web[0] + cssX * dpr).toInt().coerceIn(0, shot.width - 1), (web[1] + cssY * dpr).toInt().coerceIn(0, shot.height - 1))
            fun level(color: Int) = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
            fun spread(values: List<Int>) = (values.maxOrNull() ?: 0) - (values.minOrNull() ?: 0)

            // The columns the sentence is in: boxes that share a left edge. A sliver (the trailing space of a line) is not a line.
            val byColumn = boxes.filter { it[2] > 24 }.groupBy { (it[0] / (vw / 2)).toInt() }
            val inset = 8.0
            val step = 1.0 / dpr
            var interior = 0
            var glow = 0
            var lines = 0
            var contrast = 0
            byColumn.values.forEach { column ->
                lines = maxOf(lines, column.size)
                val sorted = column.sortedBy { it[1] }
                // Every pixel inside a line, and in the band where one line meets the next, whatever the boxes did there:
                // one tint, never a darker stripe where two boxes overlap or a lighter one where they are apart.
                val tint = mutableListOf<Int>()
                fun sweep(x0: Double, x1: Double, y0: Double, y1: Double) {
                    var y = y0
                    while (y <= y1) { var x = x0; while (x <= x1) { tint += level(pixel(x, y)); x += 6.0 }; y += step }
                }
                sorted.forEach { sweep(it[0] + inset, it[0] + it[2] - inset, it[1] + inset, it[1] + it[3] - inset) }
                sorted.zipWithNext().forEach { (upper, lower) ->
                    val from = minOf(upper[1] + upper[3], lower[1]) - 6.0
                    val to = maxOf(upper[1] + upper[3], lower[1]) + 6.0
                    sweep(maxOf(upper[0], lower[0]) + inset, minOf(upper[0] + upper[2], lower[0] + lower[2]) - inset, from, to)
                }
                interior = maxOf(interior, spread(tint))
                // The glow in the margin beside the lines that start furthest left (a paragraph's first line is indented,
                // and a sample beside it would fall inside the line below). The ends of the sentence are left out: the
                // glow fades there on purpose, and only what runs between two lines could add up.
                val leftmost = column.minOf { it[0] }
                val edgeLines = sorted.filter { it[0] - leftmost < 1.5 }
                val beside = mutableListOf<Int>()
                var y = edgeLines.first()[1] + 24.0
                while (y <= edgeLines.last().let { it[1] + it[3] } - 24.0) { beside += level(pixel(leftmost - 6.0, y)); y += step }
                if (beside.isNotEmpty()) glow = maxOf(glow, spread(beside))
                // How different from the page: the first line's own centre, against the page's colour in the margin, clear of the glow.
                val first = sorted.first()
                val centreY = first[1] + first[3] / 2
                val page = level(pixel(maxOf(2.0, leftmost - 34.0), centreY))
                contrast = maxOf(contrast, abs(level(pixel(first[0] + first[2] / 2, centreY)) - page))
            }
            shot.recycle()
            return Reading(lines, byColumn.size, interior, glow, contrast)
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    @Test fun aSentenceOverSeveralLinesIsOneEvenTintAtEveryTextSizeAndLineSpacing(): Unit = runBlocking {
        val log = StringBuilder()
        val failures = mutableListOf<String>()
        for (columns in listOf(EpubColumns.ONE, EpubColumns.TWO)) for (scale in listOf(1.3f, 2.0f)) for (spacing in listOf(1.3f, 1.5f, 1.8f)) {
            val name = "${if (columns == EpubColumns.TWO) "two" else "one"}-${(scale * 100).toInt()}-${(spacing * 10).toInt()}"
            // Long enough to run over three lines or more where the page is narrow or the type large, not so long that the page ends first.
            val chars = when {
                columns == EpubColumns.ONE && scale < 1.5f -> 300
                columns == EpubColumns.ONE -> 150
                scale < 1.5f -> 220
                else -> 130
            }
            val reading = measure(scale, spacing, columns, chars, name)
            log.append("$name: $reading\n")
            if (reading.lines < 3) failures += "$name: only ${reading.lines} lines, a three-line sentence is wanted"
            if (reading.contrast < 12) failures += "$name: the highlight is not there ($reading)"
            if (reading.interior > INTERIOR_LEVELS) failures += "$name: the tint varies by ${reading.interior} inside the sentence"
            if (reading.glow > GLOW_LEVELS) failures += "$name: the glow varies by ${reading.glow} down the sentence"
        }
        android.util.Log.i("HLTEST", log.toString())
        assertTrue(failures.joinToString("\n") + "\n" + log, failures.isEmpty())
    }

    private companion object {
        /** One level in a channel is invisible; a band that can be seen is several. */
        const val INTERIOR_LEVELS = 2
        /**
         * The glow fades with the distance from the box, so a pixel's worth of difference between two lines' left edges is
         * a few levels (2 to 6 across the twelve configurations). A visible band beside the junction of two lines is more.
         */
        const val GLOW_LEVELS = 8
    }
}
