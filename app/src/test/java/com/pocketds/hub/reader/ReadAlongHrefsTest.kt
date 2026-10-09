package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAlignedAudio
import com.pocketds.hub.model.ReadingAudioAlignment
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read along in a book whose file names have spaces, brackets and letters outside ASCII (#61): Mistborn's documents are
 * `Brandon Sanderson - [Mistborn 01] - The Final Empire_split_010.htm`. The narration's file and the page's file were
 * compared in two spellings, so every page was "unnarrated"; and `java.net.URI` threw on the brackets.
 */
class ReadAlongHrefsTest {
    // Raw spaces and brackets as the SMIL and the zip say them; an accented letter composed in one file, decomposed in the zip entry of the other.
    private val first = "EPUB/Text/Author - [Series 01] - Title_split_010.htm"
    private val second = "EPUB/Text/Café - [Séries 02]_split_011.htm"
    private val third = "EPUB/Text/Plain.xhtml"
    private val firstReadium = "EPUB/Text/Author%20-%20%5BSeries%2001%5D%20-%20Title_split_010.htm"
    private val secondReadium = "EPUB/Text/Caf%C3%A9%20-%20%5BS%C3%A9ries%2002%5D_split_011.htm"
    private val audioOne = "EPUB/Audio/Piste [01] é.mp3"
    private val audioTwo = "EPUB/Audio/plain.mp3"

    /** A book of three documents, narrated in that order: [firstOverlay] and [secondOverlay] say their text paths raw or encoded. */
    private fun book(encodedSecond: Boolean = false, decomposedEntry: Boolean = false, audioInArchive: Boolean = true): File {
        val file = File.createTempFile("readalong-hrefs-", ".epub").apply { deleteOnExit() }
        val textTwo = if (encodedSecond) "../Text/Caf%C3%A9%20-%20%5BS%C3%A9ries%2002%5D_split_011.htm" else "../Text/Café - [Séries 02]_split_011.htm"
        fun smil(text: String, audio: String, ids: List<String>) = "<smil><body><seq>" + ids.withIndexed { i, id ->
            "<par id='p$id'><text src='$text#$id'/><audio src='$audio' clipBegin='${i}s' clipEnd='${i + 1}s'/></par>"
        } + "</seq></body></smil>"
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='EPUB/Pack age/book [1].opf'/></rootfiles></container>",
            "EPUB/Pack age/book [1].opf" to ("<package><manifest>" +
                "<item id='a' href='../Text/Author - [Series 01] - Title_split_010.htm' media-overlay='sa'/>" +
                "<item id='b' href='../Text/Caf%C3%A9%20-%20%5BS%C3%A9ries%2002%5D_split_011.htm' media-overlay='sb'/>" +
                "<item id='c' href='../Text/Plain.xhtml' media-overlay='sc'/>" +
                "<item id='sa' href='../MediaOverlays/Author - [Series 01] - Title_split_010.smil'/>" +
                "<item id='sb' href='../MediaOverlays/Café - [Séries 02]_split_011.smil'/>" +
                "<item id='sc' href='../MediaOverlays/Plain.smil'/>" +
                "</manifest><spine><itemref idref='a'/><itemref idref='b'/><itemref idref='c'/></spine></package>"),
            first to "<html><body><p id='a1'>One.</p><p id='a2'>Two.</p></body></html>",
            (if (decomposedEntry) second.replace("é", "é") else second) to "<html><body><p id='b1'>Three.</p><p id='b2'>Four.</p></body></html>",
            third to "<html><body><p id='c1'>Five.</p></body></html>",
            "EPUB/MediaOverlays/Author - [Series 01] - Title_split_010.smil" to smil("../Text/Author - [Series 01] - Title_split_010.htm", "../Audio/Piste [01] é.mp3", listOf("a1", "a2")),
            "EPUB/MediaOverlays/Café - [Séries 02]_split_011.smil" to smil(textTwo, "../Audio/Piste [01] é.mp3", listOf("b1", "b2")).replace("clipBegin='0s' clipEnd='1s'", "clipBegin='2s' clipEnd='3s'")
                .replace("clipBegin='1s' clipEnd='2s'", "clipBegin='3s' clipEnd='4s'"),
            "EPUB/MediaOverlays/Plain.smil" to smil("../Text/Plain.xhtml", "../Audio/plain.mp3", listOf("c1"))
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
            if (audioInArchive) listOf(audioOne to "one", audioTwo to "two").forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return file
    }

    private inline fun List<String>.withIndexed(each: (Int, String) -> String) = mapIndexed(each).joinToString("")

    @Test fun `a book with spaces brackets and accents in its file names reads, every sentence in one spelling`() {
        for (variant in listOf(false to false, true to false, false to true, true to true)) {
            val timeline = ReadAlongPackage.read(book(encodedSecond = variant.first, decomposedEntry = variant.second))
            val label = "encoded=${variant.first} decomposed=${variant.second}"
            assertEquals(label, listOf(first, second, third), timeline.tracks.flatMap { it.segments }.map { it.textHref }.distinct())
            assertEquals(label, listOf("a1", "a2", "b1", "b2", "c1"), timeline.tracks.flatMap { it.segments }.map { it.fragment })
            // The audio, with brackets and an accent in its name, in the same spelling.
            assertEquals(label, setOf(audioOne, audioTwo), timeline.tracks.map { it.audioHref }.toSet())
        }
    }

    @Test fun `the page's href as Readium spells it finds the narration, however the file is written`() {
        for (decomposed in listOf(false, true)) {
            val timeline = ReadAlongPackage.read(book(decomposedEntry = decomposed))
            // What Readium gives the reader for the first two documents, percent-encoded (and with a fragment, for a link).
            val onFirst = DocumentPath.of(firstReadium)
            val onSecond = DocumentPath.of("$secondReadium#b1")
            assertTrue(timeline.narrates(onFirst))
            assertTrue(timeline.narrates(onSecond))
            assertEquals(listOf("a1", "a2"), timeline.fragments(onFirst))
            assertEquals(listOf("b1", "b2"), timeline.fragments(onSecond))
            assertNotNull(timeline.find(onFirst, "a2"))
            assertEquals(1000L, timeline.find(onFirst, "a2")!!.offsetMs - timeline.find(onFirst, "a1")!!.offsetMs)
            assertEquals("b2", timeline.locate(onSecond, "b2")?.segment?.fragment)
            // The spelling the old code compared (the page's own, encoded) finds nothing: that was the bug.
            assertFalse(timeline.narrates(firstReadium))
            assertEquals(emptyList<String>(), timeline.fragments(secondReadium))
        }
    }

    @Test fun `the audio of such a book is extracted from the archive under the same names`() {
        val file = book(decomposedEntry = true)
        val folder = kotlin.io.path.createTempDirectory("readalong-hrefs-audio").toFile()
        try {
            val audio = ReadAlongPackage.extractAudio(file, ReadAlongPackage.read(file), folder)
            assertEquals(listOf("one", "two"), audio.map { it.readText() }.distinct().sorted())
        } finally { folder.deleteRecursively() }
    }

    @Test fun `a missing document or audio is still refused, and a slim edition without its audio reads`() {
        assertTrue(runCatching { ReadAlongPackage.read(book(audioInArchive = false)) }.isFailure)
        val slim = ReadAlongPackage.read(book(audioInArchive = false), requireAudio = false)
        assertEquals(setOf(audioOne, audioTwo), slim.tracks.map { it.audioHref }.toSet())
    }

    // ------------------------------------------------------------------ the saved place

    private val timeline = ReadAlongTimeline(listOf(ReadAlongTrack(audioOne, listOf(
        ReadAlongSegment(first, "a1", audioOne, 1000, 2000), ReadAlongSegment(first, "a2", audioOne, 2000, 3000),
        ReadAlongSegment(second, "b1", audioOne, 3000, 4000)))))
    private val locator = Json.parseToJsonElement("""{"href":"old.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.2}}""").jsonObject

    @Test fun `a place saved in Readium's spelling, the old decoded one or with a fragment resumes at its sentence`() {
        fun saved(href: String) = Json.parseToJsonElement("""{"href":${JsonPrimitive(href)},"locations":{"fragments":["a2"]}}""").jsonObject
        val at = ReadAlongPosition(0, 1000)
        assertEquals(at, ReadAlongLocation.resume(saved(firstReadium), timeline))
        assertEquals(at, ReadAlongLocation.resume(saved(first), timeline))
        assertEquals(at, ReadAlongLocation.resume(saved("$firstReadium#a2"), timeline))
        assertNull(ReadAlongLocation.resume(saved("EPUB/Text/Elsewhere.xhtml"), timeline))
    }

    @Test fun `the place written for a sentence is a valid href, spelled as the book spells it`() {
        // By default the decoded name is encoded as Readium would encode a path.
        val plain = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 500), false)
        assertEquals(firstReadium, plain["href"]!!.jsonPrimitive.content)
        assertEquals("a1", plain["locations"]!!.jsonObject["fragments"]!!.jsonArray.single().jsonPrimitive.content)
        // The reader gives the spelling its publication uses; the place in another document goes there.
        val spelled = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 2500), false) { document ->
            if (document == second) "EPUB/Text/the%20book's%20own.htm" else DocumentPath.encode(document)
        }
        assertEquals("EPUB/Text/the%20book's%20own.htm", spelled["href"]!!.jsonPrimitive.content)
        // It resumes where it was saved, in either spelling.
        assertEquals(ReadAlongPosition(0, 0), ReadAlongLocation.resume(plain, timeline))
    }

    // ------------------------------------------------------------------ the streamed edition

    private val tracks = listOf(ReadingAudioTrack(0, "t_aaaaaaaaaaaa", "Track 01", 60_000, 1_000_000, "audio/mpeg", "\"e1\""))

    @Test fun `the hub's names for the audio, decoded, match the timeline's however the accents are written`() {
        val manifest = ReadingAudioManifest(revision = "r", aligned = true, tracks = tracks, alignment = ReadingAudioAlignment(listOf(
            // The hub names the files as the archive does: decoded, here with the accent decomposed.
            ReadingAlignedAudio("EPUB/Audio/Piste [01] é.mp3", 0, 0))))
        val streamed = ReadAlongTimeline(listOf(ReadAlongTrack(audioOne, listOf(ReadAlongSegment(first, "a1", audioOne, 1000, 2000)))))
        val sources = ReadAlongStream.sources(streamed, manifest, "item") { "https://hub/audio/tracks/$it" }
        assertEquals("https://hub/audio/tracks/0", sources.single().uri)
        assertNotNull(ReadAlongStream.fitted(streamed, manifest))
        // A file the hub did not map is still refused.
        val other = ReadAlongTimeline(listOf(ReadAlongTrack(audioTwo, listOf(ReadAlongSegment(third, "c1", audioTwo, 0, 1000)))))
        assertTrue(runCatching { ReadAlongStream.sources(other, manifest, "item") { "x" } }.isFailure)
    }

    // ------------------------------------------------------------------ Play on a page with no narration

    private val order = listOf("EPUB/Text/Title.xhtml", first, second, "EPUB/Text/Maps.xhtml", third, "EPUB/Text/Notes.xhtml")
    private val spread = ReadAlongTimeline(listOf(
        ReadAlongTrack(audioOne, listOf(ReadAlongSegment(first, "a1", audioOne, 1000, 2000), ReadAlongSegment(first, "a2", audioOne, 2000, 3000),
            ReadAlongSegment(first, "a3", audioOne, 3000, 4000), ReadAlongSegment(first, "a4", audioOne, 4000, 5000),
            ReadAlongSegment(second, "b1", audioOne, 5000, 6000))),
        ReadAlongTrack(audioTwo, listOf(ReadAlongSegment(third, "c1", audioTwo, 0, 1000), ReadAlongSegment(third, "c2", audioTwo, 1000, 2000)))
    ))

    @Test fun `Play from a page with no narration goes to the first sentence after it in reading order`() {
        // The title page: before everything narrated.
        assertEquals(ReadAlongPosition(0, 0), ReadAlongPageSync.nearest(spread, order, order[0], 0.0))
        assertEquals(ReadAlongPosition(0, 0), ReadAlongPageSync.nearest(spread, order, order[0], 0.9))
        // The maps between two narrated chapters: the first sentence of the next one (track 1 begins at 0 in its own file).
        assertEquals(ReadAlongPosition(1, 0), ReadAlongPageSync.nearest(spread, order, "EPUB/Text/Maps.xhtml", 0.5))
    }

    @Test fun `with nothing narrated after the page it goes to the last sentence before it`() {
        // The notes at the back: the last sentence of the last narrated chapter, c2.
        assertEquals(ReadAlongPosition(1, 1000), ReadAlongPageSync.nearest(spread, order, "EPUB/Text/Notes.xhtml", 0.0))
        // Only the front narrated: the last of it.
        val front = ReadAlongTimeline(listOf(ReadAlongTrack(audioOne, listOf(ReadAlongSegment(first, "a1", audioOne, 1000, 2000), ReadAlongSegment(first, "a2", audioOne, 2000, 3000)))))
        assertEquals(ReadAlongPosition(0, 1000), ReadAlongPageSync.nearest(front, order, "EPUB/Text/Notes.xhtml", 1.0))
    }

    @Test fun `a narrated document whose page shows none of it goes to the sentence at the page's share of it`() {
        assertEquals(ReadAlongPosition(0, 0), ReadAlongPageSync.nearest(spread, order, first, 0.0))
        assertEquals(ReadAlongPosition(0, 1000), ReadAlongPageSync.nearest(spread, order, first, 0.3))
        assertEquals(ReadAlongPosition(0, 2000), ReadAlongPageSync.nearest(spread, order, first, 0.5))
        assertEquals(ReadAlongPosition(0, 3000), ReadAlongPageSync.nearest(spread, order, first, 1.0))
        assertEquals(ReadAlongPosition(0, 3000), ReadAlongPageSync.nearest(spread, order, first, 7.0))
        assertEquals(ReadAlongPosition(0, 0), ReadAlongPageSync.nearest(spread, order, first, Double.NaN))
    }

    @Test fun `there is no nearest sentence for a page that is not in the book or a book that is not narrated`() {
        assertNull(ReadAlongPageSync.nearest(spread, order, "EPUB/Text/Elsewhere.xhtml", 0.0))
        assertNull(ReadAlongPageSync.nearest(spread, emptyList(), first, 0.0))
        val elsewhere = ReadAlongTimeline(listOf(ReadAlongTrack(audioOne, listOf(ReadAlongSegment("EPUB/Text/Other.xhtml", "z", audioOne, 0, 1000)))))
        assertNull(ReadAlongPageSync.nearest(elsewhere, order, order[0], 0.0))
    }
}
