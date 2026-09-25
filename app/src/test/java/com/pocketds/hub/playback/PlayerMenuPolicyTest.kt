package com.pocketds.hub.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMenuPolicyTest {
    @Test fun tracksStartOnSubtitlesAndRememberLastTab() {
        val state = PlayerMenuState()
        assertEquals("subtitles", state.trackTab)
        state.selectTrackTab("audio")
        assertEquals("audio", state.trackTab)
        state.selectTrackTab("unknown")
        assertEquals("audio", state.trackTab)
    }

    @Test fun timingLabelsExplainDirectionAndFineRange() {
        assertEquals("−0.8 s · Earlier", SubtitleTimingPolicy.label(-800))
        assertEquals("0.0 s · In sync", SubtitleTimingPolicy.label(0))
        assertEquals("+0.8 s · Later", SubtitleTimingPolicy.label(800))
        assertEquals(-5_000L, SubtitleTimingPolicy.clamp(-8_000, false))
        assertEquals(60_000L, SubtitleTimingPolicy.clamp(80_000, true))
        assertEquals(100L, SubtitleTimingPolicy.progressToOffset(51, false))
    }
}
