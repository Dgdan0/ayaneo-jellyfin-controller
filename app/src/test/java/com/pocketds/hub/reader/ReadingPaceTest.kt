package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time left in a book (#18, E3): a pace learnt from the reading, and the time it gives. */
class ReadingPaceTest {
    @Test fun `before any reading the pace is the prior`() {
        assertEquals(ReadingPace.DEFAULT_MINUTES_PER_POSITION, ReadingPace().minutesPerPosition(), 1e-9)
        assertEquals(2.5, ReadingPace().minutesPerPosition(prior = 2.5), 1e-9)
    }

    @Test fun `pages read at a steady pace calibrate it`() {
        var pace = ReadingPace()
        // A page of half a position every 40 s: 1.333 minutes a position.
        repeat(60) { pace = pace.observe(it * 0.5, (it + 1) * 0.5, 40_000) }
        assertEquals(30.0, pace.positions, 1e-9)
        assertEquals(40.0, pace.minutes, 1e-9)
        val minutes = pace.minutesPerPosition()
        assertTrue("$minutes leans to 1.333 from 1.6", minutes in 1.33..1.39)
    }

    @Test fun `jumps, pauses, moves back and flicking through count for nothing`() {
        val pace = ReadingPace(10.0, 15.0)
        assertSame(pace, pace.observe(10.0, 40.0, 60_000))
        assertSame(pace, pace.observe(10.0, 10.5, 20 * 60_000))
        assertSame(pace, pace.observe(10.0, 9.5, 30_000))
        assertSame(pace, pace.observe(10.0, 11.0, 2_000))
        // Three positions in twenty seconds is about 3,600 words a minute.
        assertSame(pace, pace.observe(10.0, 13.0, 20_000))
    }

    @Test fun `only the recent reading counts`() {
        var pace = ReadingPace(ReadingPace.WINDOW_POSITIONS, ReadingPace.WINDOW_POSITIONS * 3.0)
        // Four windows' worth at a minute a position, after reading at three.
        repeat(1000) { pace = pace.observe(0.0, 0.5, 30_000) }
        assertEquals(ReadingPace.WINDOW_POSITIONS, pace.positions, 1e-6)
        assertTrue("${pace.minutesPerPosition()} follows the new pace of 1.0", pace.minutesPerPosition() < 1.1)
    }

    @Test fun `it keeps as words and reads back`() {
        val pace = ReadingPace(12.5, 20.25)
        assertEquals(pace, ReadingPace.decode(pace.encode()))
        assertEquals(ReadingPace(), ReadingPace.decode(null))
        assertEquals(ReadingPace(), ReadingPace.decode("nonsense"))
        assertEquals(ReadingPace(), ReadingPace.decode("-1|3"))
        assertEquals(ReadingPace(3.0, 4.0), ReadingPace(1.0, 1.0) + ReadingPace(2.0, 3.0))
    }

    @Test fun `the tracker measures from where the reading was, and starts again after a pause or a jump`() {
        val tracker = ReadingPace.Tracker()
        assertNull(tracker.at(10.0, 0))
        // Scrolled a little: too soon to judge, the start is kept.
        assertNull(tracker.at(10.1, 1_000))
        val reading = tracker.at(10.6, 45_000)!!
        assertEquals(ReadingPace.Reading(10.0, 10.6, 45_000), reading)
        val learnt = ReadingPace().observe(reading)
        assertEquals(0.6, learnt.positions, 1e-9)
        assertEquals(0.75, learnt.minutes, 1e-9)
        // A jump through the contents: measured again from there.
        assertNull(tracker.at(50.0, 60_000))
        // A pause: from where the book was picked up again.
        assertNull(tracker.at(50.5, 60_000 + ReadingPace.MAX_STEP_MS + 1))
        assertEquals(ReadingPace.Reading(50.5, 51.0, 30_999), tracker.at(51.0, 60_000 + ReadingPace.MAX_STEP_MS + 31_000))
        // Back a page: nothing, and measured from there.
        assertNull(tracker.at(50.8, 60_000 + ReadingPace.MAX_STEP_MS + 41_000))
        tracker.restart()
        assertNull(tracker.at(51.0, 0))
    }

    @Test fun `time left from the positions, in the chapter and in the book`() {
        // Four parts of 10, 20, 30 and 40 positions; halfway through the second, at 1.5 minutes a position.
        val left = TimeLeft.ofPositions(listOf(10, 20, 30, 40), 1, 0.5, 1.5)!!
        assertEquals(15L * 60_000, left.chapterMs)
        assertEquals(120L * 60_000, left.bookMs)
        assertEquals("15 min left in chapter · 2h 0m in book", left.label())
        assertNull(TimeLeft.ofPositions(emptyList(), 0, 0.0, 1.5))
        assertEquals(25.0, TimeLeft.position(listOf(10, 20, 30, 40), 1, 0.75)!!, 1e-9)
    }

    @Test fun `under a minute still reads as a minute`() {
        assertEquals("1 min left in chapter · 1 min in book", TimeLeft(5_000, 30_000).label())
        assertEquals("12 min left in chapter · 4h 10m in book", TimeLeft(12 * 60_000L, 250 * 60_000L).label())
    }

    @Test fun `each half of the line stands alone for a page's corner (#42)`() {
        val left = TimeLeft(12 * 60_000L, 250 * 60_000L)
        assertEquals("12 min left in chapter", left.chapterLabel())
        assertEquals("4h 10m left in book", left.bookLabel())
        assertEquals("1 min left in chapter", TimeLeft(5_000, 30_000).chapterLabel())
        assertEquals("1 min left in book", TimeLeft(5_000, 30_000).bookLabel())
        // The menu's line is the same words, joined.
        assertTrue(left.label().startsWith(left.chapterLabel()))
    }

    @Test fun `reading along, what the narration has left to say, at its speed`() {
        fun seg(href: String, begin: Long, end: Long) = ReadAlongSegment(href, "s$begin", "a.mp3", begin, end)
        val timeline = ReadAlongTimeline(listOf(
            ReadAlongTrack("a.mp3", listOf(seg("one.xhtml", 0, 60_000), seg("one.xhtml", 60_000, 120_000), seg("two.xhtml", 120_000, 300_000))),
            ReadAlongTrack("b.mp3", listOf(ReadAlongSegment("two.xhtml", "t", "b.mp3", 0, 600_000)))
        ))
        // 30 s into the first sentence of chapter one, at normal speed.
        val left = TimeLeft.ofNarration(timeline, ReadAlongPosition(0, 30_000), 1f)!!
        assertEquals(90_000L, left.chapterMs)
        assertEquals(870_000L, left.bookMs)
        // Twice as fast, half the time.
        assertEquals(45_000L, TimeLeft.ofNarration(timeline, ReadAlongPosition(0, 30_000), 2f)!!.chapterMs)
        // In chapter two, which runs on into the second part.
        assertEquals(780_000L, TimeLeft.ofNarration(timeline, ReadAlongPosition(0, 120_000), 1f)!!.chapterMs)
        assertNull(TimeLeft.ofNarration(timeline, ReadAlongPosition(5, 0), 1f))
    }
}
