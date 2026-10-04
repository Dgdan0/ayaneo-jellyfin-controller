package com.pocketds.hub.ui.glass

import com.pocketds.hub.model.ArtworkColorSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassColorsTest {

    @Test fun `hub colours parse to opaque ints and anything else is refused`() {
        assertEquals(0xFFD0B366.toInt(), GlassColors.parse("#d0b366"))
        assertEquals(0xFF0B0D12.toInt(), GlassColors.parse("#0B0D12"))
        for (bad in listOf("", "d0b366", "#d0b36", "#d0b3666", "#zzzzzz", "rgb(1,2,3)")) assertNull(bad, GlassColors.parse(bad))
    }

    @Test fun `a palette needs all four colours`() {
        val light = ArtworkPalette.from(ArtworkColorSet("#d0b366", "#1d1500", "#d0b366", "#f2e4bf"))
        assertEquals(ArtworkPalette(0xFFD0B366.toInt(), 0xFF1D1500.toInt(), 0xFFD0B366.toInt(), 0xFFF2E4BF.toInt()), light)
        assertNull(ArtworkPalette.from(ArtworkColorSet("#d0b366", "", "#d0b366", "#f2e4bf")))
        assertNull(ArtworkPalette.from(ArtworkColorSet()))
    }

    @Test fun `mixing goes the right fraction of the way on every channel`() {
        assertEquals(0xFF000000.toInt(), GlassColors.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), GlassColors.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), GlassColors.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0x80FF0000.toInt(), GlassColors.mix(0x00FF0000, 0xFFFF0000.toInt(), 0.5f))
    }

    @Test fun `a panel is the glass base tinted by the artwork at eighty percent`() {
        val gold = ArtworkPalette(0xFFD0B366.toInt(), 0xFF1D1500.toInt(), 0xFFD0B366.toInt(), 0xFFF2E4BF.toInt())
        val panel = GlassColors.panel(gold)
        assertEquals(0xCC, GlassColors.alpha(panel))
        // Thirty percent of the way from #12141C to the gold.
        assertEquals(0x4B, GlassColors.red(panel))
        assertEquals(0x44, GlassColors.green(panel))
        assertEquals(0x32, GlassColors.blue(panel))
        // Warmer than the base, never as bright as the artwork itself.
        assertTrue(GlassColors.red(panel) > GlassColors.red(GlassColors.PANEL_BASE))
        assertTrue(GlassColors.luminance(GlassColors.over(panel, gold.dark)) < GlassColors.luminance(gold.dominant))
    }

    @Test fun `white type stays readable on a panel over any artwork's dark page`() {
        val samples = listOf(
            ArtworkPalette(0xFFD0B366.toInt(), 0xFF1D1500.toInt(), 0xFFD0B366.toInt(), 0xFFF2E4BF.toInt()), // Light Bringer
            ArtworkPalette(0xFFFDF010.toInt(), 0xFF191700.toInt(), 0xFFE2D700.toInt(), 0xFFE9E7C1.toInt()), // Recursion, the brightest
            ArtworkPalette(0xFF6A0F0D.toInt(), 0xFF2C0806.toInt(), 0xFFDD2722.toInt(), 0xFFFFDBD6.toInt()), // Bleach
            ArtworkPalette.NEUTRAL
        )
        for (p in samples) {
            val panel = GlassColors.over(GlassColors.panel(p), p.dark)
            assertTrue("contrast on ${Integer.toHexString(panel)}", GlassColors.contrast(0xFFFFFFFF.toInt(), panel) >= 7.0)
        }
    }

    @Test fun `the bars are more solid than a panel and their words and key caps read over any page`() {
        val samples = listOf(
            ArtworkPalette(0xFFD0B366.toInt(), 0xFF1D1500.toInt(), 0xFFD0B366.toInt(), 0xFFF2E4BF.toInt()),
            ArtworkPalette(0xFFFDF010.toInt(), 0xFF191700.toInt(), 0xFFE2D700.toInt(), 0xFFE9E7C1.toInt()),
            ArtworkPalette(0xFF6A0F0D.toInt(), 0xFF2C0806.toInt(), 0xFFDD2722.toInt(), 0xFFFFDBD6.toInt()),
            ArtworkPalette.NEUTRAL
        )
        for (p in samples) {
            assertTrue(GlassColors.alpha(GlassColors.bar(p)) > GlassColors.alpha(GlassColors.panel(p)))
            val bar = GlassColors.over(GlassColors.bar(p), p.dark)
            assertTrue("words on ${Integer.toHexString(bar)}", GlassColors.contrast(GlassColors.over(GlassColors.BAR_TEXT, bar), bar) >= 7.0)
            // A cap's dark letter on the cap, and the cap against the bar it sits on.
            val cap = GlassColors.over(GlassColors.KEY_CAP, bar)
            assertTrue(GlassColors.contrast(GlassColors.INK, cap) >= 7.0)
            assertTrue(GlassColors.contrast(cap, bar) >= 7.0)
        }
    }

    @Test fun `a sheet is the panel's tint and hides the words under it`() {
        val gold = ArtworkPalette(0xFFD0B366.toInt(), 0xFF1D1500.toInt(), 0xFFD0B366.toInt(), 0xFFF2E4BF.toInt())
        val sheet = GlassColors.sheet(gold)
        assertEquals(GlassColors.panel(gold) and 0xFFFFFF, sheet and 0xFFFFFF)
        // White words under the sheet, already halved by the scrim, change it by under 3%.
        val under = GlassColors.over(0x80FFFFFF.toInt(), 0xFF000000.toInt())
        val through = GlassColors.over(sheet, under)
        val solid = GlassColors.over(sheet, 0xFF000000.toInt())
        assertTrue(GlassColors.red(through) - GlassColors.red(solid) <= 0.03 * 255)
    }

    @Test fun `a control over video reads on the brightest frame whatever is playing`() {
        val videoBase = 0xFF0A0D12.toInt()
        val samples = listOf(
            ArtworkPalette(0xFFD0B366.toInt(), 0xFF1D1500.toInt(), 0xFFD0B366.toInt(), 0xFFF2E4BF.toInt()),
            ArtworkPalette(0xFFFDF010.toInt(), 0xFF191700.toInt(), 0xFFE2D700.toInt(), 0xFFE9E7C1.toInt()), // the brightest
            ArtworkPalette(0xFF6A0F0D.toInt(), 0xFF2C0806.toInt(), 0xFFDD2722.toInt(), 0xFFFFDBD6.toInt()),
            ArtworkPalette.NEUTRAL
        )
        for (p in samples) {
            val face = GlassColors.overPicture(p, videoBase)
            assertEquals(GlassColors.PICTURE_ALPHA, GlassColors.alpha(face))
            // White words on the face over a white window, and over black.
            for (frame in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
                val seen = GlassColors.over(face, frame)
                assertTrue("on ${Integer.toHexString(seen)}", GlassColors.contrast(0xFFFFFFFF.toInt(), seen) >= 7.0)
            }
        }
    }

    @Test fun `the selected tab and a lit button keep their dark words readable`() {
        assertTrue(GlassColors.contrast(GlassColors.INK, 0xFFFFFFFF.toInt()) >= 15.0)
        assertEquals(0xFF, GlassColors.alpha(GlassColors.INK))
    }

    @Test fun `contrast matches WCAG's endpoints`() {
        assertEquals(21.0, GlassColors.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, GlassColors.contrast(0xFF7F7F7F.toInt(), 0xFF7F7F7F.toInt()), 0.0001)
    }
}
