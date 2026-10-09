package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Direction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.*
import com.pocketds.hub.settings.HubSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/** Exercises the production screen, HTTP download, renderer, controls and preference persistence together. */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class EpubReaderPolishTest {
    @Test fun globalAppearanceSearchAndReturnWorkInRealReader(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldUrl = HubSettings.baseUrl(activity); val oldToken = HubSettings.token(activity)
        val archive = book()
        val server = MockWebServer()
        val positions = java.util.concurrent.ConcurrentHashMap<String, String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.endsWith("/file")) return MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(archive))
                if (request.method == "POST") {
                    positions[path] = JSONObject(request.body.readUtf8()).optJSONObject("locator")?.toString() ?: "null"
                    return MockResponse().setBody("{\"ok\":true}")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"locator\":${positions[path] ?: "null"}}")
            }
        }
        server.start()
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var exits = 0
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> { exits++; true }; else -> null }
        } as ScreenHost
        var screen: EpubReaderScreen? = null
        lateinit var root: View
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        fun click(label: String) {
            val view = all(root).firstOrNull { it.isShown && (it.contentDescription?.toString() == label || it.contentDescription?.toString() == "$label, selected") }
                ?: all(root).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == label }
            var target = view
            while (!target.isClickable) target = target.parent as View
            target.requestFocus(); target.performClick()
        }
        var step = 0
        suspend fun until(check: suspend () -> Boolean) { val at = ++step; try { withTimeout(15_000) { while (!check()) delay(100) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out at wait $at", e) } }
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        suspend fun screenshot(name: String) {
            ins.waitForIdleSync(); delay(250)
            File(activity.getExternalFilesDir(null), "reader-polish-$name.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        suspend fun open(id: String) {
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), id, "edition", "The Last Observatory", { true })
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until { withContext(Dispatchers.Main) { reader()?.evaluateJavascript("document.body.innerText.includes('pines')") == "true" } }
        }
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            open("polish-${System.nanoTime()}")
            withContext(Dispatchers.Main) {
                assertTrue(screen!!.requiresTriggerHold)
                // The keys are ReaderPadMap's (#16): Y opens the contents, B the menu and then leaves.
                assertTrue(screen!!.hints().any { it.label == "Contents" })
                screen!!.onPad(PadAction.Back)
                assertEquals(0, exits)
                assertTrue(screen!!.hints().any { it.action == PadAction.Back && it.label == "Leave the book" })
                screen!!.onPad(PadAction.Back)
                assertEquals(1, exits)
                // The proxy records the exit without destroying the fixture screen.
                screen!!.onPad(PadAction.Menu)
            }
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Menu); click("Reading appearance"); click("Themes") }
            ins.waitForIdleSync()
            withContext(Dispatchers.Main) {
                assertFalse("A modal must cancel a pending chapter hold", screen!!.requiresTriggerHold)
                listOf("Paper", "Sepia", "Dim", "Dark", "Blue").forEach { label -> assertTrue(all(root).any { it.contentDescription?.toString()?.startsWith(label) == true }) }
                click("Blue")
            }
            until { withContext(Dispatchers.Main) { reader()?.evaluateJavascript("getComputedStyle(document.documentElement).getPropertyValue('--USER__backgroundColor').trim()")?.contains("1d303d", true) == true } }
            withContext(Dispatchers.Main) {
                click("Layout"); click("Two pages"); click("Font"); click("Spacing"); click("Wide"); click("‹ Font"); click("Atkinson Hyperlegible"); click("Layout"); click("One full page per screen")
                assertTrue(EpubAppearanceStore.load(activity).onePagePerScreen)
                assertEquals(EpubColumns.ONE, EpubAppearanceStore.load(activity).columns)
                assertFalse(EpubAppearanceStore.load(activity).scroll)
                click("Close panel")
            }
            ins.waitForIdleSync()
            withContext(Dispatchers.Main) {
                assertTrue("Appearance opener should regain focus", all(root).first { it.contentDescription == "Reading appearance" }.hasFocus())
                assertEquals(org.readium.r2.navigator.preferences.ColumnCount.ONE, reader()!!.settings.value.columnCount)
                assertFalse(reader()!!.settings.value.scroll)
                // Keys sits at the end of the top bar now (#16), so Down from Appearance
                // lands on the position under it rather than on Next page.
                screen!!.onPad(PadAction.Step(Direction.DOWN))
                assertEquals("Reading position and navigation", root.findFocus()?.contentDescription)
                screen!!.onPad(PadAction.Step(Direction.DOWN))
                assertEquals("Browse book percentage", root.findFocus()?.contentDescription)
                screen!!.onPad(PadAction.Step(Direction.UP))
                assertEquals("Reading position and navigation", root.findFocus()?.contentDescription)
                screen!!.onPad(PadAction.Activate)
                all(root).filterIsInstance<EditText>().first { it.isShown }.setText("2")
                click("Go to section page")
            }
            until { withContext(Dispatchers.Main) { all(root).filterIsInstance<TextView>().any { it.text.contains("Section page 2/") } } }
            withContext(Dispatchers.Main) {
                val label = all(root).filterIsInstance<TextView>().first { it.contentDescription == "Reading position and navigation" }
                assertFalse("Book percentage must advance within a short chapter", label.text.contains(" · 0% of book"))
            }
            screenshot("toolbar")
            withContext(Dispatchers.Main) {
                val seek = all(root).filterIsInstance<android.widget.SeekBar>().first { it.isShown }
                seek.requestFocus(); seek.progress = 100; screen!!.onPad(PadAction.Activate)
            }
            until { withContext(Dispatchers.Main) { reader()?.currentLocator?.value?.href?.toString()?.contains("two.xhtml") == true } }
            withContext(Dispatchers.Main) { click("Return to previous place") }
            until { withContext(Dispatchers.Main) { all(root).filterIsInstance<TextView>().any { it.text.contains("Section page 2/") } } }
            withContext(Dispatchers.Main) {
                click("Search this book")
                all(root).filterIsInstance<EditText>().first { it.isShown }.setText("telescope")
                click("Search")
            }
            until { withContext(Dispatchers.Main) { all(root).filterIsInstance<TextView>().any { it.isShown && it.text.contains("telescope") } } }
            withContext(Dispatchers.Main) {
                val result = all(root).first { it.isShown && it.isClickable && it.contentDescription?.toString()?.contains("telescope") == true }
                result.performClick()
            }
            until { withContext(Dispatchers.Main) { reader()?.currentLocator?.value?.href?.toString()?.contains("two.xhtml") == true } }
            withContext(Dispatchers.Main) { click("Return to previous place") }
            until { withContext(Dispatchers.Main) { reader()?.currentLocator?.value?.href?.toString()?.contains("one.xhtml") == true } }
            withContext(Dispatchers.Main) {
                val router = com.pocketds.hub.input.PadEventRouter(triggerHoldContext = { screen!!.takeIf { it.requiresTriggerHold } }, emit = { screen!!.onPad(it) })
                router.onKeyDown(com.pocketds.hub.input.PadNames.KEYCODE_BUTTON_R2, nowMs = 0L)
                router.onTick(599L)
                assertTrue(reader()!!.currentLocator.value.href.toString().contains("one.xhtml"))
                router.onTick(600L)
                router.onKeyUp(com.pocketds.hub.input.PadNames.KEYCODE_BUTTON_R2)
            }
            until { withContext(Dispatchers.Main) { reader()?.currentLocator?.value?.href?.toString()?.contains("two.xhtml") == true } }
            withContext(Dispatchers.Main) { screen!!.onHide(); screen!!.onDestroyView(); screen = null }
            open("second-book-${System.nanoTime()}")
            assertEquals(EpubTheme.BLUE, EpubAppearanceStore.load(activity).theme)
            until { withContext(Dispatchers.Main) { reader()?.evaluateJavascript("getComputedStyle(document.documentElement).getPropertyValue('--USER__backgroundColor').trim()")?.contains("1d303d", true) == true } }
            withContext(Dispatchers.Main) { screen!!.onPad(PadAction.Menu); click("Reading appearance"); click("Themes") }
            screenshot("themes")
            withContext(Dispatchers.Main) { click("Layout") }
            screenshot("layout")
            withContext(Dispatchers.Main) { click("Font") }
            screenshot("font")
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-polish-failure.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original); HubSettings.save(activity, oldUrl, oldToken); server.shutdown()
        }
    }

    private fun book(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val paragraphs = (1..25).joinToString("") { "<p>The pines marked the quiet path. Mara followed the lantern toward the ridge. This is passage $it of the first chapter.</p>" }
            val files = mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-polish</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-27T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
                "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title></head><body><h1>A light beyond the ridge</h1>$paragraphs</body></html>""",
                "EPUB/two.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>The keeper of the lens</title></head><body><h1>The keeper of the lens</h1><p>The telescope showed a small light over the mountains.</p></body></html>""",
                "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li><li><a href="two.xhtml">The keeper of the lens</a></li></ol></nav></body></html>"""
            )
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return output.toByteArray()
    }
}
