package com.pocketds.hub.ui

import com.pocketds.hub.ui.glass.ArtworkPalette
import com.pocketds.hub.ui.glass.GlassColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    private val page = Theme.page
    private val video = Theme.video

    @Test fun `the page is the dark palette made see-through`() {
        assertNotEquals(page, video)
        // The words and the states are the dark palette's.
        assertEquals(video.primaryText, page.primaryText)
        assertEquals(video.mutedText, page.mutedText)
        assertEquals(video.badgeAvailable, page.badgeAvailable)
        assertEquals(video.badgePartial, page.badgePartial)
        assertEquals(video.dangerText, page.dangerText)
    }

    @Test fun `the page colour paints nothing but fades into the dark base`() {
        assertEquals(0, GlassColors.alpha(page.background))
        assertEquals(ArtworkPalette.NEUTRAL.dark and 0xFFFFFF, page.background and 0xFFFFFF)
    }

    @Test fun `cards and chips are see-through tints`() {
        assertEquals(GlassColors.panel(ArtworkPalette.NEUTRAL), page.cardSurface)
        assertEquals(GlassColors.bar(ArtworkPalette.NEUTRAL), page.stripBackground)
        assertTrue(GlassColors.alpha(page.cardSurface) in 1..0xFE)
        assertTrue(GlassColors.alpha(page.stripBackground) in 1..0xFE)
    }

    @Test fun `what is drawn over video is solid`() {
        assertEquals(0xFF, GlassColors.alpha(video.background))
        assertEquals(0xFF, GlassColors.alpha(video.cardSurface))
    }

    @Test fun `words on a white pill read on both palettes`() {
        for (palette in listOf(page, video)) {
            assertEquals(0xFF, GlassColors.alpha(palette.inverseText))
            assertTrue(GlassColors.contrast(palette.inverseText, palette.primaryText) >= 7.0)
        }
        assertEquals(GlassColors.INK, page.inverseText)
    }
}
