package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackTrickplay
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRulesTest {
    private val playing = com.pocketds.hub.model.PlaybackPrepareResponse(
        sessionId = "session", positionMillis = 5_000, selectedMediaSourceId = "source-1",
        selectedAudioIndex = 1, selectedSubtitleIndex = null
    )

    /**
     * Jellyfin applies an audio or subtitle stream index only with the media
     * source it belongs to; without one it converted the default track again
     * (#24: Harry Potter stayed in Russian after English was chosen).
     */
    @Test
    fun `a track chosen on its own names the version playing`() {
        val audio = PlaybackRules.selection(playing, positionMillis = 8_000, audioStreamIndex = 2)
        assertEquals("source-1", audio.mediaSourceId)
        assertEquals(2, audio.audioStreamIndex)
        assertEquals(null, audio.subtitleStreamIndex)
        assertEquals(8_000L, audio.positionMillis)
        val subtitle = PlaybackRules.selection(playing, subtitleStreamIndex = 4)
        assertEquals("source-1", subtitle.mediaSourceId)
        assertEquals(4, subtitle.subtitleStreamIndex)
        assertEquals("the plan's position when none is given", 5_000L, subtitle.positionMillis)
        val quality = PlaybackRules.selection(playing, maxBitrate = 10_000_000, forceTranscode = true)
        assertEquals("source-1", quality.mediaSourceId)
        assertEquals(10_000_000, quality.maxBitrate)
        assertEquals(true, quality.forceTranscode)
    }

    @Test
    fun `a version chosen is the one named, and a plan without one names none`() {
        assertEquals("source-2", PlaybackRules.selection(playing, mediaSourceId = "source-2").mediaSourceId)
        assertEquals(null, PlaybackRules.selection(playing.copy(selectedMediaSourceId = ""), audioStreamIndex = 2).mediaSourceId)
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
    fun `only a real final position counts as natural completion`() {
        assertEquals(false, PlaybackRules.reachedNaturalEnd(180_000, 3_188_000))
        assertEquals(false, PlaybackRules.reachedNaturalEnd(3_186_999, 3_188_000))
        assertEquals(true, PlaybackRules.reachedNaturalEnd(3_187_000, 3_188_000))
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
}
