package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
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

    @Test fun `the speed without a trailing nought`() {
        assertEquals("1×", ReadAlongDockText.speed(1f))
        assertEquals("1.25×", ReadAlongDockText.speed(1.25f))
        assertEquals("1.5×", ReadAlongDockText.speed(1.5f))
        assertEquals("0.75×", ReadAlongDockText.speed(.75f))
    }

    @Test fun `the sentence glows in the accent it is given`() {
        val gold = 0xFFE3B341.toInt()
        assertEquals("rgba(227, 179, 65, 0.28)", ReadAlongGlow.rgba(gold, ReadAlongGlow.WASH))
        val element = ReadAlongGlow.element(gold)
        assertTrue(element, element.startsWith("<div class=\"pocket-narration\""))
        assertTrue(element, "box-shadow: 0 0 0 3px rgba(227, 179, 65, 0.22), 0 0 14px 4px rgba(227, 179, 65, 0.42)" in element)
        assertTrue(ReadAlongGlow.STYLESHEET.startsWith(".pocket-narration {"))
    }
}
