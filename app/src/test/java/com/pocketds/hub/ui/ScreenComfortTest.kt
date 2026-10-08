package com.pocketds.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenComfortTest {
    @Test fun `the dim is clear at full brightness and bounded at the dimmest`() {
        // The player's drag and the readers' sheet dim by one rule.
        assertEquals(0f, ScreenComfort.dimAlpha(1f), 0f)
        assertEquals(.85f, ScreenComfort.dimAlpha(0f), 0f)
        assertEquals(.425f, ScreenComfort.dimAlpha(.5f), .001f)
        assertEquals(0f, ScreenComfort.dimAlpha(9f), 0f)
        assertEquals(.765f, ScreenComfort(brightness = ScreenComfort.MIN_BRIGHTNESS).dimAlpha, .001f)
    }

    @Test fun `warmth multiplies white towards candlelight and leaves black black`() {
        assertEquals(ScreenComfort.WHITE, ScreenComfort.warmColor(0f))
        assertEquals(ScreenComfort.CANDLE, ScreenComfort.warmColor(1f))
        assertEquals(ScreenComfort.CANDLE, ScreenComfort.warmColor(3f))
        val half = ScreenComfort.warmColor(.5f)
        assertEquals(255, (half shr 16) and 0xFF)
        assertEquals((255 + 0xB4) / 2.0, ((half shr 8) and 0xFF).toDouble(), 1.0)
        assertEquals((255 + 0x6B) / 2.0, (half and 0xFF).toDouble(), 1.0)
        // Red stays full and blue drops most: amber, never a grey that dims as well.
        assertTrue((half and 0xFF) < ((half shr 8) and 0xFF))
        // Multiplied, black is black at any warmth: the OLED's black page stays off.
        fun multiply(page: Int, over: Int) = ((page and 0xFF) * (over and 0xFF)) / 255
        assertEquals(0, multiply(0, ScreenComfort.warmColor(1f)))
    }

    @Test fun `nothing is drawn until something is chosen`() {
        assertTrue(ScreenComfort().drawsNothing)
        assertFalse(ScreenComfort(brightness = .9f).drawsNothing)
        assertFalse(ScreenComfort(warmth = .1f).drawsNothing)
    }

    @Test fun `the screen stays on only while narration plays and only if asked`() {
        assertTrue(ScreenComfort().keepsScreenOn(narrating = true))
        assertFalse(ScreenComfort().keepsScreenOn(narrating = false))
        assertFalse(ScreenComfort(awakeWhileNarrating = false).keepsScreenOn(narrating = true))
    }

    @Test fun `labels`() {
        assertEquals("100%", ScreenComfort.brightnessLabel(1f))
        assertEquals("35%", ScreenComfort.brightnessLabel(.35f))
        assertEquals("Off", ScreenComfort.warmthLabel(0f))
        assertEquals("40%", ScreenComfort.warmthLabel(.4f))
    }
}
