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

    /** The word edition our packs make (#66): a `<seq epub:textref="…#sentence">` round each sentence's words. */
    private fun wordBook(smil: String, text: String = "<html><body><p><span id='s1'><span id='s1-w0'>Hello</span> <span id='s1-w1'>world</span></span></p></body></html>"): File {
        val file = File.createTempFile("word-readalong-", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='EPUB/package.opf'/></rootfiles></container>",
                "EPUB/package.opf" to "<package><manifest><item id='c' href='chapter.xhtml' media-overlay='s'/><item id='s' href='overlays/one.smil'/></manifest><spine><itemref idref='c'/></spine></package>",
                "EPUB/chapter.xhtml" to text,
                "EPUB/overlays/one.smil" to smil,
                "EPUB/audio/voice.mp3" to "test audio"
            )
            entries.forEach { (path, body) -> zip.putNextEntry(ZipEntry(path)); zip.write(body.toByteArray()); zip.closeEntry() }
        }
        return file
    }

    private fun wordPar(id: String, begin: String, end: String) =
        "<par id='$id'><text src='../chapter.xhtml#$id'/><audio src='../audio/voice.mp3' clipBegin='$begin' clipEnd='$end'/></par>"

    @Test fun nestedWordOverlaysRemainIndividuallySeekable() {
        val file = wordBook("<smil xmlns:epub='http://www.idpf.org/2007/ops'><body><seq epub:textref='../chapter.xhtml'><seq id='s1-seq' epub:textref='../chapter.xhtml#s1'>" +
            wordPar("s1-w0", "0.1s", "0.4s") + wordPar("s1-w1", "0.5s", "0.9s") + "</seq></seq></body></smil>")
        val timeline = ReadAlongPackage.read(file)
        assertEquals(2, timeline.tracks.single().segments.size)
        assertTrue(timeline.wordLevel)
        assertEquals("s1-w0", timeline.active(0, 0)?.fragment)
        // Between two words of one sentence the word before is still the one being read (#66).
        assertEquals("s1-w0", timeline.active(0, 350)?.fragment)
        assertEquals("s1-w1", timeline.active(0, 450)?.fragment)
        // After the sentence's last word nothing is.
        assertNull(timeline.active(0, 900))
        assertEquals(ReadAlongPosition(0, 400), timeline.find("EPUB/chapter.xhtml", "s1-w1"))
        // Each word is a word of its sentence; a place by the sentence finds its first word.
        assertEquals(listOf("s1", "s1"), timeline.tracks.single().segments.map { it.sentenceFragment })
        assertEquals(ReadAlongPosition(0, 0), timeline.find("EPUB/chapter.xhtml", "s1"))
        assertEquals(listOf("s1"), timeline.fragments("EPUB/chapter.xhtml"))
    }

    @Test fun aSeqWithoutAPlaceInTheTextOrInAnotherDocumentMakesNoWords() {
        // A seq with an id and no textref (an older word experiment), and one naming another document's place: sentences of their own.
        val bare = ReadAlongPackage.read(wordBook("<smil><body><seq><seq id='s1'>" + wordPar("s1-w0", "0.1s", "0.4s") + "</seq></seq></body></smil>"))
        assertFalse(bare.wordLevel)
        assertEquals("s1-w0", bare.tracks.single().segments.single().sentenceFragment)
        val elsewhere = ReadAlongPackage.read(wordBook("<smil xmlns:epub='http://www.idpf.org/2007/ops'><body><seq epub:textref='../other.xhtml#s1'>" +
            wordPar("s1-w0", "0.1s", "0.4s") + "</seq></body></smil>"))
        assertFalse(elsewhere.wordLevel)
    }

    /** The aligner can place a word a moment before the word ahead of it (0.5% of The Final Empire's): the highlight only goes forward. */
    @Test fun aSentencesWordsAreMadeToRunForwardWithoutSplittingTheNarration() {
        val file = wordBook("<smil xmlns:epub='http://www.idpf.org/2007/ops'><body><seq epub:textref='../chapter.xhtml'>" +
            "<seq epub:textref='../chapter.xhtml#s1'>" + wordPar("s1-w0", "1s", "1.5s") + wordPar("s1-w1", "2s", "2.8s") +
            wordPar("s1-w2", "1.6s", "1.9s") + wordPar("s1-w3", "3s", "3.5s") + "</seq>" +
            "<seq epub:textref='../chapter.xhtml#s2'>" + wordPar("s2-w0", "4s", "4.5s") + "</seq></seq></body></smil>",
            "<html><body><p><span id='s1'><span id='s1-w0'>a</span> <span id='s1-w1'>b</span> <span id='s1-w2'>c</span> <span id='s1-w3'>d</span></span> <span id='s2'><span id='s2-w0'>e</span></span></p></body></html>")
        val timeline = ReadAlongPackage.read(file)
        // One stretch: the player plays it straight through and nothing is heard twice.
        val segments = timeline.tracks.single().segments
        assertEquals(listOf("s1-w0", "s1-w1", "s1-w2", "s1-w3", "s2-w0"), segments.map { it.fragment })
        // The word placed early begins and ends with the word ahead of it, and every clip runs forward.
        assertEquals(2_000L to 2_800L, segments[2].beginMs to segments[2].endMs)
        assertTrue(segments.zipWithNext().all { (a, b) -> b.beginMs >= a.beginMs && b.endMs >= a.endMs && b.endMs > b.beginMs })
        assertEquals("s1-w2", timeline.active(0, 1_500)?.fragment)
        assertEquals("s1-w0", timeline.active(0, 800)?.fragment)
    }

    @Test fun aSentenceThatGoesBackInTheAudioStillStartsAStretchInAWordEdition() {
        val file = wordBook("<smil xmlns:epub='http://www.idpf.org/2007/ops'><body><seq epub:textref='../chapter.xhtml'>" +
            "<seq epub:textref='../chapter.xhtml#s1'>" + wordPar("s1-w0", "30s", "31s") + wordPar("s1-w1", "31s", "32s") + "</seq>" +
            "<seq epub:textref='../chapter.xhtml#s2'>" + wordPar("s2-w0", "2s", "3s") + "</seq></seq></body></smil>",
            "<html><body><p><span id='s1'><span id='s1-w0'>a</span> <span id='s1-w1'>b</span></span> <span id='s2'><span id='s2-w0'>e</span></span></p></body></html>")
        val timeline = ReadAlongPackage.read(file)
        assertEquals(listOf(listOf("s1-w0", "s1-w1"), listOf("s2-w0")), timeline.tracks.map { track -> track.segments.map { it.fragment } })
        // L1/R1 still go a sentence at a time.
        assertEquals(ReadAlongPosition(1, 0), timeline.step(ReadAlongPosition(0, 500), 1))
    }

    /** Voices that overlap (a dramatization), or a word end that runs on: the same stretch, so nothing is heard twice. */
    @Test fun aWordEditionsSentenceThatOverlapsTheOneBeforeStaysInItsStretch() {
        val file = wordBook("<smil xmlns:epub='http://www.idpf.org/2007/ops'><body><seq epub:textref='../chapter.xhtml'>" +
            "<seq epub:textref='../chapter.xhtml#s1'>" + wordPar("s1-w0", "10s", "11s") + wordPar("s1-w1", "11s", "12.5s") + "</seq>" +
            "<seq epub:textref='../chapter.xhtml#s2'>" + wordPar("s2-w0", "12.2s", "13s") + wordPar("s2-w1", "13s", "14s") + "</seq></seq></body></smil>",
            "<html><body><p><span id='s1'><span id='s1-w0'>a</span> <span id='s1-w1'>b</span></span> <span id='s2'><span id='s2-w0'>e</span> <span id='s2-w1'>f</span></span></p></body></html>")
        val timeline = ReadAlongPackage.read(file)
        val segments = timeline.tracks.single().segments
        assertEquals(listOf("s1-w0", "s1-w1", "s2-w0", "s2-w1"), segments.map { it.fragment })
        assertTrue(segments.zipWithNext().all { (a, b) -> b.beginMs >= a.beginMs && b.endMs >= a.endMs })
        assertEquals("s2-w0", timeline.active(0, 2_300)?.fragment)
    }

    /** [chapters] overlays of [sentences] sentences of ten words each, a word a millisecond: words or sentences of their own. */
    private fun manyBook(chapters: Int, sentences: Int, words: Boolean): File {
        val file = File.createTempFile("word-many-", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() }
            val manifest = (0 until chapters).joinToString("") { "<item id='c$it' href='c$it.xhtml' media-overlay='o$it'/><item id='o$it' href='o$it.smil'/>" }
            put("META-INF/container.xml", "<container><rootfiles><rootfile full-path='EPUB/package.opf'/></rootfiles></container>")
            put("EPUB/package.opf", "<package><manifest>$manifest</manifest><spine>${(0 until chapters).joinToString("") { "<itemref idref='c$it'/>" }}</spine></package>")
            put("EPUB/voice.mp3", "test audio")
            var ms = 0L
            for (c in 0 until chapters) {
                put("EPUB/c$c.xhtml", "<html/>")
                val body = StringBuilder("<smil xmlns:epub='http://www.idpf.org/2007/ops'><body><seq epub:textref='c$c.xhtml'>")
                for (s in 0 until sentences) {
                    if (words) body.append("<seq epub:textref='c$c.xhtml#s$s'>")
                    for (w in 0 until 10) {
                        val id = if (words) "s$s-w$w" else "s$s-$w"
                        body.append("<par><text src='c$c.xhtml#$id'/><audio src='voice.mp3' clipBegin='${ms}ms' clipEnd='${ms + 1}ms'/></par>")
                        ms++
                    }
                    if (words) body.append("</seq>")
                }
                put("EPUB/o$c.smil", body.append("</seq></body></smil>").toString())
            }
        }
        return file
    }

    @Test fun aWordEditionMayHoldFarMoreThanTheSentenceCapAndASentenceEditionMayNot() {
        // 210,000 words in 21 overlays of 1,000 sentences: past the 200,000 a sentence edition (and the hub's sentence set) is held to.
        val words = ReadAlongPackage.read(manyBook(chapters = 21, sentences = 1_000, words = true), requireAudio = false)
        assertEquals(210_000, words.tracks.sumOf { it.segments.size })
        assertTrue(words.wordLevel)
        val sentences = runCatching { ReadAlongPackage.read(manyBook(chapters = 21, sentences = 1_000, words = false), requireAudio = false) }
        assertEquals("Narration timeline is too large", sentences.exceptionOrNull()?.message)
        assertEquals(200_000, ReadAlongPackage.SENTENCE_LIMIT)
        assertEquals(1_000_000, ReadAlongPackage.WORD_LIMIT)
    }

    @Test fun aSentenceEditionKeepsItsStretchesAsBefore() {
        val forward = ReadAlongPackage.stretches((0 until 10).map { ReadAlongSegment("c.xhtml", "s$it", "a.mp3", it * 10L, it * 10L + 5) })
        assertEquals(1, forward.size)
        // A sentence that begins before the one ahead of it ends, or another file: a new stretch, as always.
        val back = ReadAlongPackage.stretches(listOf(
            ReadAlongSegment("c.xhtml", "s0", "a.mp3", 0, 10), ReadAlongSegment("c.xhtml", "s1", "a.mp3", 5, 20),
            ReadAlongSegment("c.xhtml", "s2", "b.mp3", 0, 10)))
        assertEquals(listOf(listOf("s0"), listOf("s1"), listOf("s2")), back.map { track -> track.segments.map { it.fragment } })
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

    private fun book(audio: String = "../audio/voice.mp3", begin: String = "1s", end: String = "2s", doctype: String = "",
                     withAudio: Boolean = true, withText: Boolean = true, secondBegin: String = "3s", secondEnd: String = "4s"): File {
        val file = File.createTempFile("readalong-", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mutableMapOf(
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='EPUB/package.opf'/></rootfiles></container>",
                "EPUB/package.opf" to "<package><manifest><item id='c' href='chapter.xhtml' media-overlay='s'/><item id='s' href='overlays/one.smil'/></manifest><spine><itemref idref='c'/></spine></package>",
                "EPUB/chapter.xhtml" to "<html><body><p id='sentence1'>A test.</p><p id='sentence2'>Another.</p></body></html>",
                "EPUB/overlays/one.smil" to "$doctype<smil><body><seq><par><text src='../chapter.xhtml#sentence1'/><audio src='$audio' clipBegin='$begin' clipEnd='$end'/></par><par><text src='../chapter.xhtml#sentence2'/><audio src='$audio' clipBegin='$secondBegin' clipEnd='$secondEnd'/></par></seq></body></smil>",
                "EPUB/audio/voice.mp3" to "test audio"
            )
            if (!withAudio) entries.remove("EPUB/audio/voice.mp3")
            if (!withText) entries.remove("EPUB/chapter.xhtml")
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
        for ((begin, end) in listOf("-1s" to "2s", "NaN" to "2s")) {
            assertTrue(runCatching { ReadAlongPackage.read(book(begin = begin, end = end)) }.isFailure)
        }
        assertTrue(runCatching { ReadAlongPackage.read(book(doctype = "<!DOCTYPE smil [<!ENTITY x SYSTEM 'file:///secret'>]>")) }.isFailure)
    }

    /**
     * A clip that ends before it begins (an older aligner's; the hub mends those it serves now, but an edition kept
     * from before has them) skips its one sentence, as a clip of no length does; the edition reads on. Only an
     * edition of nothing but such clips has no narration.
     */
    @Test fun aClipEndingBeforeItBeginsSkipsItsSentenceNotTheEdition() {
        val timeline = ReadAlongPackage.read(book(begin = "2s", end = "1s"))
        assertEquals(1, timeline.tracks.size)
        assertEquals(listOf("sentence2"), timeline.tracks[0].segments.map { it.fragment })
        assertEquals(3_000L, timeline.tracks[0].startMs)
        assertEquals(1_000L, timeline.tracks[0].durationMs)
        // A clip of no length is skipped the same way.
        assertEquals(listOf("sentence2"), ReadAlongPackage.read(book(begin = "2s", end = "2s")).tracks[0].segments.map { it.fragment })
        val none = runCatching { ReadAlongPackage.read(book(begin = "2s", end = "1s", secondBegin = "5s", secondEnd = "4s")) }
        assertEquals("This edition has no aligned narration", none.exceptionOrNull()?.message)
    }

    /** #19: the hub's slim edition keeps its SMIL but not its audio, which streams from the tracks. */
    @Test fun theEditionWithoutItsAudioReadsWhenAskedToAndOnlyThen() {
        val slim = book(withAudio = false)
        assertTrue("The whole edition must hold its audio", runCatching { ReadAlongPackage.read(slim) }.isFailure)
        val timeline = ReadAlongPackage.read(slim, requireAudio = false)
        assertEquals("EPUB/audio/voice.mp3", timeline.tracks.single().audioHref)
        assertEquals(listOf("sentence1", "sentence2"), timeline.tracks.single().segments.map { it.fragment })
        // The words must still be there.
        assertTrue(runCatching { ReadAlongPackage.read(book(withAudio = false, withText = false), requireAudio = false) }.isFailure)
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
