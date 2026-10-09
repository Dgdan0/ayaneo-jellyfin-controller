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
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.DocumentPath
import com.pocketds.hub.reader.EpubAppearancePanel
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubColumns
import com.pocketds.hub.reader.EpubPagePalette
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.EpubTheme
import com.pocketds.hub.reader.HighlightColor
import com.pocketds.hub.reader.HighlightLook
import com.pocketds.hub.reader.PageSpan
import com.pocketds.hub.reader.ReadAlongGlow
import com.pocketds.hub.reader.ReadAlongHighlightStore
import com.pocketds.hub.reader.ReadAlongHighlights
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadAlongPosition
import com.pocketds.hub.reader.ReadAlongSegment
import com.pocketds.hub.reader.ReadAlongWordHighlight
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Read along by the word, style A (#66), in the pixels of a page and in the reader's behaviour, on generated word editions
 * (each word a span, a `<par>` per word in a `<seq>` naming its sentence, as a wordsync pack makes them) and silence on a
 * local hub. Nothing here opens a real book or reaches a real server.
 *
 *  - the word strong, the trail light from the sentence's first letter to the word, no trail at 0%, line boxes across a
 *    line break, nothing between sentences, in Paper, Sepia, Dim and Dark;
 *  - a sentence edition (a book with no pack) washes its sentence in the chosen colour;
 *  - the setting: tabs, swatches, the trail, the default, kept on the device;
 *  - follow and turn, page and voice both ways, a jump to a place on another page, Play from the nearest narration;
 *  - a saved place is a sentence;
 *  - a minute of word changes, its frames timed against a minute of sentences.
 */
@RunWith(AndroidJUnit4::class)
class ReadAlongWordTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()

    private suspend fun until(what: String, timeoutMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(50) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /** A local hub serving one edition for every `/file` route and keeping what the reader posts as its place. */
    private class Hub(archive: ByteArray) {
        val posted = CopyOnWriteArrayList<JSONObject>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path.endsWith("/file")) return MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(archive))
                    if (request.method == "POST") {
                        runCatching { JSONObject(request.body.readUtf8()) }.getOrNull()?.let { if (path.endsWith("/position")) posted += it }
                        return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true,\"timestamp\":1}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"locator\":null}")
                }
            }
            start()
        }
    }

    private inner class Open(val activity: ReaderFixtureActivity, val screen: EpubReaderScreen, val root: View, val hub: Hub, val notes: MutableList<String>) {
        fun voice(): ReadAlongPlayback = screen.field("narration")
        fun span(): PageSpan? = screen.field("pageSpan")
        fun key(): String? = screen.field("pageKey")
        fun navigator(): EpubNavigatorFragment = screen.field("navigator")
        fun segments(): List<ReadAlongSegment> = voice().timeline.tracks.flatMap { it.segments }
        fun document(): String? = screen.field<org.readium.r2.shared.publication.Locator?>("latestLocator")?.let { DocumentPath.of(it.href.toString()) }
        suspend fun js(script: String): String = withContext(Dispatchers.Main) { navigator().evaluateJavascript(script) }.orEmpty()
        suspend fun highlight(segment: ReadAlongSegment?) = withContext(Dispatchers.Main) {
            screen.javaClass.getDeclaredMethod("highlightNarration", ReadAlongSegment::class.java).apply { isAccessible = true }.invoke(screen, segment)
        }
        suspend fun turn(direction: Direction) {
            val before = withContext(Dispatchers.Main) { key() }
            withContext(Dispatchers.Main) { assertTrue(screen.onPad(PadAction.Step(direction))) }
            until("the page after a turn $direction") { key() != before && span() != null }
        }
        suspend fun press(label: String) = withContext(Dispatchers.Main) { all(root).first { it.contentDescription == label && it.isShown }.performClick() }
    }

    private suspend fun withBook(
        epub: ByteArray, preferences: EpubReaderPreferences = EpubReaderPreferences(columns = EpubColumns.ONE),
        highlights: ReadAlongHighlights = ReadAlongHighlights(), name: String = "word", block: suspend Open.() -> Unit
    ) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val originalHighlights = ReadAlongHighlightStore.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val hub = Hub(epub)
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        val notes = mutableListOf<String>()
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; "notify" -> { notes += args[0].toString(); null }; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, preferences)
            ReadAlongHighlightStore.save(activity, highlights)
            ComfortSettings.save(activity, ScreenComfort())
            lateinit var root: View
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "$name-${System.nanoTime()}", "edition-aligned", "The Last Observatory", { true },
                    readAlong = true, readAlongAvailable = true)
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the narration and the first page looked at") { screen!!.field<Any?>("narration") != null && screen!!.field<Any?>("pageSpan") != null }
            withContext(Dispatchers.Main) { if (screen!!.field<Boolean>("controlsVisible")) screen!!.onPad(PadAction.Menu) }
            until("the menu to close") { !screen!!.field<Boolean>("controlsVisible") }
            delay(500)
            Open(activity, screen!!, root, hub, notes).block()
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "readalong-word-$name-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            ReadAlongHighlightStore.save(activity, originalHighlights)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            hub.server.shutdown()
        }
    }

    // ------------------------------------------------------------------ the pixels

    private fun distance(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)

    /** A word of the page: its id, its rectangle in CSS pixels. */
    private data class Box(val id: String, val left: Double, val top: Double, val width: Double, val height: Double) {
        val right get() = left + width
        val middleX get() = left + width / 2
        val middleY get() = top + height / 2
    }

    /**
     * One word of a long sentence highlighted in [theme] with [look]: what is wrong in the page's pixels (empty when nothing
     * is). The word chosen is on the sentence's second row, so the row above is all trail and the line break is crossed.
     */
    private suspend fun pixels(theme: EpubTheme, look: HighlightLook, name: String): List<String> {
        val problems = mutableListOf<String>()
        withBook(ReaderFixtures.longEpub(sentences = 12, words = true, longSentenceChars = 300, longSentenceIndex = 1),
            EpubReaderPreferences(theme = theme, columns = EpubColumns.ONE, fontScale = 2.0f, lineHeight = 1.5f),
            ReadAlongHighlights().with(theme, look), name) {
            fun boxes(json: org.json.JSONArray) = (0 until json.length()).map { i ->
                val b = json.getJSONArray(i); Box(b.getString(0), b.getDouble(1), b.getDouble(2), b.getDouble(3), b.getDouble(4))
            }
            val read = """(function(){function rects(sel){return Array.from(document.querySelectorAll(sel)).map(function(e){var r=e.getBoundingClientRect();return [e.id||'',r.left,r.top,r.width,r.height];});}
                return JSON.stringify({dpr:window.devicePixelRatio,vw:window.innerWidth,vh:window.innerHeight,
                line:parseFloat(getComputedStyle(document.getElementById('s1')).lineHeight),
                words:rects('[id^="s1-w"]'),before:rects('[id^="s0-w"]'),after:rects('[id^="s2-w"]'),
                trail:rects('.pocket-narration').filter(function(r){return r[3]>0;}),strong:rects('.${ReadAlongGlow.WORD_CLASS}')});})()"""
            fun parse(raw: String) = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
            val layout = parse(js(read))
            val vw = layout.getDouble("vw")
            val vh = layout.getDouble("vh")
            // Only what is on this page: a sentence that runs on to the next page has words beyond its edge.
            fun onPage(box: Box) = box.width > 0 && box.left >= 0 && box.right <= vw && box.top >= 0 && box.top + box.height <= vh
            val words = boxes(layout.getJSONArray("words")).filter(::onPage)
            val line = layout.getDouble("line")
            // Rows of the sentence's words, top to bottom (a word hyphenated over a line break is on two, and in none here).
            val rows = words.filter { it.height < line * 1.2 }.groupBy { word -> Math.round(word.middleY / (line / 2)) }.values.sortedBy { it.first().top }
            assertTrue("$name: the long sentence runs over three rows or more on the page (${rows.size})", rows.size >= 3)
            val chosen = rows[1][minOf(3, rows[1].lastIndex)]
            val index = words.indexOf(chosen)
            val segment = withContext(Dispatchers.Main) { segments().first { it.fragment == chosen.id } }
            highlight(segment)
            delay(1_400)
            val drawn = parse(js(read))
            val strongBoxes = boxes(drawn.getJSONArray("strong"))
            android.util.Log.i("WORDTEST", "$name: chosen $chosen, word boxes $strongBoxes, trail ${drawn.getJSONArray("trail")}, style " +
                js("(function(){var e=document.querySelector('.${ReadAlongGlow.WORD_CLASS}');if(!e)return '';var s=getComputedStyle(e);return [s.zIndex,s.position,s.backgroundColor,s.display,e.parentElement&&e.parentElement.getAttribute('data-style')].join('|');})()"))
            val dpr = drawn.getDouble("dpr")
            val web = withContext(Dispatchers.Main) {
                all(root).filterIsInstance<android.webkit.WebView>().first { it.isShown && it.width > 0 }.let { view -> IntArray(2).also(view::getLocationOnScreen) }
            }
            // As the reader sees it, kept for the report.
            ins.waitForIdleSync()
            shell("screencap -p /sdcard/Download/readalong-word-$name.png")
            val seen = ins.uiAutomation.takeScreenshot()
            // With the words made invisible: only what is tinted.
            js("""(function(){Array.from(document.querySelectorAll('body, body *')).forEach(function(e){
                e.style.setProperty('color','transparent','important');e.style.setProperty('-webkit-text-fill-color','transparent','important');
                e.style.setProperty('text-shadow','none','important');});})()""")
            delay(500)
            val tinted = ins.uiAutomation.takeScreenshot()
            fun pixel(shot: Bitmap, x: Double, y: Double): Int =
                shot.getPixel((web[0] + x * dpr).toInt().coerceIn(0, shot.width - 1), (web[1] + y * dpr).toInt().coerceIn(0, shot.height - 1))
            val (page, ink) = EpubPagePalette.of(theme)
            val tints = ReadAlongWordHighlight.tints(look, page, ink, ReadAlongWordHighlight.isDark(theme))
            val trail = tints.trail ?: page
            fun near(what: String, x: Double, y: Double, want: Int, levels: Int = 12) {
                val got = pixel(tinted, x, y)
                if (distance(got, want) > levels) problems += "$name: $what at (${"%.1f".format(x)}, ${"%.1f".format(y)}) is ${hex(got)}, wanted ${hex(want)}"
            }
            // The word, strong; drawn as its line's line box.
            near("the word being said", chosen.middleX, chosen.middleY, tints.word)
            if (strongBoxes.isEmpty()) problems += "$name: no box drawn for the word"
            strongBoxes.forEach { box -> if (abs(box.height - (line + 1.5)) > 1.0) problems += "$name: the word's box is ${box.height} tall, its line ${line}" }
            // The trail: every word before it in the sentence, and the spaces between them.
            words.take(index).filter { it.height < line * 1.2 }.forEach { word -> near("${word.id} (trail)", word.middleX, word.middleY, trail) }
            words.take(index + 1).zipWithNext().filter { (a, b) -> a.height < line * 1.2 && b.height < line * 1.2 && abs(a.middleY - b.middleY) < line / 2 && b.left - a.right > 3 }.forEach { (a, b) ->
                near("the space after ${a.id} (trail)", (a.right + b.left) / 2, a.middleY, trail)
            }
            // Nothing after the word.
            words.drop(index + 1).filter { it.height < line * 1.2 }.forEach { word -> near("${word.id} (not yet said)", word.middleX, word.middleY, page) }
            // Line boxes across the break: from the first row's middle to the second's, under a word both rows' trail covers, one colour.
            val first = rows[0]
            val x = rows[1].filter { words.indexOf(it) < index }.map { it.middleX }.firstOrNull { it > first.first().left + 4 }
            if (tints.trail != null && x != null) {
                var y = first.first().middleY
                while (y <= rows[1].first().middleY) { near("the join of the first two rows", x, y, trail, 14); y += 1.0 / dpr }
            }
            // Nothing between sentences: the space before the sentence's first word, after the last word of the one before.
            val before = boxes(drawn.getJSONArray("before")).filter { onPage(it) && abs(it.middleY - words.first().middleY) < line / 2 && it.right <= words.first().left }
            before.maxByOrNull { it.right }?.let { last ->
                var gap = last.right + 1.0
                while (gap < words.first().left - ReadAlongGlow.SIDE_PX - 1.5) { near("the space between two sentences", gap, last.middleY, page, 8); gap += 1.0 / dpr }
            }
            // The ink: the page's own on the word, which the wash is behind.
            var inkiest = page; var far = -1
            var yy = chosen.top
            while (yy <= chosen.top + chosen.height) {
                var xx = chosen.left
                while (xx <= chosen.right) { val c = pixel(seen, xx, yy); val d = distance(c, tints.word); if (d > far) { far = d; inkiest = c }; xx += 1.0 / dpr }
                yy += 1.0 / dpr
            }
            if (distance(inkiest, ink) > 24) problems += "$name: the word's ink is ${hex(inkiest)}, the page's ${hex(ink)}"
            seen.recycle(); tinted.recycle()
            android.util.Log.i("WORDTEST", "$name: word ${chosen.id} (${index + 1} of ${words.size}), ${rows.size} rows, problems $problems")
        }
        return problems
    }

    @Test fun theWordIsStrongTheTrailLightAndNothingElseInEveryTheme(): Unit = runBlocking {
        val problems = mutableListOf<String>()
        for ((name, theme) in listOf("paper" to EpubTheme.LIGHT, "sepia" to EpubTheme.SEPIA, "dim" to EpubTheme.DARK, "dark" to EpubTheme.BLACK)) {
            problems += pixels(theme, ReadAlongWordHighlight.defaultLook(theme), name)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun aTrailAtNoneDrawsOnlyTheWordAndAnotherColourIsDrawnInIt(): Unit = runBlocking {
        val problems = pixels(EpubTheme.LIGHT, HighlightLook(HighlightColor.GOLD, 0), "paper-no-trail") +
            pixels(EpubTheme.BLACK, HighlightLook(HighlightColor.SKY, 100), "dark-sky-full-trail")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** A book with no pack (Storyteller's sentence edition) washes the sentence whole, in the colour chosen for the page. */
    @Test fun aSentenceEditionWashesItsSentenceInTheChosenColour(): Unit = runBlocking {
        val look = HighlightLook(HighlightColor.ROSE, 40)
        withBook(ReaderFixtures.longEpub(sentences = 12), EpubReaderPreferences(theme = EpubTheme.LIGHT, columns = EpubColumns.ONE),
            ReadAlongHighlights().with(EpubTheme.LIGHT, look), "sentence") {
            assertFalse(withContext(Dispatchers.Main) { voice().timeline.wordLevel })
            highlight(withContext(Dispatchers.Main) { segments()[1] })
            delay(1_400)
            val colour = js("(function(){var e=document.querySelector('.pocket-narration');return e?getComputedStyle(e).backgroundColor:'';})()").trim('"')
            val (page, ink) = EpubPagePalette.of(EpubTheme.LIGHT)
            val want = ReadAlongGlow.rgb(ReadAlongWordHighlight.tints(look, page, ink, false).sentence)
            assertEquals("The sentence's wash is the rose of the setting", want, colour)
            assertEquals("No word is drawn", "0", js("document.querySelectorAll('.${ReadAlongGlow.WORD_CLASS}').length").trim('"'))
            ins.waitForIdleSync()
            shell("screencap -p /sdcard/Download/readalong-word-sentence-fallback.png")
        }
    }

    // ------------------------------------------------------------------ the setting

    private fun find(view: View, tag: String): View? = all(view).firstOrNull { it.tag == tag }

    @Test fun theSettingPicksAColourAndATrailForEachThemeKeepsThemAndUseTheDefaultTakesBoth(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val original = ReadAlongHighlightStore.load(activity)
        try {
            var kept = ReadAlongHighlights()
            lateinit var panel: EpubAppearancePanel
            withContext(Dispatchers.Main) {
                panel = EpubAppearancePanel(activity, Theme.colors(activity)) { true }
                activity.setContentView(panel)
                panel.show(EpubReaderPreferences(theme = EpubTheme.SEPIA), {}, {}, highlights = kept, pageTheme = EpubTheme.SEPIA,
                    onHighlights = { kept = it; ReadAlongHighlightStore.save(activity, it) })
                all(panel).first { it is android.widget.TextView && it.text == "Themes" }.performClick()
            }
            ins.waitForIdleSync()
            withContext(Dispatchers.Main) {
                val row = find(panel, "highlight")!!
                assertTrue("the row says the page's colour and trail: ${row.contentDescription}", row.contentDescription.toString().contains("Gold · trail 40%"))
                row.performClick()
            }
            ins.waitForIdleSync()
            // It opens at its top: the theme tabs and the preview in view.
            delay(400)
            withContext(Dispatchers.Main) {
                android.util.Log.i("WORDTEST", "setting opened, focus on ${panel.findFocus()?.tag}")
                val rect = android.graphics.Rect()
                for (tag in listOf("highlight-theme:${EpubTheme.SEPIA}", "highlight-preview")) {
                    val view = find(panel, tag)!!
                    assertTrue("$tag is in view", view.getGlobalVisibleRect(rect) && rect.height() >= view.height * 0.9)
                }
            }
            withContext(Dispatchers.Main) {
                // The page themes as tabs, the preview, the eight colours, the trail, the default.
                assertEquals(EpubPagePalette.CHOICES.map { "highlight-theme:$it" }, all(panel).mapNotNull { it.tag as? String }.filter { it.startsWith("highlight-theme:") })
                assertEquals(HighlightColor.entries.map { "highlight-color:${it.id}" }, all(panel).mapNotNull { it.tag as? String }.filter { it.startsWith("highlight-color:") })
                assertNotNull(find(panel, "highlight-preview")); assertNotNull(find(panel, "highlight-trail")); assertNotNull(find(panel, "highlight-default"))
                // Gold marked as the default on Sepia, and chosen.
                val gold = find(panel, "highlight-color:gold")!!
                assertTrue(gold.contentDescription.toString(), gold.contentDescription.toString().startsWith("Gold\ndefault, selected"))
            }
            ins.waitForIdleSync(); delay(300)
            shell("screencap -p /sdcard/Download/readalong-setting-1-sepia.png")
            withContext(Dispatchers.Main) {
                find(panel, "highlight-color:teal")!!.performClick()
                (all(find(panel, "highlight-trail")!!).first { it is SeekBar } as SeekBar).progress = 12  // 60%
            }
            assertEquals(HighlightLook(HighlightColor.TEAL, 60), kept.of(EpubTheme.SEPIA))
            assertEquals("Other themes keep theirs", ReadAlongWordHighlight.defaultLook(EpubTheme.LIGHT), kept.of(EpubTheme.LIGHT))
            // The preview is drawn in the new colour and trail on the theme's own page.
            withContext(Dispatchers.Main) {
                val preview = find(panel, "highlight-preview") as android.widget.TextView
                val spans = (preview.text as android.text.Spanned).getSpans(0, preview.text.length, android.text.style.BackgroundColorSpan::class.java).map { it.backgroundColor }.toSet()
                val (page, ink) = EpubPagePalette.of(EpubTheme.SEPIA)
                val tints = ReadAlongWordHighlight.tints(HighlightLook(HighlightColor.TEAL, 60), page, ink, false)
                assertEquals(setOf(tints.word, tints.trail!!), spans)
            }
            ins.waitForIdleSync(); delay(300)
            shell("screencap -p /sdcard/Download/readalong-setting-2-teal-60.png")
            // The foot of the sheet: the trail and the default.
            withContext(Dispatchers.Main) { all(find(panel, "highlight-trail")!!).first { it is SeekBar }.requestFocus() }
            ins.waitForIdleSync(); delay(400)
            shell("screencap -p /sdcard/Download/readalong-setting-2b-trail.png")
            // Another theme's tab: Dark, its own default (Ember) and its own trail.
            withContext(Dispatchers.Main) { find(panel, "highlight-theme:${EpubTheme.BLACK}")!!.performClick() }
            ins.waitForIdleSync()
            withContext(Dispatchers.Main) {
                assertTrue(find(panel, "highlight-color:ember")!!.contentDescription.toString().startsWith("Ember\ndefault, selected"))
                find(panel, "highlight-color:lavender")!!.performClick()
            }
            ins.waitForIdleSync(); delay(300)
            shell("screencap -p /sdcard/Download/readalong-setting-3-dark.png")
            assertEquals(HighlightLook(HighlightColor.LAVENDER, 40), kept.of(EpubTheme.BLACK))
            // Kept on the device: the preferences file on disk holds it, which is what a restarted app reads.
            val file = File(activity.applicationInfo.dataDir, "shared_prefs/epub-reader.xml")
            until("the setting written to disk") { file.readText().let { "readalong.color.SEPIA\">teal" in it && "readalong.trail.SEPIA\" value=\"60\"" in it } }
            assertEquals(kept, ReadAlongHighlightStore.load(activity))
            // Use the default: colour and trail, for that theme only.
            withContext(Dispatchers.Main) { find(panel, "highlight-theme:${EpubTheme.SEPIA}")!!.performClick() }
            ins.waitForIdleSync()
            withContext(Dispatchers.Main) { find(panel, "highlight-default")!!.performClick() }
            assertEquals(ReadAlongWordHighlight.defaultLook(EpubTheme.SEPIA), kept.of(EpubTheme.SEPIA))
            assertEquals(HighlightLook(HighlightColor.LAVENDER, 40), kept.of(EpubTheme.BLACK))
            until("the default written to disk") { !file.readText().contains("readalong.color.SEPIA") }
            // B goes back to the Themes tab.
            withContext(Dispatchers.Main) { assertTrue(panel.onPad(PadAction.Back)); assertNotNull(find(panel, "highlight")) }
        } finally {
            ReadAlongHighlightStore.save(activity, original)
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }

    /** Picking applies at once to the page behind the sheet while reading along. */
    @Test fun aColourPickedRedrawsThePageBehindAtOnce(): Unit = runBlocking {
        withBook(ReaderFixtures.longEpub(sentences = 12, words = true), EpubReaderPreferences(theme = EpubTheme.LIGHT, columns = EpubColumns.ONE), name = "redraw") {
            val word = withContext(Dispatchers.Main) { segments().first { it.fragment == "s1-w4" } }
            highlight(word)
            delay(1_200)
            fun strong() = "(function(){var e=document.querySelector('.${ReadAlongGlow.WORD_CLASS}');return e?getComputedStyle(e).backgroundColor:'';})()"
            val (page, ink) = EpubPagePalette.of(EpubTheme.LIGHT)
            assertEquals(ReadAlongGlow.rgb(ReadAlongWordHighlight.tints(ReadAlongWordHighlight.defaultLook(EpubTheme.LIGHT), page, ink, false).word), js(strong()).trim('"'))
            withContext(Dispatchers.Main) {
                screen.javaClass.getDeclaredMethod("setHighlights", ReadAlongHighlights::class.java).apply { isAccessible = true }
                    .invoke(screen, ReadAlongHighlights().with(EpubTheme.LIGHT, HighlightLook(HighlightColor.MINT, 40)))
            }
            val want = ReadAlongGlow.rgb(ReadAlongWordHighlight.tints(HighlightLook(HighlightColor.MINT, 40), page, ink, false).word)
            until("the page redrawn in mint", 5_000) { true }
            withTimeout(5_000) { while (js(strong()).trim('"') != want) delay(100) }
        }
    }

    // ------------------------------------------------------------------ behaviour with the word timeline

    @Test fun theVoiceTurnsThePageAtTheNextPagesFirstWordAndAPageTurnedByHandSendsItToTheFirstWordOnIt(): Unit = runBlocking {
        withBook(ReaderFixtures.longEpub(sentenceSeconds = 4, words = true)) {
            assertTrue(withContext(Dispatchers.Main) { voice().timeline.wordLevel })
            val first = withContext(Dispatchers.Main) { span()!! }
            val end = first.end!!
            // The next page's first word is a word's own begin, not a share of a sentence.
            val word = withContext(Dispatchers.Main) { voice().timeline.active(end.track, end.offsetMs)!! }
            assertTrue("a word: ${word.fragment}", word.isWord)
            assertEquals("its begin, exactly", word.beginMs - voice().timeline.tracks[end.track].startMs, end.offsetMs)
            val keyBefore = withContext(Dispatchers.Main) { key() }
            withContext(Dispatchers.Main) { voice().seek(ReadAlongPosition(end.track, end.offsetMs - 2_000)); voice().toggle() }
            var turnedAt = -1L
            withTimeout(20_000) {
                while (turnedAt < 0) {
                    delay(30)
                    val (now, position) = withContext(Dispatchers.Main) { key() to voice().position.offsetMs }
                    if (now != keyBefore) turnedAt = position
                }
            }
            assertTrue("the page turned early, at $turnedAt of ${end.offsetMs}", turnedAt >= end.offsetMs)
            assertTrue("the page turned late, at $turnedAt of ${end.offsetMs}", turnedAt <= end.offsetMs + 1_500)
            until("the new page looked at") { span() != null && key() != keyBefore }
            val next = withContext(Dispatchers.Main) { span()!! }
            assertEquals("the new page's first word is where the old page said", end.offsetMs, next.start!!.offsetMs)
            // A page turned by hand while the voice reads: the voice goes to the first word on it, a word's own begin.
            turn(Direction.RIGHT)
            val third = withContext(Dispatchers.Main) { span()!! }
            val at = withContext(Dispatchers.Main) { voice().position.offsetMs }
            assertTrue("at the new page's first word (${third.start}): $at", at >= third.start!!.offsetMs && at < third.start!!.offsetMs + 1_500)
            val startWord = withContext(Dispatchers.Main) { voice().timeline.active(third.start!!.track, third.start!!.offsetMs)!! }
            assertTrue("a word on that page: ${startWord.fragment} in ${third.visible}", startWord.isWord && startWord.sentenceFragment in third.visible)
            ins.waitForIdleSync()
            shell("screencap -p /sdcard/Download/readalong-word-playing.png")
            // The voice on a word two pages on, and the page sent to it (#59's goTo): the word's sentence is on the page.
            val later = withContext(Dispatchers.Main) { segments().first { it.isWord && it.beginMs > third.end!!.offsetMs + 20_000 } }
            withContext(Dispatchers.Main) {
                voice().pause()
                voice().seek(voice().timeline.find(later.textHref, later.fragment)!!)
                screen.javaClass.getDeclaredMethod("follow").apply { isAccessible = true }.invoke(screen)
            }
            until("the page sent to the word") { span()?.visible?.contains(later.sentenceFragment) == true }
            // The highlight on it: the word strong, its own id.
            withTimeout(5_000) { while (js("(function(){return window.__pocketNarrationWord||''})()").trim('"') != later.fragment) delay(100) }
            delay(800)
        }
    }

    /** Play on a page with no narration (#61) starts the word edition at the nearest sentence's first word. */
    @Test fun playFromAPageWithNoNarrationStartsTheNearestSentence(): Unit = runBlocking {
        withBook(ReaderFixtures.namedEpub(words = true), EpubReaderPreferences(), name = "named") {
            until("the page at the start of the narration") { document() == ReaderFixtures.NAMED_ONE && span()?.href == ReaderFixtures.NAMED_ONE }
            withContext(Dispatchers.Main) {
                screen.javaClass.getDeclaredMethod("goTo", org.readium.r2.shared.publication.Locator::class.java).apply { isAccessible = true }
                    .invoke(screen, org.readium.r2.shared.publication.Locator.fromJSON(JSONObject().put("href", DocumentPath.encode(ReaderFixtures.NAMED_TITLE)).put("type", "application/xhtml+xml")))
                // As Contents does: a page moved to by hand, paused, is read on its own and Play starts from it.
                screen.javaClass.getDeclaredMethod("movedByHand").apply { isAccessible = true }.invoke(screen)
            }
            until("the title page in front") { document() == ReaderFixtures.NAMED_TITLE && span()?.href == ReaderFixtures.NAMED_TITLE }
            assertFalse(withContext(Dispatchers.Main) { span()!!.narrated })
            withContext(Dispatchers.Main) { notes.clear() }
            // Play, as the dock's button does (the menu, which shows the dock, is closed here).
            withContext(Dispatchers.Main) { screen.field<com.pocketds.hub.reader.ReadAlongDock>("narrationDock").onPlay() }
            until("the voice to start at the first narrated sentence", 30_000) { voice().isOn && document() == ReaderFixtures.NAMED_ONE }
            val start = withContext(Dispatchers.Main) { voice().position }
            val first = withContext(Dispatchers.Main) { segments().first() }
            assertTrue("the first word of the first sentence: $start", start.track == 0 && start.offsetMs in 0..2_900)
            assertEquals("a0-w0", first.fragment)
            assertTrue("a note says so: $notes", withContext(Dispatchers.Main) { notes.any { "no narration" in it && "nearest" in it } })
            // The page settled where the voice is before the book is closed: Readium's page fragment must not be torn down mid-load.
            until("the page looked at") { span()?.href == ReaderFixtures.NAMED_ONE && span()!!.narrated }
            withContext(Dispatchers.Main) { voice().pause() }
            delay(800)
        }
    }

    /** The place saved while reading along by the word is the sentence the word is in. */
    @Test fun aSavedPlaceIsASentence(): Unit = runBlocking {
        withBook(ReaderFixtures.longEpub(sentenceSeconds = 3, words = true), name = "saved") {
            withContext(Dispatchers.Main) { voice().seek(ReadAlongPosition(0, 7_400)); voice().toggle() }
            until("the voice") { voice().isPlaying && voice().position.offsetMs > 7_700 }
            val word = withContext(Dispatchers.Main) { voice().timeline.active(0, voice().position.offsetMs)!! }
            assertTrue(word.isWord)
            withContext(Dispatchers.Main) { voice().pause() }
            until("the place posted", 15_000) { hub.posted.isNotEmpty() }
            delay(800)
            val locator = hub.posted.last().getJSONObject("locator")
            val fragments = locator.getJSONObject("locations").getJSONArray("fragments")
            assertEquals("one fragment", 1, fragments.length())
            assertEquals("the sentence, not the word", word.sentenceFragment, fragments.getString(0))
            assertFalse(fragments.getString(0).contains("-w"))
        }
    }

    // ------------------------------------------------------------------ the size of a word edition

    /**
     * A word edition as large as The Final Empire (Dramatized)'s, 193k words in 42 overlays, generated: how long the reader
     * takes to read its narration and find a saved place in it, on this device. (The real books are read on the PC, by
     * ReadAlongRealPacksTest, never opened here.)
     */
    @Test fun aWordEditionAsLargeAsTheLargestPackOpensInSeconds() {
        val chapters = 42
        val sentencesPerChapter = 470
        val wordsPerSentence = 10
        val file = File(ins.targetContext.cacheDir, "word-size-${System.nanoTime()}.epub")
        java.util.zip.ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, body: String) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() }
            put("META-INF/container.xml", "<container><rootfiles><rootfile full-path='EPUB/package.opf'/></rootfiles></container>")
            put("EPUB/package.opf", "<package><manifest>" + (0 until chapters).joinToString("") { "<item id='c$it' href='Text/c$it.xhtml' media-overlay='o$it'/><item id='o$it' href='Smil/c$it.smil'/>" } +
                "</manifest><spine>" + (0 until chapters).joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>")
            var ms = 0L
            for (c in 0 until chapters) {
                put("EPUB/Text/c$c.xhtml", "<html/>")
                val smil = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<smil xmlns=\"http://www.w3.org/ns/SMIL\" xmlns:epub=\"http://www.idpf.org/2007/ops\" version=\"3.0\">\n  <body>\n    <seq id=\"c${c}_overlay\" epub:textref=\"../Text/c$c.xhtml\" epub:type=\"chapter\">\n")
                for (s in 0 until sentencesPerChapter) {
                    smil.append("      <seq id=\"c$c-s$s-seq\" epub:textref=\"../Text/c$c.xhtml#c$c-s$s\">\n")
                    for (w in 0 until wordsPerSentence) {
                        smil.append("        <par id=\"c$c-s$s-w$w\">\n          <text src=\"../Text/c$c.xhtml#c$c-s$s-w$w\"/>\n          <audio src=\"../Audio/00001-${"%05d".format(c / 3 + 1)}.mp4\" clipBegin=\"${ms / 1000}.${"%03d".format(ms % 1000)}s\" clipEnd=\"${(ms + 280) / 1000}.${"%03d".format((ms + 280) % 1000)}s\"/>\n        </par>\n")
                        ms += 320
                    }
                    smil.append("      </seq>\n")
                    ms += 400
                }
                put("EPUB/Smil/c$c.smil", smil.append("    </seq>\n  </body>\n</smil>\n").toString())
            }
        }
        try {
            // Where the time goes: the overlays unzipped and decoded, then scanned, then the whole read as the reader does it.
            val unzipStart = android.os.SystemClock.elapsedRealtime()
            val texts = java.util.zip.ZipFile(file).use { zip -> zip.entries().toList().filter { it.name.endsWith(".smil") }.map { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) } }
            val unzipped = android.os.SystemClock.elapsedRealtime() - unzipStart
            val scanStart = android.os.SystemClock.elapsedRealtime()
            val scanned = texts.sumOf { com.pocketds.hub.reader.ReadAlongSmil.pars(it).size }
            val scan = android.os.SystemClock.elapsedRealtime() - scanStart
            val started = android.os.SystemClock.elapsedRealtime()
            val timeline = com.pocketds.hub.reader.ReadAlongPackage.read(file, requireAudio = false)
            val read = android.os.SystemClock.elapsedRealtime() - started
            val found = timeline.find("EPUB/Text/c41.xhtml", "c41-s200")
            val indexed = android.os.SystemClock.elapsedRealtime() - started - read
            val words = timeline.tracks.sumOf { it.segments.size }
            val summary = "a word edition of $words words, ${file.length() / 1024} KB zipped (${texts.sumOf { it.length } / 1024} KB of SMIL): " +
                "read in $read ms (unzip $unzipped ms, scan of $scanned pars $scan ms), its places indexed in $indexed ms"
            android.util.Log.i("WORDTEST", summary)
            File(ins.targetContext.getExternalFilesDir(null), "readalong-word-size.txt").writeText(summary)
            assertEquals(chapters * sentencesPerChapter * wordsPerSentence, words)
            assertNotNull(found)
            assertTrue(summary, read + indexed < 20_000)
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------ smoothness

    private data class Frames(val total: Int, val janky: Int, val p50: Int, val p90: Int, val p95: Int, val p99: Int, val slowUi: Int, val slowDraw: Int)

    private fun frames(packageName: String): Frames {
        val out = shell("dumpsys gfxinfo $packageName")
        fun int(label: String) = Regex("""$label:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toInt() ?: -1
        fun ms(label: String) = Regex("""$label:\s*(\d+)ms""").find(out)?.groupValues?.get(1)?.toInt() ?: -1
        return Frames(int("Total frames rendered"), int("Janky frames"), ms("50th percentile"), ms("90th percentile"), ms("95th percentile"), ms("99th percentile"),
            int("Number Slow UI thread"), int("Number Slow issue draw commands"))
    }

    private suspend fun aMinuteOf(words: Boolean): Pair<Frames, Map<String, Int>> {
        var result: Pair<Frames, Map<String, Int>>? = null
        withBook(ReaderFixtures.longEpub(sentences = 60, sentenceSeconds = 4, words = words), name = if (words) "minute-words" else "minute-sentences") {
            val packageName = activity.packageName
            val highlighter: Any = withContext(Dispatchers.Main) { screen.field<Any>("highlighter\$delegate").let { (it as Lazy<*>).value!! } }
            fun count(field: String) = highlighter.javaClass.getDeclaredField(field).apply { isAccessible = true }.getInt(highlighter)
            withContext(Dispatchers.Main) { voice().seek(ReadAlongPosition(0, 0)); voice().toggle() }
            until("the voice") { voice().isPlaying }
            shell("dumpsys gfxinfo $packageName reset")
            val sentencesBefore = count("sentenceDraws"); val wordsBefore = count("wordDraws")
            delay(60_000)
            val measured = frames(packageName)
            val draws = mapOf("sentences" to count("sentenceDraws") - sentencesBefore, "words" to count("wordDraws") - wordsBefore)
            // The script's own cost on this page: the fit of a sentence and its word, timed in the page fifty times.
            // The word being said and the next one, in turn, so that every fit has a word to move (the same word again writes nothing).
            val cost = js("""(function(){var w=window.__pocketNarrationWord||'';var m=/^(.*-w)(\d+)${'$'}/.exec(w);var a=w,b=m?m[1]+(+m[2]+1):w;
                if(!document.getElementById(b))b=a;var t=performance.now();
                for(var i=0;i<50;i++){window.__pocketNarrationWord=(i%2)?b:a;window.__pocketNarrationFit();}
                var spent=(performance.now()-t)/50;window.__pocketNarrationWord=w;window.__pocketNarrationFit();return spent.toFixed(2);})()""").trim('"')
            withContext(Dispatchers.Main) { voice().pause() }
            android.util.Log.i("WORDTEST", "minute of ${if (words) "words" else "sentences"}: $measured, draws $draws, fit ${cost} ms")
            result = measured to (draws + ("fitMicros" to ((cost.toDoubleOrNull() ?: -1.0) * 1000).toInt()))
        }
        return result!!
    }

    @Test fun aMinuteOfWordChangesIsTimedAgainstAMinuteOfSentences(): Unit = runBlocking {
        val (before, beforeDraws) = aMinuteOf(words = false)
        val (after, afterDraws) = aMinuteOf(words = true)
        val summary = "sentences: $before $beforeDraws\nwords: $after $afterDraws"
        android.util.Log.i("WORDTEST", summary)
        File(ins.targetContext.getExternalFilesDir(null), "readalong-word-frames.txt").writeText(summary)
        assertTrue("words changed several times a second: $afterDraws", (afterDraws["words"] ?: 0) >= 150)
        assertTrue("a frame is drawn: $after", after.total > 0)
        // The emulator's GPU is emulated, and nearly every frame it draws counts as janky whatever the page does (gfxinfo's
        // "slow issue draw commands"), so the frames are reported, not judged; what is judged is the page's own work for a
        // word, which must leave most of a frame free.
        assertTrue("the script for a word takes under 8 ms: $summary", (afterDraws["fitMicros"] ?: Int.MAX_VALUE) in 0 until 8_000)
    }
}
