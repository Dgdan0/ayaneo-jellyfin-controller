package com.pocketds.hub.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {

    @Test fun `glass is the default and only a stored classic is classic`() {
        assertEquals(Look.GLASS, Look.fromStored(null))
        assertEquals(Look.GLASS, Look.fromStored(""))
        assertEquals(Look.GLASS, Look.fromStored("marquee"))
        assertEquals(Look.GLASS, Look.fromStored("glass"))
        assertEquals(Look.CLASSIC, Look.fromStored("classic"))
        for (look in Look.entries) assertEquals(look, Look.fromStored(look.stored))
    }

    @Test fun `glass is dark whatever the theme says`() {
        for (theme in ThemeSettings.Mode.entries) {
            assertTrue(theme.name, Look.GLASS.dark(theme, systemNight = false))
            assertTrue(theme.name, Look.GLASS.dark(theme, systemNight = true))
        }
    }

    @Test fun `classic keeps following the theme setting`() {
        assertFalse(Look.CLASSIC.dark(ThemeSettings.Mode.LIGHT, systemNight = true))
        assertTrue(Look.CLASSIC.dark(ThemeSettings.Mode.DARK, systemNight = false))
        assertTrue(Look.CLASSIC.dark(ThemeSettings.Mode.SYSTEM, systemNight = true))
        assertFalse(Look.CLASSIC.dark(ThemeSettings.Mode.SYSTEM, systemNight = false))
    }
}
