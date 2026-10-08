package com.pocketds.hub.reader

import com.pocketds.hub.reader.EpubFonts.Face
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's typefaces (#47): Literata by default, the files they need, and the one move from Times. */
class EpubFontsTest {
    private val assets: File = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    @Test fun `literata is the default, on a new device and after a reset`() {
        assertEquals(Face.LITERATA, EpubFonts.DEFAULT)
        assertEquals("literata", EpubReaderPreferences().fontFamily)
        assertEquals(Face.LITERATA, EpubFonts.face(EpubReaderPreferences().fontFamily))
    }

    @Test fun `the menu lists the book's own face and three of the reader's, Serif gone`() {
        assertEquals(listOf("Original", "Literata", "Charis", "Atkinson Hyperlegible"), EpubFonts.CHOICES.map { it.label })
        assertFalse(EpubFonts.known("serif"))
        assertFalse(EpubFonts.known("sans-serif"))
        assertTrue(EpubFonts.known("publisher"))
    }

    @Test fun `a stored id keeps its meaning, and an unknown one reads in the default`() {
        assertEquals(Face.ORIGINAL, EpubFonts.face("publisher"))
        assertEquals(Face.CHARIS, EpubFonts.face("charis"))
        assertEquals(Face.ATKINSON, EpubFonts.face("atkinson"))
        assertEquals(Face.LITERATA, EpubFonts.face("serif"))
        assertEquals(Face.LITERATA, EpubFonts.face(null))
        assertEquals(Face.LITERATA, EpubFonts.face("Comic Sans"))
    }

    @Test fun `the book's own font asks Readium for nothing, and the others for one name each`() {
        assertNull(Face.ORIGINAL.css)
        val names = Face.entries.mapNotNull { it.css }
        assertEquals(listOf("Literata", "Charis", "Atkinson"), names)
        // A CSS name with no space needs no quoting wherever Readium writes it.
        assertTrue(names.none { it.contains(' ') })
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun `every face's files are in the assets, as shipped`() {
        EpubFonts.ASSETS.forEach { asset ->
            val file = File(assets, asset)
            assertTrue("$asset is missing", file.isFile)
            // A real font: the sfnt header, 0x00010000 (TrueType) or "OTTO".
            val head = file.inputStream().use { it.readNBytes(4) }
            assertTrue("$asset is not a font", head.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(head) == "OTTO")
        }
        assertEquals(8, EpubFonts.ASSETS.size)
        assertEquals(EpubFonts.ASSETS.size, EpubFonts.ASSETS.toSet().size)
    }

    @Test fun `the fonts are served from the assets folder the declarations point at`() {
        assertTrue(Regex(EpubFonts.SERVED_ASSETS).matches("fonts/Literata.ttf"))
        EpubFonts.ASSETS.forEach { assertTrue(it, Regex(EpubFonts.SERVED_ASSETS).matches(it)) }
    }

    @Test fun `a variable file covers a range of weights and a static one a single weight`() {
        val literata = Face.LITERATA.sources
        assertTrue(literata.all { it.variable && it.minWeight == 200 && it.maxWeight == 900 })
        assertEquals(listOf(false, true), literata.map { it.italic })
        assertTrue(Face.ATKINSON.sources.all { it.variable })
        val charis = Face.CHARIS.sources
        assertTrue(charis.none { it.variable })
        assertEquals(setOf(400 to false, 700 to false, 400 to true, 700 to true), charis.map { it.minWeight to it.italic }.toSet())
        // The preview is the roman.
        assertEquals("fonts/Literata.ttf", Face.LITERATA.preview?.asset)
        assertNull(Face.ORIGINAL.preview)
        assertNotNull(Face.CHARIS.preview)
    }

    @Test fun `each bundled face has its licence text in the assets`() {
        listOf("Literata-OFL-1.1.txt", "Charis-OFL-1.1.txt", "AtkinsonHyperlegibleNext-OFL-1.1.txt").forEach { name ->
            val text = File(assets, "licenses/$name").readText()
            assertTrue(name, text.contains("SIL OPEN FONT LICENSE Version 1.1"))
            assertTrue(name, text.contains("Copyright"))
        }
        // Charis's reserved names are why its files are shipped as released.
        assertTrue(File(assets, "licenses/Charis-OFL-1.1.txt").readText().contains("Reserved Font Names \"Charis\" and \"SIL\""))
    }

    @Test fun `a device moves to literata once, whatever typeface it kept`() {
        assertEquals("literata", EpubLookMigration.fontFamily("publisher", alreadyMoved = false))
        assertEquals("literata", EpubLookMigration.fontFamily("serif", alreadyMoved = false))
        assertEquals("literata", EpubLookMigration.fontFamily("monospace", alreadyMoved = false))
        // The sans it chose becomes the menu's sans.
        assertEquals("atkinson", EpubLookMigration.fontFamily("sans-serif", alreadyMoved = false))
        // Nothing stored: it has the default already.
        assertNull(EpubLookMigration.fontFamily(null, alreadyMoved = false))
        // Once: a later choice, the book's own among them, is never undone.
        listOf("publisher", "serif", "sans-serif", "literata", "charis", null).forEach { assertNull(EpubLookMigration.fontFamily(it, alreadyMoved = true)) }
    }

    @Test fun `reset text style brings literata back and leaves the size`() {
        val old = EpubReaderPreferences(fontFamily = "charis", fontScale = 1.6f, lineHeight = 1.1f, publisherStyles = true)
        val reset = EpubLayoutPolicy.resetTextStyle(old)
        assertEquals("literata", reset.fontFamily)
        assertEquals(1.6f, reset.fontScale, 0f)
        assertEquals("Literata, justified, hyphenated, 1.5 spacing", EpubLayoutPolicy.textStyleSummary())
    }
}
