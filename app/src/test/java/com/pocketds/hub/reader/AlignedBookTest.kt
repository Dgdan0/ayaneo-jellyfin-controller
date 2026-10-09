package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAlignedAudio
import com.pocketds.hub.model.ReadingAudioAlignment
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import org.junit.Assert.*
import org.junit.Test

class AlignedBookTest {
    private val document = "OEBPS/chapter-1.xhtml"

    /** The read-along edition: every sentence is its own element. */
    private val aligned = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title><style>p{}</style></head><body>
        <h1>Chapter One</h1>
        <p><span id="s0">The harbor bells were still ringing when Maren climbed the last of the stone steps.</span> <span id="s1">Ash had fallen through the night.</span></p>
        <p><span id="s2">She did not stop to look at them.</span> <span id="s3">In spite of the cold, she pulled off her <em>gloves</em> and set her palm against the door.</span>
        <span id="s4">&#8220;They said it would take off,&#8221; her brother had told her.</span></p></body></html>"""

    /** The plain ebook: the same words, other markup and no ids. */
    private val ebook = """<html><head></head><body><h1>Chapter One</h1><p>The harbor bells were still ringing when Maren climbed the last of the stone steps. Ash had fallen through the night.</p>
        <p>She did not stop to look at them. In spite of the cold, she pulled off her gloves and set her palm against the door. &ldquo;They said it would take off,&rdquo; her brother had told her.</p></body></html>"""

    private val timeline = ReadAlongTimeline(listOf(ReadAlongTrack("audio/voice.mp3", listOf(
        ReadAlongSegment(document, "s0", "audio/voice.mp3", 0, 4_000), ReadAlongSegment(document, "s1", "audio/voice.mp3", 4_000, 6_000),
        ReadAlongSegment(document, "s2", "audio/voice.mp3", 6_000, 8_000), ReadAlongSegment(document, "s3", "audio/voice.mp3", 8_000, 13_000),
        ReadAlongSegment(document, "s4", "audio/voice.mp3", 13_000, 16_000)))))

    private fun book(vararg files: Pair<String, String>) = AlignedBook(timeline, files.toMap()::get)

    @Test fun aDocumentIsReadTwiceAsItWasWrittenAndInTheComparisonFormWithEachSentencesPlaceInBoth() {
        val scan = DocumentText.scan(aligned, setOf("s0", "s1", "s3"))
        assertTrue(scan.raw, scan.raw.contains("Chapter One"))
        assertFalse("the head is not text", scan.raw.contains("p{}") || scan.raw.contains("One</title>"))
        val s1 = scan.normalSpans.getValue("s1")
        assertEquals("ash had fallen through the night.", scan.normal.substring(s1.first, s1.last + 1).trim())
        val raw1 = scan.rawSpans.getValue("s1")
        assertEquals("Ash had fallen through the night.", scan.raw.substring(raw1.first, raw1.last + 1))
        val s3 = scan.rawSpans.getValue("s3")
        assertTrue(scan.raw.substring(s3.first, s3.last + 1).contains("her gloves and set"))
    }

    @Test fun aPassageOfTheEbookFindsTheSentenceOfTheNarrationThatHoldsIt() {
        val book = book(document to aligned)
        val quote = AnnotationQuote("In spite of the cold, she pulled off her ", "gloves", " and set her palm against the door.")
        assertEquals("s3", book.sentenceOf(document, quote)?.fragment)
        assertEquals("s1", book.sentenceOf(document, AnnotationQuote("", "fallen through", ""))?.fragment)
        // The quotes the ebook prints are the same quotes: a passage across the marks of the sentence is still its sentence.
        assertEquals("s4", book.sentenceOf(document, AnnotationQuote("", "“They said it would take off,”", ""))?.fragment)
    }

    @Test fun aPassageBetweenSentencesTakesTheOneThatFollowsAndOneThatIsNotThereTakesNone() {
        val book = book(document to aligned)
        assertEquals("s0", book.sentenceOf(document, AnnotationQuote("", "Chapter One", ""))?.fragment)
        assertNull(book.sentenceOf(document, AnnotationQuote("", "a sentence this book does not have", "")))
        assertNull(book.sentenceOf("OEBPS/other.xhtml", AnnotationQuote("", "harbor", "")))
    }

    @Test fun aSentenceOfTheNarrationIsAnAnchorTheEbookCanFindWithItsOwnCapitalsAndQuotes() {
        val book = book(document to aligned)
        val anchor = book.anchorOf(timeline.tracks[0].segments[4])!!
        assertEquals(document, anchor.document)
        assertEquals("“They said it would take off,” her brother had told her.", anchor.quote.highlight)
        assertTrue(anchor.quote.before.endsWith("against the door."))
        val found = AnnotationFinder.find(DocumentText.plain(ebook), anchor.quote)
        assertNotNull("the plain ebook holds it: ${anchor.quote}", found)
        // And one in the middle of a paragraph, with an element inside it.
        val inner = book.anchorOf(timeline.tracks[0].segments[3])!!
        assertEquals("In spite of the cold, she pulled off her gloves and set her palm against the door.", inner.quote.highlight)
        assertNotNull(AnnotationFinder.find(DocumentText.plain(ebook), inner.quote))
    }

