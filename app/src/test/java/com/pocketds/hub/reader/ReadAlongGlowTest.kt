package com.pocketds.hub.reader

import com.pocketds.hub.settings.AccentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentence being read (#52): its wash, and the strings Readium lays it down with. The pixels are ReadAlongHighlightTest's. */
class ReadAlongGlowTest {
    private val themes = listOf(EpubTheme.LIGHT, EpubTheme.SEPIA, EpubTheme.DARK, EpubTheme.BLACK, EpubTheme.BLUE)

    /** How far along the way from the page to the accent a colour is (the channel that moves most). */
    private fun strength(wash: Int, page: Int, accent: Int): Double =
        listOf(16, 8, 0).map { shift ->
            val from = (page shr shift) and 0xFF
            val to = (accent shr shift) and 0xFF
            if (from == to) null else (((wash shr shift) and 0xFF) - from).toDouble() / (to - from)
        }.filterNotNull().max()

    @Test fun `a light page takes the most of the accent, a dark page what leaves its ink readable`() {
        val gold = AccentPreset.GOLD.color
        val (paper, paperInk) = EpubPagePalette.of(EpubTheme.LIGHT)
        val (sepia, sepiaInk) = EpubPagePalette.of(EpubTheme.SEPIA)
        assertEquals(ReadAlongGlow.mix(paper, gold, 0.45), ReadAlongGlow.wash(gold, paper, paperInk))
        assertEquals(ReadAlongGlow.mix(sepia, gold, 0.45), ReadAlongGlow.wash(gold, sepia, sepiaInk))
        // Stronger than the 30% overlay it replaces, which the owner found faint.
        assertTrue(strength(ReadAlongGlow.wash(gold, sepia, sepiaInk), sepia, gold) > 0.40)
        val (dark, darkInk) = EpubPagePalette.of(EpubTheme.BLACK)
        val onDark = ReadAlongGlow.wash(gold, dark, darkInk)
        assertTrue("less than a light page: ${strength(onDark, dark, gold)}", strength(onDark, dark, gold) < 0.40)
        assertTrue(ReadAlongGlow.contrast(darkInk, onDark) >= ReadAlongGlow.MIN_CONTRAST)
    }

    @Test fun `every theme and every accent keeps the page's ink readable on the wash, which is opaque and not the page`() {
        themes.forEach { theme ->
            val (page, ink) = EpubPagePalette.of(theme)
            AccentPreset.entries.forEach { accent ->
                val wash = ReadAlongGlow.wash(accent.color, page, ink)
                val label = "$theme ${accent.id}: ${ReadAlongGlow.rgb(wash)} on ${ReadAlongGlow.rgb(page)}"
                assertEquals(label, 0xFF, wash ushr 24)
                assertTrue("$label reads at ${ReadAlongGlow.contrast(ink, wash)}", ReadAlongGlow.contrast(ink, wash) >= ReadAlongGlow.MIN_CONTRAST)
                // Faint on a dark page is what #52 was about: never under a quarter of the way.
                assertTrue("$label is ${strength(wash, page, accent.color)} of the way", strength(wash, page, accent.color) >= 0.24)
            }
        }
    }

    @Test fun `an accent that cannot be read on is let in only a little`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // Dark ink on a black page: no mix of white into it has the ink readable, so it is the least there is.
        assertEquals(ReadAlongGlow.mix(black, white, ReadAlongGlow.WEAKEST), ReadAlongGlow.wash(white, black, 0xFF222222.toInt()))
    }

    @Test fun `the wash moves the page towards the accent, a channel at a time`() {
        assertEquals(0xFF808080.toInt(), ReadAlongGlow.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5))
        assertEquals(0xFF000000.toInt(), ReadAlongGlow.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.0))
        assertEquals(0xFFFFFFFF.toInt(), ReadAlongGlow.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1.0))
    }

    @Test fun `the contrast is the WCAG ratio`() {
        assertEquals(21.0, ReadAlongGlow.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, ReadAlongGlow.contrast(0xFF336699.toInt(), 0xFF336699.toInt()), 0.001)
    }

    /** #52: the owner's Pocket showed the sentence's own words lighter than the rest, and its glow on the words around it. */
    @Test fun `the boxes are one opaque colour, behind the page's words, with no glow`() {
        val element = ReadAlongGlow.element(0xFFE3B341.toInt())
        assertEquals("<div class=\"pocket-narration\" style=\"background-color: rgb(227, 179, 65) !important;\"></div>", element)
        // Opaque: two boxes that overlap are one colour, so there is nothing to add up.
        assertFalse(element, "rgba" in element || "opacity" in element)
        val sheet = ReadAlongGlow.STYLESHEET
        // Behind the words, which is what leaves their ink as the page's own...
        assertTrue(sheet, ".pocket-narration { z-index: -1 !important;" in sheet)
        // ...and nothing that would make the group a stacking context of its own and bring it back over the text, or spill off the line.
        listOf("opacity", "box-shadow", "filter", "mix-blend-mode", "transform", "will-change").forEach { assertFalse("$it in $sheet", it in sheet) }
        assertEquals("readalong", ReadAlongGlow.GROUP)
    }

    @Test fun `each box is made its line's line box by a script that reads the sentence's own line height`() {
        val script = ReadAlongGlow.fitScript("s12")
        assertTrue(script.startsWith("(function () {") && script.endsWith("})()"))
        // Readium's own markup: the group's container, one container for the decoration, a box for each line.
        assertTrue(script, "'readalong'" in script && "'pocket-narration'" in script && "[data-style]" in script)
        // The sentence's element says how tall a line is; the box keeps its centre and takes that height, plus a seam.
        assertTrue(script, "window.__pocketNarrationFragment = 's12';" in script)
        assertTrue(script, "document.getElementById(window.__pocketNarrationFragment)" in script && "lineHeight" in script)
        assertTrue(script, "centre - line / 2 - SEAM" in script && "line + 2 * SEAM" in script)
        // Square where two lines join, round at the edge of the shape; and it keeps the boxes fitted after a reflow.
        assertTrue(script, "border-radius" in script && "MutationObserver" in script && "disconnect()" in script)
    }

    @Test fun `a fragment cannot break out of the script's string`() {
        assertEquals("'s12'", ReadAlongGlow.jsString("s12"))
        assertEquals("'a\\'b\\\\c'", ReadAlongGlow.jsString("a'b\\c"))
        assertEquals("'x\\n\\u003c/script>'", ReadAlongGlow.jsString("x\n</script>"))
        assertTrue(ReadAlongGlow.fitScript("a'; alert(1); '").contains("'a\\'; alert(1); \\''"))
    }
}
