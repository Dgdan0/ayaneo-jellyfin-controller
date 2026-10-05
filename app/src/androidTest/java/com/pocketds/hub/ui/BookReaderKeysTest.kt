package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.settings.HubSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * A book's keys (#16) in the real reader, on a generated EPUB from a local
 * hub: B opens the menu, whose row names the keys, and B again leaves;
 * continuous scrolling scrolls with the D-pad and the right stick; R3 lists
 * the keys. No real book is opened.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class BookReaderKeysTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun keysMenuAndScrollingInTheRealReader(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val archive = book()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.endsWith("/file")) return MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(archive))
                if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"locator\":null}")
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
        suspend fun until(what: String, check: suspend () -> Boolean) {
            try { withTimeout(20_000) { while (!check()) delay(100) } } catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
        }
        suspend fun shot(name: String) {
            ins.waitForIdleSync(); delay(450)
            File(activity.getExternalFilesDir(null), "book-keys-$name.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        fun web(): WebView? = all(root).filterIsInstance<WebView>().filter { it.isShown }.maxByOrNull {
            val r = android.graphics.Rect(); if (it.getGlobalVisibleRect(r)) r.width() * r.height() else 0
        }
        suspend fun pad(vararg actions: PadAction) = withContext(Dispatchers.Main) { actions.forEach { assertTrue(screen!!.onPad(it)) } }
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences(scroll = true))
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "keys-${System.nanoTime()}", "edition", "The Last Observatory", { true })
                root = screen!!.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { withContext(Dispatchers.Main) { reader()?.evaluateJavascript("document.body.innerText.includes('pines')") == "true" } }
            delay(800)
            shot("01-scrolling")
            // The D-pad scrolls a part of a screen; the right stick glides.
            val start = withContext(Dispatchers.Main) { web()!!.scrollY }
            pad(PadAction.Step(Direction.DOWN))
            delay(300)
            val afterStep = withContext(Dispatchers.Main) { web()!!.scrollY }
            assertTrue("The D-pad scrolled: $start -> $afterStep", afterStep > start)
            repeat(30) { pad(PadAction.Pan(0f, 0.02f)); delay(16) }
            delay(300)
            val afterStick = withContext(Dispatchers.Main) { web()!!.scrollY }
            assertTrue("The right stick scrolled: $afterStep -> $afterStick", afterStick > afterStep)
            shot("02-scrolled")
            // B: the menu, with the keys' row; B again leaves the book.
            pad(PadAction.Back)
            until("the menu") { withContext(Dispatchers.Main) { all(root).filterIsInstance<TextView>().any { it.isShown && it.text == "Leave the book" } } }
            assertEquals(0, exits)
            shot("03-menu-with-keys")
            // R3: every key, as this book reads (continuous scrolling).
            pad(PadAction.Click(Stick.RIGHT))
            until("the Controls sheet") { withContext(Dispatchers.Main) { all(root).any { it.isShown && it.contentDescription?.toString() == "Right stick: Scroll" } } }
            shot("04-controls-sheet")
            pad(PadAction.Back)
            pad(PadAction.Back)
            assertEquals(1, exits)
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "book-keys-failure.png").outputStream().use { ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original); HubSettings.save(activity, oldUrl, oldToken); server.shutdown()
        }
    }

    private fun book(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val paragraphs = (1..60).joinToString("") { "<p>The pines marked the quiet path. Mara followed the lantern toward the ridge. This is passage $it of the first chapter.</p>" }
            val files = mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-keys</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-05T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""",
                "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title></head><body><h1>A light beyond the ridge</h1>$paragraphs</body></html>""",
                "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li></ol></nav></body></html>"""
            )
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return output.toByteArray()
    }
}
