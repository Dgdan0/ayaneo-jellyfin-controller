package com.pocketds.hub.reader

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's look from what a device holds (#42, Part 3): Kindle's typography by default, anything stored kept. */
class EpubAppearanceStoreTest {
    /** A preferences file that holds [values], answering a key it does not hold with the caller's default. */
    private fun store(values: Map<String, Any> = emptyMap()): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getFloat", "getBoolean" -> values[args[0] as String] ?: args[1]
                else -> error("not read by the store: ${method.name}")
            }
        } as SharedPreferences

    @Test fun `a device with nothing stored reads in the reader's own typography`() {
        val look = EpubAppearanceStore.decode(store())
        assertFalse("Publisher styling is off", look.publisherStyles)
        assertEquals("justify", look.textAlignment)
        assertEquals(1.5f, look.lineHeight, 0f)
        assertTrue("hyphenation is on", look.hyphenation)
        // And the fallbacks are the data class's own defaults, so the two cannot drift.
        assertEquals(EpubReaderPreferences(), look)
    }

    @Test fun `the defaults are Kindle's, and Publisher styling is the way back to the book's`() {
        val defaults = EpubReaderPreferences()
        assertFalse(defaults.publisherStyles)
        assertEquals("justify", defaults.textAlignment)
        assertEquals(1.5f, defaults.lineHeight, 0f)
        assertTrue(defaults.hyphenation)
        assertTrue(defaults.copy(publisherStyles = true).publisherStyles)
    }

    @Test fun `a new device reads at 130 percent, in Kindle's three line spacings, on the middle margin`() {
        val defaults = EpubReaderPreferences()
        assertEquals(1.3f, defaults.fontScale, 0f)
        assertEquals(listOf(1.3f, 1.5f, 1.8f), EpubLayoutPolicy.SPACING.map { it.first })
        assertTrue("the default spacing is one of the three", EpubLayoutPolicy.SPACING.any { it.first == defaults.lineHeight })
        assertEquals(1.5f, defaults.lineHeight, 0f)
        assertEquals(com.pocketds.hub.reader.PageGeometry.Margin.BALANCED, PageGeometry.preset(defaults.pageMargins))
        assertEquals(1.3f, EpubAppearanceStore.decode(store()).fontScale, 0f)
    }

    @Test fun `a device that chose its size keeps it`() {
        assertEquals(1.0f, EpubAppearanceStore.decode(store(mapOf("fontScale" to 1.0f))).fontScale, 0f)
        assertEquals(1.1f, EpubAppearanceStore.decode(store(mapOf("lineHeight" to 1.1f))).lineHeight, 0f)
    }

    @Test fun `a device whose look was changed keeps it`() {
        val look = EpubAppearanceStore.decode(store(mapOf(
            "publisherStyles" to true, "textAlignment" to "start", "lineHeight" to 1.25f, "hyphenation" to false,
            "theme" to "DARK", "fontScale" to 1.3f
        )))
        assertTrue(look.publisherStyles)
        assertEquals("start", look.textAlignment)
        assertEquals(1.25f, look.lineHeight, 0f)
        assertFalse(look.hyphenation)
        assertEquals(EpubTheme.DARK, look.theme)
        assertEquals(1.3f, look.fontScale, 0f)
    }

    @Test fun `a key that is not stored takes the default whatever else is`() {
        // A device that changed only its theme before hyphenation existed: the rest of its look is not stored.
        val look = EpubAppearanceStore.decode(store(mapOf("theme" to "BLUE")))
        assertEquals(EpubTheme.BLUE, look.theme)
        assertEquals(EpubReaderPreferences().copy(theme = EpubTheme.BLUE), look)
    }

    @Test fun `values out of range and names this build does not know are brought back`() {
        val look = EpubAppearanceStore.decode(store(mapOf("lineHeight" to 9f, "fontScale" to 0.1f, "theme" to "NEON", "columns" to "SEVEN")))
        assertEquals(2f, look.lineHeight, 0f)
        assertEquals(.7f, look.fontScale, 0f)
        assertEquals(EpubReaderPreferences().theme, look.theme)
        assertEquals(EpubReaderPreferences().columns, look.columns)
    }

    @Test fun `one full page per screen still wins its layout`() {
        val look = EpubAppearanceStore.decode(store(mapOf("onePagePerScreen" to true, "scroll" to true)))
        assertTrue(look.onePagePerScreen)
        assertFalse(look.scroll)
        assertEquals(EpubColumns.ONE, look.columns)
    }
}
