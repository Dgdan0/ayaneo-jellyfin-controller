package com.pocketds.hub.ui

import com.pocketds.hub.settings.Look
import com.pocketds.hub.ui.glass.ArtworkPalette
import com.pocketds.hub.ui.glass.GlassColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    private val glass = Theme.base(Look.GLASS, dark = true)
    private val dark = Theme.base(Look.CLASSIC, dark = true)
    private val light = Theme.base(Look.CLASSIC, dark = false)

    @Test fun `glass is one dark palette whatever the theme setting`() {
        assertSame(glass, Theme.base(Look.GLASS, dark = false))
        assertNotEquals(glass, dark)
        // The words and the states are the dark theme's.
        assertEquals(dark.primaryText, glass.primaryText)
        assertEquals(dark.mutedText, glass.mutedText)
        assertEquals(dark.badgeAvailable, glass.badgeAvailable)
        assertEquals(dark.badgePartial, glass.badgePartial)
        assertEquals(dark.dangerText, glass.dangerText)
    }

    @Test fun `the glass page colour paints nothing but fades into the glass base`() {
        assertEquals(0, GlassColors.alpha(glass.background))
        assertEquals(ArtworkPalette.NEUTRAL.dark and 0xFFFFFF, glass.background and 0xFFFFFF)
    }

    @Test fun `glass cards and chips are see-through tints`() {
        assertEquals(GlassColors.panel(ArtworkPalette.NEUTRAL), glass.cardSurface)
        assertEquals(GlassColors.bar(ArtworkPalette.NEUTRAL), glass.stripBackground)
        assertTrue(GlassColors.alpha(glass.cardSurface) in 1..0xFE)
        assertTrue(GlassColors.alpha(glass.stripBackground) in 1..0xFE)
    }

    @Test fun `only the glass palette puts panels on the glass page`() {
        assertTrue(Theme.onGlass(glass))
        // Classic, and the dark palette the player draws over video with.
        assertEquals(false, Theme.onGlass(dark))
        assertEquals(false, Theme.onGlass(light))
    }

    @Test fun `words on a white pill read in every look, and classic keeps its page colour for them`() {
        for (palette in listOf(glass, dark, light)) {
            assertEquals(0xFF, GlassColors.alpha(palette.inverseText))
            assertTrue(GlassColors.contrast(palette.inverseText, palette.primaryText) >= 7.0)
        }
        assertEquals(GlassColors.INK, glass.inverseText)
        assertEquals(dark.background, dark.inverseText)
        assertEquals(light.background, light.inverseText)
    }
}
