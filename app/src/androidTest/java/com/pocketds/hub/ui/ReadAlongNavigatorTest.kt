package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReadAlongNavigatorTest {
    @Test fun alignedFragmentChangesChapterAndDrawsHighlightInRealReadium() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        val file = File(activity.cacheDir, "readalong-navigator.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            val documents = mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:pocketds:readalong-test</dc:identifier><dc:title>Narration test</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-22T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
                "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p id="first">A quiet test starts here.</p></body></html>""",
                "EPUB/two.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Two</title></head><body><p id="target">The narration follows this sentence on the next page.</p></body></html>""",
                "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">One</a></li><li><a href="two.xhtml">Two</a></li></ol></nav></body></html>"""
            )
            documents.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        var publication: Publication? = null
        var reader: EpubNavigatorFragment? = null
        try {
            withContext(Dispatchers.Main) {
                val container = FrameLayout(activity).apply { id = View.generateViewId() }
                activity.setContentView(container)
                val http = DefaultHttpClient()
                val retriever = AssetRetriever(activity.contentResolver, http)
                val asset = retriever.retrieve(file).getOrElse { error(it.toString()) }
                val opened = PublicationOpener(DefaultPublicationParser(activity, assetRetriever = retriever, httpClient = http, pdfFactory = null)).open(asset, allowUserInteraction = false).getOrElse { error(it.toString()) }
                publication = opened
                reader = EpubNavigatorFactory(opened).createFragmentFactory(initialLocator = null).instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
                activity.supportFragmentManager.beginTransaction().add(container.id, reader!!).commitNow()
            }
            delay(1200)
            val target = Locator.fromJSON(JSONObject("""{"href":"EPUB/two.xhtml","type":"application/xhtml+xml","locations":{"fragments":["target"]}}"""))!!
            withContext(Dispatchers.Main) { assertTrue(reader!!.go(target, animated = false)) }
            var loaded = false
            repeat(100) {
                if (!loaded) {
                    delay(100)
                    loaded = withContext(Dispatchers.Main) { reader!!.evaluateJavascript("!!document.getElementById('target')") == "true" }
                }
            }
            assertTrue("Aligned target chapter must be visible", loaded)
            withContext(Dispatchers.Main) {
                reader!!.applyDecorations(listOf(Decoration("narration", target, Decoration.Style.Highlight(0xFFFFC857.toInt(), isActive = true))), "readalong")
            }
            var drawn = false
            repeat(30) {
                if (!drawn) {
                    delay(100)
                    drawn = withContext(Dispatchers.Main) { reader!!.evaluateJavascript("(function(){var e=document.querySelector('[data-group=readalong] [data-style]');return !!e&&Array.from(e.children).some(function(h){var r=h.getBoundingClientRect();return r.width>0&&r.height>0&&r.bottom>0&&r.top<innerHeight;});})()") == "true" }
                }
            }
            assertTrue("Actual Readium highlight must be drawn", drawn)
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                val image = File(output, "readalong-highlight.png").apply { parentFile?.mkdirs() }
                image.outputStream().use { i.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            Unit
        } finally {
            withContext(Dispatchers.Main) {
                reader?.let { activity.supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss() }
                publication?.close(); activity.finish()
            }
            file.delete()
        }
    }
}
