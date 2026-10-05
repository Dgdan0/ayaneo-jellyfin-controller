package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAlongFollowTest {
    // Two audio files: the first narrates chapter one from 5 s in, the second chapter two.
    private val timeline = ReadAlongTimeline(listOf(
        ReadAlongTrack("one.mp4", listOf(
            ReadAlongSegment("one.xhtml", "s0", "one.mp4", 5_000, 6_000),
            ReadAlongSegment("one.xhtml", "s1", "one.mp4", 6_000, 10_000),
            ReadAlongSegment("one.xhtml", "s2", "one.mp4", 10_000, 12_000)
        )),
        ReadAlongTrack("two.mp4", listOf(
            ReadAlongSegment("two.xhtml", "t0", "two.mp4", 0, 3_000),
            ReadAlongSegment("two.xhtml", "t1", "two.mp4", 3_000, 4_000)
        ))
    ))

    @Test fun `R1 goes to the next sentence, across files`() {
        // In s1 (6.5 s into the file, 1.5 s into the track).
        assertEquals(ReadAlongPosition(0, 5_000), timeline.step(ReadAlongPosition(0, 1_500), 1))
        // From the last sentence of the first file to the first of the second.
        assertEquals(ReadAlongPosition(1, 0), timeline.step(ReadAlongPosition(0, 6_000), 1))
        assertNull(timeline.step(ReadAlongPosition(1, 3_500), 1))
    }

    @Test fun `L1 goes back a sentence, or to this one's start when well into it`() {
        // Half a second into s1: s0.
        assertEquals(ReadAlongPosition(0, 0), timeline.step(ReadAlongPosition(0, 1_500), -1))
        // Three seconds into s1: s1's own start.
        assertEquals(ReadAlongPosition(0, 1_000), timeline.step(ReadAlongPosition(0, 4_000), -1))
        // From the second file's first sentence back to the first file's last.
        assertEquals(ReadAlongPosition(0, 5_000), timeline.step(ReadAlongPosition(1, 500), -1))
        assertNull(timeline.step(ReadAlongPosition(0, 200), -1))
    }

    @Test fun `which pages are narrated, and what the page says`() {
        assertTrue(timeline.narrates("two.xhtml"))
        assertFalse(timeline.narrates("front.xhtml"))
        assertEquals("Following", ReadAlongFollow.label(following = true, narrated = true))
        assertEquals("Reading", ReadAlongFollow.label(following = false, narrated = true))
        assertEquals("Alignment unavailable", ReadAlongFollow.label(following = true, narrated = false))
    }
}
