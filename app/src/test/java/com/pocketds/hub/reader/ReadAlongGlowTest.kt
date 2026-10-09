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

    @Test fun `a light page takes all the strength asked for, a dark page what leaves its ink readable`() {
        val gold = ReadAlongColor.GOLD.argb
        val (paper, paperInk) = EpubPagePalette.of(EpubTheme.LIGHT)
        val (sepia, sepiaInk) = EpubPagePalette.of(EpubTheme.SEPIA)
        assertEquals(ReadAlongGlow.mix(paper, gold, 0.45), ReadAlongGlow.wash(gold, paper, paperInk, 0.45))
        assertEquals(ReadAlongGlow.mix(sepia, gold, 0.62), ReadAlongGlow.wash(gold, sepia, sepiaInk, 0.62))
        // Stronger than the 30% overlay of the first version, which the owner found faint.
        assertTrue(strength(ReadAlongGlow.wash(gold, sepia, sepiaInk, 0.45), sepia, gold) > 0.40)
        val (dark, darkInk) = EpubPagePalette.of(EpubTheme.BLACK)
        val onDark = ReadAlongGlow.wash(gold, dark, darkInk, 0.45)
        assertTrue("less than a light page: ${strength(onDark, dark, gold)}", strength(onDark, dark, gold) < 0.40)
        assertTrue(ReadAlongGlow.contrast(darkInk, onDark) >= ReadAlongGlow.MIN_CONTRAST)
    }

    @Test fun `every theme and every colour keeps the page's ink readable on the wash, which is opaque and not the page`() {
        themes.forEach { theme ->
            val (page, ink) = EpubPagePalette.of(theme)
            (ReadAlongColor.entries.map { it.argb } + AccentPreset.entries.map { it.color }).forEach { color ->
                val wash = ReadAlongGlow.wash(color, page, ink, ReadAlongWordHighlight.SENTENCE)
                val label = "$theme ${ReadAlongGlow.rgb(color)}: ${ReadAlongGlow.rgb(wash)} on ${ReadAlongGlow.rgb(page)}"
                assertEquals(label, 0xFF, wash ushr 24)
                assertTrue("$label reads at ${ReadAlongGlow.contrast(ink, wash)}", ReadAlongGlow.contrast(ink, wash) >= ReadAlongGlow.MIN_CONTRAST)
                // Faint on a dark page is what #52 was about: never under a quarter of the way.
                assertTrue("$label is ${strength(wash, page, color)} of the way", strength(wash, page, color) >= 0.24)
            }
        }
    }

    @Test fun `a colour that cannot be read on is let in only a little, and a strength under the least is taken as it is`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // Dark ink on a black page: no mix of white up to 45% has the ink readable (less white is darker, and worse), so the
        // demo's loop runs down to its first step at or under 0.06.
        val least = ReadAlongGlow.wash(white, black, 0xFF222222.toInt(), 0.45)
        var t = 0.45
        while (t > ReadAlongGlow.WEAKEST) t -= ReadAlongGlow.STEP
        assertEquals(ReadAlongGlow.mix(black, white, t), least)
        assertEquals(ReadAlongGlow.mix(black, white, 0.03), ReadAlongGlow.wash(white, black, 0xFF222222.toInt(), 0.03))
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
        assertTrue(sheet, ".pocket-narration, .pocket-narration-word { z-index: -1 !important;" in sheet)
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

    /** #56: Storyteller leaves the space after a sentence inside its element, and the wash ran on over it. */
    @Test fun `each row's box is cut back to the sentence's words, not the white space inside its element`() {
        val script = ReadAlongGlow.fitScript("s12")
        // The words: the element's text nodes in order, from the first character that is not white space to the last.
        assertTrue(script, "createTreeWalker(element, NodeFilter.SHOW_TEXT)" in script && "walker.nextNode()" in script)
        assertTrue(script, "text.search(/\\S/)" in script && "/\\s/.test(text.charAt(to - 1))" in script)
        assertTrue(script, "ends(target)" in script && "setStart(start.node, start.at)" in script && "setEnd(end.node, end.at)" in script)
        assertTrue(script, "spans(sentence.first, said ? said.last : sentence.last)" in script)
        // Their rectangles, row by row, in the page's own numbers (the scroll added back, as Readium's boxes are).
        assertTrue(script, "scroller.scrollLeft" in script && "r.left + across" in script && "r.right + across" in script)
        // Each box's own extent is cut back to them on its row; a row with nothing but white space has no box.
        assertTrue(script, "Math.max(own.l, here.l)" in script && "Math.min(own.l + own.w, here.r)" in script)
        assertTrue(script, "set(el, 'display', 'none')" in script && "removeProperty('display')" in script)
        // A hidden box takes no part in the corners, which stay square where two lines' boxes join.
        assertTrue(script, "var shown = placed.filter" in script && "c !== own" in script)
        // The air round the words is the stylesheet's, so the cut is to the words and the box keeps its side room.
        assertTrue(ReadAlongGlow.STYLESHEET, "margin-left: -${ReadAlongGlow.SIDE_PX}px; padding: 0 ${ReadAlongGlow.SIDE_PX}px" in ReadAlongGlow.STYLESHEET)
        // The observer and the refit are as they were, and nothing of the Kotlin template is left in the page.
        assertTrue(script, "window.__pocketNarrationFit()" in script && "childList: true, subtree: true" in script)
        assertFalse(script, "\$" in script)
    }

    /** #66: style A, the trail cut back to the word being said and the word drawn over it, from one script per word. */
    @Test fun `a word is drawn over the trail, which ends at the word, and a trail at 0 percent is no trail at all`() {
        val script = ReadAlongGlow.fitScript("s12", word = "s12-w3", wordTint = 0xFFF4DC9F.toInt(), trail = true)
        assertTrue(script, "window.__pocketNarrationFragment = 's12';" in script && "window.__pocketNarrationWord = 's12-w3';" in script)
        assertTrue(script, "window.__pocketNarrationWordTint = 'rgb(244, 220, 159)';" in script && "window.__pocketNarrationTrail = true;" in script)
        // The word must be inside the sentence; the trail is the sentence's first letter to the word's last.
        assertTrue(script, "target.contains(word)" in script && "said ? said.last : sentence.last" in script)
        // Readium's own extent kept, so the trail can grow back over it on the next word.
        assertTrue(script, "kept.get(el)" in script && "new WeakMap()" in script)
        // The word's boxes: their own class, in the decoration's container, line boxes with the seam, the tint important.
        assertTrue(script, "'${ReadAlongGlow.WORD_CLASS}'" in script && "item.appendChild(box)" in script && "'important'" in script)
        assertTrue(script, "w.row - height / 2 - SEAM" in script && "height + 2 * SEAM" in script)
        // The last word's boxes go before the next are drawn, and the script's own changes are not watched.
        assertTrue(script, "entry.old[extra].remove()" in script && "watcher.disconnect()" in script && "__pocketLast" in script)
        val none = ReadAlongGlow.fitScript("s12", word = "s12-w3", wordTint = 0xFFF4DC9F.toInt(), trail = false)
        assertTrue(none, "window.__pocketNarrationTrail = false;" in none && "if (!trail) return null;" in none && "set(el, 'display', 'none')" in none)
        // A sentence edition's sentence: no word, the whole sentence.
        val sentence = ReadAlongGlow.fitScript("s12")
        assertTrue(sentence, "window.__pocketNarrationWord = null;" in sentence && "window.__pocketNarrationWordTint = null;" in sentence)
        // The word's boxes sit behind the words as the trail's do, with the same air and corners.
        assertTrue(ReadAlongGlow.STYLESHEET, ".${ReadAlongGlow.CLASS}, .${ReadAlongGlow.WORD_CLASS} { z-index: -1 !important;" in ReadAlongGlow.STYLESHEET)
        assertFalse(script, "\$" in script)
        assertTrue(ReadAlongGlow.fitScript("s", word = "a'; x; '").contains("'a\\'; x; \\''"))
    }

    @Test fun `a fragment cannot break out of the script's string`() {
        assertEquals("'s12'", ReadAlongGlow.jsString("s12"))
        assertEquals("'a\\'b\\\\c'", ReadAlongGlow.jsString("a'b\\c"))
        assertEquals("'x\\n\\u003c/script>'", ReadAlongGlow.jsString("x\n</script>"))
        assertTrue(ReadAlongGlow.fitScript("a'; alert(1); '").contains("'a\\'; alert(1); \\''"))
    }
}
