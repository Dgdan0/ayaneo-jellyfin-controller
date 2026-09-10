package com.pocketds.hub.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressStoreTest {
    private val now = 1_000_000L

    @Test
    fun `recent checkpoint resumes the same item`() {
        val checkpoint = PlaybackCheckpoint("episode", 300_000L, 1_200_000L, now - 1_000L)
        assertEquals(300_000L, checkpoint.resumePosition("episode", "resume", now))
    }

    @Test
    fun `checkpoint never overrides restart another item or old state`() {
        val checkpoint = PlaybackCheckpoint("episode", 300_000L, 1_200_000L, now - 1_000L)
        assertEquals(0L, checkpoint.resumePosition("episode", "restart", now))
        assertEquals(0L, checkpoint.resumePosition("other", "resume", now))
        assertEquals(0L, checkpoint.copy(updatedAtMillis = now - 120_001L)
            .resumePosition("episode", "resume", now))
    }

    @Test
    fun `resume thresholds match Jellyfin playback rules`() {
        assertEquals(0L, PlaybackCheckpoint("episode", 29_999L, 600_000L, now)
            .resumePosition("episode", "resume", now))
        assertEquals(0L, PlaybackCheckpoint("episode", 570_000L, 600_000L, now)
            .resumePosition("episode", "resume", now))
        assertEquals(30_000L, PlaybackCheckpoint("episode", 30_000L, 600_000L, now)
            .resumePosition("episode", "resume", now))
    }
}
