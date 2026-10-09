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

    /** [count] paragraphs of generated words that do not compress away: Readium counts a part's positions from its size (#55). */
    private fun filler(seed: Int, count: Int): String {
        val random = java.util.Random(seed.toLong())
        return (1..count).joinToString("") {
            "<p>" + (1..60).joinToString(" ") { (1..3 + random.nextInt(7)).map { 'a' + random.nextInt(26) }.joinToString("") } + ".</p>"
        }
    }

    /**
     * A generated book for Contents' page numbers (#55): a prologue, a long second part with four entries that point
     * into it (`two.xhtml#c1` to `#c3b`, the last a sub-entry), an epilogue, and an entry for a file that is in the
     * book but not in its reading order. [CONTENTS_TITLES] are the entries' words, in order.
     */
    fun contentsEpub(): ByteArray {
        val output = ByteArrayOutputStream()
        fun page(title: String, body: String) =
            """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>$body</body></html>"""
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-contents</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-09T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="three" href="three.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/><itemref idref="two"/><itemref idref="three"/></spine></package>""",
            "EPUB/one.xhtml" to page("Prologue", filler(1, 14)),
            "EPUB/two.xhtml" to page("Part one", filler(2, 6) +
                "<h2 id=\"c1\">1. First light</h2>" + filler(3, 20) +
                "<h2 id=\"c2\">2. The ridge</h2>" + filler(4, 30) +
                "<h2 id=\"c3\">3. The lens</h2>" + filler(5, 20) +
                "<h3 id=\"c3b\">The keeper's log</h3>" + filler(6, 24)),
            "EPUB/three.xhtml" to page("Epilogue", filler(7, 12)),
            "EPUB/notes.xhtml" to page("Author's note", filler(8, 4)),
            "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">Prologue</a></li><li><a href="two.xhtml">Part one</a><ol><li><a href="two.xhtml#c1">1. First light</a></li><li><a href="two.xhtml#c2">2. The ridge</a></li><li><a href="two.xhtml#c3">3. The lens</a><ol><li><a href="two.xhtml#c3b">The keeper's log</a></li></ol></li></ol></li><li><a href="three.xhtml">Epilogue</a></li><li><a href="notes.xhtml">Author's note</a></li></ol></nav></body></html>"""
        )
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    /** What [contentsEpub]'s Contents says, in order (a sub-entry is indented by two spaces for each level). */
    val CONTENTS_TITLES = listOf("Prologue", "Part one", "  1. First light", "  2. The ridge", "  3. The lens", "    The keeper's log", "Epilogue", "Author's note")

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
    fun longEpub(sentences: Int = 120, sentenceSeconds: Int = 3, twoColumns: Boolean = false, longSentenceChars: Int = 0, longSentenceIndex: Int = 0,
                 words: Boolean = false): ByteArray {
        val vocabulary = listOf("lantern", "ridge", "pines", "observatory", "quiet", "path", "Mara", "followed", "toward", "night",
            "light", "kept", "through", "morning", "valley", "river", "stone", "bridge", "slowly", "carried")
        val texts = (0 until sentences).map { i ->
            // [longSentenceChars] makes sentence [longSentenceIndex] as long as that, so it runs over three lines or more
            // with other sentences before and after it, in the same paragraph and the one above (#52).
            val count = if (i == longSentenceIndex && longSentenceChars > 0) longSentenceChars / 7 else 12 + (i * 7) % 24
            "Sentence ${i + 1} began beyond the old ridge and " +
                (0 until count).joinToString(" ") { vocabulary[(i * 3 + it) % vocabulary.size] } + "."
        }
        // [words]: the word edition a wordsync pack makes (#66), each word its own span in its sentence's and a <par> of
        // its own in a <seq> naming the sentence, the sentence's time shared out among its words with a short pause after each.
        val wordPattern = Regex("[A-Za-z0-9]+")
        fun sentenceSpan(i: Int, text: String): String =
            if (!words) "<span id=\"s$i\">$text</span>"
            else { var n = 0; "<span id=\"s$i\">" + wordPattern.replace(text) { "<span id=\"s$i-w${n++}\">${it.value}</span>" } + "</span>" }
        val body = texts.withIndex().chunked(3).joinToString("") { group ->
            "<p>" + group.joinToString(" ") { (i, text) -> sentenceSpan(i, text) } + "</p>"
        }
        val columnRule = if (twoColumns) HUB_COLUMN_RULE else ""
        val pars = texts.indices.joinToString("") { i ->
            if (!words) "<par id=\"p$i\"><text src=\"one.xhtml#s$i\"/><audio src=\"voice.wav\" clipBegin=\"${i * sentenceSeconds}s\" clipEnd=\"${(i + 1) * sentenceSeconds}s\"/></par>"
            else {
                val count = wordPattern.findAll(texts[i]).count()
                val slot = sentenceSeconds * 1000.0 / count
                "<seq id=\"s$i-seq\" epub:textref=\"one.xhtml#s$i\">" + (0 until count).joinToString("") { w ->
                    val begin = i * sentenceSeconds * 1000 + (w * slot).toLong()
                    val end = if (w == count - 1) (i + 1) * sentenceSeconds * 1000L else begin + (slot * 0.8).toLong()
                    "<par id=\"s$i-w$w\"><text src=\"one.xhtml#s$i-w$w\"/><audio src=\"voice.wav\" clipBegin=\"${begin}ms\" clipEnd=\"${end}ms\"/></par>"
                } + "</seq>"
            }
        }
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-long</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-05T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml" media-overlay="mo1"/><item id="mo1" href="one.smil" media-type="application/smil+xml"/><item id="voice" href="voice.wav" media-type="audio/wav"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
            "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title>$columnRule</head><body>${if (longSentenceChars > 0) "" else "<h1>A light beyond the ridge</h1>"}$body</body></html>""".toByteArray(),
            "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li></ol></nav></body></html>""".toByteArray(),
            "EPUB/one.smil" to """<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq epub:textref="one.xhtml">$pars</seq></body></smil>""".toByteArray(),
            "EPUB/voice.wav" to silence(seconds = sentences * sentenceSeconds)
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return output.toByteArray()
    }

    /**
     * A short aligned book whose sentences are exactly [sentences], one element `s{n}` each and side by side in one
     * paragraph, as Storyteller wraps them (#56): the white space between two sentences is inside one of them, after
     * its last word or before the next one's first. [sentenceSeconds] each over generated silence.
     */
    fun spacedEpub(sentences: List<String>, sentenceSeconds: Int = 3): ByteArray {
        val body = "<p>" + sentences.withIndex().joinToString("") { (i, text) -> "<span id=\"s$i\">$text</span>" } + "</p>"
        val pars = sentences.indices.joinToString("") {
            "<par id=\"p$it\"><text src=\"one.xhtml#s$it\"/><audio src=\"voice.wav\" clipBegin=\"${it * sentenceSeconds}s\" clipEnd=\"${(it + 1) * sentenceSeconds}s\"/></par>"
        }
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-spaced</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-09T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml" media-overlay="mo1"/><item id="mo1" href="one.smil" media-type="application/smil+xml"/><item id="voice" href="voice.wav" media-type="audio/wav"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
            "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A light beyond the ridge</title></head><body>$body</body></html>""".toByteArray(),
            "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">A light beyond the ridge</a></li></ol></nav></body></html>""".toByteArray(),
            "EPUB/one.smil" to """<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq epub:textref="one.xhtml">$pars</seq></body></smil>""".toByteArray(),
            "EPUB/voice.wav" to silence(seconds = sentences.size * sentenceSeconds)
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return output.toByteArray()
    }

    /** The documents of [namedEpub], decoded as the zip holds them: raw spaces and brackets, an accented letter (#61). */
    const val NAMED_TITLE = "EPUB/Text/Title Page [front].xhtml"
    const val NAMED_ONE = "EPUB/Text/Author - [Series 01] - Title_split_010.htm"
    const val NAMED_TWO = "EPUB/Text/Café - [Séries 02]_split_011.htm"
    const val NAMED_BACK = "EPUB/Text/Back Matter (notes).xhtml"

    /**
     * A read-along book whose file names are Mistborn's kind (#61): `Author - [Series 01] - Title_split_010.htm`, with
     * spaces, brackets and an accented letter, written raw in the package and the overlays and percent-encoded in the
     * contents. Four documents in reading order: a title page and a back matter with no narration, and between them two
     * narrated chapters, [first] sentences `a0…` in the first and [second] sentences `b0…` in the second, [sentenceSeconds]
     * each over one file of generated silence.
     */
    fun namedEpub(first: Int = 8, second: Int = 5, sentenceSeconds: Int = 3, words: Boolean = false): ByteArray {
        fun page(title: String, body: String) = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>$body</body></html>"""
        val wordPattern = Regex("[A-Za-z0-9]+")
        fun text(prefix: String, i: Int) = "The pines marked the quiet path, and Mara followed the lantern toward the ridge, sentence ${i + 1} of $prefix."
        // [words]: a wordsync pack's word edition of it (#66), each word a span and a <par> in a <seq> naming its sentence.
        fun wrapped(id: String, sentence: String) = if (!words) sentence else { var n = 0; wordPattern.replace(sentence) { "<span id=\"$id-w${n++}\">${it.value}</span>" } }
        fun sentences(prefix: String, count: Int) = (0 until count).chunked(2).joinToString("") { pair ->
            "<p>" + pair.joinToString(" ") { "<span id=\"$prefix$it\">${wrapped("$prefix$it", text(prefix, it))}</span>" } + "</p>"
        }
        fun overlay(document: String, prefix: String, count: Int, from: Int) =
            "<smil xmlns=\"http://www.w3.org/ns/SMIL\" xmlns:epub=\"http://www.idpf.org/2007/ops\" version=\"3.0\"><body><seq epub:textref=\"../Text/$document\">" +
                (0 until count).joinToString("") { i ->
                    val begin = (from + i) * sentenceSeconds * 1000L
                    if (!words) "<par id=\"p$prefix$i\"><text src=\"../Text/$document#$prefix$i\"/><audio src=\"../Audio/voice.wav\" clipBegin=\"${(from + i) * sentenceSeconds}s\" clipEnd=\"${(from + i + 1) * sentenceSeconds}s\"/></par>"
                    else {
                        val count = wordPattern.findAll(text(prefix, i)).count()
                        val slot = sentenceSeconds * 1000L / count
                        "<seq epub:textref=\"../Text/$document#$prefix$i\">" + (0 until count).joinToString("") { w ->
                            val end = if (w == count - 1) begin + sentenceSeconds * 1000L else begin + w * slot + slot * 4 / 5
                            "<par><text src=\"../Text/$document#$prefix$i-w$w\"/><audio src=\"../Audio/voice.wav\" clipBegin=\"${begin + w * slot}ms\" clipEnd=\"${end}ms\"/></par>"
                        } + "</seq>"
                    }
                } + "</seq></body></smil>"
        val one = NAMED_ONE.substringAfterLast('/')
        val two = NAMED_TWO.substringAfterLast('/')
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "EPUB/package.opf" to ("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-named</dc:identifier><dc:title>The Last Observatory</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-09T00:00:00Z</meta></metadata><manifest>""" +
                """<item id="title" href="Text/${NAMED_TITLE.substringAfterLast('/')}" media-type="application/xhtml+xml"/>""" +
                """<item id="one" href="Text/$one" media-type="application/xhtml+xml" media-overlay="mo1"/>""" +
                """<item id="two" href="Text/$two" media-type="application/xhtml+xml" media-overlay="mo2"/>""" +
                """<item id="back" href="Text/${NAMED_BACK.substringAfterLast('/')}" media-type="application/xhtml+xml"/>""" +
                """<item id="mo1" href="MediaOverlays/${one.replace(".htm", ".smil")}" media-type="application/smil+xml"/>""" +
                """<item id="mo2" href="MediaOverlays/${two.replace(".htm", ".smil")}" media-type="application/smil+xml"/>""" +
                """<item id="voice" href="Audio/voice.wav" media-type="audio/wav"/>""" +
                """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>""" +
                """<spine><itemref idref="title"/><itemref idref="one"/><itemref idref="two"/><itemref idref="back"/></spine></package>""").toByteArray(),
            NAMED_TITLE to page("Title page", "<p>A book with a name that has spaces and brackets in it.</p>").toByteArray(),
            NAMED_ONE to page("Chapter one", sentences("a", first)).toByteArray(),
            NAMED_TWO to page("Chapter two", sentences("b", second)).toByteArray(),
            NAMED_BACK to page("Back matter", "<p>Notes at the back, which nobody narrates.</p>").toByteArray(),
            "EPUB/MediaOverlays/${one.replace(".htm", ".smil")}" to overlay(one, "a", first, 0).toByteArray(),
            "EPUB/MediaOverlays/${two.replace(".htm", ".smil")}" to overlay(two, "b", second, first).toByteArray(),
            "EPUB/Audio/voice.wav" to silence(seconds = (first + second) * sentenceSeconds),
            // The contents spell the same files percent-encoded, the way a producer that escapes them would.
            "EPUB/nav.xhtml" to ("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>""" +
                "<li><a href=\"Text/Title%20Page%20%5Bfront%5D.xhtml\">Title page</a></li>" +
                "<li><a href=\"Text/Author%20-%20%5BSeries%2001%5D%20-%20Title_split_010.htm\">Chapter one</a></li>" +
                "<li><a href=\"Text/Caf%C3%A9%20-%20%5BS%C3%A9ries%2002%5D_split_011.htm\">Chapter two</a></li>" +
                "<li><a href=\"Text/Back%20Matter%20(notes).xhtml\">Back matter</a></li></ol></nav></body></html>").toByteArray()
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

    /** The sentences of [selectionEpub], in the order they are read: paragraph one has the first two, and so on. */
    val SELECTION_PARAGRAPHS: List<List<String>> = listOf(
        listOf("The harbor bells were still ringing when Maren climbed the last of the stone steps.",
            "Ash had fallen through the night, soft as flour, and it lay in the gutters and on the shoulders of the men who waited by the boats."),
        listOf("She did not stop to look at them.",
            "In spite of the cold, she pulled off her gloves and set her palm against the lighthouse door.",
            "The iron was warm.",
            "Somewhere above her, the lantern was still burning."),
        listOf("\u201CThey said it would take off on its own,\u201D her brother had told her, \u201Cthe old light, like a bird.\u201D",
            "She had laughed at him then.",
            "She was not laughing now."),
        listOf("Inside, the stair wound up into darkness.",
            "Maren counted the steps the way their father had taught her, and listened for anything that was not the sea.")
    )

    /**
     * A short book of made-up sentences for selecting words, phrases and sentences (#62): a dictionary word ("lantern"), phrases the
     * dictionary has as one entry ("take off", "pulled off"), a phrase it has not ("harbor bells were"), a name it does not know
     * ("Maren"), and enough passages after them to run to several pages. With [aligned] every sentence is its own element, narrated
     * [sentenceSeconds] long over generated silence (without the audio when [withAudio] is false, as the hub's slim edition).
     */
    fun selectionEpub(aligned: Boolean = false, sentenceSeconds: Int = 1, withAudio: Boolean = true): ByteArray {
        val output = ByteArrayOutputStream()
        val flat = SELECTION_PARAGRAPHS.flatten()
        var n = 0
        val text = SELECTION_PARAGRAPHS.joinToString("") { paragraph ->
            "<p>" + paragraph.joinToString(" ") { sentence ->
                val id = n++
                if (aligned) "<span id=\"s$id\">$sentence</span>" else sentence
            } + "</p>"
        } + (1..40).joinToString("") { "<p>The observatory kept its light on through the night. This is passage $it.</p>" }
        val overlay = if (aligned) " media-overlay=\"mo1\"" else ""
        val extra = if (aligned) """<item id="mo1" href="one.smil" media-type="application/smil+xml"/><item id="voice" href="voice.wav" media-type="audio/wav"/>""" else ""
        ZipOutputStream(output).use { zip ->
            val files = mutableMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
                "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:reader-selection</dc:identifier><dc:title>The Lantern Keeper</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-09T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"$overlay/>$extra<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
                "EPUB/one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter One</title></head><body><h1>Chapter One</h1>$text</body></html>""".toByteArray(),
                "EPUB/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">Chapter One</a></li></ol></nav></body></html>""".toByteArray()
            )
            if (aligned) {
                val pars = flat.indices.joinToString("") {
                    "<par id=\"p$it\"><text src=\"one.xhtml#s$it\"/><audio src=\"voice.wav\" clipBegin=\"${it * sentenceSeconds}s\" clipEnd=\"${(it + 1) * sentenceSeconds}s\"/></par>"
                }
                files["EPUB/one.smil"] = """<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq epub:textref="one.xhtml">$pars</seq></body></smil>""".toByteArray()
                if (withAudio) files["EPUB/voice.wav"] = silence(seconds = flat.size * sentenceSeconds)
            }
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return output.toByteArray()
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
