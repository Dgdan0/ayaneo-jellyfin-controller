package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The reader's page colours (#47): Kindle's Sepia and Dark, today's grey as Dim. */
class EpubPagePaletteTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((color shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(palette: Pair<Int, Int>): Double {
        val (a, b) = luminance(palette.first) to luminance(palette.second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    @Test fun `sepia and dark are Kindle's measured colours`() {
        assertEquals(0xfffcf0d9.toInt() to 0xff5a4931.toInt(), EpubPagePalette.of(EpubTheme.SEPIA))
        assertEquals(0xff000000.toInt() to 0xffafafaf.toInt(), EpubPagePalette.of(EpubTheme.BLACK))
    }

    @Test fun `the grey page stays as dim, with softer ink`() {
        assertEquals(0xff202020.toInt() to 0xffc8c8c2.toInt(), EpubPagePalette.of(EpubTheme.DARK))
        assertEquals("Dim", EpubPagePalette.label(EpubTheme.DARK))
        assertEquals("Dark", EpubPagePalette.label(EpubTheme.BLACK))
    }

    @Test fun `paper and blue are as they were`() {
        assertEquals(0xfffbfaf6.toInt() to 0xff282b29.toInt(), EpubPagePalette.of(EpubTheme.LIGHT))
        assertEquals(0xff1d303d.toInt() to 0xffdce6e8.toInt(), EpubPagePalette.of(EpubTheme.BLUE))
    }

    @Test fun `every theme stays readable, and sepia and dark are Kindle's contrast`() {
        EpubPagePalette.CHOICES.forEach { assertTrue("$it ${contrast(EpubPagePalette.of(it))}", contrast(EpubPagePalette.of(it)) >= 7.0) }
        assertEquals(7.7, contrast(EpubPagePalette.of(EpubTheme.SEPIA)), 0.1)
        assertEquals(9.6, contrast(EpubPagePalette.of(EpubTheme.BLACK)), 0.1)
        assertEquals(9.7, contrast(EpubPagePalette.of(EpubTheme.DARK)), 0.1)
    }

    @Test fun `the menu lists five themes, the system's colours apart`() {
        assertEquals(listOf(EpubTheme.LIGHT, EpubTheme.SEPIA, EpubTheme.DARK, EpubTheme.BLACK, EpubTheme.BLUE), EpubPagePalette.CHOICES)
        assertEquals(listOf("Paper", "Sepia", "Dim", "Dark", "Blue"), EpubPagePalette.CHOICES.map { EpubPagePalette.label(it) })
        assertFalse(EpubTheme.SYSTEM in EpubPagePalette.CHOICES)
    }

    @Test fun `the system's colours are paper by day and dark at night`() {
        assertEquals(EpubTheme.LIGHT, EpubPagePalette.resolve(EpubTheme.SYSTEM, night = false))
        assertEquals(EpubTheme.BLACK, EpubPagePalette.resolve(EpubTheme.SYSTEM, night = true))
        assertEquals(EpubPagePalette.of(EpubTheme.LIGHT), EpubPagePalette.of(EpubTheme.SYSTEM, night = false))
        assertEquals(EpubPagePalette.of(EpubTheme.BLACK), EpubPagePalette.of(EpubTheme.SYSTEM, night = true))
        // A theme that was chosen is not changed by the night.
        EpubPagePalette.CHOICES.forEach { assertEquals(it, EpubPagePalette.resolve(it, night = true)) }
    }

    @Test fun `the dark pages are the dark ones`() {
        assertTrue(EpubPagePalette.isDark(EpubTheme.DARK) && EpubPagePalette.isDark(EpubTheme.BLACK) && EpubPagePalette.isDark(EpubTheme.BLUE))
        assertFalse(EpubPagePalette.isDark(EpubTheme.LIGHT) || EpubPagePalette.isDark(EpubTheme.SEPIA))
        assertFalse(EpubPagePalette.isDark(EpubTheme.SYSTEM, night = false))
        assertTrue(EpubPagePalette.isDark(EpubTheme.SYSTEM, night = true))
    }

    @Test fun `a stored id keeps its meaning`() {
        // DARK is the grey and always was; the black is a new id.
        assertEquals(EpubTheme.DARK, EpubTheme.valueOf("DARK"))
        assertEquals(EpubTheme.BLACK, EpubTheme.valueOf("BLACK"))
    }

    @Test fun `a device that read on a black page moves to dark, and one that did not keeps its theme`() {
        EpubTheme.entries.forEach { assertEquals(EpubTheme.BLACK, EpubLookMigration.theme(true, it)) }
        EpubTheme.entries.forEach { assertEquals(it, EpubLookMigration.theme(false, it)) }
    }
}
