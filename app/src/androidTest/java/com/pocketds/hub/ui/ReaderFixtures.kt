package com.pocketds.hub.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * Generated reading content for the reader tests (#16): comic pages drawn in
 * bands, a short book with or without narration, an audiobook of parts, all
 * silence and colour, never a real title, and the local hub that serves them.
 */
object ReaderFixtures {
    /** A comic page in four labelled bands, so a screenshot shows which part is on screen. */
    fun page(width: Int, height: Int, label: String): ByteArray {
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

    /** A generated square cover: a night sky with a low sun and the title, never a real book's. */
    fun cover(size: Int, title: String): ByteArray = cover(size, size, title)

    /**
     * A generated cover [width] by [height]: a sky that warms towards its
     * foot, a low sun and the title. No dark band at the foot: one read as a
     * gap under the cover on the audiobook's screen (#18).
     */
    fun cover(width: Int, height: Int, title: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(),
            Color.rgb(28, 44, 86), Color.rgb(176, 92, 74), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val size = minOf(width, height)
        paint.color = Color.rgb(233, 150, 64)
        canvas.drawCircle(width * .68f, height * .66f, size * .2f, paint)
        paint.color = Color.WHITE
        paint.textSize = size / 11f
        paint.textAlign = Paint.Align.CENTER
        title.split(' ').chunked(2).forEachIndexed { line, words ->
            canvas.drawText(words.joinToString(" "), width / 2f, height * .18f + line * size * .11f, paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray().also { bitmap.recycle() }
    }

    /**
     * A comic page with a plain paper border (#18, C5): [borderX] of its width
     * at each side and [borderY] of its height at the top and the foot, the
     * art inside drawn in bands as [page] draws a whole page.
     */
    fun borderedPage(width: Int, height: Int, borderX: Float, borderY: Float, label: String, paper: Int = Color.rgb(244, 241, 232)): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(paper)
        val left = width * borderX
        val top = height * borderY
        val right = width - left
        val bottom = height - top
        val bands = listOf(Color.rgb(196, 64, 52), Color.rgb(52, 118, 170), Color.rgb(60, 140, 70), Color.rgb(170, 130, 40))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = width / 12f; textAlign = Paint.Align.CENTER }
        val band = (bottom - top) / 4f
        for (i in 0 until 4) {
            paint.color = bands[i]
            canvas.drawRect(left, top + band * i, right, top + band * (i + 1), paint)
            paint.color = Color.WHITE
            canvas.drawText("$label · ${i + 1}/4", width / 2f, top + band * (i + 0.55f), paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bitmap.recycle() }
    }

    /** A page's thumbnail as the hub's H1 route makes one: [width] across, its shape kept, a JPEG at quality 80. */
    fun thumbnail(page: ByteArray, width: Int): ByteArray {
        val full = android.graphics.BitmapFactory.decodeByteArray(page, 0, page.size)
        val small = Bitmap.createScaledBitmap(full, width, (full.height.toLong() * width / full.width).toInt().coerceAtLeast(1), true)
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            .also { full.recycle(); if (small !== full) small.recycle() }
    }

    /**
     * A generated book of two chapters (#18): the first opens with a note
     * reference ([NOTE_ID]'s aside) and a link on to the second, then runs on
     * long enough to scroll; the second follows.
     */
    fun notedEpub(): ByteArray {
        val output = ByteArrayOutputStream()
        val passages = (1..50).joinToString("") { "<p>The pines marked the quiet path. Mara followed the lantern toward the ridge. This is passage $it of the first chapter.</p>" }
        val later = (1..50).joinToString("") { "<p>The observatory kept its light on through the night. This is passage $it of the second chapter.</p>" }
        val files = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-notes</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-05T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
            "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>A light beyond the ridge</title></head><body><h1>A light beyond the ridge</h1><p>The lantern<a id="$NOTE_REF" epub:type="noteref" href="#$NOTE_ID">1</a> swung over the path. <a id="$LINK_ID" href="two.xhtml">On to the observatory</a>.</p>$passages<aside id="$NOTE_ID" epub:type="footnote"><p>A ship's lantern, &amp; older than the observatory. <a href="#$NOTE_REF">↩</a></p></aside></body></html>""",
            "EPUB/two.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>The observatory</title></head><body><h1>The observatory</h1>$later</body></html>""",
            "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li><li><a href="two.xhtml">The observatory</a></li></ol></nav></body></html>"""
        )
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    const val NOTE_REF = "ref1"
    const val NOTE_ID = "note1"
    const val LINK_ID = "onward"
    /** What the note's card says, the link back gone. */
    const val NOTE_TEXT = "A ship's lantern, & older than the observatory."

    /**
     * A short generated book; [aligned] adds a media overlay narrating its
     * first eight sentences, [sentenceSeconds] each, over generated silence;
     * [withAudio] false leaves the silence out, as the hub's slim edition does.
     */
    /**
     * [twoColumns]: the hub's own two-column rule in each document (`epub_html.go`, `columnStyle`), which the reading copy carries. Readium
     * CSS asks for two columns only from 60em (960 CSS px), so on the Pocket's 853 dp page that rule is what makes "Two pages" work.
     */
    fun epub(aligned: Boolean, sentenceSeconds: Int = 1, withAudio: Boolean = true, twoColumns: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        val sentences = (0 until 8).map { "The pines marked the quiet path, and Mara followed the lantern toward the ridge, sentence ${it + 1}." }
        ZipOutputStream(output).use { zip ->
            val text = sentences.chunked(2).joinToString("") { pair ->
                "<p>" + pair.joinToString(" ") { s -> "<span id=\"s${sentences.indexOf(s)}\">$s</span>" } + "</p>"
            } + (1..30).joinToString("") { "<p>The observatory kept its light on through the night. This is passage $it.</p>" }
            val columnRule = if (twoColumns) HUB_COLUMN_RULE else ""
            val overlay = if (aligned) " media-overlay=\"mo1\"" else ""
            val extra = if (aligned) """<item id="mo1" href="one.smil" media-type="application/smil+xml"/><item id="voice" href="voice.wav" media-type="audio/wav"/>""" else ""
            val files = mutableMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
                "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-glass</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-05T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"$overlay/>$extra<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
                "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title>$columnRule</head><body><h1>A light beyond the ridge</h1>$text</body></html>""".toByteArray(),
                "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li></ol></nav></body></html>""".toByteArray()
            )
            if (aligned) {
                val pars = sentences.indices.joinToString("") {
                    "<par id=\"p$it\"><text src=\"one.xhtml#s$it\"/><audio src=\"voice.wav\" clipBegin=\"${it * sentenceSeconds}s\" clipEnd=\"${(it + 1) * sentenceSeconds}s\"/></par>"
                }
                files["EPUB/one.smil"] = """<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq epub:textref="one.xhtml">$pars</seq></body></smil>""".toByteArray()
                // Without its audio it is the hub's slim edition (#19): the SMIL still names voice.wav.
                if (withAudio) files["EPUB/voice.wav"] = silence(seconds = sentences.size * sentenceSeconds)
            }
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    /**
     * A long aligned book (#49): [sentences] narrated sentences of 100 to 250 characters, [sentenceSeconds] each over
     * generated silence, in paragraphs of three, so that it runs to many pages and the page breaks fall inside
     * sentences at all sorts of places. Sentence n is the element `s{n}`, and its text begins "Sentence {n+1} ".
     */
    fun longEpub(sentences: Int = 120, sentenceSeconds: Int = 3, twoColumns: Boolean = false): ByteArray {
        val words = listOf("lantern", "ridge", "pines", "observatory", "quiet", "path", "Mara", "followed", "toward", "night",
            "light", "kept", "through", "morning", "valley", "river", "stone", "bridge", "slowly", "carried")
        val texts = (0 until sentences).map { i ->
            "Sentence ${i + 1} began beyond the old ridge and " +
                (0 until 12 + (i * 7) % 24).joinToString(" ") { words[(i * 3 + it) % words.size] } + "."
        }
        val body = texts.withIndex().chunked(3).joinToString("") { group ->
            "<p>" + group.joinToString(" ") { (i, text) -> "<span id=\"s$i\">$text</span>" } + "</p>"
        }
        val columnRule = if (twoColumns) HUB_COLUMN_RULE else ""
        val pars = texts.indices.joinToString("") {
            "<par id=\"p$it\"><text src=\"one.xhtml#s$it\"/><audio src=\"voice.wav\" clipBegin=\"${it * sentenceSeconds}s\" clipEnd=\"${(it + 1) * sentenceSeconds}s\"/></par>"
        }
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-long</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-05T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml" media-overlay="mo1"/><item id="mo1" href="one.smil" media-type="application/smil+xml"/><item id="voice" href="voice.wav" media-type="audio/wav"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
            "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title>$columnRule</head><body><h1>A light beyond the ridge</h1>$body</body></html>""".toByteArray(),
            "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li></ol></nav></body></html>""".toByteArray(),
            "EPUB/one.smil" to """<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq epub:textref="one.xhtml">$pars</seq></body></smil>""".toByteArray(),
            "EPUB/voice.wav" to silence(seconds = sentences * sentenceSeconds)
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return output.toByteArray()
    }

    private const val HUB_COLUMN_RULE = "<style type=\"text/css\">@media screen and (min-width: 30em) { " +
        ":root[style*=\"--USER__colCount: 2\"], :root[style*=\"--USER__colCount:2\"] { --RS__colWidth: auto !important; " +
        "-webkit-column-count: 2 !important; column-count: 2 !important; -webkit-column-width: auto !important; column-width: auto !important; } }</style>"

    /** An audiobook as Storyteller sends one: a ZIP of parts, here [parts] of generated silence. */
    fun audiobook(parts: List<Pair<String, Int>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            parts.forEach { (name, seconds) -> zip.putNextEntry(ZipEntry(name)); zip.write(silence(seconds)); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    /** Generated silence, never a real recording: 8 kHz mono WAV. */
    fun silence(seconds: Int): ByteArray {
        val bytes = 8000 * 2 * seconds
        return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes)
        }.array()
    }

    /** A local hub serving one file for every `/file` route and saving nothing anywhere. */
    fun fileServer(archive: ByteArray, contentType: String = "application/epub+zip") = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path.endsWith("/file")) return MockResponse().setHeader("Content-Type", contentType).setBody(Buffer().write(archive))
                if (request.method == "POST") return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}")
                return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"locator\":null}")
            }
        }
        start()
    }
}
