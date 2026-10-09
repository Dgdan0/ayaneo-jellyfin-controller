package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderMarksTest {
    private val paper = 0xFFFAF0DC.toInt()
    private val paperInk = 0xFF3B3224.toInt()
    private val night = 0xFF14161B.toInt()
    private val nightInk = 0xFFE7E4DC.toInt()

    @Test fun everyColourKeepsTheInkReadableOnEveryPage() {
        for ((page, ink) in listOf(paper to paperInk, night to nightInk)) {
            for (color in HighlightColor.entries) {
                val tint = ReaderMarks.tint(color, page, ink)
                assertTrue("$color on ${Integer.toHexString(page)}: ${ReadAlongGlow.contrast(ink, tint)}", ReadAlongGlow.contrast(ink, tint) >= ReadAlongGlow.MIN_CONTRAST)
            }
        }
    }

    @Test fun theFourColoursAreFourDifferentWashes() {
        val tints = HighlightColor.entries.map { ReaderMarks.tint(it, paper, paperInk) }
        assertEquals(4, tints.toSet().size)
        assertEquals(4, HighlightColor.entries.map(ReaderMarks::base).toSet().size)
    }

    @Test fun aHighlightIsAnOpaqueBoxBehindTheWordsAndAMarkIsOverThem() {
        val tint = ReaderMarks.tint(HighlightColor.PINK, paper, paperInk)
        val box = ReaderMarks.element(ReaderMarks.HIGHLIGHT, tint)
        assertTrue(box, box.contains("class=\"pd-hl\"") && box.contains("background-color: ${ReadAlongGlow.rgb(tint)} !important"))
        assertTrue(ReaderMarks.element(ReaderMarks.NOTE, tint).contains("class=\"pd-note\""))
        assertTrue(ReaderMarks.element(ReaderMarks.HEARD, tint).contains("--heard"))
        // Nothing else is a highlight: a decoration made without a kind is the box.
        assertTrue(ReaderMarks.element(null, tint).contains("pd-hl"))
        assertTrue(ReaderMarks.STYLESHEET.contains(".pd-hl { z-index: -1 !important;"))
        assertTrue(ReaderMarks.STYLESHEET.contains(".pd-note { z-index: 6"))
        assertTrue(ReaderMarks.STYLESHEET.contains("Heard to here"))
    }

    @Test fun markupBecomesTheTextThePageShows() {
        val html = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>A &amp; B</title><style>p{}</style></head>
            <body><h1>Chapter&nbsp;One</h1><p>She <em>did</em> not stop.<br/>In spite&#8217;s <span id="s1">cold</span> &#x201C;gloves&#x201D;.</p><script>x()</script><!-- c --></body></html>"""
        val text = AnnotationFinder.normalize(DocumentText.plain(html))
        assertEquals("chapter one she did not stop. in spite's cold \"gloves\".", text)
    }

    @Test fun anEntityItDoesNotKnowIsLeftAsItWas() {
        assertEquals("a &zzz; b", DocumentText.plain("a &zzz; b"))
        assertEquals("a © b", DocumentText.plain("a &copy; b"))
    }

    @Test fun theFiltersKeepWhatTheyNameAndNothingElse() {
        fun note(color: String, text: String = "") = ReadingAnnotation(AnnotationIds.new(), color, text)
        val all = listOf(note("yellow"), note("blue", "why?"), note("blue"), note("green", "yes"))
        assertEquals(4, HighlightFilter.ALL.apply(all).size)
        assertEquals(2, HighlightFilter.BLUE.apply(all).size)
        assertEquals(1, HighlightFilter.YELLOW.apply(all).size)
        assertEquals(0, HighlightFilter.PINK.apply(all).size)
        assertEquals(listOf("blue", "green"), HighlightFilter.NOTES.apply(all).map { it.color })
        assertEquals(6, HighlightFilter.entries.size)
        assertTrue(HighlightFilter.entries.all { it.label.isNotBlank() && it.description.isNotBlank() })
    }

    @Test fun anIdIsTheHubsKindAndNeverTheSameTwice() {
        val ids = (1..200).map { AnnotationIds.new() }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all(AnnotationIds::valid))
        assertFalse(AnnotationIds.valid("an_xyz"))
        assertFalse(AnnotationIds.valid("AN_" + "a".repeat(32)))
    }
}
