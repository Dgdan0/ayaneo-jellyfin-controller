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
        assertTrue(ReaderMarks.STYLESHEET.contains(".pd-hl { z-index: -2 !important;"))
        assertTrue(ReaderMarks.STYLESHEET.contains(".pd-note { z-index: 6"))
    }

    /**
     * A highlight on words the voice is saying (#66 with #62): the word and the trail are drawn over it, whichever was put on the page first.
     * Both are behind the words (a negative z-index) in the page's one stacking context, so the order is the z-index's, not the order Readium
     * made their groups in: measured on a word edition, a highlight made after the word was lit covered the word and the trail.
     */
    @Test fun theVoicesWordAndTrailAreDrawnOverAHighlightAndAllOfThemBehindTheWords() {
        fun zIndex(stylesheet: String, selector: String): Int =
            Regex("""${Regex.escape(selector)}[^{]*\{[^}]*z-index:\s*(-?\d+)""").find(stylesheet)?.groupValues?.get(1)?.toInt()
                ?: error("no z-index for $selector in $stylesheet")
        val highlight = zIndex(ReaderMarks.STYLESHEET, ".pd-hl")
        val sentence = zIndex(ReadAlongGlow.STYLESHEET, "." + ReadAlongGlow.CLASS)
        val word = zIndex(ReadAlongGlow.STYLESHEET, "." + ReadAlongGlow.WORD_CLASS)
        assertTrue("behind the words: highlight $highlight, sentence $sentence, word $word", highlight < 0 && sentence < 0 && word < 0)
        assertTrue("the voice over the highlight: highlight $highlight, sentence $sentence, word $word", sentence > highlight && word > highlight)
        // The marks stay over the words.
        assertTrue(zIndex(ReaderMarks.STYLESHEET, ".pd-note") > 0 && zIndex(ReaderMarks.STYLESHEET, ".pd-heard") > 0)
    }

    @Test fun heardToHereIsAnUnderlineAndATabInTheMarginAndNeverAWordsOnThePage() {
        val tint = ReaderMarks.tint(HighlightColor.YELLOW, paper, paperInk)
        // Nothing of it is text drawn over the page: the old pill (a label above the first line) is gone from the stylesheets.
        assertFalse(ReaderMarks.STYLESHEET.contains("Heard to here"))
        assertFalse(ReaderMarks.TAB_STYLESHEET.contains("content:"))
        assertTrue(ReaderMarks.STYLESHEET.contains(".pd-heard { z-index: 6 !important; background: transparent !important; border-bottom: 2px solid var(--heard)"))
        // The tab is the first line's, in the margin of the column: 3px from its edge and 6px wide, inside the 16px the page keeps free.
        assertTrue(ReaderMarks.TAB_STYLESHEET.contains(".pd-tab:not(:first-child) .pd-tab-bar { display: none; }"))
        assertTrue(ReaderMarks.TAB_STYLESHEET.contains("left: 3px"))
        assertTrue(ReaderMarks.TAB_STYLESHEET.contains("width: 6px"))
        assertTrue(3 + 6 < PageGeometry.GUTTER_DP)
        // Its words are the page's accessibility text, once, on the bar.
        val tab = ReaderMarks.tabElement(tint)
        assertTrue(tab, tab.contains("""role="img" aria-label="Heard to here""""))
        assertEquals(1, Regex("aria-label").findAll(tab).count())
        // Painted by the element itself, with !important: Readium's stylesheet blanks the background of every element otherwise.
        assertTrue(tab, tab.contains("""style="background-color: ${ReadAlongGlow.rgb(tint)} !important;""""))
        assertFalse(ReaderMarks.TAB_STYLESHEET.contains("var(--heard)"))
        // The page is XHTML: an element Readium puts in it is parsed as XML, so every attribute has a value.
        for (element in listOf(tab, ReaderMarks.element(ReaderMarks.HEARD, tint), ReaderMarks.element(ReaderMarks.NOTE, tint), ReaderMarks.element(null, tint))) {
            assertFalse(element, Regex("""<[^>]*\s(hidden|disabled|checked|selected)(\s|/|>)""").containsMatchIn(element))
        }
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
