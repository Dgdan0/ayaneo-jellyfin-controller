package com.pocketds.hub.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReadAlongPackageTest {
    @Test fun activeWordLookupDoesNotScanAnEntireBookEveryPlaybackTick() {
        var reads = 0
        val segments = object : AbstractList<ReadAlongSegment>() {
            override val size = 100_000
            override fun get(index: Int): ReadAlongSegment {
                reads++
                check(reads < 100) { "Playback scanned the word timeline" }
                return ReadAlongSegment("chapter.xhtml", "word-$index", "audio.mp3", index * 2L, index * 2L + 1)
            }
        }
        val timeline = ReadAlongTimeline(listOf(ReadAlongTrack("audio.mp3", segments)))
        assertEquals("word-85000", timeline.active(0, 170_000)?.fragment)
        assertNull(timeline.active(0, 170_001))
    }

    @Test fun nestedWordOverlaysRemainIndividuallySeekable() {
        val file = File.createTempFile("word-readalong-", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='EPUB/package.opf'/></rootfiles></container>",
                "EPUB/package.opf" to "<package><manifest><item id='c' href='chapter.xhtml' media-overlay='s'/><item id='s' href='overlays/one.smil'/></manifest><spine><itemref idref='c'/></spine></package>",
                "EPUB/chapter.xhtml" to "<html><body><p><span id='s1'><span id='s1-w0'>Hello</span> <span id='s1-w1'>world</span></span></p></body></html>",
                "EPUB/overlays/one.smil" to "<smil><body><seq><seq id='s1'><par><text src='../chapter.xhtml#s1-w0'/><audio src='../audio/voice.mp3' clipBegin='0.1s' clipEnd='0.4s'/></par><par><text src='../chapter.xhtml#s1-w1'/><audio src='../audio/voice.mp3' clipBegin='0.5s' clipEnd='0.9s'/></par></seq></seq></body></smil>",
                "EPUB/audio/voice.mp3" to "test audio"
            )
            entries.forEach { (path, body) -> zip.putNextEntry(ZipEntry(path)); zip.write(body.toByteArray()); zip.closeEntry() }
        }
        val timeline = ReadAlongPackage.read(file)
        assertEquals(2, timeline.tracks.single().segments.size)
        assertEquals("s1-w0", timeline.active(0, 0)?.fragment)
        assertNull(timeline.active(0, 350))
        assertEquals("s1-w1", timeline.active(0, 450)?.fragment)
        assertEquals(ReadAlongPosition(0, 400), timeline.find("EPUB/chapter.xhtml", "s1-w1"))
    }

    @Test fun zeroDurationWordFromAlignerDoesNotDiscardTheWholeBook() {
        val timeline = ReadAlongPackage.read(book(begin = "1s", end = "1s"))
        assertEquals(listOf("sentence2"), timeline.tracks.single().segments.map { it.fragment })
    }

    @Test fun optionalSyntheticWordAlignmentParsesGeneratedStorytellerOutput() {
        val path = System.getenv("POCKETDS_WORD_ALIGNMENT_PROBE")
        assumeTrue("Set POCKETDS_WORD_ALIGNMENT_PROBE to the isolated word-aligned EPUB", !path.isNullOrBlank())
        val timeline = ReadAlongPackage.read(File(path!!))
        assertEquals(43, timeline.tracks.sumOf { it.segments.size })
        assertNotNull(timeline.find("OEBPS/chapter.xhtml", "chapter-s1-w5"))
        assertEquals("chapter-s1-w0", timeline.active(0, 130)?.fragment)
    }

    @Test fun optionalLocalDarkMatterAlignmentParsesAllEightTracks() {
        val path = System.getenv("POCKETDS_READALONG_TEST_EPUB")
        assumeTrue("Set POCKETDS_READALONG_TEST_EPUB to the local aligned EPUB", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("Local aligned EPUB must exist", file.isFile)
        val timeline = ReadAlongPackage.read(file)
        assertEquals(8, timeline.tracks.size)
        assertEquals(9322, timeline.tracks.sumOf { it.segments.size })
        assertTrue(timeline.tracks.all { it.durationMs > 0 && it.audioHref.endsWith(".mp3") })
    }

    private fun book(audio: String = "../audio/voice.mp3", begin: String = "1s", end: String = "2s", doctype: String = ""): File {
        val file = File.createTempFile("readalong-", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='EPUB/package.opf'/></rootfiles></container>",
                "EPUB/package.opf" to "<package><manifest><item id='c' href='chapter.xhtml' media-overlay='s'/><item id='s' href='overlays/one.smil'/></manifest><spine><itemref idref='c'/></spine></package>",
                "EPUB/chapter.xhtml" to "<html><body><p id='sentence1'>A test.</p><p id='sentence2'>Another.</p></body></html>",
                "EPUB/overlays/one.smil" to "$doctype<smil><body><seq><par><text src='../chapter.xhtml#sentence1'/><audio src='$audio' clipBegin='$begin' clipEnd='$end'/></par><par><text src='../chapter.xhtml#sentence2'/><audio src='$audio' clipBegin='3s' clipEnd='4s'/></par></seq></body></smil>",
                "EPUB/audio/voice.mp3" to "test audio"
            )
            entries.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return file
    }

    @Test fun spineOrderAndRelativeReferencesResolveToArchiveResources() {
        val timeline = ReadAlongPackage.read(book())
        assertEquals(1, timeline.tracks.size)
        assertEquals("EPUB/chapter.xhtml", timeline.tracks[0].segments[0].textHref)
        assertEquals("sentence1", timeline.tracks[0].segments[0].fragment)
        assertEquals("EPUB/audio/voice.mp3", timeline.tracks[0].audioHref)
        assertEquals(1000L, timeline.tracks[0].startMs)
        assertEquals(3000L, timeline.tracks[0].durationMs)
        assertEquals("sentence1", timeline.active(0, 0)?.fragment)
        assertNull(timeline.active(0, 1500)) // narration gap: never highlight the wrong sentence
        assertEquals("sentence2", timeline.active(0, 2500)?.fragment)
        assertNull(timeline.active(0, 3000))
    }

    @Test fun textSeekAndResumeUseExactFragmentAndTrackOffset() {
        val timeline = ReadAlongPackage.read(book())
        assertEquals(ReadAlongPosition(0, 2000), timeline.find("EPUB/chapter.xhtml", "sentence2"))
        assertNull(timeline.find("missing.xhtml", "sentence1"))
        assertNull(timeline.find("EPUB/chapter.xhtml", "missing"))
    }

    @Test fun clockSyntaxSupportsHoursMinutesMillisecondsAndNpt() {
        assertEquals(3723250L, ReadAlongPackage.clock("01:02:03.250"))
        assertEquals(1250L, ReadAlongPackage.clock("npt=1.25s"))
        assertEquals(1250L, ReadAlongPackage.clock("1250ms"))
        assertEquals(120000L, ReadAlongPackage.clock("2min"))
    }

    @Test fun unsafeResourcesMissingFilesAndInvalidTimingAreRejected() {
        for (audio in listOf("https://evil/audio.mp3", "../../../outside.mp3", "../audio/missing.mp3", "file:///secret.mp3")) {
            assertTrue(audio, runCatching { ReadAlongPackage.read(book(audio)) }.isFailure)
        }
        for ((begin, end) in listOf("2s" to "1s", "-1s" to "2s", "NaN" to "2s")) {
            assertTrue(runCatching { ReadAlongPackage.read(book(begin = begin, end = end)) }.isFailure)
        }
        assertTrue(runCatching { ReadAlongPackage.read(book(doctype = "<!DOCTYPE smil [<!ENTITY x SYSTEM 'file:///secret'>]>")) }.isFailure)
    }

    @Test fun audioExtractionCannotEscapeCacheAndIsExact() {
        val file = book()
        val folder = kotlin.io.path.createTempDirectory("readalong-audio").toFile()
        try {
            val audio = ReadAlongPackage.extractAudio(file, ReadAlongPackage.read(file), folder)
            assertEquals("test audio", audio.single().readText())
            assertEquals(folder.canonicalFile, audio.single().parentFile.canonicalFile)
        } finally { folder.deleteRecursively() }
    }
}
