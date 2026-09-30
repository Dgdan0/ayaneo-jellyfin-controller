package com.pocketds.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeLabelTest {

    @Test
    fun `episodes read the way the hub writes them`() {
        // hub/internal/adapters/jellyfin/models.go Item.Subtitle
        assertEquals("S1E4 · Mindy's Back", EpisodeLabel.of(1, 4, "Mindy's Back"))
    }

    @Test
    fun `season zero is specials`() {
        assertEquals("Specials", EpisodeLabel.season(0))
        assertEquals("Season 3", EpisodeLabel.season(3))
    }

    @Test
    fun `specials and unnumbered episodes still read cleanly`() {
        assertEquals("S0E2 · Behind the Scenes", EpisodeLabel.of(0, 2, "Behind the Scenes"))
        assertEquals("Pilot", EpisodeLabel.of(0, 0, "Pilot"))
        assertEquals("S2E3", EpisodeLabel.of(2, 3, ""))
    }
}
