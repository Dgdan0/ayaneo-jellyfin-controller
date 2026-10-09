package com.pocketds.hub.reader

/**
 * What the reader draws on the page besides the narration's glow (#62), as Readium's underline decoration: the colours of the
 * highlights, the note mark at the end of one that has a note, and the "Heard to here" mark where the voice stopped. One
 * template serves all three, told apart by the decoration's `kind`; [ReadAlongGlow] keeps the highlight decoration for the
 * narration. Pure strings and colours, so a JVM test pins them; the pixels are checked on a real page by
 * ReaderHighlightsTest.
 *
 * The highlight's box sits behind the words, as the narration's does, and is an opaque mix of the colour with the page
 * ([ReadAlongGlow.wash]): the ink is the page's own in every theme, and two highlights that overlap are one colour instead of a darker one.
 */
object ReaderMarks {
    const val GROUP = "annotations"
    const val NOTES_GROUP = "annotation-notes"
    const val HEARD_GROUP = "heard"
    const val HIGHLIGHT = "highlight"
    const val NOTE = "note"
    const val HEARD = "heard"
    const val KIND = "kind"
    const val HL = "pd-hl"

    /** The four colours as they are drawn: bright enough to read as the colour on the dark pages, which [wash] pales for the light ones. */
    fun base(color: HighlightColor): Int = when (color) {
        HighlightColor.YELLOW -> 0xFFF2D34B.toInt()
        HighlightColor.BLUE -> 0xFF6FA8D6.toInt()
        HighlightColor.PINK -> 0xFFE48AA6.toInt()
        HighlightColor.GREEN -> 0xFF86BE66.toInt()
    }

    /** The tint of a highlight on a page of [page] with [ink] text: as much of the colour as keeps the ink readable. */
    fun tint(color: HighlightColor, page: Int, ink: Int): Int = ReadAlongGlow.wash(base(color), page, ink, STRENGTH)

    /**
     * The most of a highlight's colour let into the page, stepped down from there until the ink reads at 4.5:1: the 0.45 the
     * narration's sentence wash had when the highlights were made, so they look as they did (#66 gave the wash its strength).
     */
    const val STRENGTH = 0.45

    /**
     * The element Readium lays over each line of a decoration of [kind], [tint] being the colour its style carries. The page is XHTML, so
     * Readium parses this as XML: every attribute needs a value (a bare `hidden` made it throw, and the whole decoration was dropped
     * without a word, which ReaderMarksTest now looks for).
     */
    fun element(kind: String?, tint: Int): String = when (kind) {
        NOTE -> """<div class="pd-note" style="--mark: ${ReadAlongGlow.rgb(tint)};"></div>"""
        HEARD -> """<div class="pd-heard" style="--heard: ${ReadAlongGlow.rgb(tint)};"></div>"""
        else -> """<div class="$HL" style="background-color: ${ReadAlongGlow.rgb(tint)} !important;"></div>"""
    }

    /** A note's page as a mask so the mark takes the highlight's colour darkened by the page's ink. */
    private const val NOTE_MASK = "data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' " +
        "stroke-width='3' stroke-linejoin='round'%3E%3Cpath d='M5 4h14v12l-4 4H5z'/%3E%3Cpath d='M15 20v-4h4'/%3E%3C/svg%3E"

    /**
     * The tab for "Heard to here" ([MarginTab]): one box a line, as wide as a page, of which only the first draws anything. Its words are
     * the accessibility text of the page, so a screen reader says "Heard to here" where it is. The colour is on the element, as the
     * highlight's is: Readium's own stylesheet makes every element's background transparent once the book has a colour of its own, and only an
     * inline `!important` outranks that (the tab was measured on the page and drawn nowhere).
     */
    fun tabElement(tint: Int): String =
        """<div class="pd-tab"><span class="pd-tab-bar" role="img" aria-label="$HEARD_LABEL" style="background-color: ${ReadAlongGlow.rgb(tint)} !important;"></span></div>"""

    /** The words of the mark, for the page's accessibility text and the toast on coming back to the ebook. */
    const val HEARD_LABEL = "Heard to here"

    /**
     * The highlight behind the words with soft corners; the note mark up at the end of the last line, over the text and out of the
     * way of it; and the underline of "Heard to here" in the accent under all of the sentence. Its tab is [TAB_STYLESHEET]'s.
     *
     * The highlight is one step further back than the narration's boxes ([ReadAlongGlow.STYLESHEET], -1): both are behind the words, and
     * the word being said and its trail are drawn over a highlight on the same words. At the same z-index the later of Readium's groups
     * was on top, so a highlight made while a word was lit covered the word (#66).
     */
    val STYLESHEET: String = """
        .$HL { z-index: -2 !important; margin-left: -2px; padding: 0 2px; box-sizing: content-box; border-radius: 3px; }
        .pd-note { z-index: 6 !important; background: transparent !important; }
        .pd-note::after { content: ""; position: absolute; left: 100%; top: -8px; width: 16px; height: 16px; margin-left: -4px;
            background-color: var(--mark); -webkit-mask: url("$NOTE_MASK") center / contain no-repeat; filter: brightness(.55) saturate(1.4); }
        .pd-heard { z-index: 6 !important; background: transparent !important; border-bottom: 2px solid var(--heard); box-sizing: border-box; }
    """.trimIndent().replace(Regex("\\s*\\n\\s*"), " ")

    /**
     * "Heard to here"'s tab: the boxes are as wide as a column (Readium's `page` width, which is where the page's margin is whether the
     * book is in one column or two, and in a scrolling page), a line high; the first line's box draws a rounded bar in the margin, 3px in
     * from the column's edge, inside the 16px the page keeps free there ([PageGeometry.GUTTER_DP]). It stands beside the line and over
     * no word, so it cannot hide one at any size of text or spacing of lines.
     */
    val TAB_STYLESHEET: String = """
        .pd-tab { z-index: 6 !important; background: transparent !important; }
        .pd-tab-bar { position: absolute; left: 3px; top: 1px; bottom: 1px; width: 6px; border-radius: 3px; }
        .pd-tab:not(:first-child) .pd-tab-bar { display: none; }
    """.trimIndent().replace(Regex("\\s*\\n\\s*"), " ")
}
