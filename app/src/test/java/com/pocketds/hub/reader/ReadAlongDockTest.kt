package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAlongDockTest {
    private fun timeline(vararg seconds: Long) = ReadAlongTimeline(seconds.mapIndexed { index, length ->
        ReadAlongTrack("part$index.mp4", listOf(ReadAlongSegment("one.xhtml", "s$index", "part$index.mp4", 0, length * 1000)))
    })

    @Test fun `the time in the part playing, and which part when there are several`() {
        assertEquals("4:13 of 27:05", ReadAlongDockText.time(ReadAlongPosition(0, 253_000), timeline(1625)))
        assertEquals("0:10 of 1:00 · part 2 of 3", ReadAlongDockText.time(ReadAlongPosition(1, 10_000), timeline(30, 60, 90)))
        // Past the end of a part never reads past its length.
        assertEquals("1:00 of 1:00 · part 2 of 3", ReadAlongDockText.time(ReadAlongPosition(1, 99_000), timeline(30, 60, 90)))
        assertEquals("0:00 of 0:00", ReadAlongDockText.time(ReadAlongPosition(4, 0), timeline(30)))
    }

    @Test fun `the line shows how far through the part`() {
        assertEquals(0.5, ReadAlongDockText.fraction(ReadAlongPosition(0, 30_000), timeline(60)), 0.001)
        assertEquals(1.0, ReadAlongDockText.fraction(ReadAlongPosition(0, 90_000), timeline(60)), 0.001)
        assertEquals(0.0, ReadAlongDockText.fraction(ReadAlongPosition(3, 0), timeline(60)), 0.001)
    }

    @Test fun `the sentence glows in the accent it is given`() {
        val gold = 0xFFE3B341.toInt()
        assertEquals("rgb(227, 179, 65)", ReadAlongGlow.rgb(gold))
        val element = ReadAlongGlow.element(gold)
        assertTrue(element, element.startsWith("<div class=\"pocket-narration\""))
        assertTrue(element, "box-shadow: 0 0 0 3px rgb(227, 179, 65), 0 0 14px 4px rgb(227, 179, 65)" in element)
        assertTrue(ReadAlongGlow.STYLESHEET.startsWith(".pocket-narration {"))
    }

    /** #52: a sentence over several lines has a box for each, and translucent boxes add up where they meet. */
    @Test fun `the boxes are solid and the sentence is made see-through once, as a whole`() {
        val element = ReadAlongGlow.element(0xFF3DDBC6.toInt())
        // Nothing in a box has an alpha of its own: overlapping solids are one colour, never a darker band.
        assertFalse(element, "rgba" in element || "opacity" in element)
        // The transparency is applied once, to Readium's container of the whole group.
        assertTrue(ReadAlongGlow.STYLESHEET, "[data-group=\"${ReadAlongGlow.GROUP}\"] { opacity: ${ReadAlongGlow.OPACITY} !important; }" in ReadAlongGlow.STYLESHEET)
        assertEquals("readalong", ReadAlongGlow.GROUP)
        assertEquals(1, Regex("opacity").findAll(ReadAlongGlow.STYLESHEET).count())
    }
}
