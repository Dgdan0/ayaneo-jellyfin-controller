package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTest {
    private val duration = 1_360_000L
    private val segments = listOf(
        PlaybackSegment("op", "Intro", 199_000, 289_000),
        PlaybackSegment("ed", "Outro", 1_207_000, 1_297_000)
    )

    @Test
    fun `the card waits for the credits, or comes 20 seconds before the end`() {
        assertEquals(1_207_000L, UpNext.cardAt(NextEpisodeTiming.CREDITS, segments, duration))
        assertEquals(1_340_000L, UpNext.cardAt(NextEpisodeTiming.CREDITS, emptyList(), duration))
        assertEquals(1_340_000L, UpNext.cardAt(NextEpisodeTiming.BEFORE_END, segments, duration))
        assertNull(UpNext.cardAt(NextEpisodeTiming.NEVER, segments, duration))
    }

    @Test
    fun `an ending theme in the first half is not the credits`() {
        val early = listOf(PlaybackSegment("ed", "Outro", 100_000, 190_000))
        assertEquals(1_340_000L, UpNext.cardAt(NextEpisodeTiming.CREDITS, early, duration))
    }

    @Test
    fun `a very short video gets no card`() {
        assertNull(UpNext.cardAt(NextEpisodeTiming.BEFORE_END, emptyList(), 30_000))
    }

    @Test
    fun `the card shows from its start until the video ends`() {
        assertFalse(UpNext.showsCard(1_206_999, 1_207_000, duration))
        assertTrue(UpNext.showsCard(1_207_000, 1_207_000, duration))
        assertFalse(UpNext.showsCard(duration, 1_207_000, duration))
        assertFalse(UpNext.showsCard(1_300_000, null, duration))
    }

    @Test
    fun `intros, recaps, previews and ads get a skip button, credits do not`() {
        assertEquals("Skip intro", UpNext.skipLabel("Intro"))
        assertEquals("Skip recap", UpNext.skipLabel("recap"))
        assertEquals("Skip ad", UpNext.skipLabel("Commercial"))
        assertNull(UpNext.skipLabel("Outro"))
        assertTrue(UpNext.skipsAutomatically("Intro"))
        assertFalse(UpNext.skipsAutomatically("Preview"))
    }
}
