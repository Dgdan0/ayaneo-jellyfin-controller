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

    /**
     * A short generated book; [aligned] adds a media overlay narrating its
     * first eight sentences, [sentenceSeconds] each, over generated silence.
     */
    fun epub(aligned: Boolean, sentenceSeconds: Int = 1): ByteArray {
        val output = ByteArrayOutputStream()
        val sentences = (0 until 8).map { "The pines marked the quiet path, and Mara followed the lantern toward the ridge, sentence ${it + 1}." }
        ZipOutputStream(output).use { zip ->
            val text = sentences.chunked(2).joinToString("") { pair ->
                "<p>" + pair.joinToString(" ") { s -> "<span id=\"s${sentences.indexOf(s)}\">$s</span>" } + "</p>"
            } + (1..30).joinToString("") { "<p>The observatory kept its light on through the night. This is passage $it.</p>" }
            val overlay = if (aligned) " media-overlay=\"mo1\"" else ""
            val extra = if (aligned) """<item id="mo1" href="one.smil" media-type="application/smil+xml"/><item id="voice" href="voice.wav" media-type="audio/wav"/>""" else ""
            val files = mutableMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
                "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-glass</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-05T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"$overlay/>$extra<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
                "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title></head><body><h1>A light beyond the ridge</h1>$text</body></html>""".toByteArray(),
                "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li></ol></nav></body></html>""".toByteArray()
            )
            if (aligned) {
                val pars = sentences.indices.joinToString("") {
                    "<par id=\"p$it\"><text src=\"one.xhtml#s$it\"/><audio src=\"voice.wav\" clipBegin=\"${it * sentenceSeconds}s\" clipEnd=\"${(it + 1) * sentenceSeconds}s\"/></par>"
                }
                files["EPUB/one.smil"] = """<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq epub:textref="one.xhtml">$pars</seq></body></smil>""".toByteArray()
                files["EPUB/voice.wav"] = silence(seconds = sentences.size * sentenceSeconds)
            }
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return output.toByteArray()
    }

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
