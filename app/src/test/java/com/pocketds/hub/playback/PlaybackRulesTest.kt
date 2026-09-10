package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackTrickplay
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRulesTest {
    @Test
    fun `resume requires thirty seconds watched and remaining`() {
        assertEquals(0L, PlaybackRules.resumePosition(29_999, 600_000))
        assertEquals(30_000L, PlaybackRules.resumePosition(30_000, 600_000))
        assertEquals(0L, PlaybackRules.resumePosition(570_000, 600_000))
        assertEquals(0L, PlaybackRules.resumePosition(120_000, 600_000, played = true))
    }

    @Test
    fun `held seek accelerates in bounded stages`() {
        assertEquals(10_000L, PlaybackRules.seekStep(0))
        assertEquals(30_000L, PlaybackRules.seekStep(5))
        assertEquals(60_000L, PlaybackRules.seekStep(12))
    }

    @Test
    fun `seek never escapes the item`() {
        assertEquals(0L, PlaybackRules.clampSeek(-1_000, 100_000))
        assertEquals(35_000L, PlaybackRules.clampSeek(35_000, 100_000))
        assertEquals(100_000L, PlaybackRules.clampSeek(101_000, 100_000))
    }

    @Test
    fun `horizontal scrub is proportional and clamped`() {
        assertEquals(1_200_000L, PlaybackRules.scrubTarget(600_000, 0.5f, 4_200_000))
        assertEquals(0L, PlaybackRules.scrubTarget(10_000, -1f, 4_200_000))
        assertEquals(4_200_000L, PlaybackRules.scrubTarget(4_190_000, 1f, 4_200_000))
    }

    @Test
    fun `trickplay position maps into its sprite tile`() {
        val info = PlaybackTrickplay(
            width = 320,
            height = 180,
            tileWidth = 4,
            tileHeight = 3,
            thumbnailCount = 30,
            intervalMillis = 10_000
        )
        assertEquals(
            PlaybackRules.TrickplayFrame(14, 1, 2, 0),
            PlaybackRules.trickplayFrame(145_000, info)
        )
        assertEquals(29, PlaybackRules.trickplayFrame(Long.MAX_VALUE, info)?.thumbnailIndex)
    }

    @Test
    fun `quality caps match the player contract`() {
        assertEquals(listOf(0, 40_000_000, 20_000_000, 10_000_000, 5_000_000, 2_000_000),
            PlaybackRules.qualities.map { it.bitrate })
    }

    @Test
    fun `next episode counts down once and can be cancelled`() {
        val state = NextEpisodeCountdown(3)
        state.start()
        assertEquals(3, state.remaining)
        assertEquals(false, state.elapse())
        assertEquals(2, state.remaining)
        assertEquals(false, state.elapse())
        assertEquals(true, state.elapse())
        assertEquals(false, state.active)

        state.start()
        state.cancel()
        assertEquals(false, state.elapse())
        assertEquals(0, state.remaining)
    }
}
