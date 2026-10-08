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
import com.pocketds.hub.reader.EpubPagePalette
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.EpubTheme
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
 * The sentence being read, behind its words, in one even tint, and nowhere else (#52), checked in the pixels of
 * the page itself. The owner's Pocket showed three faults of the first even-tint version: the words of the
 * sentence read lighter than their neighbours (the tint was over them), the box over a sentence's last line
 * tinted the words above it and the box over its first line the words below, and it was faint.
 *
 * A long second sentence, so there are other sentences before it and after it, on its first line and its last
 * and above and below. The page is looked at twice: as the reader sees it (the words' ink), and with the words made
 * invisible (what is tinted). For each of Paper, Sepia, Dim and Dark, at 130% and 200%, with the lines 1.3 and 1.8
 * apart, in one column and in two:
 *
 *  - the tint inside the sentence's lines and the gaps between them is one colour;
 *  - it is not the page's colour, and at least as far from it as the first version's 30% overlay was;
 *  - the ink of the sentence's words is the ink of the page's other words, and the theme's own;
 *  - no pixel inside any other word's own rectangle is tinted: the words above the sentence, below it, and
 *    on its first and last lines at either side.
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
        /** How far the tint varies inside the sentence (its lines and the seams between them), in levels of a channel. */
        val tintSpread: Int,
        /** How far the tint is from the page, in levels summed over the channels; and the first version's overlay. */
        val strength: Int, val before: Int,
        /** The words of other sentences close above or below the sentence, which the bleed was looked for in. */
        val neighbours: Int,
        /** The most any pixel inside another word's rectangle is off the page's colour. */
        val bleed: Int,
        /** The ink of the sentence's words against the page's other words, and the other words against the theme's ink. */
        val inkShift: Int, val inkOffTheme: Int
    )

    /** One word of the page: its rectangle in CSS pixels, and whether it belongs to the sentence. */
    private class Word(val left: Double, val top: Double, val width: Double, val height: Double, val inside: Boolean)

    private suspend fun measure(theme: EpubTheme, scale: Float, spacing: Float, columns: EpubColumns, chars: Int, name: String): Reading {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        val original = EpubAppearanceStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val two = columns == EpubColumns.TWO
        val server = ReaderFixtures.fileServer(ReaderFixtures.longEpub(sentences = 20, twoColumns = two, longSentenceChars = chars, longSentenceIndex = LONG))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences(theme = theme, columns = columns, fontScale = scale, lineHeight = spacing))
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
            lateinit var fragment: String
            withContext(Dispatchers.Main) {
                val audio: ReadAlongPlayback = screen!!.field("narration")
                val segment = audio.timeline.tracks[0].segments[LONG]
                fragment = segment.fragment
                val method = screen!!.javaClass.getDeclaredMethod("highlightNarration", ReadAlongSegment::class.java).apply { isAccessible = true }
                method.invoke(screen, segment)
            }
            delay(1_400)
            val navigator: org.readium.r2.navigator.epub.EpubNavigatorFragment = screen!!.field("navigator")
            // The boxes of the sentence and the gaps filled between them, and every word of the page, read off it.
            val raw = withContext(Dispatchers.Main) {
                navigator.evaluateJavascript("""(function(){
                    function rects(selector){return Array.from(document.querySelectorAll(selector)).map(function(b){var r=b.getBoundingClientRect();return [r.left,r.top,r.width,r.height];});}
                    var words=[];
                    var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
                    var node;
                    while(node=walker.nextNode()){
                        var inside=!!(node.parentElement&&node.parentElement.closest('#$fragment'));
                        var re=/\S+/g,m;
                        while(m=re.exec(node.nodeValue)){
                            var range=document.createRange();range.setStart(node,m.index);range.setEnd(node,m.index+m[0].length);
                            var found=range.getClientRects();
                            if(found.length!==1)continue;
                            var b=found[0];words.push([b.left,b.top,b.width,b.height,inside?1:0]);
                        }
                    }
                    var sentence=document.getElementById('$fragment');
                    var line=sentence?parseFloat(getComputedStyle(sentence).lineHeight):0;
                    return JSON.stringify({dpr:window.devicePixelRatio,vw:window.innerWidth,vh:window.innerHeight,line:line,boxes:rects('.pocket-narration'),words:words});})()""")
            }
            val json = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
            val dpr = json.getDouble("dpr")
            val vw = json.getDouble("vw")
            val vh = json.getDouble("vh")
            val line = json.getDouble("line")
            fun list(key: String) = (0 until json.getJSONArray(key).length()).map { i ->
                val b = json.getJSONArray(key).getJSONArray(i)
                (0 until b.length()).map { b.getDouble(it) }
            }
            // Only what is on this page: a sentence that goes on to the next has boxes beyond the edge.
            val boxes = list("boxes").filter { it[0] >= 0 && it[0] + it[2] <= vw + 1 }
            val words = list("words").map { Word(it[0], it[1], it[2], it[3], it[4] > 0.5) }
                .filter { it.left >= 0 && it.left + it.width <= vw && it.top >= 0 && it.top + it.height <= vh }
            val web = withContext(Dispatchers.Main) {
                all(root).filterIsInstance<android.webkit.WebView>().first { it.isShown && it.width > 0 }.let { view -> IntArray(2).also(view::getLocationOnScreen) }
            }
            fun save(shot: Bitmap, suffix: String) =
                File(activity.getExternalFilesDir(null), "highlight-$name$suffix.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // As a reader sees it: the ink.
            val seen = ins.uiAutomation.takeScreenshot()
            save(seen, "-words")
            // With the words made invisible: only what is tinted.
            withContext(Dispatchers.Main) {
                navigator.evaluateJavascript("""(function(){
                    Array.from(document.querySelectorAll('body, body *')).forEach(function(e){
                        e.style.setProperty('color','transparent','important');
                        e.style.setProperty('-webkit-text-fill-color','transparent','important');
                        e.style.setProperty('text-shadow','none','important');});})()""")
            }
            delay(500)
            val tinted = ins.uiAutomation.takeScreenshot()
            save(tinted, "")
            fun pixel(shot: Bitmap, cssX: Double, cssY: Double): Int =
                shot.getPixel((web[0] + cssX * dpr).toInt().coerceIn(0, shot.width - 1), (web[1] + cssY * dpr).toInt().coerceIn(0, shot.height - 1))
            fun distance(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
            val step = 1.0 / dpr

            // The page's own colour: the margin, where there is nothing.
            val page = pixel(tinted, 1.0, vh / 2)

            // One tint: every pixel inside a line's box, and the seams where two of the sentence's lines meet.
            val samples = mutableListOf<Int>()
            fun sweep(rect: List<Double>, inset: Double) {
                var y = rect[1] + inset
                while (y <= rect[1] + rect[3] - inset) {
                    var x = rect[0] + inset
                    while (x <= rect[0] + rect[2] - inset) { samples += pixel(tinted, x, y); x += 5.0 }
                    y += step * 2
                }
            }
            boxes.forEach { sweep(it, 3.0) }
            // Where two of the sentence's lines meet, which is where two boxes' edges could add up or leave a hairline.
            val byTop = boxes.sortedBy { it[1] }
            byTop.forEachIndexed { index, upper ->
                byTop.drop(index + 1).forEach { lower ->
                    val gap = lower[1] - (upper[1] + upper[3])
                    val left = maxOf(upper[0], lower[0]) + 4.0
                    val right = minOf(upper[0] + upper[2], lower[0] + lower[2]) - 4.0
                    if (gap in -4.0..3.0 && right > left) {
                        var y = lower[1] - 2.0
                        while (y <= upper[1] + upper[3] + 2.0) {
                            var x = left
                            while (x <= right) { samples += pixel(tinted, x, y); x += 5.0 }
                            y += step
                        }
                    }
                }
            }
            val spread = (0..2).maxOf { channel ->
                val values = samples.map { listOf(Color.red(it), Color.green(it), Color.blue(it))[channel] }
                (values.maxOrNull() ?: 0) - (values.minOrNull() ?: 0)
            }
            val tint = if (samples.isEmpty()) page else pixel(tinted, boxes.first()[0] + boxes.first()[2] / 2, boxes.first()[1] + boxes.first()[3] / 2)
            val accent = Theme.colors(activity).accent
            // The first even-tint version: the accent at 30% over the page.
            val old = Color.rgb(
                (Color.red(page) * 0.7 + Color.red(accent) * 0.3).toInt(),
                (Color.green(page) * 0.7 + Color.green(accent) * 0.3).toInt(),
                (Color.blue(page) * 0.7 + Color.blue(accent) * 0.3).toInt()
            )

            // Nothing outside the sentence: the words next to it, inside their own rectangles.
            val height = boxes.maxOfOrNull { it[3] } ?: 20.0
            val outside = words.filter { !it.inside && it.width >= 12 }
            val neighbours = outside.count { word ->
                boxes.any { box ->
                    val above = box[1] - (word.top + word.height)
                    val below = word.top - (box[1] + box[3])
                    (above in -height..height * 1.6 || below in -height..height * 1.6) &&
                        word.left < box[0] + box[2] && word.left + word.width > box[0]
                }
            }
            // A word's own line: the line-height round it, which is the lines' share of the page (the words' own rectangles
            // are the font's, taller than the line at 1.3, and overlap their neighbours' by a pixel or two of nothing).
            var bleed = 0
            outside.forEach { word ->
                val half = (if (line > 0) line else word.height) / 2
                val middle = word.top + word.height / 2
                var y = middle - half + 1.5
                while (y <= middle + half - 1.5) {
                    var x = word.left + 1
                    while (x <= word.left + word.width - 1) { bleed = maxOf(bleed, distance(pixel(tinted, x, y), page)); x += 3.0 }
                    y += step * 2
                }
            }

            // Ink: the most inked pixel of each word, as the reader sees it; the middle of the sentence's words and of the others.
            fun inkOf(word: Word, background: Int): Int {
                var best = background
                var far = -1
                var y = word.top
                while (y <= word.top + word.height) {
                    var x = word.left
                    while (x <= word.left + word.width) {
                        val colour = pixel(seen, x, y)
                        val d = distance(colour, background)
                        if (d > far) { far = d; best = colour }
                        x += step
                    }
                    y += step
                }
                return best
            }
            fun middle(colours: List<Int>, channel: (Int) -> Int): Int = colours.map(channel).sorted().let { it[it.size / 2] }
            val insideInk = words.filter { it.inside && it.width >= 20 }.map { inkOf(it, tint) }
            val outsideInk = outside.filter { it.width >= 20 }.map { inkOf(it, page) }
            val theme2 = EpubPagePalette.of(theme).second
            fun shift(a: List<Int>, b: Int, channels: List<(Int) -> Int>) = channels.maxOf { c -> abs(middle(a, c) - c(b)) }
            val channels = listOf<(Int) -> Int>({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })
            val inkShift = if (insideInk.isEmpty() || outsideInk.isEmpty()) 255 else channels.maxOf { c -> abs(middle(insideInk, c) - middle(outsideInk, c)) }
            val inkOffTheme = if (outsideInk.isEmpty()) 255 else shift(outsideInk, theme2, channels)
            seen.recycle(); tinted.recycle()
            val column = boxes.groupBy { (it[0] / (vw / 2)).toInt() }
            return Reading(column.values.maxOfOrNull { it.size } ?: 0, column.size, spread, distance(tint, page), distance(old, page), neighbours, bleed, inkShift, inkOffTheme)
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    @Test fun aSentenceIsOneEvenTintBehindItsWordsAndNowhereElseInEveryThemeSizeSpacingAndColumn(): Unit = runBlocking {
        val log = StringBuilder()
        val failures = mutableListOf<String>()
        val themes = listOf("paper" to EpubTheme.LIGHT, "sepia" to EpubTheme.SEPIA, "dim" to EpubTheme.DARK, "dark" to EpubTheme.BLACK)
        for ((themeName, theme) in themes) for (columns in listOf(EpubColumns.ONE, EpubColumns.TWO)) for (scale in listOf(1.3f, 2.0f)) for (spacing in listOf(1.3f, 1.8f)) {
            val name = "$themeName-${if (columns == EpubColumns.TWO) "two" else "one"}-${(scale * 100).toInt()}-${(spacing * 10).toInt()}"
            // Long enough to run over three lines or more where the page is narrow or the type large, not so long that the page ends first.
            val chars = when {
                columns == EpubColumns.ONE && scale < 1.5f -> 300
                columns == EpubColumns.ONE -> 150
                scale < 1.5f -> 220
                spacing > 1.5f -> 80
                else -> 130
            }
            val reading = measure(theme, scale, spacing, columns, chars, name)
            log.append("$name: $reading\n")
            android.util.Log.i("HLTEST", "$name: $reading")
            if (reading.lines < 3) failures += "$name: only ${reading.lines} lines, a three-line sentence is wanted"
            if (reading.neighbours < 3) failures += "$name: only ${reading.neighbours} words of other sentences close by to look for a bleed in"
            if (reading.tintSpread > TINT_LEVELS) failures += "$name: the tint varies by ${reading.tintSpread} levels inside the sentence"
            if (reading.strength < MIN_STRENGTH) failures += "$name: the tint is only ${reading.strength} from the page"
            if (reading.strength * 100 < reading.before * 95) failures += "$name: the tint (${reading.strength} from the page) is fainter than the 30% overlay was (${reading.before})"
            if (reading.bleed > BLEED_LEVELS) failures += "$name: another sentence's words are tinted by ${reading.bleed} levels"
            if (reading.inkShift > INK_LEVELS) failures += "$name: the sentence's ink is ${reading.inkShift} levels off the other words'"
            if (reading.inkOffTheme > THEME_INK_LEVELS) failures += "$name: the page's ink is ${reading.inkOffTheme} levels off the theme's"
        }
        assertTrue(failures.joinToString("\n") + "\n" + log, failures.isEmpty())
    }

    private companion object {
        /** The sentence being read is the second: sentences before it, and after. */
        const val LONG = 1
        /** One level in a channel is invisible; a band that can be seen is several. */
        const val TINT_LEVELS = 2
        /** Off the page's colour, summed over the channels: the least a wash may be to read at all. */
        const val MIN_STRENGTH = 60
        /** Another word's rectangle is the page's colour, give or take nothing a person could see. */
        const val BLEED_LEVELS = 4
        const val INK_LEVELS = 6
        /** Readium's text colour is the palette's own; a word's most inked pixel is a stem, not an edge. */
        const val THEME_INK_LEVELS = 12
    }
}