    /** A word edition (#66): the voice is on a word, and "Heard to here" and a switch to the ebook still name its whole sentence. */
    @Test fun aWordOfTheNarrationIsTheAnchorOfItsSentence() {
        val words = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>
            <p><span id="s0"><span id="s0-w0">Ash</span> <span id="s0-w1">had</span> <span id="s0-w2">fallen.</span></span>
            <span id="s1"><span id="s1-w0">She</span> <span id="s1-w1">did</span> <span id="s1-w2">not</span> <span id="s1-w3">stop.</span></span></p></body></html>"""
        val wordTimeline = ReadAlongTimeline(listOf(ReadAlongTrack("audio/voice.mp3", listOf(
            ReadAlongSegment(document, "s0-w0", "audio/voice.mp3", 0, 300, "s0"), ReadAlongSegment(document, "s0-w1", "audio/voice.mp3", 300, 600, "s0"),
            ReadAlongSegment(document, "s0-w2", "audio/voice.mp3", 600, 900, "s0"), ReadAlongSegment(document, "s1-w0", "audio/voice.mp3", 1_000, 1_200, "s1"),
            ReadAlongSegment(document, "s1-w1", "audio/voice.mp3", 1_200, 1_400, "s1"), ReadAlongSegment(document, "s1-w2", "audio/voice.mp3", 1_400, 1_600, "s1"),
            ReadAlongSegment(document, "s1-w3", "audio/voice.mp3", 1_600, 1_900, "s1")))))
        val book = AlignedBook(wordTimeline, mapOf(document to words)::get)
        val anchor = book.anchorOf(wordTimeline.tracks[0].segments[5])!!
        assertEquals("She did not stop.", anchor.quote.highlight)
        assertTrue(anchor.quote.before, anchor.quote.before.trim().endsWith("Ash had fallen."))
        // And back: a passage of the ebook is the first word of the sentence that holds it, where the voice starts.
        assertEquals("s1-w0", book.sentenceOf(document, AnnotationQuote("", "did not", ""))?.fragment)
    }

    @Test fun aDocumentTheBookCannotReadIsNoSentenceAndNoAnchor() {
        val none = book()
        assertNull(none.sentenceOf(document, AnnotationQuote("", "harbor", "")))
        assertNull(none.anchorOf(timeline.tracks[0].segments[0]))
    }

    @Test fun markupWithEntitiesCommentsAndQuotedGreaterThanSignsIsStillRead() {
        val scan = DocumentText.scan("""<p><!-- x > y --><a title="a > b" id="s1">Tom &amp; Jerry&nbsp;ran</a><br/>home</p>""", setOf("s1"))
        assertEquals("Tom & Jerry ran", scan.raw.substring(scan.rawSpans.getValue("s1").first, scan.rawSpans.getValue("s1").last + 1).trim())
        assertTrue(scan.normal.contains("home"))
    }

    private fun manifest() = ReadingAudioManifest(
        tracks = listOf(ReadingAudioTrack(index = 0, id = "t0", durationMs = 600_000), ReadingAudioTrack(index = 1, id = "t1", durationMs = 900_000)),
        alignment = ReadingAudioAlignment(listOf(
            ReadingAlignedAudio("audio/voice.mp3", track = 1, startMs = 120_000),
            ReadingAlignedAudio("audio/earlier.mp3", track = 0, startMs = 0)))
    )

    @Test fun aSentenceIsHeardInTheTrackAndMomentTheHubMappedItsAudioTo() {
        val place = AlignedPlaces.audioPlace(manifest(), timeline.tracks[0].segments[3])!!
        assertEquals("t1", place.trackId)
        assertEquals(120_000L + 8_000L, place.offsetMs)
        assertNull(AlignedPlaces.audioPlace(manifest(), ReadAlongSegment(document, "x", "audio/missing.mp3", 0, 1_000)))
    }

    @Test fun aMomentOfTheAudioIsTheSentenceBeingSpokenThereOrTheLastBeforeIt() {
        val m = manifest()
        assertEquals("s3", AlignedPlaces.segmentAt(m, timeline, 1, 120_000 + 9_500)?.fragment)
        assertEquals("s0", AlignedPlaces.segmentAt(m, timeline, 1, 120_000 + 100)?.fragment)
        assertEquals("s4", AlignedPlaces.segmentAt(m, timeline, 1, 120_000 + 90_000)?.fragment)
        assertNull("nothing is mapped to a track with no file", AlignedPlaces.segmentAt(m, timeline, 3, 1_000))
        // The place and the sentence agree both ways.
        val s2 = timeline.tracks[0].segments[2]
        val place = AlignedPlaces.audioPlace(m, s2)!!
        assertEquals("s2", AlignedPlaces.segmentAt(m, timeline, 1, place.offsetMs)?.fragment)
    }

    @Test fun aSentenceIsWhereThePlayerStandsInTheTimeline() {
        val at = AlignedPlaces.position(timeline, timeline.tracks[0].segments[3])!!
        assertEquals(ReadAlongPosition(0, 8_000), at)
        assertNull(AlignedPlaces.position(timeline, ReadAlongSegment("OEBPS/none.xhtml", "z", "a", 0, 1)))
    }
}
