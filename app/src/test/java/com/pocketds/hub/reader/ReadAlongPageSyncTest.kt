package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page and the voice move each other (#49): the maths of where a page break is in the narration's time. */
class ReadAlongPageSyncTest {
    // One file, ten seconds a sentence. The first sentence is "alpha delta gamma betas" (20 letters).
    private val alpha = "alpha delta gamma betas"
    private val second = "Epsilon zeta eta theta."
    private val third = "Iota kappa lambda mu."
    private val timeline = ReadAlongTimeline(listOf(
        ReadAlongTrack("one.mp4", listOf(
            ReadAlongSegment("one.xhtml", "s0", "one.mp4", 0, 10_000),
            ReadAlongSegment("one.xhtml", "s1", "one.mp4", 10_000, 20_000),
            ReadAlongSegment("one.xhtml", "s2", "one.mp4", 21_000, 30_000)
        )),
        ReadAlongTrack("two.mp4", listOf(
            ReadAlongSegment("two.xhtml", "t0", "two.mp4", 5_000, 9_000)
        ))
    ))

    private fun page(first: PageEdge?, last: PageEdge?, vararg visible: String, href: String = "one.xhtml") =
        PageProbe(href, first, last, visible.toList())

    // ------------------------------------------------------------------ letters, not spaces

    @Test fun `only letters and digits are counted, not spaces or punctuation`() {
        assertEquals(20, ReadAlongPageSync.spokenCount(alpha))
        assertEquals(5, ReadAlongPageSync.spokenCount("a, b... c! 1 2", 0))
        assertEquals(10, ReadAlongPageSync.spokenCount(alpha, 0, 12))
        assertEquals(0, ReadAlongPageSync.spokenCount("  \u2014 ,"))
        // A range outside the text is the text.
        assertEquals(20, ReadAlongPageSync.spokenCount(alpha, -4, 400))
    }

    @Test fun `a point in a sentence is snapped on to the start of a word`() {
        assertEquals(0, ReadAlongPageSync.wordStart(alpha, 0))
        // On a word, the next one is the first to begin.
        assertEquals(6, ReadAlongPageSync.wordStart(alpha, 1))
        assertEquals(6, ReadAlongPageSync.wordStart(alpha, 6))
        // In the gap before a word, that word.
        assertEquals(12, ReadAlongPageSync.wordStart(alpha, 11))
        // In the last word nothing begins any more.
        assertNull(ReadAlongPageSync.wordStart(alpha, 19))
        assertNull(ReadAlongPageSync.wordStart(alpha, 99))
    }

    @Test fun `an apostrophe or a soft hyphen does not split a word`() {
        assertEquals(6, ReadAlongPageSync.wordStart("don't stop", 1))
        assertEquals(6, ReadAlongPageSync.wordStart("don\u2019t stop", 1))
        assertNull(ReadAlongPageSync.wordStart("con\u00ADtinued", 1))
        // A quote that opens a word is not inside one.
        assertEquals(1, ReadAlongPageSync.wordStart("\u2019Tis", 1))
        assertEquals(7, ReadAlongPageSync.wordStart("quote 'Hello'", 1))
        // A hyphen between two words does.
        assertEquals(5, ReadAlongPageSync.wordStart("well-known", 1))
    }

    // ------------------------------------------------------------------ the time of a point

    @Test fun `a point in a sentence is its begin plus the share of its letters times its length`() {
        val s = timeline.tracks[0].segments[0]
        // "gamma" starts after 10 of 20 letters: half way.
        assertEquals(5_000L, ReadAlongPageSync.timeAt(s, alpha, 12))
        // The start of the sentence is its begin.
        assertEquals(0L, ReadAlongPageSync.timeAt(s, alpha, 0))
        // Inside "delta" the next word begins: "gamma".
        assertEquals(5_000L, ReadAlongPageSync.timeAt(s, alpha, 8))
        // "betas" after 15 of 20.
        assertEquals(7_500L, ReadAlongPageSync.timeAt(s, alpha, 18))
        // Nothing begins in the tail of the last word.
        assertNull(ReadAlongPageSync.timeAt(s, alpha, 20))
    }

    @Test fun `a sentence that begins late in its file counts from its own begin`() {
        val s = timeline.tracks[0].segments[2]
        // "Iota kappa lambda mu.": letters 4 + 5 + 6 + 2 = 17; "lambda" after 9: 9/17 of nine seconds.
        assertEquals(21_000L + Math.round(9.0 / 17 * 9_000), ReadAlongPageSync.timeAt(s, third, 11))
    }

    @Test fun `text with no letters has no word to begin at`() {
        assertNull(ReadAlongPageSync.timeAt(timeline.tracks[0].segments[1], "\u2026 ", 0))
    }

    // ------------------------------------------------------------------ the page in time

    @Test fun `a page that begins with a sentence begins at the sentence`() {
        val probe = page(PageEdge("s1", second, 0), PageEdge("s2", third, third.length), "s1", "s2")
        assertEquals(ReadAlongPosition(0, 10_000), ReadAlongPageSync.startOf(timeline, probe))
    }

    @Test fun `a page that begins inside a sentence begins at the first word on it`() {
        // The break fell inside "delta": the page begins at "gamma", half way through the first sentence.
        val probe = page(PageEdge("s0", alpha, 8), PageEdge("s1", second, second.length), "s0", "s1")
        assertEquals(ReadAlongPosition(0, 5_000), ReadAlongPageSync.startOf(timeline, probe))
        // The break in the last word's tail: the page begins with the next sentence.
        val tail = page(PageEdge("s0", alpha, 19), PageEdge("s1", second, second.length), "s0", "s1")
        assertEquals(ReadAlongPosition(0, 10_000), ReadAlongPageSync.startOf(timeline, tail))
    }

    @Test fun `the next page's first word comes after a sentence that ends on this page`() {
        val probe = page(PageEdge("s0", alpha, 0), PageEdge("s1", second, second.length), "s0", "s1")
        // s2 begins a second after s1 ends: the voice reaches the next page there.
        assertEquals(ReadAlongPosition(0, 21_000), ReadAlongPageSync.endOf(timeline, probe))
    }

    @Test fun `the next page's first word is inside a sentence the break goes through`() {
        // s1 = "Epsilon zeta eta theta." (7 + 4 + 3 + 5 = 19 letters); the page ends after "zeta ".
        val cut = second.indexOf(" eta") + 1
        val probe = page(PageEdge("s0", alpha, 0), PageEdge("s1", second, cut), "s0", "s1")
        // "eta" after 11 of 19 letters: 10 s + 11/19 of 10 s.
        assertEquals(ReadAlongPosition(0, 10_000 + Math.round(11.0 / 19 * 10_000)), ReadAlongPageSync.endOf(timeline, probe))
    }

    @Test fun `a break inside a word moves to the next word`() {
        // Cut inside "zeta": "eta" is the first word to begin on the next page.
        val cut = second.indexOf("zeta") + 2
        val probe = page(PageEdge("s0", alpha, 0), PageEdge("s1", second, cut), "s0", "s1")
        assertEquals(ReadAlongPosition(0, 10_000 + Math.round(11.0 / 19 * 10_000)), ReadAlongPageSync.endOf(timeline, probe))
    }

    @Test fun `the voice reaches the next file's first sentence at the end of a file`() {
        val probe = page(PageEdge("s2", third, 0), PageEdge("s2", third, third.length), "s2")
        assertEquals(ReadAlongPosition(1, 0), ReadAlongPageSync.endOf(timeline, probe))
    }

    @Test fun `there is no next page after the last sentence of the book`() {
        val probe = page(PageEdge("t0", "End.", 0), PageEdge("t0", "End.", 4), "t0", href = "two.xhtml")
        assertNull(ReadAlongPageSync.endOf(timeline, probe))
    }

    @Test fun `a page with no narrated text has no time`() {
        val probe = page(null, null)
        assertNull(ReadAlongPageSync.startOf(timeline, probe))
        assertNull(ReadAlongPageSync.endOf(timeline, probe))
        assertTrue(!ReadAlongPageSync.span(timeline, probe).narrated)
    }

    @Test fun `a spread is one page, from the first column's first word to the second column's last`() {
        // The window holds both columns: the first character is at the top of the left one, the last at the foot of the right.
        val spread = page(PageEdge("s0", alpha, 0), PageEdge("s2", third, third.indexOf("mu")), "s0", "s1", "s2")
        val span = ReadAlongPageSync.span(timeline, spread)
        assertEquals(ReadAlongPosition(0, 0), span.start)
        // "mu" is the last word: 15 of 17 letters before it.
        assertEquals(ReadAlongPosition(0, 21_000 + Math.round(15.0 / 17 * 9_000)), span.end)
        assertEquals(setOf("s0", "s1", "s2"), span.visible)
    }

    @Test fun `the same page probed again has the same key and another page does not`() {
        val a = page(PageEdge("s1", second, 0), null, "s1")
        assertEquals(a.key, page(PageEdge("s1", second, 0), PageEdge("s1", second, 5), "s1").key)
        assertTrue(a.key != page(PageEdge("s1", second, 8), null, "s1").key)
        assertTrue(a.key != page(PageEdge("s1", second, 0), null, "s1", href = "two.xhtml").key)
    }

    // ------------------------------------------------------------------ the page follows the voice

    private val span = PageSpan("one.xhtml", ReadAlongPosition(0, 0), ReadAlongPosition(0, 15_000), setOf("s0", "s1"))

    @Test fun `the voice inside the page leaves the page alone`() {
        assertEquals(ReadAlongPageSync.Step.Stay, ReadAlongPageSync.follow(timeline, ReadAlongPosition(0, 3_000), span))
        assertEquals(ReadAlongPageSync.Step.Stay, ReadAlongPageSync.follow(timeline, ReadAlongPosition(0, 14_999), span))
    }

    @Test fun `the page turns when the voice reaches the next page's first word, in the middle of a sentence`() {
        // The break is 5 s into s1; the voice has just got there.
        assertEquals(ReadAlongPageSync.Step.TurnPage, ReadAlongPageSync.follow(timeline, ReadAlongPosition(0, 15_000), span))
        assertEquals(ReadAlongPageSync.Step.TurnPage, ReadAlongPageSync.follow(timeline, ReadAlongPosition(0, 17_500), span))
    }

    @Test fun `a sentence on another page is gone to`() {
        val onward = ReadAlongPageSync.follow(timeline, ReadAlongPosition(0, 22_000), span)
        assertEquals(ReadAlongPageSync.Step.GoTo(timeline.tracks[0].segments[2]), onward)
        // Or in another part of the book.
        assertEquals(ReadAlongPageSync.Step.GoTo(timeline.tracks[1].segments[0]), ReadAlongPageSync.follow(timeline, ReadAlongPosition(1, 0), span))
        // Or before the page, after a step back.
        val later = span.copy(visible = setOf("s2"), start = ReadAlongPosition(0, 21_000), end = null)
        assertEquals(ReadAlongPageSync.Step.GoTo(timeline.tracks[0].segments[0]), ReadAlongPageSync.follow(timeline, ReadAlongPosition(0, 1_000), later))
    }

    @Test fun `between two sentences there is nothing to follow, and the last page never turns`() {
        // s1 ends at 20 s, s2 begins at 21 s.
        assertEquals(ReadAlongPageSync.Step.Stay, ReadAlongPageSync.follow(timeline, ReadAlongPosition(0, 20_500), span.copy(visible = setOf("s0", "s1", "s2"))))
        val last = PageSpan("two.xhtml", ReadAlongPosition(1, 0), null, setOf("t0"))
        assertEquals(ReadAlongPageSync.Step.Stay, ReadAlongPageSync.follow(timeline, ReadAlongPosition(1, 3_900), last))
    }

    // ------------------------------------------------------------------ a page turned by hand

    @Test fun `a page turned by hand to the sentence being spoken restarts nothing`() {
        // The sentence is cut by the break and the new page still shows its end.
        assertEquals(ReadAlongPageSync.Manual.Keep, ReadAlongPageSync.afterManualTurn(timeline, ReadAlongPosition(0, 12_000), span))
    }

    @Test fun `a page turned by hand elsewhere sends the voice to its first word`() {
        val next = PageSpan("one.xhtml", ReadAlongPosition(0, 15_000), ReadAlongPosition(1, 0), setOf("s1", "s2"))
        // The voice is in s0, which this page does not show.
        assertEquals(ReadAlongPageSync.Manual.Jump(ReadAlongPosition(0, 15_000)),
            ReadAlongPageSync.afterManualTurn(timeline, ReadAlongPosition(0, 4_000), next))
        // And in another file altogether.
        assertEquals(ReadAlongPageSync.Manual.Jump(ReadAlongPosition(0, 15_000)),
            ReadAlongPageSync.afterManualTurn(timeline, ReadAlongPosition(1, 1_000), next))
    }

    @Test fun `a page turned by hand to a page with no narration leaves the voice alone`() {
        val bare = PageSpan("one.xhtml", null, null, emptySet())
        assertEquals(ReadAlongPageSync.Manual.Nothing, ReadAlongPageSync.afterManualTurn(timeline, ReadAlongPosition(0, 4_000), bare))
    }

    @Test fun `between two sentences a page turned by hand takes the voice to the page`() {
        val next = PageSpan("one.xhtml", ReadAlongPosition(0, 21_000), null, setOf("s2"))
        assertEquals(ReadAlongPageSync.Manual.Jump(ReadAlongPosition(0, 21_000)),
            ReadAlongPageSync.afterManualTurn(timeline, ReadAlongPosition(0, 20_500), next))
    }

    @Test fun `a page turned back to a sentence the voice has gone on from does not turn itself forward again`() {
        // Page break at 15 s; the voice is at 17 s, in a sentence both pages show.
        assertNull(ReadAlongPageSync.keptFor(span, ReadAlongPosition(0, 17_000)).end)
        // The voice has not reached the break: the turn still comes.
        assertEquals(ReadAlongPosition(0, 15_000), ReadAlongPageSync.keptFor(span, ReadAlongPosition(0, 12_000)).end)
        // A page that never turns stays that way.
        assertNull(ReadAlongPageSync.keptFor(span.copy(end = null), ReadAlongPosition(0, 12_000)).end)
    }

    // ------------------------------------------------------------------ the word edition (#66)

    /** "Alpha delta gamma betas" as four timed words, then "Epsilon zeta eta theta." with "zeta" cut by the dramatization. */
    private val words = ReadAlongTimeline(listOf(ReadAlongTrack("one.mp4", listOf(
        ReadAlongSegment("one.xhtml", "s0-w0", "one.mp4", 0, 1_000, "s0"),
        ReadAlongSegment("one.xhtml", "s0-w1", "one.mp4", 1_200, 2_000, "s0"),
        ReadAlongSegment("one.xhtml", "s0-w2", "one.mp4", 2_100, 3_000, "s0"),
        ReadAlongSegment("one.xhtml", "s0-w3", "one.mp4", 3_100, 4_000, "s0"),
        ReadAlongSegment("one.xhtml", "s1-w0", "one.mp4", 5_000, 6_000, "s1"),
        ReadAlongSegment("one.xhtml", "s1-w2", "one.mp4", 6_500, 7_000, "s1"),
        ReadAlongSegment("one.xhtml", "s1-w3", "one.mp4", 7_100, 8_000, "s1")
    ))))

    @Test fun `in a word edition the page's first word is that word's own time, not a share of the sentence`() {
        val start = ReadAlongPageSync.startOf(words, page(PageEdge("s0", alpha, 12, "s0-w2"), null, "s0"))
        assertEquals(ReadAlongPosition(0, 2_100), start)
        // A word the voice does not say (zeta has no clip): the next one it does.
        assertEquals(ReadAlongPosition(0, 6_500), ReadAlongPageSync.startOf(words, page(PageEdge("s1", second, 8, "s1-w1"), null, "s1")))
        // No word of the sentence begins on the page: the next sentence's first word.
        assertEquals(ReadAlongPosition(0, 5_000), ReadAlongPageSync.startOf(words, page(PageEdge("s0", alpha, 20, null), null, "s0")))
    }

    @Test fun `in a word edition the next page begins at its first word`() {
        val span = ReadAlongPageSync.span(words, page(PageEdge("s0", alpha, 0, "s0-w0"), PageEdge("s0", alpha, 11, "s0-w2"), "s0"))
        assertEquals(ReadAlongPosition(0, 0), span.start)
        assertEquals(ReadAlongPosition(0, 2_100), span.end)
        // The voice turns the page when it reaches that word, not before.
        assertEquals(ReadAlongPageSync.Step.Stay, ReadAlongPageSync.follow(words, ReadAlongPosition(0, 2_050), span))
        assertEquals(ReadAlongPageSync.Step.TurnPage, ReadAlongPageSync.follow(words, ReadAlongPosition(0, 2_100), span))
        // A sentence that ends on the page hands over to the next.
        val whole = ReadAlongPageSync.span(words, page(PageEdge("s0", alpha, 0, "s0-w0"), PageEdge("s0", alpha, alpha.length, null), "s0"))
        assertEquals(ReadAlongPosition(0, 5_000), whole.end)
    }

    @Test fun `a word is on the page with its sentence`() {
        val span = ReadAlongPageSync.span(words, page(PageEdge("s0", alpha, 0, "s0-w0"), PageEdge("s1", second, second.length, null), "s0", "s1"))
        assertEquals(ReadAlongPageSync.Step.Stay, ReadAlongPageSync.follow(words, ReadAlongPosition(0, 6_600), span))
        assertEquals(ReadAlongPageSync.Manual.Keep, ReadAlongPageSync.afterManualTurn(words, ReadAlongPosition(0, 1_500), span))
        val other = ReadAlongPageSync.span(words, page(PageEdge("s1", second, 0, "s1-w0"), null, "s1"))
        // The word being said is off this page: go to the word itself.
        assertEquals(ReadAlongPageSync.Step.GoTo(words.tracks[0].segments[1]), ReadAlongPageSync.follow(words, ReadAlongPosition(0, 1_500), other))
        // L1/R1 go a sentence at a time, from a word in the middle.
        assertEquals(ReadAlongPosition(0, 5_000), words.step(ReadAlongPosition(0, 1_500), 1))
        assertEquals(ReadAlongPosition(0, 0), words.step(ReadAlongPosition(0, 3_500), -1))
        // Play from a page that shows none of the narration starts a sentence, not a word in the middle of one.
        assertEquals(ReadAlongPosition(0, 5_000), ReadAlongPageSync.nearest(words, listOf("one.xhtml"), "one.xhtml", 0.9))
        // The probe asks for sentences, not words.
        assertEquals(listOf("s0", "s1"), words.fragments("one.xhtml"))
    }

    @Test fun `positions compare by file, then by time`() {
        assertTrue(ReadAlongPageSync.compare(ReadAlongPosition(0, 99_000), ReadAlongPosition(1, 0)) < 0)
        assertTrue(ReadAlongPageSync.compare(ReadAlongPosition(1, 5), ReadAlongPosition(1, 4)) > 0)
        assertEquals(0, ReadAlongPageSync.compare(ReadAlongPosition(1, 5), ReadAlongPosition(1, 5)))
    }
}
